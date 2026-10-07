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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;

/**
 * A photo's GPS location (16/09/2026, explicit ask) - no legacy equivalent
 * to port (legacy never read or stored per-image GPS at all), a genuinely
 * new capability. Same library as {@link ExifDateReader} (metadata-extractor)
 * but a separate class rather than folded into that one: a different
 * {@code Metadata} directory ({@link GpsDirectory}, not
 * {@code ExifSubIFDDirectory}/{@code ExifIFD0Directory}), a different return
 * shape (a location-or-nothing, not a fallback chain through six signals),
 * and callers that want one concern without the other (the "web"-tier/
 * thumbnail-serving code path never needs GPS; nothing needs a date-only
 * read either, but the asymmetry of *some* callers wanting just one still
 * argues for keeping them decoupled).
 *
 * A second, independent {@code ImageMetadataReader.readMetadata} parse per
 * file, not combined into {@link ExifDateReader}'s own read - a deliberate
 * trade-off for a rarely-hot path (indexing/import, not every image request)
 * favoring two small, separately testable single-purpose classes over one
 * doing two things; the read itself is a small fraction of the cost of the
 * surrounding thumbnail/derivative generation either way.
 */
@Component
public class ExifGpsReader {

	private static final Logger log = LoggerFactory.getLogger(ExifGpsReader.class);

	public record Coordinates(double latitude, double longitude) {
	}

	/**
	 * @param imageFile the file on disk to read EXIF GPS from
	 * @return the photo's GPS coordinates, or {@code null} if it carries no GPS tag at all (never a placeholder like 0/0 - see {@link GeoLocation#isZero()})
	 */
	public Coordinates readGpsCoordinates(Path imageFile) {
		try (InputStream in = Files.newInputStream(imageFile)) {
			Metadata metadata = ImageMetadataReader.readMetadata(in);
			GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
			GeoLocation location = gpsDirectory != null ? gpsDirectory.getGeoLocation() : null;
			if (location == null || location.isZero()) {
				return null;
			}
			return new Coordinates(location.getLatitude(), location.getLongitude());
		} catch (ImageProcessingException | IOException e) {
			// Matches ExifDateReader's own discipline: a read failure just means
			// no GPS-derived location, not a hard error for the caller.
			log.info("readGpsCoordinates: file={} readError={}", imageFile.getFileName(), e.getClass().getSimpleName());
			return null;
		}
	}
}
