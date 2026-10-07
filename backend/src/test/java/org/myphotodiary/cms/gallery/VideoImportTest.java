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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.ImportResponse;
import org.myphotodiary.cms.settings.AppSettingsService;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Video support (28/08/2026 - "have a talk about a new topic not in the
 * legacy: Video"). Real ffmpeg-generated fixtures, not mocked bytes with a
 * .mp4 extension slapped on - VideoFormatValidator's whole point is codec-
 * level inspection (ffprobe), so a fixture that isn't real H.264 wouldn't
 * exercise it meaningfully. Skips entirely (assumeTrue) if ffmpeg isn't on
 * PATH, rather than failing - this feature's own hard dependency
 * (VideoFormatValidator/VideoThumbnailExtractor's own javadoc), not
 * something every environment running this suite is guaranteed to have.
 */
@SpringBootTest
@Transactional
class VideoImportTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
	}

	@Autowired
	private ImportService importService;
	@Autowired
	private ImageRepository imageRepository;
	@Autowired
	private UserService userService;
	@Autowired
	private AppSettingsService appSettingsService;

	private Authentication auth;

	private static boolean ffmpegAvailable;

	@BeforeAll
	static void checkFfmpeg() {
		ffmpegAvailable = commandRuns("ffmpeg", "-version") && commandRuns("ffprobe", "-version");
	}

	@BeforeEach
	void setUpAuth() {
		assumeTrue(ffmpegAvailable, "ffmpeg/ffprobe not on PATH - skipping video tests (see VideoFormatValidator's own javadoc)");
		userService.createUser(new CreateUserRequest("video-test-admin", "Video Test Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("video-test-admin", null);
	}

	@Test
	void validH264Mp4_importsAsVideo_withThumbnailFromFirstFrame() throws IOException {
		MockMultipartFile file = h264Mp4File("clip.mp4");

		ImportResponse result = importService.importImage(auth, file, false, false, null, "videos", null);

		assertThat(result.image().mediaType()).isEqualTo("VIDEO");
		Path original = storageRoot.resolve("videos").resolve("clip.mp4");
		// .jpg appended, not the video's own name mirrored verbatim - see
		// ImageStorageService.resolveVideoThumbnail's own comment for why.
		// .thumbnails co-located inside the sequence directory (31/08/2026 restructure, matches legacy exactly).
		Path thumbnail = storageRoot.resolve("videos").resolve(".thumbnails").resolve("clip.mp4.jpg");
		assertThat(Files.exists(original)).isTrue();
		assertThat(Files.exists(thumbnail)).isTrue();
		// The thumbnail is a real JPEG (Thumbnailator ran against the
		// extracted frame, not just an empty/placeholder file) - a decodable
		// image, not merely a non-empty one.
		assertThat(javax.imageio.ImageIO.read(thumbnail.toFile())).isNotNull();
		assertThat(imageRepository.findById(result.image().id())).isPresent();
	}

	@Test
	void movContainer_h264_importsAsVideo_notMisroutedAsImage() throws IOException {
		// Real bug found live on iPad (03/09/2026): MobileMoviePage's own
		// native camera capture always produces a .mov QuickTime container,
		// regardless of this app's own accept="video/mp4" hint (that
		// attribute only steers *picker* file-type filtering, never the
		// format a device's camera itself records in) - VIDEO_EXTENSIONS
		// used to only recognize "mp4", so this fell through to
		// detectMediaType's MediaType.IMAGE default branch and crashed
		// trying to decode video bytes as a picture, never even reaching
		// VideoFormatValidator (which was always container-agnostic, see
		// its own javadoc).
		MockMultipartFile file = h264MovFile("clip.mov");

		ImportResponse result = importService.importImage(auth, file, false, false, null, "videos", null);

		assertThat(result.image().mediaType()).isEqualTo("VIDEO");
		Path original = storageRoot.resolve("videos").resolve("clip.mov");
		assertThat(Files.exists(original)).isTrue();
	}

	@Test
	void wrongCodec_rejected_notH264() throws IOException {
		MockMultipartFile file = mpeg4Mp4File("wrong-codec.mp4");

		assertThatThrownBy(() -> importService.importImage(auth, file, false, false, null, "videos", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("H.264");
	}

	@Test
	void garbageFile_rejected_notReadableAsVideo() {
		MockMultipartFile file = new MockMultipartFile("file", "not-a-video.mp4", "video/mp4", "just some bytes, not a real mp4".getBytes());

		assertThatThrownBy(() -> importService.importImage(auth, file, false, false, null, "videos", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exceedsConfiguredMaxSize_rejected() throws IOException {
		appSettingsService.updateMaxVideoSizeBytes(100); // far smaller than any real fixture
		MockMultipartFile file = h264Mp4File("too-big.mp4");

		assertThatThrownBy(() -> importService.importImage(auth, file, false, false, null, "videos", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exceeds");
	}

	@Test
	void videoUpload_stillGoesThroughRbac_lowerRoleRejected() throws IOException {
		userService.createUser(new CreateUserRequest("video-test-lower", "Video Test Lower", "x", "public", "LOWER"));
		Authentication lowerAuth = new UsernamePasswordAuthenticationToken("video-test-lower", null);
		MockMultipartFile file = h264Mp4File("lower-role.mp4");

		assertThatThrownBy(() -> importService.importImage(lowerAuth, file, false, false, null, "videos-lower", null))
				.isInstanceOf(AccessDeniedException.class);
	}

	private static boolean commandRuns(String... command) {
		try {
			Process process = new ProcessBuilder(command).start();
			process.getInputStream().readAllBytes();
			process.getErrorStream().readAllBytes();
			return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}

	private static MockMultipartFile h264Mp4File(String name) throws IOException {
		return generatedVideo(name, "libx264", ".mp4", "video/mp4");
	}

	private static MockMultipartFile mpeg4Mp4File(String name) throws IOException {
		return generatedVideo(name, "mpeg4", ".mp4", "video/mp4");
	}

	// Same H.264+AAC content as h264Mp4File, just muxed into a QuickTime
	// container instead of an MP4 one - real content-type an iOS camera
	// capture actually sends (movContainer_h264_importsAsVideo test above),
	// not something VideoFormatValidator itself distinguishes (it inspects
	// codecs via ffprobe, never the container) but exactly the thing
	// detectMediaType's own extension-based routing needs to get right.
	private static MockMultipartFile h264MovFile(String name) throws IOException {
		return generatedVideo(name, "libx264", ".mov", "video/quicktime");
	}

	private static MockMultipartFile generatedVideo(String name, String videoCodec, String suffix, String contentType) throws IOException {
		Path temp = Files.createTempFile("mpd-test-video-", suffix);
		Files.delete(temp); // ffmpeg -y below (re)creates it, but the file must not exist as a non-shaped empty file first
		try {
			// All -i inputs come first, codec/output options after - ffmpeg
			// reinterprets output options placed before a later -i as input
			// (decoder) options for that next input, which is exactly what
			// broke the first version of this fixture generator ("Unknown
			// decoder 'libx264'" - libx264 is an *encoder*, ffmpeg was trying
			// to use it to decode the second -i because of argument order).
			List<String> command = new ArrayList<>(List.of("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x64:rate=5"));
			boolean withAudio = "libx264".equals(videoCodec);
			if (withAudio) {
				command.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono"));
			}
			command.addAll(List.of("-c:v", videoCodec, "-pix_fmt", "yuv420p", "-t", "1"));
			if (withAudio) {
				command.addAll(List.of("-c:a", "aac", "-shortest")); // exercises the audio-codec branch too, not just video
			}
			command.add(temp.toString());
			Process process = new ProcessBuilder(command).start();
			process.getInputStream().readAllBytes();
			byte[] err = process.getErrorStream().readAllBytes();
			boolean finished = process.waitFor(30, TimeUnit.SECONDS);
			if (!finished || process.exitValue() != 0 || Files.size(temp) == 0) {
				throw new IllegalStateException("Failed to generate test fixture with ffmpeg: " + new String(err));
			}
			byte[] bytes = Files.readAllBytes(temp);
			return new MockMultipartFile("file", name, contentType, bytes);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
