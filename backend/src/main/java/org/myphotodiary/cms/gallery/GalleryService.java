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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.myphotodiary.cms.gallery.dto.CreateDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.CropImageRequest;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.ImageSearchResponse;
import org.myphotodiary.cms.gallery.dto.TransformImageRequest;
import org.myphotodiary.cms.gallery.dto.UpdateImageRequest;
import org.myphotodiary.cms.user.Action;
import org.myphotodiary.cms.user.Group;
import org.myphotodiary.cms.user.GroupRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Directory/image CRUD + upload - the new-stack equivalent of the legacy
 * ImportSvr/ImageDataSvr/ImgListSvr/ThuListSvr (Design.md §16.5/§16.9 step 2).
 *
 * Directory *browsing* (the fancytree-backed tree, SubDirListSvr) is
 * out of scope here - Design.md §16.9 orders it as its own screen (step 3,
 * "Navigation"), depending on this one. In the meantime {@link #listDirectories}
 * and {@link #createDirectory} stand in as a plain, temporary picker.
 */
@Service
public class GalleryService {

	private static final String DEFAULT_GROUP = "public";

	private final DirectoryRepository directoryRepository;
	private final ImageRepository imageRepository;
	private final AttributeRepository attributeRepository;
	private final GroupRepository groupRepository;
	private final ImageStorageService storageService;
	private final ExifDateReader exifDateReader;
	private final ExifGpsReader exifGpsReader;
	private final GalleryAuthorizationService authorizationService;

	public GalleryService(DirectoryRepository directoryRepository, ImageRepository imageRepository,
			AttributeRepository attributeRepository, GroupRepository groupRepository, ImageStorageService storageService,
			ExifDateReader exifDateReader, ExifGpsReader exifGpsReader, GalleryAuthorizationService authorizationService) {
		this.directoryRepository = directoryRepository;
		this.imageRepository = imageRepository;
		this.attributeRepository = attributeRepository;
		this.groupRepository = groupRepository;
		this.storageService = storageService;
		this.exifDateReader = exifDateReader;
		this.exifGpsReader = exifGpsReader;
		this.authorizationService = authorizationService;
	}

	@Transactional(readOnly = true)
	public List<DirectoryResponse> listDirectories() {
		return directoryRepository.findAll().stream().map(DirectoryResponse::from).toList();
	}

	/**
	 * The bridge the frontend tree uses to go from "user clicked this node"
	 * to an actual directory row - {@link Action#BROWSE}-gated (12/09/2026,
	 * explicit ask), same reasoning as {@code DirectoryIndexerService
	 * .getDirectory}'s own doc: this is what a tree click actually resolves
	 * to before showing the sequence's own detail/images, so leaving *this*
	 * open while gating {@link #listImages} would have made the restriction
	 * pointless in the one flow (ordinary tree navigation) that matters most
	 * - a user would still see the description/group/attributes of a
	 * sequence they can't otherwise browse. The tree *listing* endpoint
	 * itself ({@code DirectoryController.listSubdirectories}) is unaffected
	 * and stays fully open, per the same explicit ask.
	 */
	@Transactional(readOnly = true)
	public DirectoryResponse getDirectoryByPath(Authentication authentication, String path) {
		Directory directory = directoryRepository.findByPath(path).orElseThrow(() -> new DirectoryNotFoundException(path));
		authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.BROWSE);
		return DirectoryResponse.from(directory);
	}

	@Transactional
	public DirectoryResponse createDirectory(Authentication authentication, CreateDirectoryRequest request) {
		if (directoryRepository.findByPath(request.path()).isPresent()) {
			throw new DirectoryAlreadyExistsException(request.path());
		}
		String groupName = blankToNull(request.groupName()) == null ? DEFAULT_GROUP : blankToNull(request.groupName());
		// Checked against the target group *before* creating it - a brand
		// new sequence is CREATE_SEQUENCE, same as legacy's ImportSvr
		// checking the not-yet-existing directory's future group.
		authorizationService.checkAuthorization(authentication, groupName, Action.CREATE_SEQUENCE);
		// Real folder on disk too, not just the DB row below (explicit ask,
		// 28/08/2026 - see ImageStorageService.createDirectory's own
		// comment for the bug this closes: the navigation tree is
		// filesystem-driven, so a DB-only directory was never visible there).
		storageService.createDirectory(request.path());
		Directory directory = new Directory(request.path(), findOrCreateGroup(groupName), LocalDate.now());
		return DirectoryResponse.from(directoryRepository.save(directory));
	}

	/** {@link Action#BROWSE}-gated (12/09/2026, explicit ask) - see GalleryAuthorizationService's own doc for the full reasoning and why tree navigation itself stays unaffected. */
	@Transactional(readOnly = true)
	public List<ImageResponse> listImages(Authentication authentication, Long directoryId) {
		Directory directory = requireDirectory(directoryId);
		authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.BROWSE);
		return imageRepository.findByDirectory_IdOrderByNameAsc(directoryId).stream().map(ImageResponse::from).toList();
	}

	@Transactional
	public ImageResponse uploadImage(Authentication authentication, Long directoryId, MultipartFile file) {
		Directory directory = requireDirectory(directoryId);
		// Adding an image to an *existing* directory is EDIT_SEQUENCE (same
		// as legacy's ImportSvr checking editSequence when the target
		// directory already exists) - CREATE_SEQUENCE is only for the
		// directory itself not existing yet (createDirectory above).
		authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.EDIT_SEQUENCE);
		String name = file.getOriginalFilename();
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Uploaded file has no name");
		}
		if (imageRepository.existsByDirectory_IdAndName(directoryId, name)) {
			throw new ImageAlreadyExistsException(name, directoryId);
		}
		// Stored before the row is created (unlike the general rule
		// elsewhere in this class) because the capture date needs EXIF read
		// from the file once it's actually on disk - see ExifDateReader. The
		// duplicate-name check just above still runs first, so this doesn't
		// introduce a new way to leave an orphaned file behind beyond the
		// same check-then-write race every uploader here already accepts.
		storageService.store(directory.getPath(), name, file);
		var storedFile = storageService.resolveOriginal(directory.getPath(), name);
		LocalDateTime captureDate = exifDateReader.readCaptureDate(storedFile, name, null);
		if (captureDate == null) {
			captureDate = LocalDateTime.now(); // matches ImportService's own final fallback
		}
		Image image = new Image(name, directory, captureDate);
		ExifGpsReader.Coordinates gps = exifGpsReader.readGpsCoordinates(storedFile);
		if (gps != null) {
			image.setLatitude(gps.latitude());
			image.setLongitude(gps.longitude());
		}
		image = imageRepository.save(image);
		return ImageResponse.from(image);
	}

	@Transactional
	public ImageResponse updateImage(Authentication authentication, Long imageId, UpdateImageRequest request) {
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.EDIT_IMAGE);
		if (request.description() != null) {
			image.setDescription(request.description());
		}
		if (request.rating() != null) {
			if (request.rating() < Image.NOT_RATED || request.rating() > Image.VERY_GOOD) {
				throw new IllegalArgumentException("Rating out of range: " + request.rating());
			}
			image.setRating(request.rating());
		}
		if (request.attributeNames() != null) {
			Set<Attribute> resolved = new LinkedHashSet<>();
			for (String name : request.attributeNames()) {
				Attribute attribute = findOrCreateAttribute(name);
				addWithAncestors(resolved, attribute);
			}
			image.getAttributes().clear();
			image.getAttributes().addAll(resolved);
		}
		return ImageResponse.from(image);
	}

	/**
	 * Find-or-create, not find-or-throw (03/09/2026, explicit ask - restores
	 * a legacy capability: `idx.js`'s `imageEditPopup`/`dirEditPopup` both
	 * had a plain text input beside their attribute `<select>`
	 * (`#newImgParam`/`#newDirParam`) that created a brand-new, root-level
	 * tag and assigned it in the same save, not a separate admin action
	 * first). A name that already exists is reused as-is (case-sensitive
	 * exact match, same as the `<select>`'s own values) - only a genuinely
	 * new name creates a row. Deliberately *not* routed through
	 * `AttributeService.create` (`POST /api/attributes`, `ADMIN`-only,
	 * `AttributeController`) - that endpoint is for the dedicated tag-
	 * management screen; this one inherits whatever authorization the
	 * caller already passed to reach this method at all (`EDIT_IMAGE`/
	 * `EDIT_SEQUENCE`, `WRITER` included) rather than gating tag creation
	 * itself behind `ADMIN` - the same trade-off legacy made (any user who
	 * could edit an image/sequence could always mint a new tag this way,
	 * not just admins). A deliberate, narrower door than the admin screen's
	 * own, not a bypass of it.
	 */
	private Attribute findOrCreateAttribute(String name) {
		return attributeRepository.findByName(name).orElseGet(() -> attributeRepository.save(new Attribute(name, null)));
	}

	/**
	 * Replaces QuerySvr (Design.md §16.5). Full logic (revised 03/09/2026,
	 * explicit correction to the same-day original): {@code minDate AND
	 * maxDate AND minRating AND (tag match OR sequence-name match OR
	 * sequence-description match OR image-description match)} - date range
	 * and minimum rating are still a hard conjunction with everything else,
	 * but tag selection moved *into* the disjunction alongside the three
	 * free-text fields, rather than being its own separate hard filter
	 * upstream of them. An image with none of the given tags can therefore
	 * still match via `text` alone, and vice versa - a real behavior change
	 * from the original version of this method, not just a refactor.
	 *
	 * Tag matching itself keeps its existing all-of-the-given-names
	 * semantics (own or inherited from the directory - see
	 * ImageRepository), computed once up front into a `Set<Image>` via the
	 * same repository queries/intersection as before - just consulted
	 * per-image inside the disjunction now, rather than used to narrow the
	 * candidate list before filtering even starts (it can't be anymore: an
	 * image lacking every given tag might still belong in the result via a
	 * text match, so the full `findAll()` is always the starting point once
	 * `text` is capable of participating in the disjunction).
	 *
	 * `text` (03/09/2026, explicit ask - "without changing the DB schema")
	 * matches case-insensitively as a substring against the owning
	 * sequence's own name (its path's last segment, the same "name" the
	 * navigation tree shows - `DirectoryIndexerService.listSubdirectories`,
	 * not stored as its own column so derived here instead via
	 * {@link #sequenceName}), the sequence's own description, or the
	 * image's own description. **Ignored below a 4-character minimum**
	 * (explicit ask, same-day follow-up) - `textApplicable` below, not a
	 * separate empty-string check: a 1-3 character fragment is excluded
	 * from the disjunction entirely (as if `text` had never been given),
	 * not treated as "match nothing" - the disjunction as a whole still
	 * degrades to "no constraint" when neither tags nor a long-enough
	 * `text` are given, exactly like every other optional criterion here
	 * (`minRating`/`fromDate`/`toDate`) already does when omitted.
	 *
	 * Paginated (legacy QuerySvr just did `setMaxResults(maxQueryLength)` -
	 * a hard cap with no way to see the rest) - `pageSize` is normally the
	 * caller's own {@code User.maxQueryLength} (GalleryController resolves
	 * it), so page size follows the same per-user preference the legacy
	 * config screen exposed, not a fixed constant.
	 *
	 * The full match set is computed before slicing - fine at this app's
	 * personal-gallery scale, but would need a real DB-level paginated query
	 * if the corpus ever got large enough for that to matter.
	 *
	 * {@link Action#BROWSE}-filtered (12/09/2026, explicit ask) - unlike
	 * {@link #listImages}/{@link #requireReadableImage}, a search spans
	 * arbitrary directories/groups at once, so an inaccessible match is
	 * silently *skipped* here rather than the whole request being rejected -
	 * exactly legacy's own QuerySvr behavior (its per-image loop already did
	 * the same "checkAuthorization, catch, skip" rather than aborting).
	 * {@link GalleryAuthorizationService#readableGroupNames} resolves the
	 * caller's own set once (or {@code null} for a global admin, meaning "no
	 * filtering needed"), rather than one authorization check per candidate
	 * image.
	 */
	@Transactional(readOnly = true)
	public ImageSearchResponse search(Authentication authentication, List<String> attributeNames, Integer minRating, LocalDate fromDate, LocalDate toDate,
			String text, int page, int pageSize) {
		List<Image> filtered = searchMatches(authentication, attributeNames, minRating, fromDate, toDate, text);
		int from = Math.min(page * pageSize, filtered.size());
		int to = Math.min(from + pageSize, filtered.size());
		List<ImageResponse> pageItems = filtered.subList(from, to).stream().map(ImageResponse::from).toList();

		return new ImageSearchResponse(pageItems, page, pageSize, filtered.size(), to < filtered.size());
	}

	/**
	 * Whether these search criteria constrain anything at all - the same rule
	 * the frontend applies before searching (searchCriteria.js): at least one
	 * tag, a minimum rating, a date bound, or a free text of 4+ characters.
	 */
	public static boolean hasSearchCriteria(List<String> attributeNames, Integer minRating, LocalDate fromDate, LocalDate toDate, String text) {
		boolean hasTags = attributeNames != null && !attributeNames.isEmpty();
		boolean hasText = text != null && text.trim().length() >= MIN_TEXT_SEARCH_LENGTH;
		return hasTags || hasText || minRating != null || fromDate != null || toDate != null;
	}

	/**
	 * Every image matching the criteria, readable by the caller, in search
	 * order (newest first) - the full list {@link #search} pages through, and
	 * what a shared search result freezes (SharedSearchService, 04/10/2026),
	 * so both always agree on what "this search's result" is.
	 */
	@Transactional(readOnly = true)
	public List<Image> searchMatches(Authentication authentication, List<String> attributeNames, Integer minRating, LocalDate fromDate, LocalDate toDate,
			String text) {
		Set<String> readableGroups = authorizationService.readableGroupNames(authentication);
		boolean hasTags = attributeNames != null && !attributeNames.isEmpty();
		Set<Image> tagMatchedImages = null;
		if (hasTags) {
			List<Image> matched = null;
			for (String name : attributeNames) {
				Set<Image> matches = new LinkedHashSet<>(imageRepository.findByOwnAttributeName(name));
				matches.addAll(imageRepository.findByDirectoryAttributeName(name));
				matched = (matched == null) ? new ArrayList<>(matches) : intersect(matched, matches);
			}
			tagMatchedImages = new HashSet<>(matched);
		}

		String needle = (text == null || text.isBlank()) ? null : text.trim().toLowerCase();
		boolean textApplicable = needle != null && needle.length() >= MIN_TEXT_SEARCH_LENGTH;
		boolean hasOrCriteria = hasTags || textApplicable;
		Set<Image> finalTagMatchedImages = tagMatchedImages;

		// Date-range filter (legacy QuerySvr's since/till, queryEditPopup's
		// #fromDate/#toDate) - an image with no capture date can't be known
		// to fall inside a given range, so it's excluded whenever either
		// bound is set, same as it would silently never match a JP-QL
		// BETWEEN clause on a null column.
		List<Image> filtered = imageRepository.findAll().stream()
				.filter(img -> readableGroups == null || readableGroups.contains(img.getDirectory().getGroup().getGroupName()))
				.filter(img -> minRating == null || img.getRating() >= minRating)
				.filter(img -> fromDate == null || (img.getCaptureDate() != null && !img.getCaptureDate().toLocalDate().isBefore(fromDate)))
				.filter(img -> toDate == null || (img.getCaptureDate() != null && !img.getCaptureDate().toLocalDate().isAfter(toDate)))
				.filter(img -> !hasOrCriteria
						|| (hasTags && finalTagMatchedImages.contains(img))
						|| (textApplicable && matchesText(img, needle)))
				// Most recent first - a reasonable default the legacy JP-QL
				// queries didn't actually specify an order for.
				.sorted(Comparator.comparing(Image::getCaptureDate, Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(Image::getId))
				.toList();
		return filtered;
	}

	/** Below this, `text` is excluded from the search disjunction entirely (explicit ask, 03/09/2026) - see {@code search}'s own doc for why that's "no constraint", not "match nothing". */
	private static final int MIN_TEXT_SEARCH_LENGTH = 4;

	private static boolean matchesText(Image img, String needle) {
		Directory directory = img.getDirectory();
		if (sequenceName(directory.getPath()).toLowerCase().contains(needle)) {
			return true;
		}
		String sequenceDescription = directory.getDescription();
		if (sequenceDescription != null && sequenceDescription.toLowerCase().contains(needle)) {
			return true;
		}
		String imageDescription = img.getDescription();
		return imageDescription != null && imageDescription.toLowerCase().contains(needle);
	}

	/** The path's own last segment - what the navigation tree actually shows as a sequence's "name" (DirectoryIndexerService.listSubdirectories' own `name`), not stored as a column of its own. */
	private static String sequenceName(String path) {
		if (path == null || path.isEmpty()) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

	private static List<Image> intersect(List<Image> a, Set<Image> b) {
		return a.stream().filter(b::contains).toList();
	}

	// Selecting a child attribute implies its ancestors too - same
	// inheritance rule as the legacy DirDataSvr.addParent, applied here at
	// image level too (§9 point 3 extends tagging to images, not just directories).
	private void addWithAncestors(Set<Attribute> accumulator, Attribute attribute) {
		if (accumulator.add(attribute) && attribute.getParent() != null) {
			addWithAncestors(accumulator, attribute.getParent());
		}
	}

	/**
	 * Rotates a photo 90 degrees clockwise, on disk, across all three
	 * derivatives (02/09/2026, explicit ask - see {@link ImageStorageService
	 * #rotateImage}'s own doc for why there's no DB row to update
	 * afterward). {@code EDIT_IMAGE}, the same action already gating
	 * {@link #updateImage} - a rotation is an edit to the image's own
	 * content, not a new action needing its own place in the {@code Action}
	 * matrix. Rejects a video outright (Rotate is a photo-only control -
	 * rotating a video's own frames would mean re-encoding the whole file,
	 * a transcoding operation this project has deliberately never done for
	 * video, Design.md §8's "no transcoding" video policy) - the frontend
	 * already never shows the Rotate button for a video item, this is the
	 * server-side backstop.
	 */
	@Transactional
	public void rotateImage(Authentication authentication, Long imageId) {
		rotateImage(authentication, imageId, 1);
	}

	/**
	 * {@link #rotateImage(Authentication, Long)} by {@code 90 * quarterTurns}
	 * degrees clockwise in a single re-encode (26/09/2026 - the editor popup
	 * accumulates consecutive "Rotate right" clicks, see ImageEditorPopup.jsx).
	 * {@code quarterTurns} must be 1..3 - 0/4 would be a no-op the frontend
	 * never sends (it simply has nothing to save).
	 */
	@Transactional
	public void rotateImage(Authentication authentication, Long imageId, int quarterTurns) {
		requireValidQuarterTurns(quarterTurns);
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.EDIT_IMAGE);
		if (image.getMediaType() == MediaType.VIDEO) {
			throw new IllegalArgumentException("Cannot rotate a video");
		}
		storageService.rotateImage(image.getDirectory().getPath(), image.getName(), quarterTurns);
	}

	/**
	 * Read-only preview of a 90-degree rotation - the popup's own "Rotate
	 * right" button calls this now instead of committing immediately
	 * (25/09/2026, explicit ask: Crop/Rotate brought onto the same preview/
	 * Save/Cancel workflow {@link #previewTransform} already has). Same
	 * {@code readOnly = true}/{@code EDIT_IMAGE}/video-rejection shape as
	 * {@link #previewTransform} - see that method's own doc.
	 */
	@Transactional(readOnly = true)
	public byte[] previewRotate(Authentication authentication, Long imageId) {
		return previewRotate(authentication, imageId, 1);
	}

	/** Preview counterpart of {@link #rotateImage(Authentication, Long, int)}. */
	@Transactional(readOnly = true)
	public byte[] previewRotate(Authentication authentication, Long imageId, int quarterTurns) {
		requireValidQuarterTurns(quarterTurns);
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.EDIT_IMAGE);
		if (image.getMediaType() == MediaType.VIDEO) {
			throw new IllegalArgumentException("Cannot rotate a video");
		}
		return storageService.previewRotate(image.getDirectory().getPath(), image.getName(), quarterTurns);
	}

	private static void requireValidQuarterTurns(int quarterTurns) {
		if (quarterTurns < 1 || quarterTurns > 3) {
			throw new IllegalArgumentException("quarterTurns must be between 1 and 3, got " + quarterTurns);
		}
	}

	/**
	 * Crops a photo to {@code request}'s rectangle, in the true original's
	 * own pixel coordinates (explicit ask, 25/09/2026 - "Crop" half of a
	 * rudimentary image editor; see {@link ImageStorageService#cropImage}'s
	 * own doc for why the web/thumbnail tiers are re-derived rather than
	 * separately cropped). Same {@code EDIT_IMAGE}/video-rejection shape as
	 * {@link #rotateImage} - a crop is an edit to the image's own content,
	 * not a new action of its own in the {@code Action} matrix, and
	 * (Design.md §8's "no transcoding" video policy) this project has never
	 * done pixel-level editing of video frames.
	 */
	@Transactional
	public void cropImage(Authentication authentication, Long imageId, CropImageRequest request) {
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.EDIT_IMAGE);
		if (image.getMediaType() == MediaType.VIDEO) {
			throw new IllegalArgumentException("Cannot crop a video");
		}
		storageService.cropImage(image.getDirectory().getPath(), image.getName(), request.x(), request.y(), request.width(), request.height());
	}

	/**
	 * Read-only preview of a crop - the popup's own "Crop" button calls
	 * this now instead of committing immediately (25/09/2026, explicit ask:
	 * same preview/Save/Cancel workflow as {@link #previewRotate}/{@link
	 * #previewTransform}). Same {@code readOnly = true}/{@code EDIT_IMAGE}/
	 * video-rejection shape as those two - see {@link #previewTransform}'s
	 * own doc.
	 */
	@Transactional(readOnly = true)
	public byte[] previewCrop(Authentication authentication, Long imageId, CropImageRequest request) {
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.EDIT_IMAGE);
		if (image.getMediaType() == MediaType.VIDEO) {
			throw new IllegalArgumentException("Cannot crop a video");
		}
		return storageService.previewCrop(image.getDirectory().getPath(), image.getName(), request.x(), request.y(), request.width(), request.height());
	}

	/**
	 * Read-only preview of the "Transform" perspective warp - the given
	 * quad mapped onto a {@code request.destWidth}x{@code destHeight}
	 * rectangle - returned as raw bytes, <b>nothing is written to disk or
	 * to the database</b> (explicit ask, 25/09/2026: "Save" is what
	 * actually commits, see {@link #transformImage} below; this is what
	 * the popup's own "Transform" button calls to let the user see the
	 * real result before deciding). {@code readOnly = true} is accurate
	 * here, not just copied from a neighboring method - unlike every other
	 * write action in this class, this one genuinely never mutates
	 * anything.
	 */
	@Transactional(readOnly = true)
	public byte[] previewTransform(Authentication authentication, Long imageId, TransformImageRequest request) {
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.EDIT_IMAGE);
		if (image.getMediaType() == MediaType.VIDEO) {
			throw new IllegalArgumentException("Cannot transform a video");
		}
		return storageService.previewTransform(image.getDirectory().getPath(), image.getName(), request.destWidth(), request.destHeight(), request.quadX(), request.quadY());
	}

	/**
	 * Commits the exact same warp {@link #previewTransform} would have
	 * rendered - called by the popup's own "Save" button, never by
	 * "Transform" itself (ImageEditorPopup.jsx's own doc on the preview/
	 * Save workflow). Same {@code EDIT_IMAGE}/video-rejection shape as
	 * {@link #cropImage}/{@link #rotateImage} - a perspective correction is
	 * an edit to the image's own content too, not a new action of its own
	 * in the {@code Action} matrix.
	 */
	@Transactional
	public void transformImage(Authentication authentication, Long imageId, TransformImageRequest request) {
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.EDIT_IMAGE);
		if (image.getMediaType() == MediaType.VIDEO) {
			throw new IllegalArgumentException("Cannot transform a video");
		}
		storageService.transformImage(image.getDirectory().getPath(), image.getName(), request.destWidth(), request.destHeight(), request.quadX(), request.quadY());
	}

	@Transactional
	public void deleteImage(Authentication authentication, Long imageId) {
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.DELETE_IMAGE);
		storageService.delete(image.getDirectory().getPath(), image.getName());
		imageRepository.delete(image);
	}

	/**
	 * Plain lookup, no authorization check - kept as-is (not folded into
	 * {@link #requireReadableImage} below) because it also backs the
	 * genuinely unauthenticated public share/video-token flows
	 * ({@code ShareController}, {@code VideoController}), which validate
	 * their own signed tokens instead of a normal {@code Authentication}.
	 */
	@Transactional(readOnly = true)
	public Image requireImage(Long imageId) {
		return imageRepository.findById(imageId).orElseThrow(() -> new ImageNotFoundException(imageId));
	}

	/** Same lookup, plus the {@link Action#BROWSE} check (12/09/2026, explicit ask) - what every *authenticated* image-bytes endpoint (thumbnail/web/original/export) should use instead of the plain {@link #requireImage} above. */
	@Transactional(readOnly = true)
	public Image requireReadableImage(Authentication authentication, Long imageId) {
		Image image = requireImage(imageId);
		authorizationService.checkAuthorization(authentication, image.getDirectory().getGroup(), Action.BROWSE);
		return image;
	}

	private Directory requireDirectory(Long directoryId) {
		return directoryRepository.findById(directoryId).orElseThrow(() -> new DirectoryNotFoundException(directoryId));
	}

	private Group findOrCreateGroup(String groupName) {
		String name = groupName == null ? DEFAULT_GROUP : groupName;
		return groupRepository.findById(name).orElseGet(() -> groupRepository.save(new Group(name, "", LocalDate.now())));
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}
}
