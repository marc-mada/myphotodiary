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

import org.myphotodiary.cms.gallery.Image;

/**
 * The external-viewer counterpart of {@link ImageResponse} (ShareController)
 * - deliberately a separate, narrower DTO rather than reusing
 * {@code ImageResponse} directly: no {@code attributeNames} (this app's own
 * tag vocabulary is internal, not something to leak to someone with no
 * account), no {@code originalUrl}/{@code exportUrl} (full-resolution
 * download was never asked for here, only viewing - the public web/thumbnail
 * endpoints below are deliberately the only ones this DTO points at), and
 * {@code thumbnailUrl}/{@code webUrl} point at {@code ShareController}'s own
 * public, token-gated endpoints, not {@code GalleryController}'s
 * authenticated ones - already carrying the same token as a query parameter
 * so the frontend's public viewer never has to know to append it itself.
 */
public record PublicImageResponse(
		Long id,
		String name,
		String description,
		int rating,
		String mediaType,
		String thumbnailUrl,
		String webUrl,
		/** The picture's own sequence comment - only filled for a shared search result, whose pictures come from different sequences (04/10/2026); {@code null} otherwise. */
		String sequenceDescription) {

	public static PublicImageResponse from(Image image, String token) {
		return from(image, token, false);
	}

	public static PublicImageResponse from(Image image, String token, boolean withSequenceDescription) {
		String base = "/api/public/images/" + image.getId();
		String suffix = "?token=" + token;
		return new PublicImageResponse(
				image.getId(),
				image.getName(),
				image.getDescription(),
				image.getRating(),
				image.getMediaType().name(),
				base + "/thumbnail" + suffix,
				base + "/web" + suffix,
				withSequenceDescription ? image.getDirectory().getDescription() : null);
	}
}
