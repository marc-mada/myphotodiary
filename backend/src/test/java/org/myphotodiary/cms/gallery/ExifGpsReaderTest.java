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
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Same real-fixture discipline as {@link ExifDateReaderTest}'s own DateTime
 * tag tests - Apache Commons Imaging (test-scope only) writes a genuine GPS
 * EXIF tag via a lossless rewrite of a plain JPEG, which metadata-extractor
 * then reads back for real, not two libraries' internals asserted to agree
 * without the actual read ever running.
 *
 * {@code TiffOutputSet.setGpsInDegrees(double, double)} takes
 * (longitude, latitude) in that order - verified empirically before writing
 * these tests (a scratch round-trip against the real jars), not assumed
 * from the parameter names alone, which read ambiguously either way.
 */
class ExifGpsReaderTest {

	private final ExifGpsReader reader = new ExifGpsReader();

	@TempDir
	Path dir;

	@Test
	void noGpsTagAtAll_returnsNull() throws IOException {
		Path file = writePlainJpeg("no-gps.jpg");

		assertThat(reader.readGpsCoordinates(file)).isNull();
	}

	@Test
	void gpsTagPresent_returnsRealCoordinates() throws Exception {
		// Paris - both coordinates positive (northern/eastern hemisphere).
		Path file = writeJpegWithGps("paris.jpg", 2.3508, 48.8567);

		ExifGpsReader.Coordinates result = reader.readGpsCoordinates(file);

		assertThat(result).isNotNull();
		// Rational-degrees-minutes-seconds round-trip through EXIF isn't
		// exactly bit-for-bit the original double (confirmed empirically:
		// 48.8567 comes back as 48.856700000000004) - a small tolerance,
		// not exact equality, is the correct assertion here.
		assertThat(result.latitude()).isCloseTo(48.8567, within(0.0001));
		assertThat(result.longitude()).isCloseTo(2.3508, within(0.0001));
	}

	@Test
	void gpsTagPresent_negativeLatitudeAndLongitude_signsPreserved() throws Exception {
		// Rio de Janeiro - both coordinates negative (southern/western
		// hemisphere) - EXIF encodes hemisphere as a separate N/S, E/W
		// reference tag rather than a signed number, a real place for a
		// sign-handling bug to hide that a positive-only fixture like Paris
		// above could never catch.
		Path file = writeJpegWithGps("rio.jpg", -43.1729, -22.9068);

		ExifGpsReader.Coordinates result = reader.readGpsCoordinates(file);

		assertThat(result).isNotNull();
		assertThat(result.latitude()).isCloseTo(-22.9068, within(0.0001));
		assertThat(result.longitude()).isCloseTo(-43.1729, within(0.0001));
	}

	private Path writeJpegWithGps(String name, double longitude, double latitude) throws Exception {
		Path file = dir.resolve(name);
		ByteArrayOutputStream plainJpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", plainJpeg);

		TiffOutputSet outputSet = new TiffOutputSet();
		outputSet.setGpsInDegrees(longitude, latitude);

		try (OutputStream out = Files.newOutputStream(file)) {
			new ExifRewriter().updateExifMetadataLossless(plainJpeg.toByteArray(), out, outputSet);
		}
		return file;
	}

	private Path writePlainJpeg(String name) throws IOException {
		Path file = dir.resolve(name);
		ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", file.toFile());
		return file;
	}
}
