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
 * Reads a video with ffprobe (bundled with ffmpeg, already this feature's
 * dependency for thumbnails - see VideoThumbnailExtractor) and rejects only
 * what isn't a readable video at all.
 *
 * Until 27/10/2026 this enforced "H.264 + AAC only" (no transcoding - a
 * browser had to play the file as uploaded). Since then any video ffmpeg can
 * read is accepted - HEVC from iPhones, AVI/DV/MPEG-2 from old cameras and
 * camcorders, MKV... - and {@link VideoProcessingService} makes an H.264
 * copy when the original isn't playable everywhere. The codec still has to
 * be probed rather than trusted from the extension: an iPhone's HEVC can sit
 * in a `.mov` or a `.mp4` alike.
 */
@Component
public class VideoFormatValidator {

	private final String ffprobePath;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public VideoFormatValidator(@Value("${app.video.ffprobe-path:ffprobe}") String ffprobePath) {
		this.ffprobePath = ffprobePath;
	}

	/** @throws IllegalArgumentException if the file isn't a readable video (no video stream). */
	public VideoInfo validate(Path file) {
		JsonNode root = probe(file);
		JsonNode streams = root.path("streams");
		if (!streams.isArray() || streams.isEmpty()) {
			throw new IllegalArgumentException("Not a readable video file (ffprobe found no streams)");
		}
		JsonNode video = null;
		JsonNode audio = null;
		for (JsonNode stream : streams) {
			String codecType = stream.path("codec_type").asText("");
			// An attached picture (cover art) is reported as a video stream too - not the video.
			boolean coverArt = stream.path("disposition").path("attached_pic").asInt(0) == 1;
			if ("video".equals(codecType) && !coverArt && video == null) {
				video = stream;
			} else if ("audio".equals(codecType) && audio == null) {
				audio = stream;
			}
		}
		if (video == null) {
			throw new IllegalArgumentException("File has no video stream");
		}
		JsonNode format = root.path("format");
		String transfer = video.path("color_transfer").asText("");
		String fieldOrder = video.path("field_order").asText("progressive");
		return new VideoInfo(
				format.path("format_name").asText(""),
				video.path("codec_name").asText(""),
				audio == null ? null : audio.path("codec_name").asText(""),
				video.path("width").asInt(0),
				video.path("height").asInt(0),
				frameRate(video),
				format.path("bit_rate").asLong(0),
				video.path("pix_fmt").asText(""),
				!"progressive".equals(fieldOrder) && !"unknown".equals(fieldOrder) && !fieldOrder.isEmpty(),
				"smpte2084".equals(transfer) || "arib-std-b67".equals(transfer),
				format.path("duration").asDouble(0));
	}

	/** avg_frame_rate when known, else r_frame_rate - both are fractions like "30000/1001". */
	private static double frameRate(JsonNode video) {
		double avg = fraction(video.path("avg_frame_rate").asText(""));
		return avg > 0 ? avg : fraction(video.path("r_frame_rate").asText(""));
	}

	private static double fraction(String value) {
		int slash = value.indexOf('/');
		try {
			if (slash < 0) {
				return value.isEmpty() ? 0 : Double.parseDouble(value);
			}
			double den = Double.parseDouble(value.substring(slash + 1));
			return den == 0 ? 0 : Double.parseDouble(value.substring(0, slash)) / den;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private JsonNode probe(Path file) {
		List<String> command = List.of(ffprobePath, "-v", "error", "-print_format", "json", "-show_streams", "-show_format", file.toString());
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
