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

import java.time.LocalDate;
import java.util.List;

import org.myphotodiary.cms.gallery.dto.CreateDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.CropImageRequest;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.ImageSearchResponse;
import org.myphotodiary.cms.gallery.dto.TransformImageRequest;
import org.myphotodiary.cms.gallery.dto.UpdateImageRequest;
import org.myphotodiary.cms.user.User;
import org.myphotodiary.cms.user.UserRepository;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

/**
 * Replaces the legacy ImportSvr/ImageDataSvr/ImgListSvr/ThuListSvr
 * (Design.md §16.5/§16.9 step 2). Write actions (create directory, upload,
 * edit, delete) are authorized per-group by {@link GalleryAuthorizationService}
 * (Design.md §16.4, "role assigned by group") - found missing entirely
 * (28/08/2026: a LOWER-role account could upload). Read/browse endpoints
 * stay open to any authenticated user, matching legacy's own "browse is
 * unconditional" design - see that service's javadoc for the full reasoning.
 */
@RestController
public class GalleryController {

	private final GalleryService galleryService;
	private final ImageStorageService storageService;
	private final UserRepository userRepository;

	public GalleryController(GalleryService galleryService, ImageStorageService storageService, UserRepository userRepository) {
		this.galleryService = galleryService;
		this.storageService = storageService;
		this.userRepository = userRepository;
	}

	@GetMapping("/api/directories")
	public List<DirectoryResponse> listDirectories() {
		return galleryService.listDirectories();
	}

	/** Resolves a filesystem path (as returned by DirectoryController's tree) to its DB row - the bridge the frontend tree uses to go from "user clicked this node" to an actual directoryId. */
	@GetMapping("/api/directories/by-path")
	public DirectoryResponse getDirectoryByPath(Authentication authentication, @RequestParam String path) {
		return galleryService.getDirectoryByPath(authentication, path);
	}

	/**
	 * Replaces QuerySvr (Design.md §16.5) - `attribute` may repeat (all given
	 * names must match at once, own-or-directory-inherited per attribute -
	 * that composite tag match is now one alternative *inside* the same
	 * disjunction as `text`, not a separate hard filter; see
	 * GalleryService.search's own doc for the full, revised logic).
	 * `pageSize` defaults to the caller's own `maxQueryLength` preference
	 * (MeController/User.maxQueryLength, ported from the legacy
	 * UserConfiguration) rather than a fixed constant, so paging through
	 * results matches whatever page size that user has set for themselves -
	 * an explicit `pageSize` still overrides it. `text` (03/09/2026,
	 * revised same day) - ignored below 4 characters, see
	 * GalleryService.search's own doc.
	 */
	@GetMapping("/api/images/search")
	public ImageSearchResponse search(
			Authentication authentication,
			@RequestParam(required = false) List<String> attribute,
			@RequestParam(required = false) Integer minRating,
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate,
			@RequestParam(required = false) String text,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(required = false) Integer pageSize) {
		int effectivePageSize = pageSize != null ? pageSize : currentUserMaxQueryLength(authentication);
		if (effectivePageSize < 1) {
			throw new IllegalArgumentException("pageSize must be at least 1");
		}
		return galleryService.search(authentication, attribute, minRating, fromDate, toDate, text, page, effectivePageSize);
	}

	private int currentUserMaxQueryLength(Authentication authentication) {
		return userRepository.findById(authentication.getName()).map(User::getMaxQueryLength).orElse(10);
	}

	@PostMapping("/api/directories")
	@ResponseStatus(HttpStatus.CREATED)
	public DirectoryResponse createDirectory(Authentication authentication, @Valid @RequestBody CreateDirectoryRequest request) {
		return galleryService.createDirectory(authentication, request);
	}

	@GetMapping("/api/directories/{directoryId}/images")
	public List<ImageResponse> listImages(Authentication authentication, @PathVariable Long directoryId) {
		return galleryService.listImages(authentication, directoryId);
	}

	@PostMapping("/api/directories/{directoryId}/images")
	@ResponseStatus(HttpStatus.CREATED)
	public ImageResponse uploadImage(Authentication authentication, @PathVariable Long directoryId, @RequestParam("file") MultipartFile file) {
		return galleryService.uploadImage(authentication, directoryId, file);
	}

	@PatchMapping("/api/images/{imageId}")
	public ImageResponse updateImage(Authentication authentication, @PathVariable Long imageId, @RequestBody UpdateImageRequest request) {
		return galleryService.updateImage(authentication, imageId, request);
	}

	@DeleteMapping("/api/images/{imageId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteImage(Authentication authentication, @PathVariable Long imageId) {
		galleryService.deleteImage(authentication, imageId);
	}

	/**
	 * 90-degree clockwise rotation (02/09/2026, explicit ask) - a POST, not
	 * a PATCH on {@link #updateImage}: unlike a description/rating/tag
	 * edit, this has no request body and no persisted field to change (see
	 * GalleryService.rotateImage's own doc), it's an action against the
	 * files on disk, closer in shape to {@link #deleteImage} than to a
	 * field update. No response body either, for the same reason -
	 * {@code ImageResponse} has nothing that changed for the caller to see
	 * (no width/height/orientation tracked at all); the frontend
	 * cache-busts its own already-known thumbnail/web URLs to see the
	 * rotated bytes rather than needing a fresh one back.
	 */
	@PostMapping("/api/images/{imageId}/rotate")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void rotateImage(Authentication authentication, @PathVariable Long imageId,
			@RequestParam(defaultValue = "1") int quarterTurns) {
		galleryService.rotateImage(authentication, imageId, quarterTurns);
	}

	/**
	 * {@code quarterTurns} (1..3, default 1 - 26/09/2026): several consecutive
	 * "Rotate right" clicks in the editor popup are accumulated client-side
	 * and previewed/committed as one rotation of {@code 90 * quarterTurns}
	 * degrees - one re-encode on Save, not one per click.
	 *
	 * Read-only preview of a 90-degree rotation - the popup's own "Rotate
	 * right" button (GalleryService.previewRotate's own doc: nothing is
	 * written to disk here, "Save" is what actually commits, via {@link
	 * #rotateImage} above). Same shape as {@link #previewTransform} - a
	 * POST (no request body needed, but consistent with that sibling
	 * endpoint rather than a bare GET), never cached.
	 */
	@PostMapping("/api/images/{imageId}/rotate/preview")
	public ResponseEntity<byte[]> previewRotate(Authentication authentication, @PathVariable Long imageId,
			@RequestParam(defaultValue = "1") int quarterTurns) {
		Image image = galleryService.requireReadableImage(authentication, imageId);
		byte[] bytes = galleryService.previewRotate(authentication, imageId, quarterTurns);
		MediaType contentType = MediaTypeFactory.getMediaType(image.getName()).orElse(MediaType.APPLICATION_OCTET_STREAM);
		return ResponseEntity.ok().contentType(contentType).body(bytes);
	}

	/**
	 * Crop, in the true original's own pixel coordinates (explicit ask,
	 * 25/09/2026 - "Crop" half of a rudimentary image editor; see
	 * GalleryService.cropImage's own doc). Same shape as {@link #rotateImage}
	 * - a POST against the files on disk, no response body (the frontend
	 * cache-busts its own already-known thumbnail/web URLs, same mechanism
	 * already built for Rotate).
	 */
	@PostMapping("/api/images/{imageId}/crop")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cropImage(Authentication authentication, @PathVariable Long imageId, @RequestBody CropImageRequest request) {
		galleryService.cropImage(authentication, imageId, request);
	}

	/**
	 * Read-only preview of a crop - the popup's own "Crop" button
	 * (GalleryService.previewCrop's own doc: nothing is written to disk
	 * here, "Save" is what actually commits, via {@link #cropImage} above).
	 * Same shape as {@link #previewTransform}.
	 */
	@PostMapping("/api/images/{imageId}/crop/preview")
	public ResponseEntity<byte[]> previewCrop(Authentication authentication, @PathVariable Long imageId, @RequestBody CropImageRequest request) {
		Image image = galleryService.requireReadableImage(authentication, imageId);
		byte[] bytes = galleryService.previewCrop(authentication, imageId, request);
		MediaType contentType = MediaTypeFactory.getMediaType(image.getName()).orElse(MediaType.APPLICATION_OCTET_STREAM);
		return ResponseEntity.ok().contentType(contentType).body(bytes);
	}

	/**
	 * Read-only preview of a perspective-corrected warp - the popup's own
	 * "Transform" button (GalleryService.previewTransform's own doc:
	 * nothing is written to disk here, "Save" is what actually commits,
	 * see {@link #transformImage} below). A POST, not a GET: the
	 * destination rectangle and the 4 quad corners are the whole request,
	 * far too much to encode as query parameters - and unlike every GET
	 * this controller serves, the response genuinely differs on every call
	 * even for the same image id, so (unlike {@link #serve}) there's no
	 * {@code Cache-Control} header here at all, it must never be cached.
	 * {@code requireReadableImage} (not {@link GalleryService#requireImage
	 * requireImage}) purely to get the real image name for content-type
	 * inference - same pattern {@link #thumbnail}/{@link #web}/{@link
	 * #original} already use, the actual {@code EDIT_IMAGE} authorization
	 * for the warp itself happens inside {@code previewTransform}.
	 */
	@PostMapping("/api/images/{imageId}/transform/preview")
	public ResponseEntity<byte[]> previewTransform(Authentication authentication, @PathVariable Long imageId, @RequestBody TransformImageRequest request) {
		Image image = galleryService.requireReadableImage(authentication, imageId);
		byte[] bytes = galleryService.previewTransform(authentication, imageId, request);
		MediaType contentType = MediaTypeFactory.getMediaType(image.getName()).orElse(MediaType.APPLICATION_OCTET_STREAM);
		return ResponseEntity.ok().contentType(contentType).body(bytes);
	}

	/**
	 * Commits the exact warp {@link #previewTransform} would have
	 * rendered - the popup's own "Save" button (GalleryService.transformImage's
	 * own doc). Same no-response-body shape as {@link #cropImage}/{@link
	 * #rotateImage}.
	 */
	@PostMapping("/api/images/{imageId}/transform")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void transformImage(Authentication authentication, @PathVariable Long imageId, @RequestBody TransformImageRequest request) {
		galleryService.transformImage(authentication, imageId, request);
	}

	/**
	 * Video: always JPEG bytes regardless of the source Image's own media
	 * type (ImageStorageService.resolveVideoThumbnail's own comment explains
	 * why video has a distinct, guaranteed-`.jpg` on-disk thumbnail path) -
	 * can't reuse {@link #serve}'s filename-based content-type inference
	 * here, since for a video {@code image.getName()} is "clip.mp4", which
	 * would (wrongly) label these JPEG bytes as {@code video/mp4} and break
	 * `<img>` rendering in the filmstrip.
	 *
	 * Photo: routed through {@link #serve} (02/09/2026) rather than a second
	 * hardcoded {@code IMAGE_JPEG}, now that {@link ImageStorageService
	 * #readThumbnail} can fall back to the web/original tiers (own doc) -
	 * those aren't guaranteed to be JPEG the way a true thumbnail mirroring
	 * a `.jpg`/`.jpeg` original is, so the content type has to be inferred
	 * from the image's own real filename like every other non-video image
	 * endpoint already does, not assumed.
	 */
	@GetMapping("/api/images/{imageId}/thumbnail")
	public ResponseEntity<InputStreamResource> thumbnail(Authentication authentication, @PathVariable Long imageId) {
		Image image = galleryService.requireReadableImage(authentication, imageId);
		if (image.getMediaType() == org.myphotodiary.cms.gallery.MediaType.VIDEO) {
			var stream = storageService.readVideoThumbnail(image.getDirectory().getPath(), image.getName());
			long size = storageService.sizeOfVideoThumbnail(image.getDirectory().getPath(), image.getName());
			return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).contentLength(size).header("Cache-Control", "private, max-age=3600").body(new InputStreamResource(stream));
		}
		var stream = storageService.readThumbnail(image.getDirectory().getPath(), image.getName());
		long size = storageService.sizeOfThumbnail(image.getDirectory().getPath(), image.getName());
		return serve(image, stream, size, false);
	}

	/**
	 * Medium-resolution tier (legacy Configuration.webImgSize, 1600px) - what
	 * the main viewer displays (ImageResponse.webUrl), not `original`.
	 * Falls back to the full original if no web-size variant exists yet
	 * (images indexed before this tier existed - see
	 * ImageStorageService.readWeb), so this never 404s for an already-indexed
	 * photo.
	 */
	@GetMapping("/api/images/{imageId}/web")
	public ResponseEntity<InputStreamResource> web(Authentication authentication, @PathVariable Long imageId) {
		Image image = galleryService.requireReadableImage(authentication, imageId);
		var stream = storageService.readWeb(image.getDirectory().getPath(), image.getName());
		long size = storageService.sizeOfWeb(image.getDirectory().getPath(), image.getName());
		return serve(image, stream, size, false);
	}

	@GetMapping("/api/images/{imageId}/original")
	public ResponseEntity<InputStreamResource> original(Authentication authentication, @PathVariable Long imageId) {
		Image image = galleryService.requireReadableImage(authentication, imageId);
		var stream = storageService.readOriginal(image.getDirectory().getPath(), image.getName());
		long size = storageService.sizeOfOriginal(image.getDirectory().getPath(), image.getName());
		return serve(image, stream, size, false);
	}

	/** Same bytes as {@link #original}, but with `Content-Disposition: attachment` - legacy's ExportSvr ("Download this picture"), a distinct endpoint from the inline viewer rather than a query flag on it, matching legacy's own separate servlet. */
	@GetMapping("/api/images/{imageId}/export")
	public ResponseEntity<InputStreamResource> export(Authentication authentication, @PathVariable Long imageId) {
		Image image = galleryService.requireReadableImage(authentication, imageId);
		var stream = storageService.readOriginal(image.getDirectory().getPath(), image.getName());
		long size = storageService.sizeOfOriginal(image.getDirectory().getPath(), image.getName());
		return serve(image, stream, size, true);
	}

	/**
	 * `contentLength` (10/09/2026, progressive image loading) - explicit,
	 * not left for Spring to infer from the {@code InputStreamResource}: an
	 * arbitrary {@code InputStream} has no known length to a generic message
	 * converter, so without this every one of these responses went out
	 * chunked, with no `Content-Length` header at all - the frontend's own
	 * progress bar (ProgressiveImage.jsx) needs that header to compute a real
	 * percentage rather than an indeterminate "still loading" state.
	 */
	private ResponseEntity<InputStreamResource> serve(Image image, java.io.InputStream stream, long contentLength, boolean attachment) {
		MediaType contentType = MediaTypeFactory.getMediaType(image.getName()).orElse(MediaType.APPLICATION_OCTET_STREAM);
		var builder = ResponseEntity.ok().contentType(contentType).contentLength(contentLength);
		if (attachment) {
			builder = builder.header("Content-Disposition", "attachment; filename=\"" + image.getName() + "\"");
		} else {
			// Lets the browser's HTTP cache actually do something with
			// ImageViewer's next-image preload (fetch() responses are only
			// cached with an explicit validator/freshness signal, unlike a
			// plain <img src> the legacy preload relied on) - image bytes for
			// a given id never change in place (an edit replaces the row, not
			// the file), so a private, moderately long cache is safe.
			builder = builder.header("Cache-Control", "private, max-age=3600");
		}
		return builder.body(new InputStreamResource(stream));
	}
}
