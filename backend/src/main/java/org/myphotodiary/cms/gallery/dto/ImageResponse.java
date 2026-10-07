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

package org.myphotodiary.cms.gallery.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.myphotodiary.cms.gallery.Image;

public record ImageResponse(
		Long id,
		String name,
		String description,
		int rating,
		LocalDateTime captureDate,
		// "IMAGE" or "VIDEO" (28/08/2026, video support) - lets the frontend
		// branch its main-viewer rendering (<img> vs <video>, ImageViewer/
		// MobileImageViewer) without guessing from the file extension; every
		// other field/feature (description, rating, tags, thumbnailUrl,
		// directoryId...) works identically for both, by design (see
		// MediaType's own javadoc).
		String mediaType,
		String thumbnailUrl,
		String webUrl,
		String originalUrl,
		String exportUrl,
		List<String> attributeNames,
		Long directoryId,
		// GPS location (16/09/2026), read-only from the frontend's own
		// perspective - null when the photo carries no GPS tag at all. Both
		// null together always (ExifGpsReader.Coordinates is all-or-nothing),
		// never one without the other.
		Double latitude,
		Double longitude) {

	public static ImageResponse from(Image image) {
		String base = "/api/images/" + image.getId();
		return new ImageResponse(
				image.getId(),
				image.getName(),
				image.getDescription(),
				image.getRating(),
				image.getCaptureDate(),
				image.getMediaType().name(),
				base + "/thumbnail",
				// Medium-resolution tier (legacy Configuration.webImgSize,
				// ImageStorageService.readWeb) - the main viewer displays this
				// instead of `originalUrl`, matching legacy's own mobile
				// behavior (midx.js's getWebUrl, always used unconditionally)
				// rather than desktop's bandwidth-adaptive full/web switch
				// (Design.md §15, legacy feature-parity audit) - simpler,
				// consistently lighter, full resolution reserved for
				// deliberate download via `exportUrl`.
				base + "/web",
				base + "/original",
				base + "/export",
				image.getAttributes().stream().map(a -> a.getName()).toList(),
				// Lets the frontend fetch the owning sequence's own
				// description/attributes (Directory.description, distinct
				// from Image.description - Design.md §15) for
				// whatever image is currently displayed, the same way the
				// legacy postit shows #dirDesc above #imgDesc for the
				// current photo regardless of which screen (browse vs.
				// search results) put it there.
				image.getDirectory().getId(),
				image.getLatitude(),
				image.getLongitude());
	}
}
