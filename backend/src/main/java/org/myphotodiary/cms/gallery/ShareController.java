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

import java.util.List;

import org.myphotodiary.cms.gallery.ShareTokenService.ShareScope;
import org.myphotodiary.cms.gallery.ShareTokenService.ShareTokenClaims;
import org.myphotodiary.cms.gallery.dto.PublicImageResponse;
import org.myphotodiary.cms.gallery.dto.PublicSearchShareResponse;
import org.myphotodiary.cms.gallery.dto.SearchShareRequest;
import org.myphotodiary.cms.gallery.dto.SearchShareResponse;
import org.myphotodiary.cms.gallery.dto.PublicSequenceResponse;
import org.myphotodiary.cms.gallery.dto.ShareLinkResponse;
import org.myphotodiary.cms.settings.AppSettingsService;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * External (unauthenticated) sharing - image-link/sequence-link toolbar
 * icons (02/09/2026, explicit ask). Two halves, split the same way
 * {@link VideoController} splits "issue a token" from "use a token":
 *
 * <ul>
 * <li>{@code /share-token} endpoints - normal Basic-Auth-protected
 * (SecurityConfig's default {@code anyRequest().authenticated()} still
 * applies), issue a signed {@link ShareTokenService} token for the current
 * image/sequence. Viewing is BROWSE, deliberately unconditional for any
 * authenticated user everywhere else in this app (GalleryAuthorizationService's
 * own doc) - sharing a link to something you can already see needs no
 * additional per-group RBAC check beyond "does this Authentication exist",
 * same reasoning as GalleryController's own read endpoints.</li>
 * <li>{@code /api/public/*} endpoints - the one other place in this app (with
 * {@link VideoController}'s {@code /stream}) that is NOT behind Basic Auth
 * (SecurityConfig's {@code permitAll()}) - validates the query-param token by
 * hand instead, since the whole point is working for someone with no account
 * at all.</li>
 * </ul>
 *
 * Videos (14/10/2026, explicit ask): shared only when the app-wide setting
 * {@code allowVideoSharing} is on (Admin -> Configure, off by default).
 * Then a video gets an image-link token like a picture, appears in shared
 * sequences and search results, and plays through
 * {@code /api/public/images/{id}/stream} - the same Range-request serving as
 * {@link VideoController}'s signed-in stream, gated by the share token
 * instead. The setting is read on every public request, so turning it off
 * also stops the videos of links already sent (their pictures keep
 * working). Before 14/10/2026 video was out of scope entirely: a public
 * video is the original file, served to anyone holding the link for 30
 * days, a bigger exposure (bandwidth) than the picture endpoints.
 */
@RestController
public class ShareController {

	private final GalleryService galleryService;
	private final DirectoryRepository directoryRepository;
	private final ImageRepository imageRepository;
	private final ImageStorageService storageService;
	private final ShareTokenService tokenService;
	private final SharedSearchService sharedSearchService;
	private final AppSettingsService appSettingsService;

	public ShareController(
			GalleryService galleryService,
			DirectoryRepository directoryRepository,
			ImageRepository imageRepository,
			ImageStorageService storageService,
			ShareTokenService tokenService,
			SharedSearchService sharedSearchService,
			AppSettingsService appSettingsService) {
		this.galleryService = galleryService;
		this.directoryRepository = directoryRepository;
		this.imageRepository = imageRepository;
		this.storageService = storageService;
		this.tokenService = tokenService;
		this.sharedSearchService = sharedSearchService;
		this.appSettingsService = appSettingsService;
	}

	/**
	 * Shares a search result (04/10/2026) - freezes the pictures the given
	 * criteria match for the caller, at most the app-wide maximum (see
	 * SharedSearchService). A POST, unlike the two token endpoints above: it
	 * creates something on the server.
	 */
	@PostMapping("/api/search-shares")
	public SearchShareResponse shareSearch(Authentication authentication, @RequestBody SearchShareRequest request) {
		return sharedSearchService.share(authentication, request);
	}

	/** Public view of a shared search result: its pictures still available, in order, each with its own sequence comment. */
	@GetMapping("/api/public/search-shares/{sharedSearchId}")
	public PublicSearchShareResponse publicSearchShare(@PathVariable Long sharedSearchId, @RequestParam String token) {
		ShareTokenClaims claims = tokenService.validateToken(token);
		if (claims.scope() != ShareScope.SEARCH || !claims.id().equals(sharedSearchId)) {
			throw new InvalidShareTokenException("Share token does not match the requested search result");
		}
		List<PublicImageResponse> images = sharedSearchService.availableImages(sharedSearchId).stream()
				.map(img -> PublicImageResponse.from(img, token, true))
				.toList();
		return new PublicSearchShareResponse(sharedSearchId, images);
	}

	@GetMapping("/api/images/{imageId}/share-token")
	public ShareLinkResponse imageShareToken(Authentication authentication, @PathVariable Long imageId) {
		Image image = galleryService.requireImage(imageId);
		if (image.getMediaType() == org.myphotodiary.cms.gallery.MediaType.VIDEO && !appSettingsService.isVideoSharingAllowed()) {
			throw new IllegalArgumentException("Sharing videos is turned off (Admin > Configure)");
		}
		return new ShareLinkResponse(tokenService.issueToken(ShareScope.IMAGE, image.getId()));
	}

	@GetMapping("/api/directories/{directoryId}/share-token")
	public ShareLinkResponse sequenceShareToken(Authentication authentication, @PathVariable Long directoryId) {
		Directory directory = directoryRepository.findById(directoryId).orElseThrow(() -> new DirectoryNotFoundException(directoryId));
		return new ShareLinkResponse(tokenService.issueToken(ShareScope.SEQUENCE, directory.getId()));
	}

	@GetMapping("/api/public/images/{imageId}")
	public PublicImageResponse publicImage(@PathVariable Long imageId, @RequestParam String token) {
		Image image = requireAccessibleImage(imageId, token);
		return PublicImageResponse.from(image, token);
	}

	@GetMapping("/api/public/images/{imageId}/thumbnail")
	public ResponseEntity<InputStreamResource> publicThumbnail(@PathVariable Long imageId, @RequestParam String token) {
		Image image = requireAccessibleImage(imageId, token);
		var stream = storageService.readThumbnail(image.getDirectory().getPath(), image.getName());
		// Always JPEG regardless of the source format, same reasoning as
		// GalleryController.thumbnail's own doc.
		return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("Cache-Control", "private, max-age=3600").body(new InputStreamResource(stream));
	}

	@GetMapping("/api/public/images/{imageId}/web")
	public ResponseEntity<InputStreamResource> publicWeb(@PathVariable Long imageId, @RequestParam String token) {
		Image image = requireAccessibleImage(imageId, token);
		var stream = storageService.readWeb(image.getDirectory().getPath(), image.getName());
		MediaType contentType = MediaTypeFactory.getMediaType(image.getName()).orElse(MediaType.APPLICATION_OCTET_STREAM);
		return ResponseEntity.ok().contentType(contentType).header("Cache-Control", "private, max-age=3600").body(new InputStreamResource(stream));
	}

	/** A shared video, streamed with Range support - only while video sharing is allowed (see class doc). */
	@GetMapping("/api/public/images/{imageId}/stream")
	public ResponseEntity<ResourceRegion> publicStream(@PathVariable Long imageId, @RequestParam String token, @RequestHeader HttpHeaders headers) {
		Image image = requireAccessibleImage(imageId, token);
		if (image.getMediaType() != org.myphotodiary.cms.gallery.MediaType.VIDEO) {
			throw new IllegalArgumentException("Not a video: " + imageId);
		}
		return VideoRangeResponses.serve(storageService.resolveOriginal(image.getDirectory().getPath(), image.getName()), headers);
	}

	@GetMapping("/api/public/sequences/{directoryId}")
	public PublicSequenceResponse publicSequence(@PathVariable Long directoryId, @RequestParam String token) {
		ShareTokenClaims claims = tokenService.validateToken(token);
		if (claims.scope() != ShareScope.SEQUENCE || !claims.id().equals(directoryId)) {
			throw new InvalidShareTokenException("Share token does not match the requested sequence");
		}
		Directory directory = directoryRepository.findById(directoryId).orElseThrow(() -> new DirectoryNotFoundException(directoryId));
		boolean videos = appSettingsService.isVideoSharingAllowed();
		List<PublicImageResponse> images = imageRepository.findByDirectory_IdOrderByNameAsc(directoryId).stream()
				.filter(img -> videos || img.getMediaType() != org.myphotodiary.cms.gallery.MediaType.VIDEO) // see class javadoc
				.map(img -> PublicImageResponse.from(img, token))
				.toList();
		return new PublicSequenceResponse(directory.getId(), directory.getDescription(), images);
	}

	/**
	 * Validates the token against this exact image, either directly
	 * (IMAGE-scope token issued for this image specifically) or via its
	 * owning sequence (SEQUENCE-scope token issued for the directory this
	 * image belongs to - what lets a sequence-link's filmstrip fetch every
	 * image in it with the one token). Validating a token only proves the
	 * token is genuine and unexpired (ShareTokenService's own doc) - the
	 * directory-membership check here is what actually ties it to this
	 * specific image, the same double-check VideoController does against
	 * its own path variable.
	 */
	private Image requireAccessibleImage(Long imageId, String token) {
		ShareTokenClaims claims = tokenService.validateToken(token);
		Image image = galleryService.requireImage(imageId);
		boolean allowed = switch (claims.scope()) {
			case IMAGE -> claims.id().equals(image.getId());
			case SEQUENCE -> claims.id().equals(image.getDirectory().getId());
			case SEARCH -> sharedSearchService.contains(claims.id(), image.getId());
		};
		if (!allowed) {
			throw new InvalidShareTokenException("Share token does not grant access to this image");
		}
		if (image.getMediaType() == org.myphotodiary.cms.gallery.MediaType.VIDEO && !appSettingsService.isVideoSharingAllowed()) {
			throw new InvalidShareTokenException("Video sharing is turned off");
		}
		return image;
	}
}
