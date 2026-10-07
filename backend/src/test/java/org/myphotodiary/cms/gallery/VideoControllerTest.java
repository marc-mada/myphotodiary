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
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises {@link VideoController#stream} directly (this project's own
 * established style - see ShareControllerTest/GalleryServiceTest, plain Java
 * calls, no MockMvc). Real bug found live on iPad (03/09/2026): a `.mov`
 * upload was served with `Content-Type: video/quicktime` (derived from the
 * original file's own extension via Spring's MediaTypeFactory) - Safari
 * plays that natively, Chrome/Firefox generally don't, regardless of the
 * actual codec inside. Fixed by always serving `video/mp4`, since
 * VideoFormatValidator already guarantees H.264(+AAC) content regardless of
 * the original container - this test locks that in for both a `.mp4`- and a
 * `.mov`-sourced upload, so the two can never again diverge.
 */
@SpringBootTest
@Transactional
class VideoControllerTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
	}

	@Autowired
	private ImportService importService;
	@Autowired
	private VideoController videoController;
	@Autowired
	private UserService userService;

	private Authentication auth;

	private static boolean ffmpegAvailable;

	@BeforeAll
	static void checkFfmpeg() {
		ffmpegAvailable = commandRuns("ffmpeg", "-version") && commandRuns("ffprobe", "-version");
	}

	@BeforeEach
	void setUpAuth() {
		assumeTrue(ffmpegAvailable, "ffmpeg/ffprobe not on PATH - skipping video tests (see VideoFormatValidator's own javadoc)");
		userService.createUser(new CreateUserRequest("video-controller-test-admin", "Video Controller Test Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("video-controller-test-admin", null);
	}

	@Test
	void mp4Upload_streamedAsVideoMp4() throws IOException {
		assertStreamedContentTypeIsVideoMp4(generatedVideo("clip.mp4", ".mp4", "video/mp4"));
	}

	@Test
	void movUpload_stillStreamedAsVideoMp4_notVideoQuicktime() throws IOException {
		assertStreamedContentTypeIsVideoMp4(generatedVideo("clip.mov", ".mov", "video/quicktime"));
	}

	private void assertStreamedContentTypeIsVideoMp4(MockMultipartFile file) throws IOException {
		ImportResponse result = importService.importImage(auth, file, false, false, null, "video-controller-" + Long.toString(System.nanoTime(), 36), null);
		Long imageId = result.image().id();
		String token = videoController.token(auth, imageId).streamUrl().replaceAll(".*token=", "");

		ResponseEntity<ResourceRegion> response = videoController.stream(imageId, token, new HttpHeaders());

		assertThat(response.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.valueOf("video/mp4"));
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

	// Same fixture-generation approach as VideoImportTest (real ffmpeg
	// output, not fabricated bytes) - duplicated rather than shared across
	// test classes, consistent with this project's existing style of
	// self-contained test fixtures per class.
	private static MockMultipartFile generatedVideo(String name, String suffix, String contentType) throws IOException {
		Path temp = Files.createTempFile("mpd-test-video-", suffix);
		Files.delete(temp);
		try {
			List<String> command = new ArrayList<>(List.of(
					"ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x64:rate=5",
					"-c:v", "libx264", "-pix_fmt", "yuv420p", "-t", "1", temp.toString()));
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
