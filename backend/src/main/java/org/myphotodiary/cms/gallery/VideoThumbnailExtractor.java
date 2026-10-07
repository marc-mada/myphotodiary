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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Extracts a video's first frame as a JPEG - the video half of "extract the
 * first image of the video for the thumbnail in the filmstrip" (explicit
 * ask, 28/08/2026). Thumbnailator (this project's image-resize library)
 * can't touch video at all - it's an ImageIO wrapper - so this shells out to
 * ffmpeg instead, then hands the extracted frame back to
 * ImageStorageService, which resizes it through the exact same Thumbnailator
 * call a photo's own thumbnail goes through - the filmstrip doesn't need to
 * know or care that a given 128px thumbnail came from a photo or a video
 * frame.
 */
@Component
public class VideoThumbnailExtractor {

	private final String ffmpegPath;

	public VideoThumbnailExtractor(@Value("${app.video.ffmpeg-path:ffmpeg}") String ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
	}

	/** Extracts the first frame of {@code videoFile} to a fresh temp JPEG file - caller owns cleanup. */
	public Path extractFirstFrame(Path videoFile) {
		Path frameFile;
		try {
			frameFile = Files.createTempFile("mpd-video-frame-", ".jpg");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// -y: overwrite the just-created empty temp file. -vframes 1: exactly
		// one frame. -q:v 2: high JPEG quality (ffmpeg's mjpeg scale is 2-31,
		// lower is better) - Thumbnailator recompresses it down to 128px
		// right after anyway, so this only needs to be good enough as input,
		// not final-delivery quality.
		List<String> command = List.of(ffmpegPath, "-y", "-i", videoFile.toString(), "-vframes", "1", "-q:v", "2", frameFile.toString());
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			byte[] ffmpegOutput = process.getInputStream().readAllBytes(); // drain to avoid the process blocking on a full pipe
			boolean finished = process.waitFor(30, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IllegalStateException("ffmpeg timed out extracting a frame from " + videoFile.getFileName());
			}
			if (process.exitValue() != 0 || Files.size(frameFile) == 0) {
				throw new IllegalStateException(
						"ffmpeg failed to extract a frame from " + videoFile.getFileName() + ": " + new String(ffmpegOutput));
			}
			return frameFile;
		} catch (IOException e) {
			throw new IllegalStateException("Could not run ffmpeg (is ffmpeg installed and on PATH?): " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while extracting a frame from " + videoFile.getFileName(), e);
		}
	}
}
