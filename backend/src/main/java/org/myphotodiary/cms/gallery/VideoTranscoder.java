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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * The two ffmpeg encodes of option C (27/10/2026) - see
 * {@link VideoProcessingService} for when each one runs.
 *
 * <ul>
 * <li>{@link #makePlaybackCopy}: H.264 + AAC in MP4, fitted inside 1920x1080
 * (either orientation, never upscaled), at most 30 fps, CRF 23 capped at
 * 6 Mbit/s, AAC stereo 128 kbit/s, "faststart" so playback starts before the
 * whole file is downloaded. Interlaced sources (DV, MPEG-2, AVCHD 1080i) are
 * deinterlaced; HDR (iPhone Dolby Vision/HLG) is tone-mapped to SDR when
 * ffmpeg has the zscale filter (Ubuntu's ffmpeg does), otherwise only
 * converted (playable, colours a little flat).</li>
 * <li>{@link #compactOriginal}: HEVC (x265, CRF 24, tagged hvc1 so Safari
 * plays it) fitted inside 3840x2160, at most 60 fps, 10 bits kept for a 10-bit
 * source, sound re-encoded to AAC 192 kbit/s unless already AAC, metadata
 * (creation date, location) copied.</li>
 * </ul>
 *
 * Runs ffmpeg under {@code nice} when available, so a long batch doesn't
 * slow down browsing. Every running process is killed on shutdown; its job
 * is put back in the queue at the next start.
 */
@Component
public class VideoTranscoder {

	private static final Logger log = LoggerFactory.getLogger(VideoTranscoder.class);

	private final String ffmpegPath;
	private final Set<Process> running = ConcurrentHashMap.newKeySet();
	private volatile Boolean zscaleAvailable;
	private volatile boolean shuttingDown;

	public VideoTranscoder(@Value("${app.video.ffmpeg-path:ffmpeg}") String ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
	}

	public void makePlaybackCopy(Path source, VideoInfo info, Path target) {
		List<String> filters = new ArrayList<>();
		if (info.interlaced()) {
			// One output frame per input frame (50i -> 25p): bwdif's default,
			// one frame per field (50p), would then be cut to 30 fps unevenly.
			filters.add("bwdif=mode=send_frame");
		}
		if (info.hdr() && hasZscale()) {
			filters.add("zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv");
		}
		filters.add(fitFilter(VideoInfo.COPY_LONG_SIDE, VideoInfo.COPY_SHORT_SIDE));
		filters.add("format=yuv420p");

		List<String> args = new ArrayList<>(List.of("-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn",
				"-vf", String.join(",", filters),
				"-c:v", "libx264", "-preset", "medium", "-crf", "23", "-maxrate", "6M", "-bufsize", "12M", "-profile:v", "high",
				"-fpsmax", String.valueOf((int) VideoInfo.COPY_MAX_FPS),
				"-c:a", "aac", "-b:a", "128k", "-ac", "2",
				"-map_metadata", "-1", "-movflags", "+faststart", "-f", "mp4"));
		run(source, args, target, timeoutFor(info, 10));
	}

	public void compactOriginal(Path source, VideoInfo info, Path target) {
		List<String> filters = new ArrayList<>();
		if (info.interlaced()) {
			// One frame per field (50i -> 50p, 60i -> 60p): keeps all the
			// motion of the original, within the 60 fps cap.
			filters.add("bwdif=mode=send_field");
		}
		filters.add(fitFilter(VideoInfo.COMPACT_LONG_SIDE, VideoInfo.COMPACT_SHORT_SIDE));
		filters.add(info.highBitDepth() ? "format=yuv420p10le" : "format=yuv420p");

		List<String> args = new ArrayList<>(List.of("-map", "0:v:0", "-map", "0:a?", "-sn", "-dn",
				"-vf", String.join(",", filters),
				"-c:v", "libx265", "-preset", "medium", "-crf", "24", "-tag:v", "hvc1", "-x265-params", "log-level=error",
				"-fpsmax", String.valueOf((int) VideoInfo.COMPACT_MAX_FPS)));
		if ("aac".equals(info.audioCodec())) {
			args.addAll(List.of("-c:a", "copy"));
		} else {
			args.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
		}
		args.addAll(List.of("-map_metadata", "0", "-movflags", "+faststart+use_metadata_tags", "-f", "mp4"));
		run(source, args, target, timeoutFor(info, 40));
	}

	/**
	 * Fits the frame inside a long-side x short-side box whatever its
	 * orientation (ffmpeg has already applied a phone's rotation flag at this
	 * point), never upscales, keeps even dimensions (required by x264/x265).
	 */
	static String fitFilter(int longSide, int shortSide) {
		return "scale=w='if(gte(iw,ih),min(" + longSide + ",iw),min(" + shortSide + ",iw))'"
				+ ":h='if(gte(iw,ih),min(" + shortSide + ",ih),min(" + longSide + ",ih))'"
				+ ":force_original_aspect_ratio=decrease:force_divisible_by=2";
	}

	/** Generous: real time x {@code factor}, plus 10 minutes (unknown duration: 6 hours). */
	private static long timeoutFor(VideoInfo info, int factor) {
		if (info.durationSeconds() <= 0) {
			return TimeUnit.HOURS.toSeconds(6);
		}
		return (long) (info.durationSeconds() * factor) + TimeUnit.MINUTES.toSeconds(10);
	}

	private void run(Path source, List<String> outputArgs, Path target, long timeoutSeconds) {
		if (shuttingDown) {
			throw new TranscodeInterruptedException();
		}
		List<String> command = new ArrayList<>();
		if (Files.isExecutable(Path.of("/usr/bin/nice"))) {
			command.addAll(List.of("/usr/bin/nice", "-n", "10"));
		}
		command.addAll(List.of(ffmpegPath, "-hide_banner", "-nostdin", "-y", "-i", source.toString()));
		command.addAll(outputArgs);
		command.add(target.toString());

		Process process;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(true).start();
		} catch (IOException e) {
			throw new IllegalStateException("Could not run ffmpeg (is ffmpeg installed and on PATH?): " + e.getMessage(), e);
		}
		running.add(process);
		try {
			String output = lastChars(process.getInputStream(), 2000);
			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IllegalStateException("ffmpeg timed out after " + timeoutSeconds + " s on " + source.getFileName());
			}
			if (shuttingDown) {
				throw new TranscodeInterruptedException();
			}
			if (process.exitValue() != 0 || !Files.exists(target) || Files.size(target) == 0) {
				throw new IllegalStateException("ffmpeg failed on " + source.getFileName() + ": " + output.strip());
			}
		} catch (IOException e) {
			throw new IllegalStateException("ffmpeg failed on " + source.getFileName() + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new TranscodeInterruptedException();
		} finally {
			running.remove(process);
		}
	}

	/** Drains the whole output (so ffmpeg never blocks on a full pipe), keeping only the end for error messages. */
	private static String lastChars(InputStream in, int max) throws IOException {
		StringBuilder tail = new StringBuilder();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) >= 0) {
			tail.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
			if (tail.length() > 4 * max) {
				tail.delete(0, tail.length() - max);
			}
		}
		return tail.length() > max ? tail.substring(tail.length() - max) : tail.toString();
	}

	private boolean hasZscale() {
		Boolean available = zscaleAvailable;
		if (available == null) {
			available = false;
			try {
				Process process = new ProcessBuilder(ffmpegPath, "-hide_banner", "-filters").redirectErrorStream(true).start();
				String filters = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				process.waitFor(10, TimeUnit.SECONDS);
				available = filters.contains(" zscale ");
			} catch (IOException e) {
				// treated as unavailable
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (!available) {
				log.info("ffmpeg has no zscale filter: HDR videos get a playback copy without tone mapping");
			}
			zscaleAvailable = available;
		}
		return available;
	}

	@PreDestroy
	void shutdown() {
		shuttingDown = true;
		running.forEach(Process::destroyForcibly);
	}

	/** Thrown when ffmpeg was stopped by a shutdown - the job goes back to the queue, not counted as a failure. */
	public static class TranscodeInterruptedException extends RuntimeException {
		public TranscodeInterruptedException() {
			super("Transcoding interrupted by shutdown");
		}
	}
}
