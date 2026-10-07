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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.myphotodiary.cms.gallery.dto.BatchIndexResult;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.DirectoryTreeNode;
import org.myphotodiary.cms.gallery.dto.RenameDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.UpdateDirectoryDetailRequest;
import org.myphotodiary.cms.user.Action;
import org.myphotodiary.cms.user.Group;
import org.myphotodiary.cms.user.GroupRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Filesystem-driven directory tree, indexing, rename, and metadata - the
 * new-stack equivalent of the legacy SubDirListSvr, DirIndexSvr, DirDataSvr,
 * and the filesystem-scanning core of DirectoryIndexer (Design.md §16.5/§16.7/§16.9
 * point 3).
 *
 * The browsing tree is filesystem-driven, not DB-driven, on purpose - same
 * as the legacy SubDirListSvr: it scans disk, not the `directory` table, so
 * a folder dropped directly on the server (Design.md decision #6/#7) shows
 * up in navigation immediately, before anyone has indexed it.
 *
 * EXIF-based capture dates (ExifDateReader) and path-pattern date inference
 * (PathDateGuesser) are now ported faithfully rather than approximated - see
 * their own javadoc for why. Bulk index/reset/delete and recursive
 * "batch publish" (idxAdmin.js's dirIndexPane/batchPublish, admin.jsp) are
 * ported too (Design.md §15, legacy feature-parity audit, 27/08/2026) - see
 * {@link #batchIndex} and {@link #indexRecursively}.
 */
@Service
public class DirectoryIndexerService {

	private static final String DEFAULT_GROUP = "public";

	private final DirectoryRepository directoryRepository;
	private final ImageRepository imageRepository;
	private final AttributeRepository attributeRepository;
	private final GroupRepository groupRepository;
	private final ImageStorageService storageService;
	private final ExifDateReader exifDateReader;
	private final ExifGpsReader exifGpsReader;
	private final GalleryAuthorizationService authorizationService;

	public DirectoryIndexerService(DirectoryRepository directoryRepository, ImageRepository imageRepository,
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
	public List<DirectoryTreeNode> listSubdirectories(String path) {
		String normalized = normalize(path);
		return storageService.listSubdirectoryNames(normalized).stream()
				.map(name -> {
					String childPath = normalized.isEmpty() ? name : normalized + "/" + name;
					int imageCount = storageService.listImageFileNames(childPath).size();
					boolean indexed = directoryRepository.findByPath(childPath).map(Directory::isIndexingAllowed).orElse(false);
					return new DirectoryTreeNode(name, childPath, imageCount, indexed);
				})
				.toList();
	}

	/**
	 * Every directory under {@code path} (default: storage root), flattened -
	 * unlike {@link #listSubdirectories}, which only returns one level for
	 * the lazily-loaded navigation tree. Powers the Admin screen's bulk
	 * index-management table (legacy's {@code dirIndexPane}, `idxAdmin.js`),
	 * which needs to see and act on every sequence at once, not level by
	 * level.
	 */
	@Transactional(readOnly = true)
	public List<DirectoryTreeNode> listAllSubdirectoriesRecursive(String path) {
		List<DirectoryTreeNode> flat = new ArrayList<>();
		collectRecursive(normalize(path), flat);
		return flat;
	}

	private void collectRecursive(String path, List<DirectoryTreeNode> accumulator) {
		for (DirectoryTreeNode node : listSubdirectories(path)) {
			accumulator.add(node);
			collectRecursive(node.path(), accumulator);
		}
	}

	/**
	 * Applies the same index/reset-index/delete commands the single-directory
	 * endpoints already expose, to several directories in one call - legacy's
	 * {@code dirIndexPane} checkbox table (`idxAdmin.js`), which lets the
	 * admin act on many sequences at once instead of one popup at a time.
	 * Best-effort: one directory failing (e.g. already deleted from under it)
	 * doesn't abort the rest, matching the independent per-checkbox
	 * semantics of the legacy table.
	 */
	@Transactional
	public BatchIndexResult batchIndex(Authentication authentication, String cmd, List<String> paths) {
		List<String> succeeded = new ArrayList<>();
		List<BatchIndexResult.BatchFailure> failed = new ArrayList<>();
		for (String path : paths) {
			try {
				switch (cmd) {
					// "index" stays unauthenticated-role-agnostic (browse-
					// equivalent, see indexDirectory's own javadoc) -
					// "reset-index"/"delete" are DELETE_SEQUENCE, ADMIN-only
					// per the role matrix.
					case "index" -> indexDirectory(path, null);
					case "reset-index" -> resetDirectoryIndex(authentication, path);
					case "delete" -> deleteDirectoryIndexAndFiles(authentication, path);
					default -> throw new IllegalArgumentException("Unknown batch command: " + cmd);
				}
				succeeded.add(path);
			} catch (RuntimeException e) {
				failed.add(new BatchIndexResult.BatchFailure(path, e.getMessage()));
			}
		}
		return new BatchIndexResult(succeeded, failed);
	}

	/**
	 * Recursively indexes {@code rootPath} and every directory under it that
	 * has images - legacy's server-side "Batch Publish"
	 * (`dirIndexPane.batchPublish`, distinct from the client-side browser
	 * upload/import flow): scans a path already present on the server's
	 * filesystem instead of receiving files over HTTP.
	 */
	@Transactional
	public BatchIndexResult indexRecursively(Authentication authentication, String rootPath) {
		String normalized = normalize(rootPath);
		List<String> targets = new ArrayList<>();
		if (!storageService.listImageFileNames(normalized).isEmpty()) {
			targets.add(normalized);
		}
		for (DirectoryTreeNode node : listAllSubdirectoriesRecursive(normalized)) {
			if (node.imageCount() > 0) {
				targets.add(node.path());
			}
		}
		// Only ever runs the "index" branch of batchIndex (browse-equivalent,
		// no authorization check along this path) - authentication is
		// threaded through anyway for signature consistency with the other
		// caller (DirectoryController.batchIndex), which does need it.
		return batchIndex(authentication, "index", targets);
	}

	/**
	 * Scans a directory's files on disk and syncs the DB index to match -
	 * the same "index reflects disk truth" contract as the legacy
	 * indexDirectory: adds rows (+ thumbnails) for new files, removes rows
	 * for files no longer present, and - matching legacy exactly, not just
	 * approximating it - re-reads EXIF and refreshes the stored capture date
	 * for *every* file each time this runs, not only newly discovered ones
	 * (legacy's merge-diff loop always calls getImgExifData for files it
	 * finds unchanged too). The directory's own {@code sequenceDate} is then
	 * updated from the latest image date found (DirectoryDateRules).
	 */
	@Transactional
	public DirectoryResponse indexDirectory(String path, String groupName) {
		String normalized = normalize(path);
		List<String> onDisk = storageService.listImageFileNames(normalized);
		if (onDisk.isEmpty()) {
			throw new IllegalStateException("Directory has no images to index: " + normalized);
		}

		Directory directory = directoryRepository.findByPath(normalized)
				.orElseGet(() -> directoryRepository.save(new Directory(normalized, findOrCreateGroup(groupName), LocalDate.now())));
		if (!directory.isIndexingAllowed()) {
			throw new IllegalStateException("Indexing disabled for directory: " + normalized);
		}

		// The sequence's nominal date, from the path itself (e.g. "2026/09/...")
		// - used both as the per-image clamp default below and, later, as the
		// starting point for the directory's own sequenceDate merge.
		LocalDate dirDateGuess = PathDateGuesser.guess(normalized);
		LocalDateTime sequenceDefault = dirDateGuess != null ? dirDateGuess.atStartOfDay() : null;

		Map<String, Image> indexedByName = new HashMap<>();
		for (Image image : imageRepository.findByDirectory_IdOrderByNameAsc(directory.getId())) {
			indexedByName.put(image.getName(), image);
		}
		Set<String> onDiskNames = Set.copyOf(onDisk);

		LocalDate latestImageDate = null;
		for (String name : onDisk) {
			var filePath = storageService.resolveOriginal(normalized, name);
			LocalDateTime captureDate = exifDateReader.readCaptureDate(filePath, name, sequenceDefault);
			// null when the file carries no GPS tag at all - Coordinates is a
			// record, not two loose doubles, precisely so "no GPS" is a single
			// null check here, not two independently-nullable fields to keep
			// in sync.
			ExifGpsReader.Coordinates gps = exifGpsReader.readGpsCoordinates(filePath);

			Image image = indexedByName.get(name);
			if (image == null) {
				image = new Image(name, directory, captureDate);
				imageRepository.save(image);
			} else {
				image.setCaptureDate(captureDate);
			}
			// Recomputed unconditionally on every pass, same "not just newly
			// discovered rows" philosophy as captureDate just above (this
			// method's own class javadoc) - a photo replaced in place with one
			// that now has (or lost) GPS data is reflected on the next re-index,
			// not stuck with whatever the first pass happened to find.
			image.setLatitude(gps != null ? gps.latitude() : null);
			image.setLongitude(gps != null ? gps.longitude() : null);
			// Regenerated unconditionally on every pass, not just for newly
			// discovered rows (31/08/2026) - same "recompute everything, not
			// only what's new" philosophy this method already commits to for
			// EXIF dates just above (see the class javadoc). Found directly
			// relevant, not theoretical, the same day: the .thumbnails/.webimg
			// restructure (ImageStorageService's own javadoc) moves where
			// derivatives live on disk - an already-indexed directory's
			// existing rows would otherwise keep pointing at a location
			// nothing here ever regenerates for them, breaking their
			// thumbnail/web display until this ran. Also makes re-index
			// self-healing for a derivative deleted/corrupted independently
			// of its DB row, and is what the migration procedure's "Batch
			// Publish ... regenerates thumbnails" step already assumed was
			// already true.
			storageService.generateThumbnail(normalized, name);
			if (captureDate != null) {
				LocalDate day = captureDate.toLocalDate();
				latestImageDate = (latestImageDate == null || day.isAfter(latestImageDate)) ? day : latestImageDate;
			}
		}
		for (Image image : indexedByName.values()) {
			if (!onDiskNames.contains(image.getName())) {
				storageService.delete(normalized, image.getName()); // clears any leftover thumbnail; the original is already gone
				imageRepository.delete(image);
			}
		}

		directory.setSequenceDate(DirectoryDateRules.updateDirDate(directory.getSequenceDate(), latestImageDate));

		return DirectoryResponse.from(directory);
	}

	/** DB index only - matches the legacy "reset index" admin command (DirIndexSvr/Configuration.resetIndexCmd). Files on disk are untouched. */
	@Transactional
	public void resetDirectoryIndex(Authentication authentication, String path) {
		directoryRepository.findByPath(normalize(path)).ifPresent(directory -> {
			// DELETE_SEQUENCE, same as legacy's DirectoryIndexer.deleteDirectoryIndex
			// - ADMIN-only per the role matrix (WRITER can edit, but not
			// reset/delete, a sequence's index).
			authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.DELETE_SEQUENCE);
			deleteDirectoryRow(directory);
		});
	}

	/**
	 * DB index AND the files themselves - matches the legacy "delete" admin
	 * command (DirIndexSvr/Configuration.deleteCmd). Irreversible.
	 *
	 * Checked unconditionally, even when {@code path} has no DB row at all -
	 * this is the exact bug class already found and fixed once in the legacy
	 * app itself (Design.md §16.8.2: {@code DirectoryIndexer
	 * .deleteDirectoryIndexAndImages} used to skip authorization entirely for
	 * an unindexed path, then delete the directory from disk regardless -
	 * "plus sévère ... puisqu'il n'est même pas limité à un seul fichier").
	 * An unindexed path defaults to the "public" group here, same as any
	 * other never-indexed resource (see {@code findOrCreateGroup}), so the
	 * check runs against that even when there's no {@link Directory} row to
	 * read a real group from.
	 */
	@Transactional
	public void deleteDirectoryIndexAndFiles(Authentication authentication, String path) {
		String normalized = normalize(path);
		Optional<Directory> existing = directoryRepository.findByPath(normalized);
		String groupName = existing.map(d -> d.getGroup().getGroupName()).orElse(DEFAULT_GROUP);
		authorizationService.checkAuthorization(authentication, groupName, Action.DELETE_SEQUENCE);
		existing.ifPresent(this::deleteDirectoryRow);
		storageService.deleteDirectoryFiles(normalized);
	}

	// The DB's ON DELETE CASCADE (V2 migration) would clean up `image` rows
	// on its own, but Hibernate doesn't know that from the Java side - it
	// has no @OneToMany mapping from Directory to Image at all, so the
	// still-managed Image entities in this persistence context keep
	// pointing at the Directory instance after it's marked for removal,
	// which trips Hibernate's flush-time check on Image.directory (a
	// non-nullable, non-cascaded @ManyToOne) and throws
	// TransientObjectException on the next query. Removing the images
	// through the persistence context first, before the directory, avoids
	// that dangling in-memory reference entirely.
	private void deleteDirectoryRow(Directory directory) {
		imageRepository.deleteAll(imageRepository.findByDirectory_IdOrderByNameAsc(directory.getId()));
		directoryRepository.delete(directory);
	}

	@Transactional
	public DirectoryResponse renameDirectory(Authentication authentication, Long id, RenameDirectoryRequest request) {
		Directory directory = requireDirectory(id);
		authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.EDIT_SEQUENCE);
		// The name is always the last path segment (SequenceNames' own doc for
		// why "/" and ".." are refused). The year/month part is kept, unless a
		// new date is given (05/10/2026, explicit ask - move a sequence to a
		// different date): then a `year/month/name` sequence moves to
		// `newYear/newMonth/name`.
		String newName = request.newName().trim();
		SequenceNames.requireValid(newName);
		String oldPath = directory.getPath();
		String oldParentPath = oldPath.contains("/") ? oldPath.substring(0, oldPath.lastIndexOf('/')) : "";
		String parentPath = oldParentPath;
		if (request.year() != null || request.month() != null) {
			parentPath = targetYearMonth(oldPath, request.year(), request.month());
		}
		String newPath = parentPath.isEmpty() ? newName : parentPath + "/" + newName;
		if (newPath.equals(oldPath)) {
			return DirectoryResponse.from(directory); // nothing changed
		}

		if (directoryRepository.findByPath(newPath).isPresent()) {
			throw new DirectoryAlreadyExistsException(newPath);
		}
		storageService.renameDirectory(oldPath, newPath); // throws if the target already exists on disk

		// Cascade the path change to every DB row nested under the old path,
		// not just the renamed directory itself - subdirectories move on
		// disk along with their parent, so their DB paths have to follow.
		for (Directory descendant : directoryRepository.findByPathStartingWith(oldPath + "/")) {
			descendant.setPath(newPath + descendant.getPath().substring(oldPath.length()));
		}
		directory.setPath(newPath);

		// Moved to another date: the old month folder (then year folder) is
		// removed if nothing at all is left in it, so the tree doesn't keep
		// an empty branch - never one that is a sequence of its own (has a
		// Directory row), even if empty.
		if (!parentPath.equals(oldParentPath)) {
			String emptyCandidate = oldParentPath;
			while (!emptyCandidate.isEmpty()
					&& directoryRepository.findByPath(emptyCandidate).isEmpty()
					&& storageService.deleteDirectoryIfEmpty(emptyCandidate)) {
				emptyCandidate = emptyCandidate.contains("/") ? emptyCandidate.substring(0, emptyCandidate.lastIndexOf('/')) : "";
			}
		}

		return DirectoryResponse.from(directory);
	}

	private static final java.util.regex.Pattern DATED_SEQUENCE = java.util.regex.Pattern.compile("^\\d{4}/\\d{2}/[^/]+$");

	/**
	 * The new {@code yyyy/MM} parent for a sequence moved to another date
	 * (05/10/2026). Only a sequence filed as {@code year/month/name} has a
	 * date to change - a sub-sequence or a folder outside that structure is
	 * refused, as is an incomplete or impossible date.
	 */
	private static String targetYearMonth(String oldPath, Integer year, Integer month) {
		if (year == null || month == null) {
			throw new IllegalArgumentException("A sequence's new date needs both a month and a year");
		}
		if (month < 1 || month > 12 || year < 1000 || year > 9999) {
			throw new IllegalArgumentException("Invalid date: " + month + "/" + year + " (expected MM/YYYY)");
		}
		if (!DATED_SEQUENCE.matcher(oldPath).matches()) {
			throw new IllegalArgumentException("Only a sequence filed as year/month/name can be moved to another date (got " + oldPath + ")");
		}
		return String.format("%04d/%02d", year, month);
	}

	@Transactional
	public DirectoryResponse updateDirectoryDetail(Authentication authentication, Long id, UpdateDirectoryDetailRequest request) {
		Directory directory = requireDirectory(id);
		authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.EDIT_SEQUENCE);
		if (request.description() != null) {
			directory.setDescription(request.description());
		}
		if (request.latitude() != null) {
			directory.setLatitude(request.latitude());
		}
		if (request.longitude() != null) {
			directory.setLongitude(request.longitude());
		}
		if (request.groupName() != null && !request.groupName().isBlank()) {
			// Checked against the *target* group too (12/09/2026, explicit
			// ask) - this used to only ever check the current group above,
			// which let a WRITER move a sequence into an arbitrary group
			// (including one they have no rights in, or a typo that
			// find-or-create then silently minted as a brand-new orphan
			// group - both flagged as risks in the 04/09/2026 backlog entry,
			// never fixed until now). requireGroup (find-or-throw, not
			// find-or-create) closes the second risk: the frontend picker
			// only ever submits a name it just listed as already existing.
			// A global admin bypasses this check entirely
			// (GalleryAuthorizationService's own doc) - "ADMIN can pick up
			// any group" - everyone else needs an explicit role there that
			// already permits EDIT_SEQUENCE (WRITER or a group-scoped
			// ADMIN row), which is exactly "one of its own groups as a
			// WRITER (or better)"; READER/LOWER can never reach this line
			// at all, since they already fail the current-group check above.
			Group targetGroup = requireGroup(request.groupName());
			authorizationService.checkAuthorization(authentication, targetGroup, Action.EDIT_SEQUENCE);
			directory.setGroup(targetGroup);
		}
		if (request.attributeNames() != null) {
			// Rebuilt from scratch each time (mirrors the legacy
			// updateDirectory: clear then re-add) rather than diffed, since
			// the client always sends the full desired set.
			Set<Attribute> resolved = new LinkedHashSet<>();
			for (String name : request.attributeNames()) {
				Attribute attribute = findOrCreateAttribute(name);
				addWithAncestors(resolved, attribute);
			}
			directory.getAttributes().clear();
			directory.getAttributes().addAll(resolved);
		}
		return DirectoryResponse.from(directory);
	}

	/** Find-or-create, not find-or-throw - see GalleryService's own identically-named method for the full reasoning (restores a legacy capability, deliberately not routed through the ADMIN-only AttributeController). */
	private Attribute findOrCreateAttribute(String name) {
		return attributeRepository.findByName(name).orElseGet(() -> attributeRepository.save(new Attribute(name, null)));
	}

	// Selecting a child attribute implies its ancestors too - same
	// inheritance rule as the legacy DirDataSvr.addParent.
	private void addWithAncestors(Set<Attribute> accumulator, Attribute attribute) {
		if (accumulator.add(attribute) && attribute.getParent() != null) {
			addWithAncestors(accumulator, attribute.getParent());
		}
	}

	/**
	 * Lets the frontend resolve an image's owning sequence (ImageResponse.directoryId)
	 * to show its description/attributes, e.g. in the post-it - and the same
	 * lookup a tree click resolves to before showing the sequence edit
	 * panel. {@link Action#BROWSE}-gated (12/09/2026, explicit ask) - legacy's
	 * own DirDataSvr.doGet gated this identically, and a sequence's
	 * description/geolocation is exactly the kind of content the ask meant
	 * to protect, not just the images themselves; leaving this open while
	 * gating the image list would have left a "half-private" sequence (name
	 * hidden nowhere, but its comment still readable) - deliberately not
	 * done. Directory-tree *navigation* itself
	 * ({@code DirectoryController.listSubdirectories}) stays open regardless
	 * - see this service's own {@code authorizationService}'s javadoc.
	 */
	@Transactional(readOnly = true)
	public DirectoryResponse getDirectory(Authentication authentication, Long id) {
		Directory directory = requireDirectory(id);
		authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.BROWSE);
		return DirectoryResponse.from(directory);
	}

	private Directory requireDirectory(Long id) {
		return directoryRepository.findById(id).orElseThrow(() -> new DirectoryNotFoundException(id));
	}

	private Group findOrCreateGroup(String groupName) {
		String name = (groupName == null || groupName.isBlank()) ? DEFAULT_GROUP : groupName;
		return groupRepository.findById(name).orElseGet(() -> groupRepository.save(new Group(name, "", LocalDate.now())));
	}

	/** Find-or-throw, not find-or-create - see the group-reassignment branch of {@link #updateDirectoryDetail} for why this one path deliberately doesn't mint a new group on a typo. */
	private Group requireGroup(String groupName) {
		return groupRepository.findById(groupName).orElseThrow(() -> new GroupNotFoundException(groupName));
	}

	private static String normalize(String path) {
		if (path == null) return "";
		String trimmed = path.trim();
		while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
		while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
		return trimmed;
	}
}
