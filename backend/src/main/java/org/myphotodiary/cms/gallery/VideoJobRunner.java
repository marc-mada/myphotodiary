/*******************************************************************************
 * Copyright 2014-2026 Marc Lamberton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.myphotodiary.cms.gallery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.myphotodiary.cms.gallery.VideoProcessingService.ClaimedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * The transcoding workers (27/10/2026) - {@code app.video.transcode-workers}
 * threads (default 1; 0 turns background work off, as in the tests, which
 * call {@link #runNextJob} themselves). ffmpeg already uses every core, so
 * more than 1-2 workers would only slow down browsing; it also runs under
 * {@code nice} (VideoTranscoder).
 *
 * Each worker takes the next job (VideoProcessingService), encodes into the
 * work folder (never into the sequence folder, so a half-written file is
 * never visible), then hands the result back to be put in place.
 */
@Component
public class VideoJobRunner {

	private static final Logger log = LoggerFactory.getLogger(VideoJobRunner.class);
	private static final long IDLE_CHECK_MILLIS = TimeUnit.SECONDS.toMillis(30);

	private final VideoProcessingService processingService;
	private final VideoTranscoder transcoder;
	private final VideoFormatValidator videoFormatValidator;
	private final ImageStorageService storageService;
	private final VideoJobSignal signal;
	private final int workerCount;

	/** Claims are serialized: one must be committed before the next worker looks (see claimNext). */
	private final ReentrantLock claimLock = new ReentrantLock();
	private final List<Thread> workers = new ArrayList<>();
	private volatile boolean running;

	public VideoJobRunner(VideoProcessingService processingService, VideoTranscoder transcoder, VideoFormatValidator videoFormatValidator,
			ImageStorageService storageService, VideoJobSignal signal, @Value("${app.video.transcode-workers:1}") int workerCount) {
		this.processingService = processingService;
		this.transcoder = transcoder;
		this.videoFormatValidator = videoFormatValidator;
		this.storageService = storageService;
		this.signal = signal;
		this.workerCount = Math.max(0, Math.min(workerCount, 4));
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (workerCount == 0) {
			log.info("Video transcoding workers disabled (app.video.transcode-workers=0)");
			return;
		}
		int requeued = processingService.requeueInterrupted();
		storageService.clearVideoWorkDirectory();
		if (requeued > 0) {
			log.info("{} interrupted video job(s) put back in the queue", requeued);
		}
		running = true;
		for (int i = 1; i <= workerCount; i++) {
			Thread worker = new Thread(this::workLoop, "video-transcode-" + i);
			worker.setDaemon(true);
			worker.start();
			workers.add(worker);
		}
	}

	private void workLoop() {
		while (running) {
			try {
				if (!runNextJob()) {
					signal.await(IDLE_CHECK_MILLIS);
				}
			} catch (InterruptedException e) {
				return;
			} catch (RuntimeException e) {
				// e.g. the database briefly unavailable - don't let the worker die
				log.error("Video worker error", e);
				try {
					Thread.sleep(IDLE_CHECK_MILLIS);
				} catch (InterruptedException ie) {
					return;
				}
			}
		}
	}

	/** Runs one job if there is one; returns whether it did. Public for the tests (workers disabled there). */
	public boolean runNextJob() {
		Optional<ClaimedJob> claimed;
		claimLock.lock();
		try {
			claimed = processingService.claimNext();
		} finally {
			claimLock.unlock();
		}
		if (claimed.isEmpty()) {
			return false;
		}
		run(claimed.get());
		return true;
	}

	private void run(ClaimedJob job) {
		Path original = storageService.resolveOriginal(job.directoryPath(), job.name());
		Path output = storageService.videoWorkDirectory().resolve("job-" + job.jobId() + "-" + job.stage().name().toLowerCase(java.util.Locale.ROOT) + ".mp4");
		long started = System.nanoTime();
		try {
			VideoInfo info = videoFormatValidator.validate(original);
			if (job.stage() == VideoJob.Stage.PLAYBACK_COPY) {
				transcoder.makePlaybackCopy(original, info, output);
				processingService.finishCopy(job, output, info);
			} else {
				if (!VideoProcessingService.worthCompacting(info)) {
					processingService.dropJob(job);
					return;
				}
				long sizeBefore = storageService.fileSize(original);
				transcoder.compactOriginal(original, info, output);
				long sizeAfter = storageService.fileSize(output);
				if (sizeAfter >= sizeBefore) {
					log.info("Compacting {}/{} saved nothing ({} -> {} bytes): original kept", job.directoryPath(), job.name(), sizeBefore, sizeAfter);
					processingService.dropJob(job);
					return;
				}
				processingService.finishCompaction(job, output, sizeBefore);
				log.info("Compacted {}/{}: {} -> {} MB", job.directoryPath(), job.name(), sizeBefore / 1_000_000, sizeAfter / 1_000_000);
			}
			log.info("{} of {}/{} done in {} s", job.stage(), job.directoryPath(), job.name(), TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started));
		} catch (VideoTranscoder.TranscodeInterruptedException e) {
			processingService.requeue(job);
		} catch (RuntimeException e) {
			log.warn("{} of {}/{} failed: {}", job.stage(), job.directoryPath(), job.name(), e.getMessage());
			processingService.fail(job, e.getMessage());
		} finally {
			try {
				Files.deleteIfExists(output); // no-op once moved into place
			} catch (java.io.IOException ignored) {
				// emptied at the next start anyway
			}
		}
	}

	@PreDestroy
	void stop() {
		running = false;
		workers.forEach(Thread::interrupt);
		signal.wake();
		for (Thread worker : workers) {
			try {
				worker.join(TimeUnit.SECONDS.toMillis(10));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
