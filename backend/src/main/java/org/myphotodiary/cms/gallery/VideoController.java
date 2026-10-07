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

		Path path = storageService.resolveOriginal(image.getDirectory().getPath(), image.getName());
		FileSystemResource resource = new FileSystemResource(path);
		long contentLength;
		try {
			contentLength = resource.contentLength();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// Always video/mp4, never derived from the original file's own
		// extension (MediaTypeFactory.getMediaType, removed 03/09/2026) - a
		// real bug found live on iPad: a `.mov` upload (see ImportService's
		// own VIDEO_EXTENSIONS comment) was served as `video/quicktime`
		// instead, which Safari plays natively but Chrome/Firefox generally
		// don't, regardless of the actual codec inside. VideoFormatValidator
		// already guarantees every stored video is H.264(+AAC) - universally
		// playable as `video/mp4` - independent of whatever container
		// extension the original upload happened to arrive with, so the
		// original extension was never the right signal for this header to
		// begin with.
		MediaType contentType = MediaType.valueOf("video/mp4");

		List<HttpRange> ranges = headers.getRange();
		if (ranges.isEmpty()) {
			// No Range header yet - the browser's very first request for this
			// URL. Serve the whole thing but advertise Range support, so it
			// knows to send byte-range requests for subsequent seeks instead
			// of re-downloading from the start.
			ResourceRegion region = new ResourceRegion(resource, 0, contentLength);
			return ResponseEntity.ok().header("Accept-Ranges", "bytes").contentType(contentType).body(region);
		}
		ResourceRegion region = ranges.get(0).toResourceRegion(resource);
		return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).header("Accept-Ranges", "bytes").contentType(contentType).body(region);
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
