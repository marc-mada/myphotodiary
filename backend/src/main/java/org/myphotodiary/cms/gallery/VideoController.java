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
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.VideoStatusResponse;
import org.myphotodiary.cms.gallery.dto.VideoTokenResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Video streaming - the parts of the video talk (28/08/2026) that don't fit
 * GalleryController's existing whole-file `InputStreamResource` model:
 *
 * - {@code /token}: normal Basic-Auth-protected (SecurityConfig's default
 *   `anyRequest().authenticated()` still applies here, no exception needed)
 *   - issues a short-lived signed token (VideoStreamTokenService) and hands
 *   back a ready-to-use stream URL, the same way any other authenticated API
 *   call works in this app.
 * - {@code /stream}: the one endpoint in this whole app that is NOT behind
 *   Basic Auth (see SecurityConfig's `permitAll()` on this exact path) -
 *   a plain {@code <video src>} can't carry the custom Authorization header
 *   everything else uses (AuthImage's own comment explains why, for images),
 *   so this validates the query-param token by hand instead. Supports HTTP
 *   Range requests (206 Partial Content via Spring's ResourceRegion) so the
 *   browser can seek without downloading the whole file first - the one
 *   piece of this whole feature with no equivalent anywhere in the existing
 *   image-serving endpoints, since a photo is always small enough to just
 *   download in full.
 *
 * No RBAC check beyond "does this Authentication exist" on either endpoint:
 * viewing is BROWSE, deliberately unconditional for any authenticated user
 * everywhere else in this app too (GalleryAuthorizationService's own doc).
 */
@RestController
public class VideoController {

	private final GalleryService galleryService;
	private final ImageStorageService storageService;
	private final VideoStreamTokenService tokenService;

	public VideoController(GalleryService galleryService, ImageStorageService storageService, VideoStreamTokenService tokenService) {
		this.galleryService = galleryService;
		this.storageService = storageService;
		this.tokenService = tokenService;
	}

	@GetMapping("/api/videos/{imageId}/token")
	public VideoTokenResponse token(Authentication authentication, @PathVariable Long imageId) {
		Image image = requireVideo(imageId);
		String token = tokenService.issueToken(image.getId());
		return new VideoTokenResponse("/api/videos/" + image.getId() + "/stream?token=" + token);
	}

	@GetMapping("/api/videos/{imageId}/stream")
	public ResponseEntity<ResourceRegion> stream(
			@PathVariable Long imageId, @RequestParam String token, @RequestHeader HttpHeaders headers) {
		Long tokenImageId = tokenService.validateAndGetImageId(token);
		if (!tokenImageId.equals(imageId)) {
			throw new InvalidVideoTokenException("Video token does not match the requested video");
		}
		Image image = requireVideo(imageId);

		return VideoRangeResponses.serve(storageService.resolvePlayableVideo(image), headers);
	}

	/**
	 * Which file plays this video (27/10/2026) - polled by the viewer while
	 * the playback copy is being made ("Preparing video..."), to reload the
	 * player once it is ready. Same access as viewing the video.
	 */
	@GetMapping("/api/videos/{imageId}/status")
	public VideoStatusResponse status(Authentication authentication, @PathVariable Long imageId) {
		Image image = galleryService.requireReadableImage(authentication, imageId);
		if (image.getMediaType() != org.myphotodiary.cms.gallery.MediaType.VIDEO) {
			throw new IllegalArgumentException("Not a video: " + imageId);
		}
		return new VideoStatusResponse(ImageResponse.videoPlaybackOf(image));
	}

	private Image requireVideo(Long imageId) {
		Image image = galleryService.requireImage(imageId);
		// Fully-qualified: org.springframework.http.MediaType is already
		// imported above (needed for contentType) under the same simple name.
		if (image.getMediaType() != org.myphotodiary.cms.gallery.MediaType.VIDEO) {
			throw new IllegalArgumentException("Not a video: " + imageId);
		}
		return image;
	}
}
