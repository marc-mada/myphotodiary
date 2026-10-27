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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.myphotodiary.cms.settings.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background video processing, "option C" (27/10/2026, explicit ask): any
 * video ffmpeg can read is accepted and its original kept as uploaded; what
 * plays in the browser is decided here.
 *
 * <ol>
 * <li><b>During the upload/indexing request</b> (synchronous, under a
 * second): ffprobe check and first-frame thumbnail (ImportService,
 * DirectoryIndexerService) - the video shows in the filmstrip at once, and a
 * file nothing can read is refused at once. Then {@link #registerNewVideo}:
 * an original already playable everywhere ({@link VideoInfo#playableAsIs()})
 * plays as is; any other gets {@link VideoPlayback#PENDING} and a playback
 * copy job.</li>
 * <li><b>Queue, priority 0</b>: the H.264 playback copy (VideoTranscoder),
 * made from the untouched original. Meanwhile the original is offered; a
 * browser that can't play it shows "Preparing video..." and checks again.</li>
 * <li><b>Queue, priority 1</b>, only when the app-wide setting "compact
 * original videos" is on (off by default), only for an inefficient codec
 * (ProRes, DV, MJPEG, MPEG-2...), never for HDR, and only after that video's
 * copy is done: the original is re-encoded to HEVC and replaced if smaller.
 * Every waiting copy runs before any compaction, so a batch of videos
 * becomes playable first.</li>
 * </ol>
 *
 * Jobs live in a table (video_job), so a restart loses nothing; a failed job
 * is retried up to 3 times, then counted as failed in Admin -> Configure.
 * A re-index repairs what is missing (a deleted copy, a failed job, a video
 * stored before this feature) - like it already does for thumbnails.
 */
@Service
public class VideoProcessingService {

	private static final Logger log = LoggerFactory.getLogger(VideoProcessingService.class);
	static final int MAX_ATTEMPTS = 3;

	private final VideoJobRepository jobRepository;
	private final ImageRepository imageRepository;
	private final ImageStorageService storageService;
	private final VideoFormatValidator videoFormatValidator;
	private final AppSettingsService appSettingsService;
	private final VideoJobSignal signal;

	public VideoProcessingService(VideoJobRepository jobRepository, ImageRepository imageRepository, ImageStorageService storageService,
			VideoFormatValidator videoFormatValidator, AppSettingsService appSettingsService, VideoJobSignal signal) {
		this.jobRepository = jobRepository;
		this.imageRepository = imageRepository;
		this.storageService = storageService;
		this.videoFormatValidator = videoFormatValidator;
		this.appSettingsService = appSettingsService;
		this.signal = signal;
	}

	/** A video just stored (upload, Batch Publish, indexing) - called in that request's transaction. */
	@Transactional
	public void registerNewVideo(Image video, VideoInfo info) {
		if (info.playableAsIs()) {
			video.setVideoPlayback(VideoPlayback.ORIGINAL);
			return;
		}
		video.setVideoPlayback(VideoPlayback.PENDING);
		enqueue(video.getId(), VideoJob.Stage.PLAYBACK_COPY);
	}

	/**
	 * A video met again by a re-index: repairs its state. A video stored
	 * before this feature (no state yet) is probed once; a missing copy or a
	 * failed job is queued again; with compaction on, an inefficient original
	 * whose copy is done gets its compaction job.
	 */
	@Transactional
	public void reconcile(Image video) {
		String directoryPath = video.getDirectory().getPath();
		List<VideoJob> jobs = jobRepository.findByImageId(video.getId());
		VideoPlayback playback = video.getVideoPlayback();

		if (playback == null) {
			VideoInfo info = probeQuietly(directoryPath, video.getName());
			if (info != null) {
				registerNewVideo(video, info);
			}
			return;
		}
		switch (playback) {
			case ORIGINAL -> {
				// playable everywhere, and never an inefficient codec (it is H.264)
			}
			case COPY -> {
				if (!storageService.videoCopyExists(directoryPath, video.getName())) {
					video.setVideoPlayback(VideoPlayback.PENDING);
					requeueCopy(video, jobs);
				} else {
					reconcileCompaction(video, jobs);
				}
			}
			case PENDING, FAILED -> {
				video.setVideoPlayback(VideoPlayback.PENDING);
				requeueCopy(video, jobs);
			}
		}
	}

	private void requeueCopy(Image video, List<VideoJob> jobs) {
		boolean active = false;
		for (VideoJob job : jobs) {
			if (job.getStage() != VideoJob.Stage.PLAYBACK_COPY) {
				continue;
			}
			if (job.getState() == VideoJob.State.FAILED) {
				jobRepository.delete(job);
			} else {
				active = true;
			}
		}
		if (!active) {
			enqueue(video.getId(), VideoJob.Stage.PLAYBACK_COPY);
		}
	}

	private void reconcileCompaction(Image video, List<VideoJob> jobs) {
		if (!appSettingsService.get().isCompactOriginalVideos()) {
			return;
		}
		for (VideoJob job : jobs) {
			if (job.getStage() == VideoJob.Stage.COMPACT_ORIGINAL) {
				if (job.getState() != VideoJob.State.FAILED) {
					return; // already waiting or running
				}
				jobRepository.delete(job); // retried below, like a failed copy
			}
		}
		VideoInfo info = probeQuietly(video.getDirectory().getPath(), video.getName());
		if (info != null && worthCompacting(info)) {
			enqueue(video.getId(), VideoJob.Stage.COMPACT_ORIGINAL);
		}
	}

	static boolean worthCompacting(VideoInfo info) {
		return info.inefficientCodec() && !info.hdr();
	}

	private VideoInfo probeQuietly(String directoryPath, String name) {
		try {
			return videoFormatValidator.validate(storageService.resolveOriginal(directoryPath, name));
		} catch (RuntimeException e) {
			log.warn("Could not read video {}/{}: {}", directoryPath, name, e.getMessage());
			return null;
		}
	}

	private void enqueue(Long imageId, VideoJob.Stage stage) {
		jobRepository.save(new VideoJob(imageId, stage));
		signal.wakeAfterCommit();
	}

	// ---------------------------------------------------------------- runner side

	/** A job taken by a worker, with the video's path as it was when the job started. */
	public record ClaimedJob(Long jobId, VideoJob.Stage stage, Long imageId, String directoryPath, String name) {
	}

	/**
	 * Takes the next job (copies before compactions, oldest first) and marks
	 * it RUNNING. Jobs whose video is gone are dropped on the way. Callers
	 * serialize calls (VideoJobRunner's lock): the claim must be committed
	 * before another worker looks.
	 */
	@Transactional
	public Optional<ClaimedJob> claimNext() {
		while (true) {
			Optional<VideoJob> next = jobRepository.findNextPending();
			if (next.isEmpty()) {
				return Optional.empty();
			}
			VideoJob job = next.get();
			Optional<Image> video = imageRepository.findById(job.getImageId());
			if (video.isEmpty() || video.get().getMediaType() != MediaType.VIDEO) {
				jobRepository.delete(job);
				jobRepository.flush();
				continue;
			}
			job.setState(VideoJob.State.RUNNING);
			Image image = video.get();
			return Optional.of(new ClaimedJob(job.getId(), job.getStage(), image.getId(), image.getDirectory().getPath(), image.getName()));
		}
	}

	/** The playback copy is encoded: puts it in place, unless the video changed meanwhile (then the job runs again). */
	@Transactional
	public void finishCopy(ClaimedJob claimed, Path encoded, VideoInfo info) {
		Optional<Image> current = stillTheSame(claimed);
		if (current.isEmpty()) {
			deleteQuietly(encoded);
			return;
		}
		Image video = current.get();
		storageService.installVideoCopy(claimed.directoryPath(), claimed.name(), encoded);
		video.setVideoPlayback(VideoPlayback.COPY);
		jobRepository.deleteById(claimed.jobId());
		if (appSettingsService.get().isCompactOriginalVideos() && worthCompacting(info)) {
			enqueue(video.getId(), VideoJob.Stage.COMPACT_ORIGINAL);
		}
	}

	/**
	 * The compacted original is encoded and smaller: replaces the original.
	 * A new container gives a new name (clip.avi becomes clip.mp4, or
	 * clip-1.mp4 if that name is taken). Skipped if the video changed, or its
	 * original was replaced on disk, while encoding.
	 */
	@Transactional
	public void finishCompaction(ClaimedJob claimed, Path encoded, long originalSizeBefore) {
		Optional<Image> current = stillTheSame(claimed);
		if (current.isEmpty()) {
			deleteQuietly(encoded);
			return;
		}
		Image video = current.get();
		Path original = storageService.resolveOriginal(claimed.directoryPath(), claimed.name());
		if (!Files.exists(original) || storageService.fileSize(original) != originalSizeBefore) {
			deleteQuietly(encoded);
			jobRepository.deleteById(claimed.jobId()); // a new original: the next re-index decides again
			return;
		}
		String newName = compactedName(video);
		storageService.replaceVideoOriginal(claimed.directoryPath(), claimed.name(), newName, encoded);
		if (!newName.equals(claimed.name())) {
			video.moveTo(video.getDirectory(), newName);
		}
		jobRepository.deleteById(claimed.jobId());
	}

	private String compactedName(Image video) {
		String name = video.getName();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String extension = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
		if ("mp4".equals(extension)) {
			return name; // same container, same name
		}
		String directoryPath = video.getDirectory().getPath();
		String candidate = base + ".mp4";
		for (int n = 1; storageService.fileExists(directoryPath, candidate)
				|| imageRepository.existsByDirectory_IdAndName(video.getDirectory().getId(), candidate); n++) {
			candidate = base + "-" + n + ".mp4";
		}
		return candidate;
	}

	/** Nothing to gain (not an inefficient codec any more, or the HEVC file wasn't smaller): the job is done. */
	@Transactional
	public void dropJob(ClaimedJob claimed) {
		jobRepository.deleteById(claimed.jobId());
	}

	/** Stopped by a shutdown: back in the queue, not counted as an attempt. */
	@Transactional
	public void requeue(ClaimedJob claimed) {
		jobRepository.findById(claimed.jobId()).ifPresent(job -> job.setState(VideoJob.State.PENDING));
	}

	@Transactional
	public void fail(ClaimedJob claimed, String error) {
		jobRepository.findById(claimed.jobId()).ifPresent(job -> {
			job.setAttempts(job.getAttempts() + 1);
			job.setLastError(error);
			if (job.getAttempts() < MAX_ATTEMPTS) {
				job.setState(VideoJob.State.PENDING);
				return;
			}
			job.setState(VideoJob.State.FAILED);
			log.warn("Giving up on {} of video {}/{} after {} attempts: {}", claimed.stage(), claimed.directoryPath(), claimed.name(),
					job.getAttempts(), error);
			if (claimed.stage() == VideoJob.Stage.PLAYBACK_COPY) {
				imageRepository.findById(claimed.imageId()).ifPresent(video -> video.setVideoPlayback(VideoPlayback.FAILED));
			}
		});
	}

	/** At start-up: jobs left RUNNING by a stop or crash go back to the queue. */
	@Transactional
	public int requeueInterrupted() {
		// Counted first: an UPDATE matching no row logs an HSQLDB "no data" warning.
		return jobRepository.countByState(VideoJob.State.RUNNING) == 0 ? 0 : jobRepository.requeueRunning();
	}

	public record JobCounts(long pending, long running, long failed) {
	}

	@Transactional(readOnly = true)
	public JobCounts counts() {
		return new JobCounts(jobRepository.countByState(VideoJob.State.PENDING), jobRepository.countByState(VideoJob.State.RUNNING),
				jobRepository.countByState(VideoJob.State.FAILED));
	}

	/** The video's row if the job and the video still exist and the video wasn't renamed or moved meanwhile (else the job is requeued or dropped). */
	private Optional<Image> stillTheSame(ClaimedJob claimed) {
		Optional<VideoJob> job = jobRepository.findById(claimed.jobId());
		Optional<Image> video = imageRepository.findById(claimed.imageId());
		if (job.isEmpty() || video.isEmpty()) {
			job.ifPresent(jobRepository::delete);
			return Optional.empty();
		}
		Image image = video.get();
		if (!image.getName().equals(claimed.name()) || !image.getDirectory().getPath().equals(claimed.directoryPath())) {
			job.get().setState(VideoJob.State.PENDING); // runs again on the current path
			signal.wakeAfterCommit();
			return Optional.empty();
		}
		return video;
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (java.io.IOException e) {
			// best effort - the work folder is emptied at the next start anyway
		}
	}
}
