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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;

import javax.imageio.ImageIO;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The images used here (plain ImageIO-written PNGs/JPEGs) carry no EXIF at
 * all - a real-world case metadata-extractor handles gracefully (no
 * ExifSubIFDDirectory found, not an error), which exercises the fallback
 * chain exactly the way a phone photo lacking EXIF would. A genuine
 * DateTimeOriginal fixture isn't included (constructing valid EXIF bytes by
 * hand isn't worth it for this); the EXIF-directory read path itself is
 * still exercised by every call here, just returning empty.
 *
 * The DateTime (0x0132) fixture below is the one exception (01/09/2026,
 * added with the fallback it tests) - that gap was a real bug reported
 * live in production, not a hypothetical, so it gets a real fixture rather
 * than staying untested like the DateTimeOriginal/Digitized cases above:
 * Apache Commons Imaging (test-scope only, see pom.xml) writes a genuine
 * IFD0 DateTime tag via a lossless EXIF rewrite of a plain JPEG, which
 * metadata-extractor then reads back for real - not two libraries'
 * internals asserted to agree without ever actually running the read.
 */
class ExifDateReaderTest {

	private final ExifDateReader reader = new ExifDateReader();

	@TempDir
	Path dir;

	@Test
	void noExifNoWhatsappPattern_fallsBackToFileCreationTime() throws IOException {
		Path file = writeJpeg("plain-photo.jpg");

		LocalDateTime result = reader.readCaptureDate(file, "plain-photo.jpg", null);

		assertThat(result).isNotNull();
		assertThat(result.toLocalDate()).isEqualTo(LocalDate.now());
	}

	@Test
	void whatsAppFilenamePattern_parsedWhenNoExif() throws IOException {
		Path file = writeJpeg("IMG-20260315-WA0007.jpg");

		LocalDateTime result = reader.readCaptureDate(file, "IMG-20260315-WA0007.jpg", null);

		assertThat(result.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 15));
	}

	@Test
	void sequenceDefaultDate_winsWhenImageDateWouldBeAfterIt() throws IOException {
		// The legacy clamp rule: an image date after its sequence's nominal
		// date is treated as suspect, and the sequence default wins instead.
		Path file = writeJpeg("IMG-20260315-WA0007.jpg");
		LocalDateTime sequenceDefault = LocalDateTime.of(2026, 1, 1, 0, 0);

		LocalDateTime result = reader.readCaptureDate(file, "IMG-20260315-WA0007.jpg", sequenceDefault);

		assertThat(result).isEqualTo(sequenceDefault);
	}

	@Test
	void sequenceDefaultDate_doesNotOverrideWhenImageDateIsEarlier() throws IOException {
		Path file = writeJpeg("IMG-20260101-WA0007.jpg");
		LocalDateTime sequenceDefault = LocalDateTime.of(2026, 6, 1, 0, 0);

		LocalDateTime result = reader.readCaptureDate(file, "IMG-20260101-WA0007.jpg", sequenceDefault);

		assertThat(result.toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 1));
	}

	@Test
	void clientLastModified_usedWhenNoExifAndNoWhatsAppPattern() throws IOException {
		// Regression test for a second real gap found chasing the same
		// production report as the DateTime(IFD0) fix: once that fix
		// shipped, the specific failing file (01/09/2026) turned out to
		// have *no* EXIF at all and a plain numeric filename (no WhatsApp
		// pattern either) - a real, previously-discarded signal (the
		// browser's own File.lastModified) is now consulted before falling
		// all the way to the file's own creation time (effectively "now"
		// for a freshly-written upload temp file).
		Path file = writeJpeg("1000097622.jpg");
		LocalDateTime clientLastModified = LocalDateTime.of(2026, 8, 30, 21, 24, 57);

		LocalDateTime result = reader.readCaptureDate(file, "1000097622.jpg", clientLastModified, null);

		assertThat(result).isEqualTo(clientLastModified);
	}

	@Test
	void clientLastModified_doesNotOverrideWhatsAppFilenamePattern() throws IOException {
		// Ranked below the WhatsApp filename pattern deliberately (see
		// ExifDateReader's own javadoc) - a filename genuinely encoding the
		// send date is a stronger signal than a last-modified timestamp
		// that copying/re-saving can bump forward.
		Path file = writeJpeg("IMG-20260315-WA0007.jpg");
		LocalDateTime clientLastModified = LocalDateTime.of(2026, 8, 30, 21, 24, 57);

		LocalDateTime result = reader.readCaptureDate(file, "IMG-20260315-WA0007.jpg", clientLastModified, null);

		assertThat(result.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 15));
	}

	@Test
	void clientLastModified_absentAndNoOtherSignal_stillFallsBackToFileCreationTime() throws IOException {
		// The pre-existing final fallback must still work when the new
		// parameter simply isn't supplied (the 3-arg overload every
		// existing caller/test uses).
		Path file = writeJpeg("plain-photo.jpg");

		LocalDateTime result = reader.readCaptureDate(file, "plain-photo.jpg", null);

		assertThat(result.toLocalDate()).isEqualTo(LocalDate.now());
	}

	@Test
	void dateTimeTag_usedWhenDateTimeOriginalAndDigitizedAbsent() throws Exception {
		// Regression test for a real bug reported live in production
		// (01/09/2026): "Publish into a new sequence" sometimes landed on
		// the upload date instead of the photo's real capture date, despite
		// EXIF genuinely being present in the file. Root cause: some
		// cameras/phones only populate DateTime (0x0132, the main image
		// IFD) rather than DateTimeOriginal/DateTimeDigitized (the Exif
		// SubIFD) - before the fix, ExifDateReader never read this tag at
		// all and fell straight through to the WhatsApp filename pattern
		// (no match here), then the file's own creation time, which for a
		// freshly-written fixture/upload is "now" - not March 2026.
		Path file = writeJpegWithIfd0DateTime("only-datetime-tag.jpg", "2026:03:15 10:30:00");

		LocalDateTime result = reader.readCaptureDate(file, "only-datetime-tag.jpg", null);

		assertThat(result.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 15));
	}

	@Test
	void dateTimeTag_takesPriorityOverWhatsAppFilenamePattern() throws Exception {
		// DateTime (0x0132) was added as fallback #3, ahead of the WhatsApp
		// filename pattern (#4) - a filename that would otherwise match the
		// WhatsApp pattern must not win once a real EXIF DateTime is present.
		Path file = writeJpegWithIfd0DateTime("IMG-20260101-WA0007.jpg", "2026:03:15 10:30:00");

		LocalDateTime result = reader.readCaptureDate(file, "IMG-20260101-WA0007.jpg", null);

		assertThat(result.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 15));
	}

	@Test
	void dateTimeOriginal_stillTakesPriorityOverDateTimeTag() throws Exception {
		// The new DateTime (0x0132) fallback must not shadow the two tags
		// it falls back from - DateTimeOriginal (Exif SubIFD) still wins
		// when both are present on the same file.
		Path file = dir.resolve("both-tags.jpg");
		ByteArrayOutputStream plainJpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", plainJpeg);

		TiffOutputSet outputSet = new TiffOutputSet();
		TiffOutputDirectory ifd0 = outputSet.getOrCreateRootDirectory();
		ifd0.add(TiffTagConstants.TIFF_TAG_DATE_TIME, "2026:01:01 00:00:00");
		TiffOutputDirectory exifDir = outputSet.getOrCreateExifDirectory();
		exifDir.add(org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, "2026:03:15 10:30:00");

		try (OutputStream out = Files.newOutputStream(file)) {
			new ExifRewriter().updateExifMetadataLossless(plainJpeg.toByteArray(), out, outputSet);
		}

		LocalDateTime result = reader.readCaptureDate(file, "both-tags.jpg", null);

		assertThat(result.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 15));
	}

	@Test
	void exifDateTime_isTheCameraWallClockTime_notShiftedByTheServerTimeZone() throws Exception {
		// Regression test for a real bug found in testing (26/09/2026): an EXIF
		// time carries no time zone, but metadata-extractor's getDate(tag)
		// reads it as UTC and the old code converted that instant to the
		// server's zone - so on a Paris server every EXIF-dated photo was
		// stored 1h (winter) or 2h (summer) late, and one taken late on the
		// last evening of a month landed in the next month. The earlier
		// tests only compared the *day*, which is why this went unnoticed -
		// these compare the exact time. Paris is forced so the old bug shows
		// whatever zone the machine running the tests happens to be in.
		TimeZone original = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
		try {
			Path summer = writeJpegWithDateTimeOriginal("summer.jpg", "2026:07:31 23:30:00");
			Path winter = writeJpegWithDateTimeOriginal("winter.jpg", "2026:01:15 10:20:30");
			Path ifd0 = writeJpegWithIfd0DateTime("ifd0.jpg", "2026:07:31 23:30:00");

			assertThat(reader.readCaptureDate(summer, "summer.jpg", null)).isEqualTo(LocalDateTime.of(2026, 7, 31, 23, 30, 0));
			assertThat(reader.readCaptureDate(winter, "winter.jpg", null)).isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 20, 30));
			assertThat(reader.readCaptureDate(ifd0, "ifd0.jpg", null)).isEqualTo(LocalDateTime.of(2026, 7, 31, 23, 30, 0));
		} finally {
			TimeZone.setDefault(original);
		}
	}

	private Path writeJpegWithDateTimeOriginal(String name, String exifDateTime) throws Exception {
		Path file = dir.resolve(name);
		ByteArrayOutputStream plainJpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", plainJpeg);

		TiffOutputSet outputSet = new TiffOutputSet();
		TiffOutputDirectory exifDir = outputSet.getOrCreateExifDirectory();
		exifDir.add(org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDateTime);

		try (OutputStream out = Files.newOutputStream(file)) {
			new ExifRewriter().updateExifMetadataLossless(plainJpeg.toByteArray(), out, outputSet);
		}
		return file;
	}

	private Path writeJpegWithIfd0DateTime(String name, String exifDateTime) throws Exception {
		Path file = dir.resolve(name);
		ByteArrayOutputStream plainJpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", plainJpeg);

		TiffOutputSet outputSet = new TiffOutputSet();
		TiffOutputDirectory ifd0 = outputSet.getOrCreateRootDirectory();
		ifd0.add(TiffTagConstants.TIFF_TAG_DATE_TIME, exifDateTime);

		try (OutputStream out = Files.newOutputStream(file)) {
			new ExifRewriter().updateExifMetadataLossless(plainJpeg.toByteArray(), out, outputSet);
		}
		return file;
	}

	private Path writeJpeg(String name) throws IOException {
		Path file = dir.resolve(name);
		BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
		ImageIO.write(img, "jpg", file.toFile());
		return file;
	}
}
