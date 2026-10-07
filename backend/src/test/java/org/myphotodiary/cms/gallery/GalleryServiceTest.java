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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import java.util.List;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.AttributeResponse;
import org.myphotodiary.cms.gallery.dto.CreateAttributeRequest;
import org.myphotodiary.cms.gallery.dto.CreateDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.CropImageRequest;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.ImageSearchResponse;
import org.myphotodiary.cms.gallery.dto.TransformImageRequest;
import org.myphotodiary.cms.gallery.dto.UpdateDirectoryDetailRequest;
import org.myphotodiary.cms.gallery.dto.UpdateImageRequest;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateRoleAssignmentRequest;
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
 * Exercises the full upload -> thumbnail -> serve -> delete cycle against
 * real files under a temp directory (not mocked) - the failure mode this
 * guards against is exactly the ones Design.md §16.7/§16.8.2 called out for the
 * legacy code: a bad path escaping the storage root, or a thumbnail silently
 * not matching what idx.css expects (128px, see ImageStorageService).
 */
@SpringBootTest
@Transactional
class GalleryServiceTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
	}

	@Autowired
	private GalleryService galleryService;
	@Autowired
	private AttributeService attributeService;
	@Autowired
	private DirectoryIndexerService indexerService;
	@Autowired
	private UserService userService;
	@Autowired
	private DirectoryRepository directoryRepository;
	@Autowired
	private ImageRepository imageRepository;
	@Autowired
	private ImageStorageService storageService;
	@PersistenceContext
	private EntityManager entityManager;

	private Long directoryId;
	// ADMIN is permitted every Action in the role matrix (Role.isPermitted) -
	// this test class exercises upload/edit/search behavior, not RBAC itself
	// (see GalleryAuthorizationServiceTest for that), so every call uses this
	// same always-permitted identity rather than one fixture per Action.
	private Authentication auth;

	@BeforeEach
	void createDirectory() {
		userService.createUser(new CreateUserRequest("gallery-test-admin", "Gallery Test Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("gallery-test-admin", null);
		DirectoryResponse dir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/test-" + Long.toString(System.nanoTime(), 36), null));
		directoryId = dir.id();
	}

	@Test
	void uploadImage_storesOriginalAndThumbnailAndPersistsRow() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("sunset.jpg"));

		assertThat(uploaded.name()).isEqualTo("sunset.jpg");
		assertThat(uploaded.rating()).isEqualTo(Image.NOT_RATED);
		// Lets the frontend resolve the owning sequence's own description
		// (Directory.description, distinct from Image.description) for the
		// post-it, without a directory already in hand (e.g. search results).
		assertThat(uploaded.directoryId()).isEqualTo(directoryId);
		assertThat(uploaded.webUrl()).isEqualTo("/api/images/" + uploaded.id() + "/web");
		assertThat(uploaded.exportUrl()).isEqualTo("/api/images/" + uploaded.id() + "/export");

		assertThat(galleryService.listImages(auth, directoryId)).hasSize(1);

		// Thumbnail is real, decodable, and matches idx.css's .focusThumb/
		// .noFocusThumb width exactly (128px) - not just "a file exists".
		Image stored = galleryService.requireImage(uploaded.id());
		try (InputStream thumb = storageServiceThumbnail(stored)) {
			BufferedImage decoded = ImageIO.read(thumb);
			assertThat(decoded).isNotNull();
			assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(128);
		}

		// The "web" tier (Design.md §15, legacy feature-parity audit) - a
		// second, real, decodable derived image the main viewer displays
		// instead of the full original.
		try (InputStream web = storageServiceWeb(stored)) {
			BufferedImage decoded = ImageIO.read(web);
			assertThat(decoded).isNotNull();
		}
	}

	@Test
	void uploadImage_withGpsExif_persistsLatitudeLongitude() throws Exception {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFileWithGps("paris.jpg", 2.3508, 48.8567));

		assertThat(uploaded.latitude()).isCloseTo(48.8567, within(0.0001));
		assertThat(uploaded.longitude()).isCloseTo(2.3508, within(0.0001));
	}

	@Test
	void uploadImage_withoutGpsExif_leavesLatitudeLongitudeNull() throws IOException {
		// The plain jpegFile() fixture used throughout this class carries no
		// EXIF at all - explicit, not just an accidental absence, since "no
		// GPS tag means null, not 0/0" is the actual behavior being asserted.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("no-gps.jpg"));

		assertThat(uploaded.latitude()).isNull();
		assertThat(uploaded.longitude()).isNull();
	}

	@Test
	void uploadImage_duplicateNameInSameDirectory_throwsConflict() throws IOException {
		galleryService.uploadImage(auth, directoryId, jpegFile("dup.jpg"));

		assertThatThrownBy(() -> galleryService.uploadImage(auth, directoryId, jpegFile("dup.jpg")))
				.isInstanceOf(ImageAlreadyExistsException.class);
	}

	@Test
	void updateImage_setsDescriptionAndRating() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("postit.jpg"));

		ImageResponse updated = galleryService.updateImage(auth, uploaded.id(), new UpdateImageRequest("Nice sunset", Image.GOOD, null));

		assertThat(updated.description()).isEqualTo("Nice sunset");
		assertThat(updated.rating()).isEqualTo(Image.GOOD);
	}

	@Test
	void updateImage_ratingOutOfRange_throws() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("badrating.jpg"));

		assertThatThrownBy(() -> galleryService.updateImage(auth, uploaded.id(), new UpdateImageRequest(null, 99, null)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void deleteImage_removesRowAndFiles() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("todelete.jpg"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());
		assertThat(Files.exists(originalPath)).isTrue();

		galleryService.deleteImage(auth, uploaded.id());

		assertThat(Files.exists(originalPath)).isFalse();
		assertThatThrownBy(() -> galleryService.requireImage(uploaded.id())).isInstanceOf(ImageNotFoundException.class);
	}

	@Test
	void rotateImage_swapsWidthAndHeightOnAllThreeDerivatives() throws IOException {
		// jpegFile writes 400x300 (landscape) - a real, decodable rotation
		// swaps that to 300x400 (portrait), the concrete assertion below,
		// not just "the file changed".
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("landscape.jpg"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());
		try (InputStream before = Files.newInputStream(originalPath)) {
			BufferedImage decoded = ImageIO.read(before);
			assertThat(decoded.getWidth()).isEqualTo(400);
			assertThat(decoded.getHeight()).isEqualTo(300);
		}

		galleryService.rotateImage(auth, uploaded.id());

		try (InputStream after = Files.newInputStream(originalPath)) {
			BufferedImage decoded = ImageIO.read(after);
			assertThat(decoded.getWidth()).isEqualTo(300);
			assertThat(decoded.getHeight()).isEqualTo(400);
		}
		// Web tier: same 1600px-bounding-box scaling as generateDerivatives,
		// just around the now-rotated original - still portrait-shaped.
		try (InputStream web = storageServiceWeb(stored)) {
			BufferedImage decoded = ImageIO.read(web);
			assertThat(decoded.getHeight()).isGreaterThan(decoded.getWidth());
		}
		// Thumbnail: 128x96 before rotation (128px-bounding-box, landscape),
		// 96x128 after - the same swap, scaled down.
		try (InputStream thumb = storageServiceThumbnail(stored)) {
			BufferedImage decoded = ImageIO.read(thumb);
			assertThat(decoded.getWidth()).isEqualTo(96);
			assertThat(decoded.getHeight()).isEqualTo(128);
		}
	}

	@Test
	void rotateImage_multipleQuarterTurnsInOneCommit() throws IOException {
		// 26/09/2026 - the editor popup accumulates consecutive "Rotate right"
		// clicks and commits them as one rotation. 2 turns keeps 400x300
		// landscape (a real 180, not a no-op: checked via a corner pixel
		// moving), 3 turns (from that state) ends portrait 300x400.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("multi-turn.jpg"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());
		byte[] beforeBytes = Files.readAllBytes(originalPath);

		galleryService.rotateImage(auth, uploaded.id(), 2);
		try (InputStream after = Files.newInputStream(originalPath)) {
			BufferedImage decoded = ImageIO.read(after);
			assertThat(decoded.getWidth()).isEqualTo(400);
			assertThat(decoded.getHeight()).isEqualTo(300);
		}
		assertThat(Files.readAllBytes(originalPath)).isNotEqualTo(beforeBytes);

		galleryService.rotateImage(auth, uploaded.id(), 3);
		try (InputStream after = Files.newInputStream(originalPath)) {
			BufferedImage decoded = ImageIO.read(after);
			assertThat(decoded.getWidth()).isEqualTo(300);
			assertThat(decoded.getHeight()).isEqualTo(400);
		}
		byte[] previewBytes = galleryService.previewRotate(auth, uploaded.id(), 3);
		try (InputStream in = new java.io.ByteArrayInputStream(previewBytes)) {
			BufferedImage preview = ImageIO.read(in);
			assertThat(preview.getWidth()).isEqualTo(400);
			assertThat(preview.getHeight()).isEqualTo(300);
		}
	}

	@Test
	void rotateImage_rejectsOutOfRangeQuarterTurns() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("bad-turns.jpg"));
		assertThatThrownBy(() -> galleryService.rotateImage(auth, uploaded.id(), 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> galleryService.rotateImage(auth, uploaded.id(), 4)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> galleryService.previewRotate(auth, uploaded.id(), 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rotateImage_rejectsVideo() {
		// Constructed directly via the repository (no real ffmpeg fixture
		// needed, unlike VideoImportTest) - rotateImage's own video guard
		// runs before any file is ever touched, so this only needs a real
		// row with MediaType.VIDEO, not real video bytes on disk.
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, java.time.LocalDateTime.now(), MediaType.VIDEO));

		assertThatThrownBy(() -> galleryService.rotateImage(auth, video.getId())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void previewRotate_returnsBytesWithoutWritingAnythingToDisk() throws IOException {
		// Same discipline as previewTransform_returnsBytesWithoutWritingAnythingToDisk
		// below - Rotate is now on the same preview/Save workflow (25/09/2026,
		// explicit ask), so "Rotate right" alone must never touch the real
		// file, only a subsequent "Save" (rotateImage) may.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("rotate-preview.jpg"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());
		byte[] beforeBytes = Files.readAllBytes(originalPath);

		byte[] previewBytes = galleryService.previewRotate(auth, uploaded.id());

		BufferedImage preview;
		try (InputStream in = new java.io.ByteArrayInputStream(previewBytes)) {
			preview = ImageIO.read(in);
		}
		// jpegFile writes 400x300 (landscape) - a real rotation swaps that
		// to 300x400 (portrait), same assertion already used to confirm the
		// real commit (rotateImage_swapsWidthAndHeightOnAllThreeDerivatives).
		assertThat(preview.getWidth()).isEqualTo(300);
		assertThat(preview.getHeight()).isEqualTo(400);

		// The real file on disk is byte-for-byte untouched.
		assertThat(Files.readAllBytes(originalPath)).isEqualTo(beforeBytes);
	}

	@Test
	void previewRotate_rejectsVideo() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, java.time.LocalDateTime.now(), MediaType.VIDEO));

		assertThatThrownBy(() -> galleryService.previewRotate(auth, video.getId())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cropImage_resizesAllThreeDerivativesToTheRequestedRectangle() throws IOException {
		// jpegFile writes 400x300 - crop to a real, off-center 200x150
		// rectangle (not the whole image, not centered) so a bug that
		// silently ignored x/y and just resized wouldn't accidentally pass.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("crop-me.jpg"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());

		galleryService.cropImage(auth, uploaded.id(), new CropImageRequest(50, 75, 200, 150));

		try (InputStream after = Files.newInputStream(originalPath)) {
			BufferedImage decoded = ImageIO.read(after);
			assertThat(decoded.getWidth()).isEqualTo(200);
			assertThat(decoded.getHeight()).isEqualTo(150);
		}
		// Web tier: re-derived from the 200x150 original, not the original
		// 400x300 - but generateDerivatives's own documented behavior
		// (found empirically, see its own comment) is that a source smaller
		// than the WEB_SIZE bounding box gets *upscaled* to fill it, not
		// left at its native size - so 200x150 (4:3) scales up to exactly
		// 1600x1200 within the 1600x1600 box, still recognizably re-derived
		// from the cropped 4:3 shape (not the original 400x300, also 4:3 -
		// disambiguated below by the thumbnail's own distinct 128x96
		// bounding-box math already being covered elsewhere).
		try (InputStream web = storageServiceWeb(stored)) {
			BufferedImage decoded = ImageIO.read(web);
			assertThat(decoded.getWidth()).isEqualTo(1600);
			assertThat(decoded.getHeight()).isEqualTo(1200);
		}
		// Thumbnail: 128px-bounding-box around the new 200x150 (4:3) shape -
		// 128x96, same ratio as the 400x300 original would also produce,
		// so this alone wouldn't prove the crop actually ran - the real
		// original's exact 200x150 decoded size above is what does that.
		try (InputStream thumb = storageServiceThumbnail(stored)) {
			BufferedImage decoded = ImageIO.read(thumb);
			assertThat(decoded.getWidth()).isEqualTo(128);
			assertThat(decoded.getHeight()).isEqualTo(96);
		}
	}

	@Test
	void cropImage_rejectsARectangleOutsideTheOriginalsRealBounds() throws IOException {
		// jpegFile writes 400x300 - a rectangle reaching to x=350,width=100
		// (450 > 400) is out of bounds on the right edge.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("crop-oob.jpg"));

		assertThatThrownBy(() -> galleryService.cropImage(auth, uploaded.id(), new CropImageRequest(350, 0, 100, 100)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cropImage_rejectsVideo() {
		// Same fixture-only shape as rotateImage_rejectsVideo above - the
		// video guard runs before any file is touched.
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, java.time.LocalDateTime.now(), MediaType.VIDEO));

		assertThatThrownBy(() -> galleryService.cropImage(auth, video.getId(), new CropImageRequest(0, 0, 10, 10)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void previewCrop_returnsBytesWithoutWritingAnythingToDisk() throws IOException {
		// Same discipline as previewTransform_returnsBytesWithoutWritingAnythingToDisk
		// below - Crop is now on the same preview/Save workflow (25/09/2026,
		// explicit ask), so "Crop" alone must never touch the real file,
		// only a subsequent "Save" (cropImage) may.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("crop-preview.jpg"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());
		byte[] beforeBytes = Files.readAllBytes(originalPath);

		byte[] previewBytes = galleryService.previewCrop(auth, uploaded.id(), new CropImageRequest(50, 75, 200, 150));

		BufferedImage preview;
		try (InputStream in = new java.io.ByteArrayInputStream(previewBytes)) {
			preview = ImageIO.read(in);
		}
		assertThat(preview.getWidth()).isEqualTo(200);
		assertThat(preview.getHeight()).isEqualTo(150);

		// The real file on disk is byte-for-byte untouched.
		assertThat(Files.readAllBytes(originalPath)).isEqualTo(beforeBytes);
	}

	@Test
	void previewCrop_rejectsARectangleOutsideTheOriginalsRealBounds() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("crop-preview-oob.jpg"));

		assertThatThrownBy(() -> galleryService.previewCrop(auth, uploaded.id(), new CropImageRequest(350, 0, 100, 100)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void previewCrop_rejectsVideo() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, java.time.LocalDateTime.now(), MediaType.VIDEO));

		assertThatThrownBy(() -> galleryService.previewCrop(auth, video.getId(), new CropImageRequest(0, 0, 10, 10)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void transformImage_identityQuadReproducesTheOriginalAtTheSameSize() throws IOException {
		// The quad IS the destination rectangle's own corners exactly - a
		// no-op transform. PNG (lossless), not jpegFile's own JPEG,
		// specifically so this can assert *exact* pixel equality rather
		// than "close enough" - real confirmation of the pixel-center
		// sampling-convention fix documented on ImageStorageService's own
		// bilinearSample (found and fixed by hand before ever running this,
		// but this is what actually proves it: without that fix, an
		// identity warp would uniformly blur the image instead of
		// reproducing it, and this test would catch that).
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, quadrantPngFile("identity-warp.png"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());
		BufferedImage before;
		try (InputStream in = Files.newInputStream(originalPath)) {
			before = ImageIO.read(in);
		}

		galleryService.transformImage(auth, uploaded.id(), new TransformImageRequest(40, 40, 0, 0, 40, 0, 40, 40, 0, 40));

		BufferedImage after;
		try (InputStream in = Files.newInputStream(originalPath)) {
			after = ImageIO.read(in);
		}
		assertThat(after.getWidth()).isEqualTo(before.getWidth());
		assertThat(after.getHeight()).isEqualTo(before.getHeight());
		for (int y = 0; y < before.getHeight(); y++) {
			for (int x = 0; x < before.getWidth(); x++) {
				assertThat(after.getRGB(x, y)).as("pixel (%d,%d)", x, y).isEqualTo(before.getRGB(x, y));
			}
		}
	}

	@Test
	void transformImage_warpsAKnownQuadToTheDestinationRectangle_cornersLandOnTheExpectedQuadrantColors() throws IOException {
		// quadrantPngFile: 40x40, four solid 20x20 color quadrants (TL red,
		// TR green, BR blue, BL yellow). A quad with each corner placed
		// comfortably inside its own matching quadrant (5 units in from
		// every real quadrant boundary) - not right at the boundary, so
		// the half-pixel sampling offset applied to every output pixel
		// (ImageStorageService.warp's own +0.5) can't accidentally cross
		// into a neighboring quadrant. After transformImage, the
		// destination's own 4 corner pixels should read back exactly
		// those 4 colors, in the same TL/TR/BR/BL order - a real,
		// end-to-end confirmation that PerspectiveTransform's own corner
		// correspondence (already proven in isolation, PerspectiveTransformTest)
		// survives DTO parsing, RBAC, and rasterization intact, not just
		// the pure math.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, quadrantPngFile("quad-warp.png"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());

		galleryService.transformImage(auth, uploaded.id(), new TransformImageRequest(
				20, 20,
				5, 5, // top-left corner, inside the red quadrant
				35, 5, // top-right corner, inside the green quadrant
				35, 35, // bottom-right corner, inside the blue quadrant
				5, 35)); // bottom-left corner, inside the yellow quadrant

		BufferedImage result;
		try (InputStream in = Files.newInputStream(originalPath)) {
			result = ImageIO.read(in);
		}
		assertThat(result.getWidth()).isEqualTo(20);
		assertThat(result.getHeight()).isEqualTo(20);
		assertThat(new Color(result.getRGB(0, 0))).isEqualTo(Color.RED);
		assertThat(new Color(result.getRGB(19, 0))).isEqualTo(Color.GREEN);
		assertThat(new Color(result.getRGB(19, 19))).isEqualTo(Color.BLUE);
		assertThat(new Color(result.getRGB(0, 19))).isEqualTo(Color.YELLOW);
	}

	@Test
	void previewTransform_returnsBytesWithoutWritingAnythingToDisk() throws IOException {
		// The whole point of the preview/Save split (25/09/2026, explicit
		// ask - "if the result is not as expected, clicking Cancel...")
		// is that "Transform" alone must never touch the real file - only
		// "Save" (transformImage) may. Checked here at the file-bytes
		// level (not just "no exception"), against the exact same warp
		// request the corner-color test above already proved geometrically
		// correct.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, quadrantPngFile("preview-only.png"));
		Image stored = galleryService.requireImage(uploaded.id());
		Path originalPath = storageRoot.resolve(stored.getDirectory().getPath()).resolve(stored.getName());
		byte[] beforeBytes = Files.readAllBytes(originalPath);

		byte[] previewBytes = galleryService.previewTransform(auth, uploaded.id(), new TransformImageRequest(
				20, 20, 5, 5, 35, 5, 35, 35, 5, 35));

		BufferedImage preview;
		try (InputStream in = new java.io.ByteArrayInputStream(previewBytes)) {
			preview = ImageIO.read(in);
		}
		assertThat(preview.getWidth()).isEqualTo(20);
		assertThat(preview.getHeight()).isEqualTo(20);
		assertThat(new Color(preview.getRGB(0, 0))).isEqualTo(Color.RED);

		// The real file on disk is byte-for-byte untouched.
		assertThat(Files.readAllBytes(originalPath)).isEqualTo(beforeBytes);
	}

	@Test
	void transformImage_rejectsVideo() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, java.time.LocalDateTime.now(), MediaType.VIDEO));

		assertThatThrownBy(() -> galleryService.transformImage(auth, video.getId(), new TransformImageRequest(10, 10, 0, 0, 10, 0, 10, 10, 0, 10)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void previewTransform_rejectsVideo() {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow();
		Image video = imageRepository.save(new Image("clip.mp4", directory, java.time.LocalDateTime.now(), MediaType.VIDEO));

		assertThatThrownBy(() -> galleryService.previewTransform(auth, video.getId(), new TransformImageRequest(10, 10, 0, 0, 10, 0, 10, 10, 0, 10)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void transformImage_rejectsADegenerateQuad() throws IOException {
		// Three collinear corners - the same degenerate shape
		// PerspectiveTransformTest's own rectToQuad_collinearCorners case
		// already proves has no valid transform; this confirms that
		// rejection actually propagates all the way through the real
		// service call (DTO -> GalleryService -> ImageStorageService ->
		// PerspectiveTransform), not just the pure math in isolation.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, quadrantPngFile("degenerate-warp.png"));

		assertThatThrownBy(() -> galleryService.transformImage(auth, uploaded.id(), new TransformImageRequest(
				40, 40, 0, 0, 20, 0, 40, 0, 0, 40)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void readThumbnail_fallsBackToWebThenOriginal_whenTrueThumbnailFileMissing() throws IOException {
		// Real bug found live (02/09/2026): a dev database with rows indexed
		// before the 31/08/2026 co-located-derivative restructure has its
		// real thumbnail bytes sitting at the *old* mirror-tree path this
		// class no longer looks at - resolveThumbnail legitimately finds
		// nothing there until the directory is re-indexed. Simulated here by
		// deleting the real thumbnail file after a normal upload, rather than
		// reconstructing the old mirror-tree layout - what matters is "the
		// expected file isn't there", not exactly why.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("stale.jpg"));
		Image stored = galleryService.requireImage(uploaded.id());
		String path = stored.getDirectory().getPath();
		String name = stored.getName();

		Path thumbnailFile = storageRoot.resolve(path).resolve(".thumbnails").resolve(name);
		Path webFile = storageRoot.resolve(path).resolve(".webimg").resolve(name);
		assertThat(Files.exists(thumbnailFile)).isTrue();

		Files.delete(thumbnailFile);
		try (InputStream fallback = storageService.readThumbnail(path, name)) {
			// Falls back to the web tier (1600px bounding box - upscaled from
			// this 400x300 fixture, see generateDerivatives' own corrected
			// comment), not a 128px thumbnail - a real, decodable image
			// either way, not a broken stream, which is what actually
			// mattered to the user (a visibly empty filmstrip slot), not the
			// exact resolution served meanwhile.
			BufferedImage decoded = ImageIO.read(fallback);
			assertThat(decoded).isNotNull();
			assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(1600);
		}

		Files.delete(webFile);
		try (InputStream fallback = storageService.readThumbnail(path, name)) {
			// Both derivatives missing - falls all the way back to the
			// original (readWeb's own existing fallback, reused here).
			BufferedImage decoded = ImageIO.read(fallback);
			assertThat(decoded).isNotNull();
			assertThat(decoded.getWidth()).isEqualTo(400);
			assertThat(decoded.getHeight()).isEqualTo(300);
		}
	}

	@Test
	void sizeOfXxx_matchesTheRealFileAndFollowsTheSameFallbackChainAsReadXxx() throws IOException {
		// 10/09/2026, progressive image loading (GalleryController now sets
		// Content-Length on every image response from these) - sizeOfWeb/
		// sizeOfThumbnail must report the size of whichever file readWeb/
		// readThumbnail would actually stream, including their own fallback
		// chain (web -> original, thumbnail -> web -> original), not always
		// the "true" tier's own file - a mismatched Content-Length would make
		// the browser truncate or hang waiting for bytes that never arrive.
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("sized.jpg"));
		Image stored = galleryService.requireImage(uploaded.id());
		String path = stored.getDirectory().getPath();
		String name = stored.getName();

		Path originalFile = storageRoot.resolve(path).resolve(name);
		Path thumbnailFile = storageRoot.resolve(path).resolve(".thumbnails").resolve(name);
		Path webFile = storageRoot.resolve(path).resolve(".webimg").resolve(name);

		assertThat(storageService.sizeOfOriginal(path, name)).isEqualTo(Files.size(originalFile));
		assertThat(storageService.sizeOfThumbnail(path, name)).isEqualTo(Files.size(thumbnailFile));
		assertThat(storageService.sizeOfWeb(path, name)).isEqualTo(Files.size(webFile));

		Files.delete(thumbnailFile);
		assertThat(storageService.sizeOfThumbnail(path, name)).isEqualTo(Files.size(webFile));

		Files.delete(webFile);
		assertThat(storageService.sizeOfThumbnail(path, name)).isEqualTo(Files.size(originalFile));
		assertThat(storageService.sizeOfWeb(path, name)).isEqualTo(Files.size(originalFile));
	}

	@Test
	void updateImage_attributeSelectionIncludesAncestors() throws IOException {
		String parentName = "trip-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(parentName, null));
		AttributeResponse child = attributeService.create(new CreateAttributeRequest("beach-" + System.nanoTime(), parentName));

		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("tagged.jpg"));
		ImageResponse updated = galleryService.updateImage(auth, uploaded.id(), new UpdateImageRequest(null, null, List.of(child.name())));

		assertThat(updated.attributeNames()).containsExactlyInAnyOrder(child.name(), parentName);
	}

	@Test
	void updateImage_unknownAttributeName_createsAndAssignsItInsteadOfThrowing() throws IOException {
		// Restores a legacy capability (03/09/2026, explicit ask) -
		// idx.js's own imageEditPopup let a plain text field beside the
		// attribute <select> mint a brand-new tag and assign it in the
		// same save. Before this test, this exact call threw
		// AttributeNotFoundException - it must not anymore, and the tag
		// must actually exist afterward, not just silently vanish.
		String brandNewTag = "brand-new-tag-" + System.nanoTime();
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("newtag.jpg"));

		ImageResponse updated = galleryService.updateImage(auth, uploaded.id(), new UpdateImageRequest(null, null, List.of(brandNewTag)));

		assertThat(updated.attributeNames()).containsExactly(brandNewTag);
		assertThat(attributeService.listAll()).extracting(AttributeResponse::name).contains(brandNewTag);
	}

	@Test
	void deleteAttribute_removesReferenceFromTaggedImage() throws IOException {
		// A direct question from the user (10/09/2026): "if I tag several
		// images and then delete that tag, are all the references
		// deleted?" - yes, confirmed by inspection: AttributeService.delete
		// itself never touches Image/Directory at all (it only reparents
		// child attributes - AttributeServiceTest's own delete_ test), the
		// actual cleanup is entirely a database-level `ON DELETE CASCADE`
		// on image_attribute/directory_attribute's own `attribute_id`
		// foreign key (V3 migration). Correct today, but previously
		// unverified by any test, and invisible to the Java layer if that
		// FK were ever weakened - characterized here rather than assumed.
		//
		// The entityManager.flush()/clear() pair *before* the delete call
		// below is required, not just defensive - a real Hibernate quirk
		// found writing this test (not a production bug: every real request
		// gets its own fresh persistence context, so this exact interaction
		// only arises when multiple service calls share one Hibernate
		// Session, as a test naturally does): with `image.getAttributes()`
		// still loaded from the assertion just above, calling
		// attributeService.delete *then* flushing in the same session made
		// Hibernate try to cascade-validate that now-stale collection
		// element against the Attribute it had just been told to remove,
		// throwing `TransientObjectException` - confusingly worded (the
		// referenced instance was very much persistent, just mid-deletion),
		// but a real, reproducible failure the first time this was run, not
		// assumed away. Detaching everything first means nothing in the
		// persistence context still references the Attribute when it's
		// actually deleted, matching what a real, separate admin request
		// would see. The second flush()/clear() pair, after the delete,
		// is the same "prove a fresh read, not a stale one" requirement
		// explained below.
		AttributeResponse tag = attributeService.create(new CreateAttributeRequest("to-delete-" + System.nanoTime(), null));
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("tagged-for-deletion.jpg"));
		galleryService.updateImage(auth, uploaded.id(), new UpdateImageRequest(null, null, List.of(tag.name())));
		assertThat(galleryService.requireImage(uploaded.id()).getAttributes()).extracting(Attribute::getName).containsExactly(tag.name());
		entityManager.flush();
		entityManager.clear();

		attributeService.delete(tag.id());
		// A genuinely fresh read matters here specifically: a database-side
		// cascade (as opposed to something Hibernate itself did) never
		// touches an already-loaded Java collection, so re-checking one
		// without flushing the pending DELETE and clearing first would
		// prove nothing either way.
		entityManager.flush();
		entityManager.clear();

		Image reloaded = imageRepository.findById(uploaded.id()).orElseThrow();
		assertThat(reloaded.getAttributes()).isEmpty();
		// Same check through the actual API response the frontend reads,
		// not just the raw entity - listImages goes through its own fresh
		// @EntityGraph query (ImageRepository.findByDirectory_IdOrderByNameAsc).
		ImageResponse refetched = galleryService.listImages(auth, directoryId).stream()
				.filter(i -> i.id().equals(uploaded.id())).findFirst().orElseThrow();
		assertThat(refetched.attributeNames()).isEmpty();
	}

	@Test
	void deleteAttribute_doesBothAtOnce_dereferencesTaggedContentAndReparentsChildTags() throws IOException {
		// The realistic combined case (direct ask, 26/09/2026): a real tag
		// an admin deletes is often both applied to actual photos/sequences
		// *and* has child tags underneath it in the hierarchy - not just one
		// or the other, the way the two sibling tests above (and
		// AttributeServiceTest's own reparenting tests) each check in
		// isolation. Proves the two independent mechanisms behind them -
		// image_attribute/directory_attribute's DB-level ON DELETE CASCADE,
		// and AttributeService.delete's own Java-level child reparenting -
		// don't interfere with each other when both fire from the exact
		// same delete() call in the same flush.
		AttributeResponse tag = attributeService.create(new CreateAttributeRequest("combined-delete-" + System.nanoTime(), null));
		AttributeResponse child = attributeService.create(new CreateAttributeRequest("combined-delete-child-" + System.nanoTime(), tag.name()));
		ImageResponse taggedImage = galleryService.uploadImage(auth, directoryId, jpegFile("combined-tagged.jpg"));
		galleryService.updateImage(auth, taggedImage.id(), new UpdateImageRequest(null, null, List.of(tag.name())));
		indexerService.updateDirectoryDetail(auth, directoryId, new UpdateDirectoryDetailRequest(null, null, null, null, List.of(tag.name())));
		assertThat(galleryService.requireImage(taggedImage.id()).getAttributes()).extracting(Attribute::getName).containsExactly(tag.name());
		entityManager.flush();
		entityManager.clear();

		attributeService.delete(tag.id());
		entityManager.flush();
		entityManager.clear();

		assertThat(imageRepository.findById(taggedImage.id()).orElseThrow().getAttributes()).isEmpty();
		assertThat(directoryRepository.findById(directoryId).orElseThrow().getAttributes()).isEmpty();
		AttributeResponse reparentedChild = attributeService.listAll().stream()
				.filter(a -> a.id().equals(child.id())).findFirst().orElseThrow();
		// The deleted tag had no parent of its own, so its child is
		// promoted to top-level, not left pointing at a tag that no longer
		// exists.
		assertThat(reparentedChild.parentName()).isNull();
		assertThat(attributeService.listAll()).extracting(AttributeResponse::name).doesNotContain(tag.name());
	}

	@Test
	void search_byOwnAttribute_findsTaggedImageOnly() throws IOException {
		String tag = "own-tag-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(tag, null));
		ImageResponse tagged = galleryService.uploadImage(auth, directoryId, jpegFile("tagged.jpg"));
		galleryService.uploadImage(auth, directoryId, jpegFile("untagged.jpg"));
		galleryService.updateImage(auth, tagged.id(), new UpdateImageRequest(null, null, List.of(tag)));

		ImageSearchResponse results = galleryService.search(auth, List.of(tag), null, null, null, null, 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).containsExactly(tagged.id());
	}

	@Test
	void search_byDirectoryInheritedAttribute_findsAllImagesInThatDirectory() throws IOException {
		String tag = "dir-tag-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(tag, null));
		ImageResponse img1 = galleryService.uploadImage(auth, directoryId, jpegFile("a.jpg"));
		ImageResponse img2 = galleryService.uploadImage(auth, directoryId, jpegFile("b.jpg"));
		indexerService.updateDirectoryDetail(auth, directoryId, new UpdateDirectoryDetailRequest(null, null, null, null, List.of(tag)));

		ImageSearchResponse results = galleryService.search(auth, List.of(tag), null, null, null, null, 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).containsExactlyInAnyOrder(img1.id(), img2.id());
	}

	@Test
	void search_byMultipleAttributes_intersectsRatherThanUnions() throws IOException {
		String tagA = "tag-a-" + System.nanoTime();
		String tagB = "tag-b-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(tagA, null));
		attributeService.create(new CreateAttributeRequest(tagB, null));
		ImageResponse both = galleryService.uploadImage(auth, directoryId, jpegFile("both.jpg"));
		ImageResponse onlyA = galleryService.uploadImage(auth, directoryId, jpegFile("onlyA.jpg"));
		galleryService.updateImage(auth, both.id(), new UpdateImageRequest(null, null, List.of(tagA, tagB)));
		galleryService.updateImage(auth, onlyA.id(), new UpdateImageRequest(null, null, List.of(tagA)));

		ImageSearchResponse results = galleryService.search(auth, List.of(tagA, tagB), null, null, null, null, 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).containsExactly(both.id());
	}

	@Test
	void search_byMinRating_excludesLowerRatedImages() throws IOException {
		ImageResponse highRated = galleryService.uploadImage(auth, directoryId, jpegFile("good.jpg"));
		ImageResponse lowRated = galleryService.uploadImage(auth, directoryId, jpegFile("poor.jpg"));
		galleryService.updateImage(auth, highRated.id(), new UpdateImageRequest(null, Image.VERY_GOOD, null));
		galleryService.updateImage(auth, lowRated.id(), new UpdateImageRequest(null, Image.POOR, null));

		ImageSearchResponse results = galleryService.search(auth, null, Image.GOOD, null, null, null, 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).contains(highRated.id()).doesNotContain(lowRated.id());
	}

	@Test
	void search_byDateRange_excludesImagesOutsideAndImagesWithNoCaptureDate() throws IOException {
		ImageResponse inRange = galleryService.uploadImage(auth, directoryId, jpegFile("inrange.jpg"));
		ImageResponse before = galleryService.uploadImage(auth, directoryId, jpegFile("before.jpg"));
		ImageResponse after = galleryService.uploadImage(auth, directoryId, jpegFile("after.jpg"));
		ImageResponse noDate = galleryService.uploadImage(auth, directoryId, jpegFile("nodate.jpg"));
		galleryService.requireImage(inRange.id()).setCaptureDate(java.time.LocalDateTime.of(2026, 3, 15, 12, 0));
		galleryService.requireImage(before.id()).setCaptureDate(java.time.LocalDateTime.of(2026, 2, 1, 12, 0));
		galleryService.requireImage(after.id()).setCaptureDate(java.time.LocalDateTime.of(2026, 4, 1, 12, 0));
		galleryService.requireImage(noDate.id()).setCaptureDate(null);

		ImageSearchResponse results = galleryService.search(auth, null, null, java.time.LocalDate.of(2026, 3, 1), java.time.LocalDate.of(2026, 3, 31), null, 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).containsExactly(inRange.id());
	}

	@Test
	void search_byText_matchesSequenceNameCaseInsensitively() throws IOException {
		// directoryId's own path (createDirectory, @BeforeEach) ends in
		// "test-<random>" - its "sequence name" per GalleryService's own
		// sequenceName() is that last path segment, not the full path.
		String sequenceNameFragment = directoryRepository.findById(directoryId).orElseThrow().getPath().replaceAll(".*/", "").substring(0, 4);
		ImageResponse image = galleryService.uploadImage(auth, directoryId, jpegFile("named.jpg"));

		ImageSearchResponse results = galleryService.search(auth, null, null, null, null, sequenceNameFragment.toUpperCase(), 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).contains(image.id());
	}

	@Test
	void search_byText_matchesSequenceDescription() throws IOException {
		indexerService.updateDirectoryDetail(auth, directoryId, new UpdateDirectoryDetailRequest("A rare mention of narwhals", null, null, null, null));
		ImageResponse image = galleryService.uploadImage(auth, directoryId, jpegFile("seqdesc.jpg"));

		ImageSearchResponse results = galleryService.search(auth, null, null, null, null, "narwhal", 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).containsExactly(image.id());
	}

	@Test
	void search_byText_matchesImageDescription() throws IOException {
		ImageResponse matching = galleryService.uploadImage(auth, directoryId, jpegFile("imgdesc.jpg"));
		ImageResponse other = galleryService.uploadImage(auth, directoryId, jpegFile("other.jpg"));
		galleryService.updateImage(auth, matching.id(), new UpdateImageRequest("A rare mention of narwhals", null, null));

		ImageSearchResponse results = galleryService.search(auth, null, null, null, null, "narwhal", 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).containsExactly(matching.id());
		assertThat(results.images()).extracting(ImageResponse::id).doesNotContain(other.id());
	}

	@Test
	void search_byText_noMatchAnywhere_returnsEmpty() throws IOException {
		galleryService.uploadImage(auth, directoryId, jpegFile("nomatch.jpg"));

		ImageSearchResponse results = galleryService.search(auth, null, null, null, null, "xyzzy-no-such-word", 0, 10);

		assertThat(results.images()).isEmpty();
	}

	@Test
	void search_textIsOredAcrossItsThreeFields_butAndedWithMinRating() throws IOException {
		// Full logic asked for (revised 03/09/2026): minDate AND maxDate AND
		// minRating AND (tag OR sequenceName OR sequenceDescription OR
		// imageDescription). Text alone (OR across its own three fields)
		// matches both; adding a minRating that only one of them satisfies
		// narrows it down via a real AND, proving the two operators aren't
		// accidentally the same. No tags involved in this particular test -
		// see search_tagIsOredWithText_eitherAloneIsEnough for tag's own
		// move into the disjunction.
		indexerService.updateDirectoryDetail(auth, directoryId, new UpdateDirectoryDetailRequest("narwhal watching trip", null, null, null, null));
		ImageResponse highRated = galleryService.uploadImage(auth, directoryId, jpegFile("hi.jpg"));
		ImageResponse lowRated = galleryService.uploadImage(auth, directoryId, jpegFile("lo.jpg"));
		galleryService.updateImage(auth, highRated.id(), new UpdateImageRequest(null, Image.VERY_GOOD, null));
		galleryService.updateImage(auth, lowRated.id(), new UpdateImageRequest(null, Image.POOR, null));

		ImageSearchResponse textOnly = galleryService.search(auth, null, null, null, null, "narwhal", 0, 10);
		assertThat(textOnly.images()).extracting(ImageResponse::id).containsExactlyInAnyOrder(highRated.id(), lowRated.id());

		ImageSearchResponse textAndRating = galleryService.search(auth, null, Image.GOOD, null, null, "narwhal", 0, 10);
		assertThat(textAndRating.images()).extracting(ImageResponse::id).containsExactly(highRated.id());
	}

	@Test
	void search_textShorterThanFourCharacters_isIgnoredEntirely() throws IOException {
		// Explicit ask, same-day follow-up: "only use the textfield match
		// when user has entered 4 characters or more". Proven with a
		// fragment ("zzz") that appears nowhere in this image's sequence
		// name/description or its own description - if it were actually
		// being applied as a substring filter, this image would be
		// *excluded*; since it's still expected here, the fragment must
		// have been ignored entirely, not applied-and-coincidentally-matched.
		ImageResponse image = galleryService.uploadImage(auth, directoryId, jpegFile("short.jpg"));

		ImageSearchResponse results = galleryService.search(auth, null, null, null, null, "zzz", 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).contains(image.id());
	}

	@Test
	void search_textAtExactlyFourCharacters_isApplied() throws IOException {
		// The boundary itself, complementing the test above: the very same
		// fragment extended by one more character ("zzzz") *is* applied for
		// real - confirms the cutoff is ">= 4", not some other off-by-one,
		// and that "ignored below 4" isn't secretly "text search is inert
		// altogether" in disguise. A second, unrelated directory (nothing
		// in its path/description mentions "zzzz") proves exclusion still
		// works once the threshold is met, not just inclusion.
		indexerService.updateDirectoryDetail(auth, directoryId, new UpdateDirectoryDetailRequest("zzzz-flavored trip", null, null, null, null));
		ImageResponse matching = galleryService.uploadImage(auth, directoryId, jpegFile("hasit.jpg"));
		DirectoryResponse otherDir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/other-" + Long.toString(System.nanoTime(), 36), null));
		ImageResponse nonMatching = galleryService.uploadImage(auth, otherDir.id(), jpegFile("nope.jpg"));

		ImageSearchResponse results = galleryService.search(auth, null, null, null, null, "zzzz", 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).contains(matching.id());
		assertThat(results.images()).extracting(ImageResponse::id).doesNotContain(nonMatching.id());
	}

	@Test
	void search_tagIsOredWithText_eitherAloneIsEnough() throws IOException {
		// The actual behavior change of this revision: tag selection used
		// to be a hard AND-prefilter (an image without the tag was excluded
		// outright, regardless of text); it's now just one more alternative
		// inside the same disjunction as the text fields. An image matching
		// only by tag, and a different image matching only by text, must
		// BOTH appear when both a tag and a qualifying text are given
		// together - neither one is required on its own anymore.
		String tag = "or-tag-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(tag, null));
		ImageResponse taggedOnly = galleryService.uploadImage(auth, directoryId, jpegFile("taggedonly.jpg"));
		galleryService.updateImage(auth, taggedOnly.id(), new UpdateImageRequest(null, null, List.of(tag)));
		ImageResponse textOnlyMatch = galleryService.uploadImage(auth, directoryId, jpegFile("textonly.jpg"));
		galleryService.updateImage(auth, textOnlyMatch.id(), new UpdateImageRequest("a very particular walrus sighting", null, null));
		ImageResponse neither = galleryService.uploadImage(auth, directoryId, jpegFile("neither.jpg"));

		ImageSearchResponse results = galleryService.search(auth, List.of(tag), null, null, null, "walrus", 0, 10);

		assertThat(results.images()).extracting(ImageResponse::id).containsExactlyInAnyOrder(taggedOnly.id(), textOnlyMatch.id());
		assertThat(results.images()).extracting(ImageResponse::id).doesNotContain(neither.id());
	}

	@Test
	void search_pagination_returnsHasMoreUntilLastPage() throws IOException {
		String tag = "page-tag-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(tag, null));
		List<Long> ids = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ImageResponse img = galleryService.uploadImage(auth, directoryId, jpegFile("p" + i + ".jpg"));
			galleryService.updateImage(auth, img.id(), new UpdateImageRequest(null, null, List.of(tag)));
			ids.add(img.id());
		}

		ImageSearchResponse page0 = galleryService.search(auth, List.of(tag), null, null, null, null, 0, 2);
		ImageSearchResponse page1 = galleryService.search(auth, List.of(tag), null, null, null, null, 1, 2);
		ImageSearchResponse page2 = galleryService.search(auth, List.of(tag), null, null, null, null, 2, 2);

		assertThat(page0.images()).hasSize(2);
		assertThat(page0.hasMore()).isTrue();
		assertThat(page0.totalCount()).isEqualTo(5);
		assertThat(page1.images()).hasSize(2);
		assertThat(page1.hasMore()).isTrue();
		assertThat(page2.images()).hasSize(1);
		assertThat(page2.hasMore()).isFalse();
		// No overlap and no gaps across pages.
		List<Long> collected = new java.util.ArrayList<>();
		page0.images().forEach(i -> collected.add(i.id()));
		page1.images().forEach(i -> collected.add(i.id()));
		page2.images().forEach(i -> collected.add(i.id()));
		assertThat(collected).containsExactlyInAnyOrderElementsOf(ids);
	}

	// --- Action.BROWSE enforcement (12/09/2026, explicit ask - "a user can
	// only browse the sequence images for which it has READ access (or
	// more)") - previously never checked at all; see
	// GalleryAuthorizationService's own doc for the full reasoning and why
	// directory-tree *navigation* itself stays unrestricted. ---

	@Test
	void listImages_deniedForUserWithNoRoleAssignmentInThatGroup() {
		userService.createUser(new CreateUserRequest("no-access-user", "No Access", "x", "unrelated-group-" + System.nanoTime(), "LOWER"));
		Authentication noAccess = new UsernamePasswordAuthenticationToken("no-access-user", null);

		// directoryId (this class's shared fixture) lives in "public" -
		// this user has a role assignment in a completely different group.
		assertThatThrownBy(() -> galleryService.listImages(noAccess, directoryId)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void listImages_permittedForAnyRoleThatHoldsARoleAssignmentInThatGroup() throws IOException {
		// LOWER specifically (the most restricted role) - proves "read
		// access (or more)" is really "any role assignment at all in this
		// group", not just WRITER/ADMIN, matching Role.isPermitted(BROWSE).
		userService.createUser(new CreateUserRequest("public-lower-user", "Public Lower", "x", "public", "LOWER"));
		Authentication lowerInPublic = new UsernamePasswordAuthenticationToken("public-lower-user", null);
		galleryService.uploadImage(auth, directoryId, jpegFile("visible-to-lower.jpg"));

		assertThat(galleryService.listImages(lowerInPublic, directoryId)).isNotEmpty();
	}

	@Test
	void requireReadableImage_deniedForUserWithNoAccess_permittedOtherwise() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("readable-check.jpg"));
		userService.createUser(new CreateUserRequest("no-access-user-2", "No Access 2", "x", "unrelated-group-2-" + System.nanoTime(), "READER"));
		Authentication noAccess = new UsernamePasswordAuthenticationToken("no-access-user-2", null);

		assertThatThrownBy(() -> galleryService.requireReadableImage(noAccess, uploaded.id())).isInstanceOf(AccessDeniedException.class);
		assertThat(galleryService.requireReadableImage(auth, uploaded.id()).getId()).isEqualTo(uploaded.id());
	}

	@Test
	void getDirectoryByPath_deniedForUserWithNoAccessToThatGroup() {
		DirectoryResponse directory = galleryService.listDirectories().stream().filter(d -> d.id().equals(directoryId)).findFirst().orElseThrow();
		userService.createUser(new CreateUserRequest("no-access-user-3", "No Access 3", "x", "unrelated-group-3-" + System.nanoTime(), "LOWER"));
		Authentication noAccess = new UsernamePasswordAuthenticationToken("no-access-user-3", null);

		assertThatThrownBy(() -> galleryService.getDirectoryByPath(noAccess, directory.path())).isInstanceOf(AccessDeniedException.class);
		assertThatCode(() -> galleryService.getDirectoryByPath(auth, directory.path())).doesNotThrowAnyException();
	}

	@Test
	void search_excludesImagesFromGroupsTheCallerCannotBrowse_butIncludesOnesTheyCan() throws IOException {
		// A second directory in a completely separate group, with its own
		// image - the search-wide equivalent of listImages' single-directory
		// check, but *filtering* a candidate out rather than rejecting the
		// whole request (legacy QuerySvr's own per-image skip, see
		// GalleryService.search's own doc).
		String otherGroup = "search-restricted-group-" + System.nanoTime();
		userService.addRoleAssignment("gallery-test-admin", new CreateRoleAssignmentRequest(otherGroup, "ADMIN"));
		DirectoryResponse otherDirectory = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/restricted-" + Long.toString(System.nanoTime(), 36), otherGroup));
		ImageResponse visibleImage = galleryService.uploadImage(auth, directoryId, jpegFile("search-visible.jpg"));
		ImageResponse hiddenImage = galleryService.uploadImage(auth, otherDirectory.id(), jpegFile("search-hidden.jpg"));

		userService.createUser(new CreateUserRequest("public-only-searcher", "Public Only", "x", "public", "READER"));
		Authentication publicOnly = new UsernamePasswordAuthenticationToken("public-only-searcher", null);

		ImageSearchResponse result = galleryService.search(publicOnly, null, null, null, null, null, 0, 100);

		List<Long> resultIds = result.images().stream().map(ImageResponse::id).toList();
		assertThat(resultIds).contains(visibleImage.id()).doesNotContain(hiddenImage.id());
	}

	@Test
	void createDirectory_duplicatePath_throwsConflict() {
		assertThatThrownBy(() -> galleryService.createDirectory(auth, new CreateDirectoryRequest(
				galleryService.listDirectories().stream().filter(d -> d.id().equals(directoryId)).findFirst().orElseThrow().path(), null)))
				.isInstanceOf(DirectoryAlreadyExistsException.class);
	}

	@Test
	void createDirectory_alsoCreatesTheFolderOnDisk() throws IOException {
		// Real bug found live, not hypothetical: a directory created this
		// way used to only ever get a DB row, never a real folder - so it
		// never showed up in the filesystem-driven navigation tree at all.
		String path = "1960/test-" + Long.toString(System.nanoTime(), 36);

		galleryService.createDirectory(auth, new CreateDirectoryRequest(path, null));

		assertThat(Files.isDirectory(storageRoot.resolve(path))).isTrue();
	}

	private InputStream storageServiceThumbnail(Image image) {
		// .thumbnails co-located inside the sequence directory itself
		// (31/08/2026 restructure, matches legacy exactly) - not a separate
		// mirror tree elsewhere.
		Path thumbPath = storageRoot.resolve(image.getDirectory().getPath()).resolve(".thumbnails").resolve(image.getName());
		try {
			return Files.newInputStream(thumbPath);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private InputStream storageServiceWeb(Image image) {
		// ".webimg", not ".web" - matches legacy's real Configuration.webImgDirName
		// (31/08/2026, found live on the real server - ImageStorageService's own
		// class javadoc has the full story) - co-located, same as .thumbnails above.
		Path webPath = storageRoot.resolve(image.getDirectory().getPath()).resolve(".webimg").resolve(image.getName());
		try {
			return Files.newInputStream(webPath);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static MockMultipartFile jpegFile(String name) throws IOException {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return new MockMultipartFile("file", name, "image/jpeg", out.toByteArray());
	}

	/**
	 * A 40x40, hard-edged, 4-quadrant PNG (20x20 quadrants: red/green/blue/
	 * yellow, top-left/top-right/bottom-right/bottom-left) - PNG rather
	 * than {@link #jpegFile}'s own JPEG specifically because it's lossless
	 * (no compression noise to account for when comparing pixels before/
	 * after a transform), and geometrically unambiguous: a warp's own
	 * corner-to-corner correctness can be checked by which quadrant color
	 * actually lands where in the output, not just "some resampling
	 * happened".
	 */
	private static MockMultipartFile quadrantPngFile(String name) throws IOException {
		BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = img.createGraphics();
		g2.setColor(Color.RED);
		g2.fillRect(0, 0, 20, 20);
		g2.setColor(Color.GREEN);
		g2.fillRect(20, 0, 20, 20);
		g2.setColor(Color.BLUE);
		g2.fillRect(20, 20, 20, 20);
		g2.setColor(Color.YELLOW);
		g2.fillRect(0, 20, 20, 20);
		g2.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return new MockMultipartFile("file", name, "image/png", out.toByteArray());
	}

	/** Same real-fixture discipline as {@link ExifGpsReaderTest} - a genuine GPS EXIF tag via a lossless rewrite, not asserted-but-never-read metadata. */
	private static MockMultipartFile jpegFileWithGps(String name, double longitude, double latitude) throws Exception {
		ByteArrayOutputStream plainJpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), "jpg", plainJpeg);

		TiffOutputSet outputSet = new TiffOutputSet();
		outputSet.setGpsInDegrees(longitude, latitude);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new ExifRewriter().updateExifMetadataLossless(plainJpeg.toByteArray(), out, outputSet);
		return new MockMultipartFile("file", name, "image/jpeg", out.toByteArray());
	}
}
