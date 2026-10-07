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
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.ShareTokenService.ShareScope;
import org.myphotodiary.cms.gallery.dto.CreateDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.PublicImageResponse;
import org.myphotodiary.cms.gallery.dto.PublicSearchShareResponse;
import org.myphotodiary.cms.gallery.dto.SearchShareRequest;
import org.myphotodiary.cms.gallery.dto.SearchShareResponse;
import org.myphotodiary.cms.settings.AppSettingsService;
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

import jakarta.persistence.EntityManager;

/**
 * Shared search results (04/10/2026, explicit ask): frozen at creation, at
 * most the app-wide maximum (first N in search order, flagged as cut), a
 * deleted picture drops out, videos left out, access limited to the frozen
 * pictures, 30-day expiry.
 */
@SpringBootTest
@Transactional
class SharedSearchShareTest {

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
	private SharedSearchRepository sharedSearchRepository;
	@Autowired
	private ShareTokenService tokenService;
	@Autowired
	private AppSettingsService appSettingsService;
	@Autowired
	private UserService userService;
	@Autowired
	private EntityManager entityManager;

	private Authentication auth;
	private Long directoryId;
	/** Unique word in the test sequence's name - a free-text search on it matches exactly that sequence. */
	private String needle;

	@BeforeEach
	void setUp() {
		userService.createUser(new CreateUserRequest("search-share-admin", "Search Share Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("search-share-admin", null);
		needle = "srch" + Long.toString(System.nanoTime(), 36);
		DirectoryResponse dir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/" + needle, null));
		directoryId = dir.id();
	}

	private SearchShareRequest byText() {
		return new SearchShareRequest(null, null, null, null, needle);
	}

	@Test
	void share_freezesTheMatchingPicturesInSearchOrder_withTheirSequenceComment() throws IOException {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		directory.setDescription("A day at the beach");
		ImageResponse a = galleryService.uploadImage(auth, directoryId, jpegFile("a.jpg"));
		ImageResponse b = galleryService.uploadImage(auth, directoryId, jpegFile("b.jpg"));
		List<Long> expectedOrder = galleryService.searchMatches(auth, null, null, null, null, needle).stream().map(Image::getId).toList();

		SearchShareResponse shared = shareController.shareSearch(auth, byText());
		PublicSearchShareResponse view = shareController.publicSearchShare(shared.id(), shared.token());

		assertThat(shared.matchCount()).isEqualTo(2);
		assertThat(shared.sharedCount()).isEqualTo(2);
		assertThat(shared.truncated()).isFalse();
		assertThat(view.images()).extracting(PublicImageResponse::id).containsExactlyElementsOf(expectedOrder).containsExactlyInAnyOrder(a.id(), b.id());
		assertThat(view.images()).extracting(PublicImageResponse::sequenceDescription).containsOnly("A day at the beach");
		// The token opens each frozen picture's own bytes/details...
		assertThat(shareController.publicImage(a.id(), shared.token()).id()).isEqualTo(a.id());
	}

	@Test
	void share_doesNotGrantAccessToAPictureOutsideTheFrozenResult() throws IOException {
		galleryService.uploadImage(auth, directoryId, jpegFile("in.jpg"));
		DirectoryResponse otherDir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/unrelated-" + Long.toString(System.nanoTime(), 36), null));
		ImageResponse outside = galleryService.uploadImage(auth, otherDir.id(), jpegFile("outside.jpg"));

		SearchShareResponse shared = shareController.shareSearch(auth, byText());

		assertThatThrownBy(() -> shareController.publicImage(outside.id(), shared.token())).isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void share_aboveTheMaximum_sharesTheFirstOnesAndSaysItWasCut() throws IOException {
		appSettingsService.updateMaxShareSize(2);
		for (String name : new String[] { "1.jpg", "2.jpg", "3.jpg" }) {
			galleryService.uploadImage(auth, directoryId, jpegFile(name));
		}
		List<Long> expectedFirstTwo = galleryService.searchMatches(auth, null, null, null, null, needle).stream().map(Image::getId).limit(2).toList();

		SearchShareResponse shared = shareController.shareSearch(auth, byText());

		assertThat(shared.matchCount()).isEqualTo(3);
		assertThat(shared.sharedCount()).isEqualTo(2);
		assertThat(shared.truncated()).isTrue();
		assertThat(shared.maxShareSize()).isEqualTo(2);
		assertThat(shareController.publicSearchShare(shared.id(), shared.token()).images()).extracting(PublicImageResponse::id).containsExactlyElementsOf(expectedFirstTwo);
	}

	@Test
	void aPictureDeletedAfterSharing_dropsOutOfTheResult_orderOfTheOthersKept() throws IOException {
		galleryService.uploadImage(auth, directoryId, jpegFile("x.jpg"));
		galleryService.uploadImage(auth, directoryId, jpegFile("y.jpg"));
		galleryService.uploadImage(auth, directoryId, jpegFile("z.jpg"));
		List<Long> order = galleryService.searchMatches(auth, null, null, null, null, needle).stream().map(Image::getId).toList();
		SearchShareResponse shared = shareController.shareSearch(auth, byText());
		entityManager.flush();
		entityManager.clear();

		// The middle one - leaves a gap in the frozen list's sort order.
		galleryService.deleteImage(auth, order.get(1));
		entityManager.flush();
		entityManager.clear();

		assertThat(shareController.publicSearchShare(shared.id(), shared.token()).images()).extracting(PublicImageResponse::id).containsExactly(order.get(0), order.get(2));
		assertThatThrownBy(() -> shareController.publicImage(order.get(1), shared.token())).isInstanceOf(RuntimeException.class);
	}

	@Test
	void everyPictureDeleted_leavesAnEmptyButValidResult() throws IOException {
		ImageResponse only = galleryService.uploadImage(auth, directoryId, jpegFile("only.jpg"));
		SearchShareResponse shared = shareController.shareSearch(auth, byText());
		entityManager.flush();
		entityManager.clear();
		galleryService.deleteImage(auth, only.id());
		entityManager.flush();
		entityManager.clear();

		assertThat(shareController.publicSearchShare(shared.id(), shared.token()).images()).isEmpty();
	}

	@Test
	void aMatchingPictureAddedAfterSharing_isNotInTheResult() throws IOException {
		ImageResponse before = galleryService.uploadImage(auth, directoryId, jpegFile("before.jpg"));
		SearchShareResponse shared = shareController.shareSearch(auth, byText());
		ImageResponse after = galleryService.uploadImage(auth, directoryId, jpegFile("after.jpg"));

		assertThat(shareController.publicSearchShare(shared.id(), shared.token()).images()).extracting(PublicImageResponse::id).containsExactly(before.id());
		assertThatThrownBy(() -> shareController.publicImage(after.id(), shared.token())).isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void videosAreLeftOut() throws IOException {
		ImageResponse photo = galleryService.uploadImage(auth, directoryId, jpegFile("photo.jpg"));
		imageRepository.save(new Image("clip.mp4", directoryRepository.findById(directoryId).orElseThrow(), LocalDateTime.now(), MediaType.VIDEO));

		SearchShareResponse shared = shareController.shareSearch(auth, byText());

		assertThat(shared.matchCount()).isEqualTo(1);
		assertThat(shareController.publicSearchShare(shared.id(), shared.token()).images()).extracting(PublicImageResponse::id).containsExactly(photo.id());
	}

	@Test
	void noCriteria_orNothingToShare_isRefused() {
		assertThatThrownBy(() -> shareController.shareSearch(auth, new SearchShareRequest(List.of(), null, null, null, "abc")))
				.isInstanceOf(IllegalArgumentException.class);
		// A valid criterion that matches nothing (the test sequence is still empty).
		assertThatThrownBy(() -> shareController.shareSearch(auth, byText())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anotherKindOfToken_orAnExpiredResult_isRejected() throws IOException {
		galleryService.uploadImage(auth, directoryId, jpegFile("p.jpg"));
		SearchShareResponse shared = shareController.shareSearch(auth, byText());
		String sequenceToken = tokenService.issueToken(ShareScope.SEQUENCE, directoryId);
		assertThatThrownBy(() -> shareController.publicSearchShare(shared.id(), sequenceToken)).isInstanceOf(InvalidShareTokenException.class);

		LocalDateTime past = LocalDateTime.now().minusDays(31);
		SharedSearch expired = sharedSearchRepository.save(new SharedSearch(past, past.plusDays(30), "someone", 1, List.of()));
		String expiredToken = tokenService.issueToken(ShareScope.SEARCH, expired.getId());
		assertThatThrownBy(() -> shareController.publicSearchShare(expired.getId(), expiredToken)).isInstanceOf(InvalidShareTokenException.class);
	}

	private static MockMultipartFile jpegFile(String name) throws IOException {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return new MockMultipartFile("file", name, "image/jpeg", out.toByteArray());
	}
}
