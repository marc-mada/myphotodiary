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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.CreateDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.ShareLinkResponse;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises {@link ShareLinkPreviewController} directly (this project's own
 * established style, plain Java calls to the controller - see
 * ShareControllerTest's own doc). {@code app.public-base-url} is left unset
 * here on purpose - every test below exercises the request-derived fallback
 * (the {@code MockHttpServletRequest}'s own scheme/host/port), proving that
 * branch; the "explicit config wins regardless of the request" branch has
 * its own separate, minimal test class below - a different property value
 * needs a genuinely different Spring context, not something this class's
 * own single context can flip per-test.
 */
@SpringBootTest
@Transactional
class ShareLinkPreviewControllerTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
	}

	@Autowired
	private ShareLinkPreviewController previewController;
	@Autowired
	private ShareController shareController;
	@Autowired
	private GalleryService galleryService;
	@Autowired
	private DirectoryRepository directoryRepository;
	@Autowired
	private ImageRepository imageRepository;
	@Autowired
	private UserService userService;

	private Long directoryId;
	private Authentication auth;

	@BeforeEach
	void setUp() {
		userService.createUser(new CreateUserRequest("share-preview-admin", "Share Preview Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("share-preview-admin", null);
		DirectoryResponse dir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/share-preview-" + Long.toString(System.nanoTime(), 36), null));
		directoryId = dir.id();
	}

	private static MockHttpServletRequest httpsRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setScheme("https");
		request.setServerName("myphotodiary.example.com");
		request.setServerPort(443);
		return request;
	}

	@Test
	void imagePreview_includesOgImagePointingAtThisImagesOwnWebDerivative() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("shared.jpg"));
		ShareLinkResponse link = shareController.imageShareToken(auth, uploaded.id());

		String html = previewController.imagePreview(uploaded.id(), link.token(), httpsRequest());

		assertThat(html).contains("og:title").contains("myPhotoDiary");
		assertThat(html).contains("og:image").contains("/api/public/images/" + uploaded.id() + "/web?token=");
		// The request-derived fallback (app.public-base-url unset) - the
		// scheme/host this MockHttpServletRequest was given, not localhost or
		// some other default.
		assertThat(html).contains("https://myphotodiary.example.com/api/public/images/");
		assertThat(html).contains("og:url").contains("https://myphotodiary.example.com/share/image/" + uploaded.id());
	}

	@Test
	void imagePreview_omitsOgImage_forAVideo() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, LocalDateTime.now(), MediaType.VIDEO));
		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);

		String html = previewController.imagePreview(video.getId(), link.token(), httpsRequest());

		assertThat(html).doesNotContain("og:image");
	}

	@Test
	void imagePreview_rejectsATokenThatDoesNotGrantAccessToThisImage() throws IOException {
		ImageResponse shared = galleryService.uploadImage(auth, directoryId, jpegFile("shared.jpg"));
		ImageResponse other = galleryService.uploadImage(auth, directoryId, jpegFile("other.jpg"));
		ShareLinkResponse link = shareController.imageShareToken(auth, shared.id());

		assertThatThrownBy(() -> previewController.imagePreview(other.id(), link.token(), httpsRequest()))
				.isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void sequencePreview_includesOgImagePointingAtTheFirstImageByName() throws IOException {
		// "b.jpg" uploaded first but "a.jpg" sorts first by name - same
		// ordering PublicSequenceResponse's own filmstrip already uses
		// (findByDirectory_IdOrderByNameAsc), not upload order.
		galleryService.uploadImage(auth, directoryId, jpegFile("b.jpg"));
		ImageResponse first = galleryService.uploadImage(auth, directoryId, jpegFile("a.jpg"));
		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);

		String html = previewController.sequencePreview(directoryId, link.token(), httpsRequest());

		assertThat(html).contains("og:image").contains("/api/public/images/" + first.id() + "/web?token=");
	}

	@Test
	void sequencePreview_omitsOgImage_whenTheSequenceHasNoRealPhotosYet() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		imageRepository.save(new Image("clip.mp4", directory, LocalDateTime.now(), MediaType.VIDEO));
		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);

		String html = previewController.sequencePreview(directoryId, link.token(), httpsRequest());

		assertThat(html).doesNotContain("og:image");
	}

	@Test
	void sequencePreview_rejectsATokenForADifferentSequence() throws IOException {
		DirectoryResponse otherDir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/share-preview-other-" + Long.toString(System.nanoTime(), 36), null));
		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);

		assertThatThrownBy(() -> previewController.sequencePreview(otherDir.id(), link.token(), httpsRequest()))
				.isInstanceOf(InvalidShareTokenException.class);
	}

	private static MockMultipartFile jpegFile(String name) throws IOException {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return new MockMultipartFile("file", name, "image/jpeg", out.toByteArray());
	}
}
