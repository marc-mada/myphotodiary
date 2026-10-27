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

import java.util.Locale;
import java.util.Set;

/**
 * What ffprobe found in a video (27/10/2026) - enough to decide whether a
 * browser can play the original as is, or needs an H.264 copy, and whether
 * the original is worth compacting (see {@link VideoProcessingService}).
 *
 * {@code width}/{@code height} are the stored (coded) ones; a phone's
 * rotation flag doesn't matter here because every limit below is checked
 * on the long and short sides, whatever the orientation.
 */
public record VideoInfo(
		String container,
		String videoCodec,
		/** {@code null} when the video has no sound. */
		String audioCodec,
		int width,
		int height,
		double frameRate,
		/** Whole-file bit rate in bits per second, 0 if ffprobe couldn't tell. */
		long bitRate,
		String pixelFormat,
		boolean interlaced,
		boolean hdr,
		double durationSeconds) {

	/** Playback copy box: 1080p, either orientation. */
	public static final int COPY_LONG_SIDE = 1920;
	public static final int COPY_SHORT_SIDE = 1080;
	public static final double COPY_MAX_FPS = 30;
	/** Above this, even a 1080p H.264 original is heavy to stream (a camera at 24-50 Mbit/s). */
	public static final long COPY_MAX_BITRATE = 8_000_000;

	/** Compacted original box: 4K, either orientation. */
	public static final int COMPACT_LONG_SIDE = 3840;
	public static final int COMPACT_SHORT_SIDE = 2160;
	public static final double COMPACT_MAX_FPS = 60;

	/** Codecs whose files are several times larger than HEVC at the same quality. */
	private static final Set<String> INEFFICIENT_CODECS = Set.of(
			"prores", "dvvideo", "mjpeg", "mpeg1video", "mpeg2video", "mpeg4", "h263", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3",
			"wmv1", "wmv2", "wmv3", "vc1", "rawvideo", "cinepak", "svq1", "svq3", "flv1", "theora", "huffyuv", "ffv1", "dnxhd");

	/**
	 * Whether every browser can play the original as is: H.264 8-bit 4:2:0 in
	 * MP4/MOV, AAC or no sound, within the copy box, frame rate and bit rate,
	 * progressive, not HDR. Every video stored before 27/10/2026 was H.264.
	 */
	public boolean playableAsIs() {
		return "h264".equals(videoCodec)
				&& (audioCodec == null || "aac".equals(audioCodec))
				&& isMp4Family()
				&& "yuv420p".equals(pixelFormat)
				&& fitsIn(COPY_LONG_SIDE, COPY_SHORT_SIDE)
				&& frameRate <= COPY_MAX_FPS + 0.5
				&& (bitRate == 0 || bitRate <= COPY_MAX_BITRATE)
				&& !interlaced
				&& !hdr;
	}

	public boolean inefficientCodec() {
		return INEFFICIENT_CODECS.contains(videoCodec);
	}

	public boolean fitsIn(int longSide, int shortSide) {
		return Math.max(width, height) <= longSide && Math.min(width, height) <= shortSide;
	}

	/** 10/12-bit sources keep 10 bits when compacted (HEVC Main 10). */
	public boolean highBitDepth() {
		String fmt = pixelFormat == null ? "" : pixelFormat.toLowerCase(Locale.ROOT);
		return fmt.contains("10") || fmt.contains("12");
	}

	/** ffprobe reports MP4 and MOV as the same demuxer, "mov,mp4,m4a,3gp,3g2,mj2". */
	private boolean isMp4Family() {
		return container != null && container.contains("mp4");
	}
}
