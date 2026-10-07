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
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.ImportResponse;
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
 * The faithfully-ported legacy ImportSvr behavior (Design.md §16.5) - see
 * ImportService's javadoc. Files here carry no real EXIF, so auto-sort tests
 * rely on the WhatsApp-filename fallback to pin an exact, predictable date
 * (rather than "whatever the file's creation time happens to be right now"),
 * same technique as ExifDateReaderTest.
 */
@SpringBootTest
@Transactional
class ImportServiceTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
	}

	@Autowired
	private ImportService importService;
	@Autowired
	private DirectoryRepository directoryRepository;
	@Autowired
	private ImageRepository imageRepository;
	@Autowired
	private UserService userService;

	// ADMIN is permitted every Action in the role matrix - this class tests
	// the EXIF/path-computation behavior, not RBAC itself.
	private Authentication auth;

	@BeforeEach
	void setUpAuth() {
		userService.createUser(new CreateUserRequest("import-test-admin", "Import Test Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("import-test-admin", null);
	}

	@Test
	void sequenceName_withSlashOrDotDot_isRejectedBeforeAnythingIsStored() throws IOException {
		// Same rule as Rename (SequenceNames, 02/10/2026) for the Publish
		// forms' sequence-name field.
		long imagesBefore = imageRepository.count();
		for (String bad : new String[] { "a/../../escape-test", "nested/name", "..", ".webimg" }) {
			assertThatThrownBy(() -> importService.importImage(auth, jpegFile("IMG-20260315-WA0002.jpg"), true, true, bad, null, null))
					.as("sequence name %s", bad)
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(imageRepository.count()).isEqualTo(imagesBefore);
		assertThat(directoryRepository.findByPath("2026/03/a/../../escape-test")).isEmpty();
		assertThat(directoryRepository.findByPath("2026/03/nested/name")).isEmpty();
	}

	@Test
	void sequenceName_withSurroundingSlashes_isStillAccepted() throws IOException {
		// normalize() already strips leading/trailing slashes - "trip/" is a
		// valid single name once trimmed, not a nested path.
		ImportResponse result = importService.importImage(auth, jpegFile("IMG-20260315-WA0003.jpg"), true, true, "/slash-trip/", null, null);
		assertThat(result.directory().path()).isEqualTo("2026/03/slash-trip");
	}

	@Test
	void autoSort_computesYearMonthFromWhatsAppFilenameDate() throws IOException {
		ImportResponse result = importService.importImage(auth, 
				jpegFile("IMG-20260315-WA0001.jpg"), true, false, null, null, null);

		assertThat(result.directory().path()).isEqualTo("2026/03");
		assertThat(result.directory().sequenceDate()).isEqualTo(LocalDate.of(2026, 3, 15));
		assertThat(result.image().name()).isEqualTo("IMG-20260315-WA0001.jpg");
	}

	@Test
	void importImage_withGpsExif_persistsLatitudeLongitudeOnTheImageRow() throws Exception {
		// Exercises storeImportedFile (shared with importFromStagingFile) -
		// one test suffices for both real call sites, same as this file's
		// other tests never duplicate every behavior across both import
		// methods either, given that shared method (its own javadoc).
		ImportResponse result = importService.importImage(auth, jpegFileWithGps("paris.jpg", 2.3508, 48.8567), false, false, null, "gps/test", null);

		assertThat(result.image().latitude()).isCloseTo(48.8567, within(0.0001));
		assertThat(result.image().longitude()).isCloseTo(2.3508, within(0.0001));
	}

	@Test
	void autoSortOff_usesGivenDirPath() throws IOException {
		ImportResponse result = importService.importImage(auth, 
				jpegFile("photo.jpg"), false, false, null, "some/manual/path", null);

		assertThat(result.directory().path()).isEqualTo("some/manual/path");
	}

	@Test
	void createSubDir_appendsSequenceNameUnderComputedPath() throws IOException {
		ImportResponse result = importService.importImage(auth, 
				jpegFile("IMG-20260315-WA0001.jpg"), true, true, "alps trip", null, null);

		assertThat(result.directory().path()).isEqualTo("2026/03/alps trip");
	}

	@Test
	void createSubDirOff_landsDirectlyInComputedPath_noSubfolder() throws IOException {
		ImportResponse result = importService.importImage(auth, 
				jpegFile("IMG-20260315-WA0001.jpg"), true, false, "ignored-when-off", null, null);

		assertThat(result.directory().path()).isEqualTo("2026/03");
	}

	@Test
	void newDirectory_autoSort_sequenceDateIsImageDate() throws IOException {
		ImportResponse result = importService.importImage(auth, 
				jpegFile("IMG-20260601-WA0001.jpg"), true, false, null, null, null);

		assertThat(result.directory().sequenceDate()).isEqualTo(LocalDate.of(2026, 6, 1));
	}

	@Test
	void newDirectory_manualPath_sequenceDateGuessedFromPath() throws IOException {
		ImportResponse result = importService.importImage(auth, 
				jpegFile("photo.jpg"), false, false, null, "2026/07/some-sequence", null);

		// No day in the path -> last day of the guessed month (PathDateGuesser).
		assertThat(result.directory().sequenceDate()).isEqualTo(LocalDate.of(2026, 7, 31));
	}

	@Test
	void existingDirectory_secondImportMergesSequenceDate() throws IOException {
		importService.importImage(auth, jpegFile("IMG-20260315-WA0001.jpg"), true, false, null, null, null);

		ImportResponse second = importService.importImage(auth, jpegFile("IMG-20260320-WA0002.jpg"), true, false, null, null, null);

		// Later date within the same month wins (DirectoryDateRules).
		assertThat(second.directory().sequenceDate()).isEqualTo(LocalDate.of(2026, 3, 20));
	}

	@Test
	void duplicateNameInSameComputedDirectory_throwsConflict() throws IOException {
		importService.importImage(auth, jpegFile("IMG-20260315-WA0001.jpg"), true, false, null, null, null);

		assertThatThrownBy(() -> importService.importImage(auth, jpegFile("IMG-20260315-WA0001.jpg"), true, false, null, null, null))
				.isInstanceOf(ImageAlreadyExistsException.class);
	}

	@Test
	void concurrentImportsIntoTheSameNewDirectory_dontRaceEachOtherOut() throws Exception {
		// Real bug, signalled 22/09/2026 ("when we drop several files
		// together, most of the time, the first one in the list fails to
		// upload; pressing retry usually works"). react-filepond's own
		// maxParallelUploads: 2 default genuinely fires up to two concurrent
		// /api/import requests, and the common case - several files from
		// the same drop computing the same not-yet-existing target
		// directory (same EXIF month, no custom sequence name, or here:
		// the same explicit dirPath) - used to race two separate
		// transactions on directory.path's own UNIQUE constraint (V2's
		// schema): the loser saw an unhandled DataIntegrityViolationException
		// surface as a plain 500. More than 2 concurrent threads here (not
		// just 2) to make the race reliably reproducible against the
		// pre-fix code, not just "most of the time" like the live report.
		//
		// Uses the real bootstrap "admin" account (BootstrapAdminInitializer,
		// genuinely committed at application startup, real password long
		// since irrelevant here - the Authentication itself never checks
		// it), not this class's own setUpAuth()/`auth` - that user only
		// exists inside *this test method's own* still-open transaction
		// (Spring's test transaction support, rolled back at the end),
		// invisible to the background threads below, which run in their
		// own separate, genuinely committing transactions (no ambient
		// transaction bound to a pool thread) - confirmed live: a first
		// version of this test using `auth` failed every background import
		// with "no role assignment in group public", not the race this
		// test exists to catch.
		Authentication concurrentAuth = new UsernamePasswordAuthenticationToken("admin", null);
		String dirPath = "concurrency-test/" + System.nanoTime();
		int fileCount = 6;
		ExecutorService pool = Executors.newFixedThreadPool(fileCount);
		CountDownLatch ready = new CountDownLatch(fileCount);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<ImportResponse>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < fileCount; i++) {
				String name = "race-" + i + ".jpg";
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return importService.importImage(concurrentAuth, jpegFile(name), false, false, null, dirPath, null);
				}));
			}
			ready.await();
			go.countDown();

			// .get() re-throws whatever the background thread threw - this
			// is exactly where the pre-fix race surfaced as a failed upload.
			for (Future<ImportResponse> future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		Directory directory = directoryRepository.findByPath(dirPath).orElseThrow();
		List<Image> images = imageRepository.findByDirectory_IdOrderByNameAsc(directory.getId());
		assertThat(images).hasSize(fileCount);

		// Each import above ran in its own real (committed) transaction -
		// separate from this test method's own @Transactional rollback,
		// since Spring's test transaction synchronization is bound to this
		// thread, not the pool's background threads - clean up explicitly
		// rather than leaving real rows in the shared mem:testdb for later
		// tests in this class to trip over.
		imageRepository.deleteAll(images);
		directoryRepository.delete(directory);
	}

	@Test
	void clientLastModifiedEpochMillis_usedWhenNoExifAndNoWhatsAppPattern() throws IOException {
		// Regression test for a real production report (01/09/2026): a plain
		// numeric filename, no EXIF - previously fell all the way to the
		// upload's own temp-file creation time ("now"). The 8-arg overload
		// (what /api/import actually calls) now threads the browser's
		// File.lastModified all the way through to ExifDateReader.
		LocalDateTime expected = LocalDateTime.of(2026, 8, 30, 21, 24, 57);
		long epochMillis = expected.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

		ImportResponse result = importService.importImage(
				auth, jpegFile("1000097622.jpg"), true, false, null, null, null, epochMillis);

		assertThat(result.directory().path()).isEqualTo("2026/08");
		assertThat(result.image().captureDate()).isEqualTo(expected);
	}

	@Test
	void importedFile_isActuallyStoredAndThumbnailed() throws IOException {
		ImportResponse result = importService.importImage(auth,
				jpegFile("IMG-20260315-WA0001.jpg"), true, false, null, null, null);

		Path original = storageRoot.resolve("2026/03").resolve("IMG-20260315-WA0001.jpg");
		// .thumbnails co-located inside the sequence directory (31/08/2026 restructure, matches legacy exactly).
		Path thumbnail = storageRoot.resolve("2026/03").resolve(".thumbnails").resolve("IMG-20260315-WA0001.jpg");
		assertThat(java.nio.file.Files.exists(original)).isTrue();
		assertThat(java.nio.file.Files.exists(thumbnail)).isTrue();
		assertThat(imageRepository.findById(result.image().id())).isPresent();
	}

	// --- importFromStagingFile (01/09/2026 - legacy's real Batch Publish, see StagingImportService's own javadoc) ---

	@Test
	void stagingImport_autoSortsByExif_justLikeRegularPublish() throws IOException {
		Path source = writeJpegFile("IMG-20260315-WA0001.jpg");

		ImportResponse result = importService.importFromStagingFile(auth, source, "IMG-20260315-WA0001.jpg", "alps trip", null, null);

		// Same WhatsApp-filename-date fallback a regular Publish already
		// uses (ExifDateReader.readCaptureDate, the identical call) - date
		// wins the {year}/{month} destination, the staging folder's own
		// name (passed here as "alps trip") becomes the sequence, exactly
		// condition 2's own wording.
		assertThat(result.directory().path()).isEqualTo("2026/03/alps trip");
		assertThat(result.directory().sequenceDate()).isEqualTo(LocalDate.of(2026, 3, 15));
	}

	@Test
	void stagingImport_sequenceDefaultDate_usedWhenExifAbsent() throws IOException {
		// Plain name - no WhatsApp pattern, no real EXIF (writeJpegFile
		// writes a bare ImageIO JPEG with none) - the year/month selector's
		// own value (condition 3) is the only thing left to fall back on.
		Path source = writeJpegFile("plain-photo.jpg");
		LocalDateTime selectorDate = java.time.YearMonth.of(2026, 5).atEndOfMonth().atStartOfDay();

		ImportResponse result = importService.importFromStagingFile(auth, source, "plain-photo.jpg", "some sequence", selectorDate, null);

		assertThat(result.directory().path()).isEqualTo("2026/05/some sequence");
		assertThat(result.image().captureDate()).isEqualTo(selectorDate);
	}

	@Test
	void stagingImport_capturePhotoDateStillWinsOverSelectorWhenBothPresent() throws IOException {
		// Condition 4 - "capture date first, then same fallback" - the
		// selector must NOT override a real EXIF-derived date that's
		// actually earlier in the same window; ExifDateReader's own clamp
		// only ever kicks in when the found date is null or *after* the
		// selector, never simply "present".
		Path source = writeJpegFile("IMG-20260315-WA0001.jpg");
		LocalDateTime selectorDate = java.time.YearMonth.of(2026, 3).atEndOfMonth().atStartOfDay();

		ImportResponse result = importService.importFromStagingFile(auth, source, "IMG-20260315-WA0001.jpg", "seq", selectorDate, null);

		assertThat(result.directory().sequenceDate()).isEqualTo(LocalDate.of(2026, 3, 15));
	}

	@Test
	void stagingImport_copiesRatherThanMoves_sourceSurvivesBothSuccessAndFailure(@TempDir Path staging) throws IOException {
		// 01/09/2026 (real feedback, corrected the same day): a staging file
		// is never deleted by a successful import, unlike a regular upload's
		// own disposable temp file - the admin's own retained source copy
		// (e.g. a camera's SD card) is never this method's to delete.
		Path source = staging.resolve("keep-me.jpg");
		writeJpeg(source);
		importService.importFromStagingFile(auth, source, "keep-me.jpg", "seq", null, null);
		assertThat(java.nio.file.Files.exists(source)).isTrue(); // still there after a successful import
		assertThat(java.nio.file.Files.exists(storageRoot.resolve(java.time.LocalDate.now().getYear() + "")
				.resolve(String.format("%02d", java.time.LocalDate.now().getMonthValue())).resolve("seq").resolve("keep-me.jpg"))).isTrue();

		// Re-importing that same original filename into the same sequence
		// collides (already exists at the destination) - the source must
		// still survive this failure too, exactly as it survived success.
		assertThatThrownBy(() -> importService.importFromStagingFile(auth, source, "keep-me.jpg", "seq", null, null))
				.isInstanceOf(ImageAlreadyExistsException.class);
		assertThat(java.nio.file.Files.exists(source)).isTrue();
	}

	private static MockMultipartFile jpegFile(String name) throws IOException {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return new MockMultipartFile("file", name, "image/jpeg", out.toByteArray());
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

	private Path writeJpegFile(String name) throws IOException {
		Path file = java.nio.file.Files.createTempFile("staging-", "-" + name);
		writeJpeg(file);
		return file;
	}

	private static void writeJpeg(Path file) throws IOException {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		try (var out = java.nio.file.Files.newOutputStream(file)) {
			ImageIO.write(img, "jpg", out);
		}
	}
}
