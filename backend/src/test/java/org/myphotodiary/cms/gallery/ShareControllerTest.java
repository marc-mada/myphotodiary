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
import java.nio.file.Files;
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
import org.myphotodiary.cms.gallery.dto.RenameDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.ShareLinkResponse;
import org.myphotodiary.cms.settings.AppSettingsService;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
	@Autowired
	private AppSettingsService appSettingsService;
	@Autowired
	private DirectoryIndexerService indexerService;

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
	void sequenceShareToken_excludesVideoFromTheListing_whenVideoSharingIsOff() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		imageRepository.save(new Image("clip.mp4", directory, LocalDateTime.now(), MediaType.VIDEO));

		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);
		PublicSequenceResponse sequence = shareController.publicSequence(directoryId, link.token());

		assertThat(sequence.images()).isEmpty();
	}

	/**
	 * 17/10/2026, explicit ask: a link shared for a sequence keeps working
	 * after that sequence is merged into another, and still shows only the
	 * pictures that came from it - also after a second merge.
	 */
	@Test
	void sequenceShareToken_afterAMerge_stillShowsOnlyThatSequencesOwnPictures() throws IOException {
		String base = directoryRepository.findById(directoryId).orElseThrow().getPath();
		String parent = base.substring(0, base.lastIndexOf('/'));
		Directory a = directoryRepository.findById(directoryId).orElseThrow();
		a.setDescription("Comment of A");
		ImageResponse fromA = galleryService.uploadImage(auth, directoryId, jpegFile("from-a.jpg"));
		DirectoryResponse b = galleryService.createDirectory(auth, new CreateDirectoryRequest(parent + "/b-" + Long.toString(System.nanoTime(), 36), null));
		ImageResponse inB = galleryService.uploadImage(auth, b.id(), jpegFile("in-b.jpg"));
		DirectoryResponse c = galleryService.createDirectory(auth, new CreateDirectoryRequest(parent + "/c-" + Long.toString(System.nanoTime(), 36), null));
		ShareLinkResponse linkA = shareController.sequenceShareToken(auth, directoryId);

		String bName = b.path().substring(b.path().lastIndexOf('/') + 1);
		indexerService.renameDirectory(auth, directoryId, new RenameDirectoryRequest(bName, null, null, true));
		assertThat(directoryRepository.findById(directoryId)).isEmpty();

		PublicSequenceResponse view = shareController.publicSequence(directoryId, linkA.token());
		assertThat(view.description()).isEqualTo("Comment of A");
		assertThat(view.images()).extracting(PublicImageResponse::id).containsExactly(fromA.id());
		assertThat(shareController.publicImage(fromA.id(), linkA.token()).id()).isEqualTo(fromA.id());
		assertThatThrownBy(() -> shareController.publicImage(inB.id(), linkA.token())).isInstanceOf(InvalidShareTokenException.class);

		// B merged into C: A's old link still shows A's picture only.
		String cName = c.path().substring(c.path().lastIndexOf('/') + 1);
		indexerService.renameDirectory(auth, b.id(), new RenameDirectoryRequest(cName, null, null, true));
		assertThat(shareController.publicSequence(directoryId, linkA.token()).images()).extracting(PublicImageResponse::id).containsExactly(fromA.id());
	}

	@Test
	void sequenceShareToken_doesNotGrantAccessToADifferentSequence() throws IOException {
		DirectoryResponse otherDir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/other-" + Long.toString(System.nanoTime(), 36), null));

		ShareLinkResponse link = shareController.sequenceShareToken(auth, directoryId);

		assertThatThrownBy(() -> shareController.publicSequence(otherDir.id(), link.token())).isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void imageShareToken_rejectsVideo_whenVideoSharingIsOff() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, LocalDateTime.now(), MediaType.VIDEO));

		assertThatThrownBy(() -> shareController.imageShareToken(auth, video.getId())).isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * Video sharing allowed (14/10/2026, app-wide setting): a video gets a
	 * link, is listed in its shared sequence with a stream URL, and streams
	 * with Range support; turning the setting off stops links already sent.
	 */
	@Test
	void videoSharing_whenAllowed_sharesAndStreams_andTurningItOffStopsExistingLinks() throws IOException {
		appSettingsService.updateAllowVideoSharing(true);
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		byte[] bytes = new byte[5000];
		for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
		Files.write(storageRoot.resolve(directory.getPath()).resolve("clip.mp4"), bytes);
		Image video = imageRepository.save(new Image("clip.mp4", directory, LocalDateTime.now(), MediaType.VIDEO));

		ShareLinkResponse imageLink = shareController.imageShareToken(auth, video.getId());
		ShareLinkResponse sequenceLink = shareController.sequenceShareToken(auth, directoryId);
		PublicSequenceResponse sequence = shareController.publicSequence(directoryId, sequenceLink.token());
		assertThat(sequence.images()).extracting(PublicImageResponse::id).containsExactly(video.getId());
		assertThat(sequence.images().get(0).streamUrl()).isEqualTo("/api/public/images/" + video.getId() + "/stream?token=" + sequenceLink.token());

		var whole = shareController.publicStream(video.getId(), imageLink.token(), new HttpHeaders());
		assertThat(whole.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(whole.getBody().getCount()).isEqualTo(5000);
		HttpHeaders range = new HttpHeaders();
		range.set(HttpHeaders.RANGE, "bytes=100-199");
		var part = shareController.publicStream(video.getId(), sequenceLink.token(), range);
		assertThat(part.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
		assertThat(part.getBody().getPosition()).isEqualTo(100);
		assertThat(part.getBody().getCount()).isEqualTo(100);

		appSettingsService.updateAllowVideoSharing(false);
		assertThatThrownBy(() -> shareController.publicStream(video.getId(), imageLink.token(), new HttpHeaders())).isInstanceOf(InvalidShareTokenException.class);
		assertThat(shareController.publicSequence(directoryId, sequenceLink.token()).images()).isEmpty();
	}

	@Test
	void publicStream_refusesAPicture() throws IOException {
		appSettingsService.updateAllowVideoSharing(true);
		ImageResponse picture = galleryService.uploadImage(auth, directoryId, jpegFile("still.jpg"));
		ShareLinkResponse link = shareController.imageShareToken(auth, picture.id());

		assertThatThrownBy(() -> shareController.publicStream(picture.id(), link.token(), new HttpHeaders())).isInstanceOf(IllegalArgumentException.class);
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
