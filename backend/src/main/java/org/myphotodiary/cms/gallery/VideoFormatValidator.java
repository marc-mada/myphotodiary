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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Enforces the "strict MP4/H.264 only" policy decided during the video talk
 * (28/08/2026) - no server-side transcoding, so whatever's uploaded has to
 * already be playable by a plain HTML5 {@code <video>} tag everywhere,
 * including Safari/iOS (no native WebM; HEVC plays there but not reliably
 * elsewhere). Trusting the file extension/declared content-type alone isn't
 * enough for a real guarantee: an iPhone's default HEVC recording is
 * commonly muxed in a `.mov` container, but HEVC-in-`.mp4` is also possible,
 * so the container extension alone can't tell H.264 and HEVC apart - only
 * probing the actual codec can. Uses ffprobe (bundled with ffmpeg, already a
 * hard dependency of this feature for thumbnail extraction - see
 * VideoThumbnailExtractor) rather than a Java video-parsing library, same
 * "invoke the standard external tool" choice as that class.
 */
@Component
public class VideoFormatValidator {

	private final String ffprobePath;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public VideoFormatValidator(@Value("${app.video.ffprobe-path:ffprobe}") String ffprobePath) {
		this.ffprobePath = ffprobePath;
	}

	/** @throws IllegalArgumentException if the file isn't a valid H.264 (+ AAC or no audio) MP4. */
	public void validate(Path file) {
		JsonNode root = probe(file);
		JsonNode streams = root.path("streams");
		if (!streams.isArray() || streams.isEmpty()) {
			throw new IllegalArgumentException("Not a readable video file (ffprobe found no streams)");
		}

		boolean hasVideoStream = false;
		for (JsonNode stream : streams) {
			String codecType = stream.path("codec_type").asText("");
			String codecName = stream.path("codec_name").asText("");
			if ("video".equals(codecType)) {
				hasVideoStream = true;
				if (!"h264".equals(codecName)) {
					throw new IllegalArgumentException(
							"Video codec must be H.264 (found: " + codecName + "). "
									+ "Phones often record HEVC by default (e.g. iPhone's \"High Efficiency\" setting) - "
									+ "please re-export/convert to H.264 MP4 before uploading.");
				}
			} else if ("audio".equals(codecType) && !"aac".equals(codecName)) {
				throw new IllegalArgumentException("Audio codec must be AAC (found: " + codecName + ")");
			}
		}
		if (!hasVideoStream) {
			throw new IllegalArgumentException("File has no video stream");
		}
	}

	private JsonNode probe(Path file) {
		List<String> command = List.of(ffprobePath, "-v", "error", "-print_format", "json", "-show_streams", file.toString());
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
			byte[] output = process.getInputStream().readAllBytes();
			boolean finished = process.waitFor(30, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IllegalStateException("ffprobe timed out probing " + file.getFileName());
			}
			if (process.exitValue() != 0) {
				throw new IllegalArgumentException("Not a valid video file (ffprobe exit code " + process.exitValue() + ")");
			}
			return objectMapper.readTree(output);
		} catch (IOException e) {
			// ffprobe not found/not runnable - a server misconfiguration
			// (this feature's own documented dependency, see Design.md §8), not
			// something the uploader did wrong - distinct from the
			// IllegalArgumentException cases above, which are about the file.
			throw new IllegalStateException("Could not run ffprobe (is ffmpeg installed and on PATH?): " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while probing " + file.getFileName(), e);
		}
	}
}
