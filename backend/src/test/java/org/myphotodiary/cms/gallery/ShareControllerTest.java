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
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.CreateDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.PublicImageResponse;
import org.myphotodiary.cms.gallery.dto.PublicSequenceResponse;
import org.myphotodiary.cms.gallery.dto.ShareLinkResponse;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises {@link ShareController} directly, same discipline as
 * GalleryServiceTest - real files under a temp directory, no MockMvc (this
 * project's own established style, GalleryServiceTest/VideoImportTest etc.
 * all call the controller/service layer as plain Java, not through
 * simulated HTTP).
 */
@SpringBootTest
@Transactional
class ShareControllerTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
	}

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
		userService.createUser(new CreateUserRequest("share-test-admin", "Share Test Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("share-test-admin", null);
		DirectoryResponse dir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/share-test-" + Long.toString(System.nanoTime(), 36), null));
		directoryId = dir.id();
	}

	@Test
	void imageShareToken_grantsPublicAccessToThatImageOnly() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("shared.jpg"));

		ShareLinkResponse link = shareController.imageShareToken(auth, uploaded.id());
		PublicImageResponse publicImage = shareController.publicImage(uploaded.id(), link.token());

		assertThat(publicImage.id()).isEqualTo(uploaded.id());
		assertThat(publicImage.name()).isEqualTo("shared.jpg");
		// Real, decodable bytes through the public endpoint - not just "a
		// response came back" - same discipline as GalleryServiceTest's own
		// thumbnail/web assertions.
		try (InputStream web = readBody(shareController.publicWeb(uploaded.id(), link.token()).getBody())) {
			assertThat(ImageIO.read(web)).isNotNull();
		}
		try (InputStream thumb = readBody(shareController.publicThumbnail(uploaded.id(), link.token()).getBody())) {
			assertThat(ImageIO.read(thumb)).isNotNull();
		}
	}

	@Test
	void imageShareToken_doesNotGrantAccessToAnotherImageInTheSameSequence() throws IOException {
		ImageResponse shared = galleryService.uploadImage(auth, directoryId, jpegFile("shared.jpg"));
		ImageResponse other = galleryService.uploadImage(auth, directoryId, jpegFile("other.jpg"));

		ShareLinkResponse link = shareController.imageShareToken(auth, shared.id());

		assertThatThrownBy(() -> shareController.publicImage(other.id(), link.token())).isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void sequenceShareToken_grantsPublicAccessToEveryImageInIt() throws IOException {
		ImageResponse first = galleryService.uploadImage(auth, directoryId, jpegFile("a.jpg"));
		ImageResponse second = galleryService.uploadImage(auth, directoryId, jpegFile("b.jpg"));

		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);
		PublicSequenceResponse sequence = shareController.publicSequence(directoryId, link.token());

		assertThat(sequence.images()).extracting(PublicImageResponse::id).containsExactlyInAnyOrder(first.id(), second.id());
		// Same token also grants access to each individual image, not just the listing.
		PublicImageResponse publicFirst = shareController.publicImage(first.id(), link.token());
		assertThat(publicFirst.id()).isEqualTo(first.id());
	}

	@Test
	void sequenceShareToken_excludesVideoFromTheListing() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		imageRepository.save(new Image("clip.mp4", directory, LocalDateTime.now(), MediaType.VIDEO));

		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);
		PublicSequenceResponse sequence = shareController.publicSequence(directoryId, link.token());

		assertThat(sequence.images()).isEmpty();
	}

	@Test
	void sequenceShareToken_doesNotGrantAccessToADifferentSequence() throws IOException {
		DirectoryResponse otherDir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/other-" + Long.toString(System.nanoTime(), 36), null));

		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);

		assertThatThrownBy(() -> shareController.publicSequence(otherDir.id(), link.token())).isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void imageShareToken_rejectsVideoOutright() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, LocalDateTime.now(), MediaType.VIDEO));

		assertThatThrownBy(() -> shareController.imageShareToken(auth, video.getId())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void publicEndpoints_rejectAMalformedOrForgedToken() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("shared.jpg"));

		assertThatThrownBy(() -> shareController.publicImage(uploaded.id(), "not-a-real-token")).isInstanceOf(InvalidShareTokenException.class);
	}

	private static InputStream readBody(org.springframework.core.io.InputStreamResource resource) throws IOException {
		return resource.getInputStream();
	}

	private static MockMultipartFile jpegFile(String name) throws IOException {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return new MockMultipartFile("file", name, "image/jpeg", out.toByteArray());
	}
}
