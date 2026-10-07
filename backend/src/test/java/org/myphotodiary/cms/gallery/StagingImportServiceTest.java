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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import javax.imageio.ImageIO;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.BatchIndexResult;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legacy's real Batch Publish (01/09/2026) - see StagingImportService's own
 * javadoc for the full feature story. Real files on a real filesystem (a
 * genuine recursive {@code Files.walk}, not a mocked directory listing) -
 * the "folder containing the image" rule (condition 2) is exactly the kind
 * of thing worth proving against actual nested directories, not assumed.
 */
@SpringBootTest
@Transactional
class StagingImportServiceTest {

	@TempDir
	static Path storageRoot;
	@TempDir
	static Path stagingRoot;

	@DynamicPropertySource
	static void roots(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
		registry.add("staging.root", () -> stagingRoot.toString());
	}

	@Autowired
	private StagingImportService stagingImportService;
	@Autowired
	private ImageRepository imageRepository;
	@Autowired
	private UserService userService;

	private Authentication auth;

	@BeforeEach
	void setUpAuth() {
		userService.createUser(new CreateUserRequest("staging-test-admin", "Staging Test Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("staging-test-admin", null);
	}

	@Test
	void importBatch_usesImmediateParentFolderAsSequenceName_whateverTheNestingDepth() throws IOException {
		Path base = uniqueSubdir("nesting");
		writeJpeg(base.resolve("tripA").resolve("photo1.jpg"));
		// Deliberately nested two levels under a differently-named folder -
		// condition 2's own wording is "the folder containing the image",
		// the immediate parent only, not the outermost one.
		writeJpeg(base.resolve("tripB").resolve("actual-sequence").resolve("photo2.jpg"));

		BatchIndexResult result = stagingImportService.importBatch(auth, relativeTo(stagingRoot, base), null, null, null);

		assertThat(result.failed()).isEmpty();
		assertThat(result.succeeded()).containsExactlyInAnyOrder("tripA/photo1.jpg", "actual-sequence/photo2.jpg");
		// Copied, not moved (01/09/2026, real feedback) - both staging
		// sources must still be sitting exactly where they were.
		assertThat(Files.exists(base.resolve("tripA").resolve("photo1.jpg"))).isTrue();
		assertThat(Files.exists(base.resolve("tripB").resolve("actual-sequence").resolve("photo2.jpg"))).isTrue();
	}

	@Test
	void importBatch_defaultYearMonth_usedForFilesWithNoExif() throws IOException {
		Path base = uniqueSubdir("default-date");
		writeJpeg(base.resolve("some sequence").resolve("plain.jpg"));

		BatchIndexResult result = stagingImportService.importBatch(auth, relativeTo(stagingRoot, base), 2026, 7, null);

		assertThat(result.failed()).isEmpty();
		assertThat(Files.exists(storageRoot.resolve("2026/07/some sequence/plain.jpg"))).isTrue();
	}

	@Test
	void importBatch_fileDirectlyInScannedRoot_reportedAsFailure_noSequenceFolder() throws IOException {
		Path base = uniqueSubdir("no-sequence");
		writeJpeg(base.resolve("orphan.jpg")); // no enclosing sequence folder

		BatchIndexResult result = stagingImportService.importBatch(auth, relativeTo(stagingRoot, base), null, null, null);

		assertThat(result.succeeded()).isEmpty();
		assertThat(result.failed()).hasSize(1);
		assertThat(result.failed().get(0).path()).isEqualTo("orphan.jpg");
	}

	@Test
	void importBatch_oneFailureDoesNotAbortOthers_andDoesNotPoisonAlreadySucceededWork() throws IOException {
		Path base = uniqueSubdir("mixed");
		writeJpeg(base.resolve("good sequence").resolve("ok.jpg"));
		// Same target filename under a directory that will already exist
		// once the first import above lands there - forces a genuine
		// ImageAlreadyExistsException from a *second* file, not a contrived
		// mock.
		writeJpeg(base.resolve("good sequence").resolve("ok2.jpg"));
		// Manually pre-create the collision after the fact isn't possible in
		// one importBatch call (each file needs a distinct name on disk to
		// even be listed) - instead prove non-abortion via two genuinely
		// independent successes plus one real failure (no sequence folder).
		writeJpeg(base.resolve("directly-here.jpg"));

		BatchIndexResult result = stagingImportService.importBatch(auth, relativeTo(stagingRoot, base), null, null, null);

		assertThat(result.succeeded()).containsExactlyInAnyOrder("good sequence/ok.jpg", "good sequence/ok2.jpg");
		assertThat(result.failed()).hasSize(1);
		assertThat(result.failed().get(0).path()).isEqualTo("directly-here.jpg");
		// The real proof this isn't just "the Java list still has two
		// entries" - both successes are genuinely, separately persisted and
		// queryable, not rolled back by the third file's own failure.
		assertThat(imageRepository.count()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void importBatch_defaultYearWithoutMonth_throws() {
		assertThatThrownBy(() -> stagingImportService.importBatch(auth, "", 2026, null, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void importBatch_pathEscapingStagingRoot_rejected() {
		assertThatThrownBy(() -> stagingImportService.importBatch(auth, "../../etc", null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void importBatch_emptyOrMissingPath_returnsEmptyResult_noError() {
		BatchIndexResult result = stagingImportService.importBatch(auth, "does-not-exist", null, null, null);

		assertThat(result.succeeded()).isEmpty();
		assertThat(result.failed()).isEmpty();
	}

	@Test
	void folderDates_scansSortedIntoYearMonthSequence_takeThatDate_ignoringTheirOwnExif() throws Exception {
		// 06/10/2026: old photos scanned years later - their EXIF (the scan
		// date) is wrong, so the folder they were sorted into is the date.
		Path base = uniqueSubdir("scans");
		String seq = "Wedding-" + Long.toString(System.nanoTime(), 36);
		writeJpegWithExifDate(base.resolve("1975/07/" + seq + "/scan001.jpg"), "2018:03:04 10:00:00");
		writeJpeg(base.resolve("1975/7/" + seq + "-b/scan002.jpg")); // one-digit month accepted

		BatchIndexResult result = stagingImportService.importBatch(auth, relativeTo(stagingRoot, base), null, null, null, true);

		assertThat(result.failed()).isEmpty();
		assertThat(Files.exists(storageRoot.resolve("1975/07/" + seq + "/scan001.jpg"))).isTrue();
		assertThat(Files.exists(storageRoot.resolve("1975/07/" + seq + "-b/scan002.jpg"))).isTrue();
		assertThat(captureDateOf("scan001.jpg")).isEqualTo(LocalDateTime.of(1975, 7, 31, 0, 0));
	}

	@Test
	void folderDates_aFileNotAtYearMonthSequence_isReportedNotImported() throws IOException {
		Path base = uniqueSubdir("scans-bad");
		writeJpeg(base.resolve("1975/Wedding/a.jpg"));          // no month level
		writeJpeg(base.resolve("1975/13/Wedding/b.jpg"));       // month 13
		writeJpeg(base.resolve("75/07/Wedding/c.jpg"));         // 2-digit year
		writeJpeg(base.resolve("1975/07/Wedding/sub/d.jpg"));   // one level too deep

		BatchIndexResult result = stagingImportService.importBatch(auth, relativeTo(stagingRoot, base), null, null, null, true);

		assertThat(result.succeeded()).isEmpty();
		assertThat(result.failed()).hasSize(4);
	}

	@Test
	void monthSelector_isOnlyAFallback_neverPullsALaterExifDateBack() throws Exception {
		// 06/10/2026: the selector used to act as a ceiling - a May photo with
		// March selected landed in March.
		Path base = uniqueSubdir("selector");
		String seq = "spring-" + Long.toString(System.nanoTime(), 36);
		writeJpegWithExifDate(base.resolve(seq + "/may.jpg"), "2024:05:10 12:00:00");
		writeJpeg(base.resolve(seq + "/nodate.jpg"));

		BatchIndexResult result = stagingImportService.importBatch(auth, relativeTo(stagingRoot, base), 2024, 3, null);

		assertThat(result.failed()).isEmpty();
		assertThat(Files.exists(storageRoot.resolve("2024/05/" + seq + "/may.jpg"))).as("EXIF month kept").isTrue();
		assertThat(Files.exists(storageRoot.resolve("2024/03/" + seq + "/nodate.jpg"))).as("selector used as fallback").isTrue();
		assertThat(captureDateOf("may.jpg")).isEqualTo(LocalDateTime.of(2024, 5, 10, 12, 0));
	}

	@Test
	void filesInsideHiddenFolders_areSkipped() throws IOException {
		Path base = uniqueSubdir("hidden");
		writeJpeg(base.resolve(".thumbnails/x.jpg"));
		writeJpeg(base.resolve("Seq/.Trashes/y.jpg"));
		writeJpeg(base.resolve("Seq/z.jpg"));

		BatchIndexResult result = stagingImportService.importBatch(auth, relativeTo(stagingRoot, base), null, null, null);

		assertThat(result.succeeded()).containsExactly("Seq/z.jpg");
		assertThat(result.failed()).isEmpty();
	}

	private LocalDateTime captureDateOf(String name) {
		return imageRepository.findAll().stream().filter(img -> img.getName().equals(name)).findFirst().orElseThrow().getCaptureDate();
	}

	private static void writeJpegWithExifDate(Path file, String exifDateTime) throws Exception {
		Files.createDirectories(file.getParent());
		ByteArrayOutputStream plainJpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", plainJpeg);
		TiffOutputSet outputSet = new TiffOutputSet();
		TiffOutputDirectory exifDir = outputSet.getOrCreateExifDirectory();
		exifDir.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDateTime);
		try (OutputStream out = Files.newOutputStream(file)) {
			new ExifRewriter().updateExifMetadataLossless(plainJpeg.toByteArray(), out, outputSet);
		}
	}

	/** A fresh, uniquely-named subdirectory of the shared static stagingRoot, so tests never see each other's leftover files. */
	private static Path uniqueSubdir(String label) throws IOException {
		Path dir = stagingRoot.resolve(label + "-" + Long.toString(System.nanoTime(), 36));
		Files.createDirectories(dir);
		return dir;
	}

	private static String relativeTo(Path root, Path dir) {
		return root.relativize(dir).toString();
	}

	private static void writeJpeg(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		try (var out = Files.newOutputStream(file)) {
			ImageIO.write(img, "jpg", out);
		}
	}
}
