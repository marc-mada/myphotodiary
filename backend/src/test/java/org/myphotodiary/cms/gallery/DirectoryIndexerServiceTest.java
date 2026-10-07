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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import javax.imageio.ImageIO;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.AttributeResponse;
import org.myphotodiary.cms.gallery.dto.BatchIndexResult;
import org.myphotodiary.cms.gallery.dto.CreateAttributeRequest;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.DirectoryTreeNode;
import org.myphotodiary.cms.gallery.dto.RenameDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.UpdateDirectoryDetailRequest;
import org.myphotodiary.cms.user.Group;
import org.myphotodiary.cms.user.GroupRepository;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateRoleAssignmentRequest;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises the filesystem side of directory indexing against a real temp
 * directory (not mocked), the same "characterize before trusting it"
 * discipline as GalleryServiceTest - the failure mode this specifically
 * guards against is the index silently drifting from what's actually on
 * disk (Design.md §16.7's whole point for DirectoryIndexer).
 */
@SpringBootTest
@Transactional
class DirectoryIndexerServiceTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
	}

	@Autowired
	private DirectoryIndexerService indexerService;
	@Autowired
	private AttributeService attributeService;
	@Autowired
	private DirectoryRepository directoryRepository;
	@Autowired
	private ImageRepository imageRepository;
	@Autowired
	private UserService userService;
	@Autowired
	private GroupRepository groupRepository;
	@PersistenceContext
	private EntityManager entityManager;

	// ADMIN is permitted every Action in the role matrix - this class tests
	// filesystem-sync/indexing behavior, not RBAC itself. Only needed for the
	// write actions that are actually gated (reset/delete/rename/update-detail/
	// batch reset-or-delete) - indexDirectory itself stays open to any
	// authenticated role (browse-equivalent, see its own javadoc), so it's
	// unaffected by this fixture.
	private Authentication auth;

	@BeforeEach
	void setUpAuth() {
		userService.createUser(new CreateUserRequest("dirindex-test-admin", "DirIndex Test Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("dirindex-test-admin", null);
	}

	@Test
	void listSubdirectories_reflectsDiskNotDatabase() throws IOException {
		String root = "unindexed-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(root).resolve("sub1");
		Files.createDirectories(dir);
		writeFile(dir, "a.jpg");
		writeFile(dir, "b.jpg");

		// Never indexed (no DirectoryIndexerService.indexDirectory call) -
		// still shows up, because the tree is filesystem-driven like the
		// legacy SubDirListSvr, not database-driven.
		List<DirectoryTreeNode> nodes = indexerService.listSubdirectories(root);

		assertThat(nodes).hasSize(1);
		assertThat(nodes.get(0).name()).isEqualTo("sub1");
		assertThat(nodes.get(0).imageCount()).isEqualTo(2);
		assertThat(nodes.get(0).indexed()).isFalse();
	}

	@Test
	void indexDirectory_createsRowsAndThumbnailsForNewFiles() throws IOException {
		String path = "2026/08/index-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "one.jpg");
		writeFile(dir, "two.jpg");

		DirectoryResponse result = indexerService.indexDirectory(path, null);

		assertThat(result.path()).isEqualTo(path);
		List<Image> images = imageRepository.findByDirectory_IdOrderByNameAsc(result.id());
		assertThat(images).extracting(Image::getName).containsExactly("one.jpg", "two.jpg");
		// .thumbnails co-located inside the sequence directory (31/08/2026 restructure, matches legacy exactly).
		assertThat(Files.exists(storageRoot.resolve(path).resolve(".thumbnails").resolve("one.jpg"))).isTrue();
	}

	@Test
	void indexDirectory_readsGpsFromExifWhenPresent_leavesItNullWhenAbsent() throws Exception {
		String path = "2026/08/gps-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFileWithGps(dir, "with-gps.jpg", 2.3508, 48.8567); // Paris
		writeFile(dir, "no-gps.jpg");

		DirectoryResponse result = indexerService.indexDirectory(path, null);

		List<Image> images = imageRepository.findByDirectory_IdOrderByNameAsc(result.id());
		Image withGps = images.stream().filter(i -> i.getName().equals("with-gps.jpg")).findFirst().orElseThrow();
		Image noGps = images.stream().filter(i -> i.getName().equals("no-gps.jpg")).findFirst().orElseThrow();

		assertThat(withGps.getLatitude()).isCloseTo(48.8567, within(0.0001));
		assertThat(withGps.getLongitude()).isCloseTo(2.3508, within(0.0001));
		assertThat(noGps.getLatitude()).isNull();
		assertThat(noGps.getLongitude()).isNull();
	}

	@Test
	void indexDirectory_recomputesGpsOnEveryRescan_clearsItWhenFileNoLongerHasIt() throws Exception {
		// Same "recompute unconditionally, not just for newly discovered
		// rows" philosophy already established for captureDate/thumbnails
		// (DirectoryIndexerService's own class javadoc) - a file replaced in
		// place with one that lost its GPS tag must have the stale
		// coordinates cleared on the next re-index, not left stuck.
		String path = "2026/08/gps-rescan-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFileWithGps(dir, "photo.jpg", 2.3508, 48.8567);
		indexerService.indexDirectory(path, null);

		Image indexed = imageRepository.findByDirectory_IdOrderByNameAsc(
				directoryRepository.findByPath(path).orElseThrow().getId()).get(0);
		assertThat(indexed.getLatitude()).isNotNull();

		// Same file name, but the replacement copy carries no GPS tag at all.
		writeFile(dir, "photo.jpg");
		indexerService.indexDirectory(path, null);

		Image rescanned = imageRepository.findByDirectory_IdOrderByNameAsc(
				directoryRepository.findByPath(path).orElseThrow().getId()).get(0);
		assertThat(rescanned.getLatitude()).isNull();
		assertThat(rescanned.getLongitude()).isNull();
	}

	@Test
	void indexDirectory_removesRowsForFilesDeletedFromDisk() throws IOException {
		String path = "2026/08/sync-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "keep.jpg");
		writeFile(dir, "remove.jpg");
		indexerService.indexDirectory(path, null);

		Files.delete(dir.resolve("remove.jpg"));
		DirectoryResponse result = indexerService.indexDirectory(path, null);

		List<Image> images = imageRepository.findByDirectory_IdOrderByNameAsc(result.id());
		assertThat(images).extracting(Image::getName).containsExactly("keep.jpg");
	}

	@Test
	void indexDirectory_setsSequenceDateFromWhatsAppFilenameDate() throws IOException {
		// No real EXIF in these fixtures - the WhatsApp-filename fallback
		// (ExifDateReaderTest) gives a predictable, assertable date instead
		// of "whatever the file's creation time happens to be right now".
		// Unique suffix in base 36, not a raw decimal System.nanoTime(): the
		// legacy date-guessing regex (PathDateGuesser) isn't anchored to the
		// path's start, so a long run of plain digits glued onto the path
		// can itself look like a (bogus) year/month/day match and get picked
		// up in preference to the real "2026/08" prefix - a real quirk of
		// the faithfully-ported regex, not something to work around there,
		// just something this test's own fixture needs to avoid triggering.
		String path = "2026/08/wa-date-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "IMG-20260315-WA0001.jpg");

		DirectoryResponse result = indexerService.indexDirectory(path, null);

		assertThat(result.sequenceDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 15));
	}

	@Test
	void indexDirectory_recomputesDateForAlreadyIndexedFilesOnEveryRescan() throws IOException {
		// Matches the legacy merge-diff loop exactly: it recomputes EXIF for
		// every file it finds on each scan, not just newly discovered ones
		// (unlike a "only touch what changed" sync, which was this method's
		// original, simplified behavior before EXIF was ported - see
		// DirectoryIndexerService's javadoc). Re-scanning an unchanged file
		// twice must still land on the same correct date each time, not
		// silently skip or null it out the second time around.
		String path = "2026/08/refresh-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "IMG-20260101-WA0001.jpg");

		indexerService.indexDirectory(path, null);
		DirectoryResponse rescanned = indexerService.indexDirectory(path, null);

		Image image = imageRepository.findByDirectory_IdOrderByNameAsc(rescanned.id()).get(0);
		assertThat(image.getCaptureDate().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 1, 1));
	}

	@Test
	void indexDirectory_regeneratesThumbnailForAlreadyIndexedFileOnEveryRescan() throws IOException {
		// Same "recompute everything, not just what's new" philosophy as the
		// EXIF-date test just above, extended to thumbnails (31/08/2026,
		// found directly relevant while restructuring .thumbnails/.webimg to
		// be co-located per sequence - an already-indexed row would
		// otherwise keep pointing at a derivative location nothing ever
		// regenerates for it). Deleting the thumbnail after the first index
		// and confirming a rescan restores it - self-healing for a
		// derivative missing for any reason, not only a location change.
		String path = "2026/08/thumb-refresh-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		indexerService.indexDirectory(path, null);

		Path thumbnail = storageRoot.resolve(path).resolve(".thumbnails").resolve("photo.jpg");
		assertThat(Files.exists(thumbnail)).isTrue();
		Files.delete(thumbnail);
		assertThat(Files.exists(thumbnail)).isFalse();

		indexerService.indexDirectory(path, null);

		assertThat(Files.exists(thumbnail)).isTrue();
	}

	@Test
	void indexDirectory_emptyDirectory_throws() throws IOException {
		String path = "2026/08/empty-" + Long.toString(System.nanoTime(), 36);
		Files.createDirectories(storageRoot.resolve(path));

		assertThatThrownBy(() -> indexerService.indexDirectory(path, null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void resetDirectoryIndex_removesDbRowsButKeepsFiles() throws IOException {
		String path = "2026/08/reset-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		indexerService.indexDirectory(path, null);

		indexerService.resetDirectoryIndex(auth, path);

		assertThat(directoryRepository.findByPath(path)).isEmpty();
		assertThat(Files.exists(dir.resolve("photo.jpg"))).isTrue();
	}

	@Test
	void deleteDirectoryIndexAndFiles_removesBothDbRowsAndFiles() throws IOException {
		String path = "2026/08/delete-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		indexerService.indexDirectory(path, null);

		indexerService.deleteDirectoryIndexAndFiles(auth, path);

		assertThat(directoryRepository.findByPath(path)).isEmpty();
		assertThat(Files.exists(dir)).isFalse();
	}

	@Test
	void renameDirectory_movesFilesAndCascadesPathToDescendants() throws IOException {
		String oldParent = "2026/08/old-name-" + Long.toString(System.nanoTime(), 36);
		Path parentDir = storageRoot.resolve(oldParent);
		Path childDir = parentDir.resolve("child");
		Files.createDirectories(childDir);
		writeFile(parentDir, "parent.jpg");
		writeFile(childDir, "child.jpg");
		DirectoryResponse parent = indexerService.indexDirectory(oldParent, null);
		indexerService.indexDirectory(oldParent + "/child", null);

		DirectoryResponse renamed = indexerService.renameDirectory(auth, parent.id(), new RenameDirectoryRequest("new-name"));

		String newParentPath = renamed.path();
		assertThat(newParentPath).endsWith("new-name");
		assertThat(Files.exists(storageRoot.resolve(newParentPath).resolve("parent.jpg"))).isTrue();
		assertThat(Files.exists(storageRoot.resolve(newParentPath).resolve("child").resolve("child.jpg"))).isTrue();
		assertThat(directoryRepository.findByPath(newParentPath + "/child")).isPresent();
		assertThat(directoryRepository.findByPath(oldParent + "/child")).isEmpty();
		// .thumbnails is co-located inside each sequence directory now
		// (31/08/2026 restructure) - moves along with everything else in a
		// single Files.move of the parent, no separate mirror-tree move step
		// needed any more (the now-removed moveMirrorDirIfPresent). Checked
		// on both levels: the parent's own thumbnail, and the child's -
		// confirms the whole subtree really moved, not just the top level.
		assertThat(Files.exists(storageRoot.resolve(newParentPath).resolve(".thumbnails").resolve("parent.jpg"))).isTrue();
		assertThat(Files.exists(storageRoot.resolve(newParentPath).resolve("child").resolve(".thumbnails").resolve("child.jpg"))).isTrue();
	}

	@Test
	void renameDirectory_targetAlreadyExists_throws() throws IOException {
		String base = "2026/08/rename-conflict-" + Long.toString(System.nanoTime(), 36);
		Files.createDirectories(storageRoot.resolve(base).resolve("a"));
		writeFile(storageRoot.resolve(base).resolve("a"), "photo.jpg");
		Files.createDirectories(storageRoot.resolve(base).resolve("b"));
		DirectoryResponse dirA = indexerService.indexDirectory(base + "/a", null);

		assertThatThrownBy(() -> indexerService.renameDirectory(auth, dirA.id(), new RenameDirectoryRequest("b")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void renameDirectory_rejectsNamesThatAreNotASingleFolderName_andMovesNothing() throws IOException {
		// 02/10/2026: a "/" used to silently create nested folders, and a
		// ".." segment moved the folder on disk to the normalized path
		// while the database kept the raw text - the two then disagreed.
		// Every refused name must leave both disk and database untouched.
		String path = "2026/08/rename-guard-" + Long.toString(System.nanoTime(), 36);
		Files.createDirectories(storageRoot.resolve(path));
		writeFile(storageRoot.resolve(path), "photo.jpg");
		DirectoryResponse dir = indexerService.indexDirectory(path, null);

		for (String bad : new String[] { "2024/05/trip", "../../2024/05/trip", "..", ".", ".thumbnails", "a\\b", "   " }) {
			assertThatThrownBy(() -> indexerService.renameDirectory(auth, dir.id(), new RenameDirectoryRequest(bad)))
					.as("name %s", bad)
					.isInstanceOf(IllegalArgumentException.class);
		}

		assertThat(Files.exists(storageRoot.resolve(path).resolve("photo.jpg"))).isTrue();
		assertThat(directoryRepository.findByPath(path)).isPresent();
		assertThat(Files.exists(storageRoot.resolve("2024/05/trip"))).isFalse();
		assertThat(Files.exists(storageRoot.resolve("2026/08/2024"))).isFalse();
	}

	@Test
	void renameDirectory_toAnotherDate_movesEverything_andRemovesTheEmptiedMonthAndYear() throws IOException {
		// 05/10/2026: move a sequence to a different date. Unique year/month so
		// the "old folders now empty" cleanup can be asserted (other tests in
		// this class share the storage root and use 2026).
		String name = "move-" + Long.toString(System.nanoTime(), 36);
		String oldPath = "1971/05/" + name;
		Path seqDir = storageRoot.resolve(oldPath);
		Files.createDirectories(seqDir.resolve("child"));
		writeFile(seqDir, "photo.jpg");
		writeFile(seqDir.resolve("child"), "child.jpg");
		DirectoryResponse seq = indexerService.indexDirectory(oldPath, null);
		indexerService.indexDirectory(oldPath + "/child", null);

		DirectoryResponse moved = indexerService.renameDirectory(auth, seq.id(), new RenameDirectoryRequest(name, 1972, 6));

		String newPath = "1972/06/" + name;
		assertThat(moved.path()).isEqualTo(newPath);
		assertThat(Files.exists(storageRoot.resolve(newPath).resolve("photo.jpg"))).isTrue();
		assertThat(Files.exists(storageRoot.resolve(newPath).resolve(".thumbnails").resolve("photo.jpg"))).isTrue();
		assertThat(Files.exists(storageRoot.resolve(newPath).resolve("child").resolve("child.jpg"))).isTrue();
		assertThat(directoryRepository.findByPath(newPath + "/child")).isPresent();
		assertThat(directoryRepository.findByPath(oldPath)).isEmpty();
		assertThat(Files.exists(storageRoot.resolve("1971/05"))).as("emptied month removed").isFalse();
		assertThat(Files.exists(storageRoot.resolve("1971"))).as("emptied year removed").isFalse();
	}

	@Test
	void renameDirectory_nameAndDateTogether_andAnUnchangedRequestIsANoOp() throws IOException {
		String name = "both-" + Long.toString(System.nanoTime(), 36);
		Files.createDirectories(storageRoot.resolve("1973/02/" + name));
		writeFile(storageRoot.resolve("1973/02/" + name), "p.jpg");
		DirectoryResponse seq = indexerService.indexDirectory("1973/02/" + name, null);

		DirectoryResponse same = indexerService.renameDirectory(auth, seq.id(), new RenameDirectoryRequest(name, 1973, 2));
		assertThat(same.path()).isEqualTo("1973/02/" + name);

		DirectoryResponse moved = indexerService.renameDirectory(auth, seq.id(), new RenameDirectoryRequest(name + "-renamed", 1974, 11));
		assertThat(moved.path()).isEqualTo("1974/11/" + name + "-renamed");
		assertThat(Files.exists(storageRoot.resolve("1974/11/" + name + "-renamed/p.jpg"))).isTrue();
	}

	@Test
	void renameDirectory_badDate_orNonDatedSequence_orTakenTarget_isRefused_andNothingMoves() throws IOException {
		String name = "guard-" + Long.toString(System.nanoTime(), 36);
		String path = "1975/03/" + name;
		Files.createDirectories(storageRoot.resolve(path));
		writeFile(storageRoot.resolve(path), "p.jpg");
		DirectoryResponse seq = indexerService.indexDirectory(path, null);
		Files.createDirectories(storageRoot.resolve("1976/04/" + name)); // target already taken on disk

		assertThatThrownBy(() -> indexerService.renameDirectory(auth, seq.id(), new RenameDirectoryRequest(name, 1975, 13))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> indexerService.renameDirectory(auth, seq.id(), new RenameDirectoryRequest(name, 999, 3))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> indexerService.renameDirectory(auth, seq.id(), new RenameDirectoryRequest(name, null, 3))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> indexerService.renameDirectory(auth, seq.id(), new RenameDirectoryRequest(name, 1976, 4))).isInstanceOf(IllegalStateException.class);

		// A sub-sequence (year/month/name/sub) has no date of its own to change.
		Files.createDirectories(storageRoot.resolve(path + "/sub"));
		writeFile(storageRoot.resolve(path + "/sub"), "s.jpg");
		DirectoryResponse sub = indexerService.indexDirectory(path + "/sub", null);
		assertThatThrownBy(() -> indexerService.renameDirectory(auth, sub.id(), new RenameDirectoryRequest("sub", 1977, 1))).isInstanceOf(IllegalArgumentException.class);

		assertThat(Files.exists(storageRoot.resolve(path).resolve("p.jpg"))).isTrue();
		assertThat(directoryRepository.findByPath(path)).isPresent();
		assertThat(directoryRepository.findByPath(path + "/sub")).isPresent();
	}

	@Test
	void updateDirectoryDetail_attributeSelectionIncludesAncestors() throws IOException {
		String grandparent = "region-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(grandparent, null));
		AttributeResponse child = attributeService.create(new CreateAttributeRequest("city-" + System.nanoTime(), grandparent));

		String path = "2026/08/attr-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse directory = indexerService.indexDirectory(path, null);

		DirectoryResponse updated = indexerService.updateDirectoryDetail(auth, directory.id(),
				new UpdateDirectoryDetailRequest("A lovely spot", 48.85, 2.35, null, List.of(child.name())));

		assertThat(updated.description()).isEqualTo("A lovely spot");
		assertThat(updated.latitude()).isEqualTo(48.85);
		// Selecting the child attribute pulls in its parent automatically -
		// same inheritance rule as the legacy DirDataSvr.addParent.
		assertThat(updated.attributeNames()).containsExactlyInAnyOrder(child.name(), grandparent);
	}

	@Test
	void updateDirectoryDetail_unknownAttributeName_createsAndAssignsItInsteadOfThrowing() throws IOException {
		// Same restored legacy capability as GalleryServiceTest's own
		// identically-named test (03/09/2026, explicit ask) - idx.js's
		// dirEditPopup had the same plain "new attribute" text field
		// beside its own <select> (#newDirParam).
		String brandNewTag = "brand-new-seq-tag-" + System.nanoTime();
		String path = "2026/08/newtag-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse directory = indexerService.indexDirectory(path, null);

		DirectoryResponse updated = indexerService.updateDirectoryDetail(auth, directory.id(),
				new UpdateDirectoryDetailRequest(null, null, null, null, List.of(brandNewTag)));

		assertThat(updated.attributeNames()).containsExactly(brandNewTag);
		assertThat(attributeService.listAll()).extracting(AttributeResponse::name).contains(brandNewTag);
	}

	// --- Sequence group picker (12/09/2026, explicit ask - "an ADMIN user
	// can pick up any group / a WRITER user can only pick up one of its own
	// groups as a WRITER / a READER/LOWER cannot change the sequence group")
	// ---

	@Test
	void updateDirectoryDetail_groupReassignment_globalAdminCanMoveToAnyExistingGroup() throws IOException {
		// A *global* admin (Spring ROLE_ADMIN, derived from a user's primary
		// role assignment - DomainUserDetailsService), not merely a per-group
		// ADMIN row - see GalleryAuthorizationServiceTest's own two,
		// deliberately distinct, admin tests for why that distinction
		// matters. No RoleAssignment row of this principal's own in either
		// group - the whole point of the bypass.
		Authentication globalAdmin = new UsernamePasswordAuthenticationToken("global-admin-mover", null,
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
		groupRepository.save(new Group("vacation-photos-" + System.nanoTime(), "", LocalDate.now()));
		String targetGroup = groupRepository.findAll().stream()
				.map(Group::getGroupName)
				.filter(name -> name.startsWith("vacation-photos-"))
				.findFirst().orElseThrow();

		String path = "2026/08/admin-group-move-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse directory = indexerService.indexDirectory(path, null); // lands in "public"

		DirectoryResponse updated = indexerService.updateDirectoryDetail(globalAdmin, directory.id(),
				new UpdateDirectoryDetailRequest(null, null, null, targetGroup, null));

		assertThat(updated.groupName()).isEqualTo(targetGroup);
	}

	@Test
	void updateDirectoryDetail_groupReassignment_writerCanOnlyMoveToAGroupWhereTheyAreWriter() throws IOException {
		userService.createUser(new CreateUserRequest("group-writer", "Group Writer", "x", "writer-home-" + System.nanoTime(), "WRITER"));
		Authentication writerAuth = new UsernamePasswordAuthenticationToken("group-writer", null);
		// The writer's own primary group, resolved from the RoleAssignment
		// just created above rather than duplicating the same random suffix.
		String homeGroup = userService.listRoleAssignments("group-writer").get(0).groupName();

		String path = "2026/08/writer-group-move-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse directory = indexerService.indexDirectory(path, homeGroup);

		String otherGroup = "other-group-" + System.nanoTime();
		assertThatThrownBy(() -> indexerService.updateDirectoryDetail(writerAuth, directory.id(),
				new UpdateDirectoryDetailRequest(null, null, null, otherGroup, null)))
				// requireGroup throws before any authorization check on a
				// name that has never been created at all - same as the
				// dedicated GroupNotFoundException test below, not
				// re-asserted here; give this writer an explicit (but
				// group-less) role there instead by creating the group
				// first, to isolate this test to the *authorization*
				// question rather than the group's mere existence.
				.isInstanceOf(GroupNotFoundException.class);

		userService.addRoleAssignment("group-writer", new CreateRoleAssignmentRequest(otherGroup, "READER"));
		assertThatThrownBy(() -> indexerService.updateDirectoryDetail(writerAuth, directory.id(),
				new UpdateDirectoryDetailRequest(null, null, null, otherGroup, null)))
				.isInstanceOf(AccessDeniedException.class);

		userService.removeRoleAssignment("group-writer", otherGroup);
		userService.addRoleAssignment("group-writer", new CreateRoleAssignmentRequest(otherGroup, "WRITER"));
		DirectoryResponse updated = indexerService.updateDirectoryDetail(writerAuth, directory.id(),
				new UpdateDirectoryDetailRequest(null, null, null, otherGroup, null));

		assertThat(updated.groupName()).isEqualTo(otherGroup);
	}

	@Test
	void updateDirectoryDetail_groupReassignment_unknownGroupName_throwsGroupNotFoundException() throws IOException {
		Authentication globalAdmin = new UsernamePasswordAuthenticationToken("global-admin-typo", null,
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
		String path = "2026/08/typo-group-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse directory = indexerService.indexDirectory(path, null);

		assertThatThrownBy(() -> indexerService.updateDirectoryDetail(globalAdmin, directory.id(),
				new UpdateDirectoryDetailRequest(null, null, null, "typo-name-never-created", null)))
				.isInstanceOf(GroupNotFoundException.class);
	}

	@Test
	void updateDirectoryDetail_groupReassignment_readerCannotChangeGroupAtAll() throws IOException {
		userService.createUser(new CreateUserRequest("group-reader", "Group Reader", "x", "reader-home-" + System.nanoTime(), "READER"));
		Authentication readerAuth = new UsernamePasswordAuthenticationToken("group-reader", null);
		String homeGroup = userService.listRoleAssignments("group-reader").get(0).groupName();

		String path = "2026/08/reader-group-move-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse directory = indexerService.indexDirectory(path, homeGroup);

		// Fails on the *current*-group check (READER never permits
		// EDIT_SEQUENCE at all) before the group-reassignment branch is ever
		// reached - the same existing gate every other field on this form
		// already goes through, not a separate check invented for this field.
		assertThatThrownBy(() -> indexerService.updateDirectoryDetail(readerAuth, directory.id(),
				new UpdateDirectoryDetailRequest(null, null, null, homeGroup, null)))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void deleteAttribute_removesReferenceFromTaggedDirectory() throws IOException {
		// Same question as GalleryServiceTest's identically-purposed test,
		// for the other join table this same `ON DELETE CASCADE` covers
		// (directory_attribute, not just image_attribute) - a sequence
		// tagged directly (not a tag merely inherited from a parent) whose
		// tag is then deleted from Admin/Tags. See that test's own comment
		// for why the flush()/clear() pair *before* the delete call is
		// required (a real Hibernate TransientObjectException hit while
		// writing this, not just defensive), and why the pair *after* it
		// is too (proving a fresh read, not a stale in-memory one).
		AttributeResponse tag = attributeService.create(new CreateAttributeRequest("to-delete-seq-" + System.nanoTime(), null));
		String path = "2026/08/attr-delete-test-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse directory = indexerService.indexDirectory(path, null);
		indexerService.updateDirectoryDetail(auth, directory.id(),
				new UpdateDirectoryDetailRequest(null, null, null, null, List.of(tag.name())));
		assertThat(indexerService.getDirectory(auth, directory.id()).attributeNames()).containsExactly(tag.name());
		entityManager.flush();
		entityManager.clear();

		attributeService.delete(tag.id());
		entityManager.flush();
		entityManager.clear();

		Directory reloaded = directoryRepository.findById(directory.id()).orElseThrow();
		assertThat(reloaded.getAttributes()).isEmpty();
		assertThat(indexerService.getDirectory(auth, directory.id()).attributeNames()).isEmpty();
	}

	@Test
	void getDirectory_returnsById() throws IOException {
		String path = "2026/08/get-by-id-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse indexed = indexerService.indexDirectory(path, null);

		DirectoryResponse fetched = indexerService.getDirectory(auth, indexed.id());

		assertThat(fetched.id()).isEqualTo(indexed.id());
		assertThat(fetched.path()).isEqualTo(path);
	}

	@Test
	void getDirectory_deniedForUserWithNoAccessToThatGroup_evenThoughTreeNavigationItselfStaysOpen() throws IOException {
		// The "half-private sequence" gap this exact check closes (explicit
		// ask, 12/09/2026) - a sequence's own description/geolocation, not
		// just its images, needs the same read-access check; the tree
		// listing/navigation endpoints themselves (not exercised through
		// this service at all) are the deliberate, separate exception.
		String path = "2026/08/detail-access-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		DirectoryResponse indexed = indexerService.indexDirectory(path, null); // "public"

		userService.createUser(new CreateUserRequest("detail-no-access", "Detail No Access", "x", "unrelated-detail-group-" + System.nanoTime(), "LOWER"));
		Authentication noAccess = new UsernamePasswordAuthenticationToken("detail-no-access", null);

		assertThatThrownBy(() -> indexerService.getDirectory(noAccess, indexed.id())).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void listAllSubdirectoriesRecursive_flattensEveryLevel() throws IOException {
		String base = "2026/08/recursive-" + Long.toString(System.nanoTime(), 36);
		Files.createDirectories(storageRoot.resolve(base).resolve("a").resolve("nested"));
		writeFile(storageRoot.resolve(base).resolve("a"), "top.jpg");
		writeFile(storageRoot.resolve(base).resolve("a").resolve("nested"), "deep.jpg");
		Files.createDirectories(storageRoot.resolve(base).resolve("b"));

		List<DirectoryTreeNode> flat = indexerService.listAllSubdirectoriesRecursive(base);

		assertThat(flat).extracting(DirectoryTreeNode::path).containsExactlyInAnyOrder(base + "/a", base + "/a/nested", base + "/b");
		assertThat(flat).filteredOn(n -> n.path().equals(base + "/a/nested")).extracting(DirectoryTreeNode::imageCount).containsExactly(1);
	}

	@Test
	void batchIndex_indexesEveryPathAndReportsFailuresIndependently() throws IOException {
		String base = "2026/08/batch-" + Long.toString(System.nanoTime(), 36);
		Path dirA = storageRoot.resolve(base).resolve("a");
		Path dirB = storageRoot.resolve(base).resolve("b-empty");
		Files.createDirectories(dirA);
		Files.createDirectories(dirB);
		writeFile(dirA, "photo.jpg");

		BatchIndexResult result = indexerService.batchIndex(auth, "index", List.of(base + "/a", base + "/b-empty"));

		assertThat(result.succeeded()).containsExactly(base + "/a");
		// Empty directory can't be indexed (indexDirectory's own guard) - the
		// batch reports it as a failure instead of aborting the whole call.
		assertThat(result.failed()).extracting(BatchIndexResult.BatchFailure::path).containsExactly(base + "/b-empty");
		assertThat(directoryRepository.findByPath(base + "/a")).isPresent();
	}

	@Test
	void batchIndex_resetIndexAndDelete_matchSingleDirectoryCommands() throws IOException {
		String path = "2026/08/batch-reset-" + Long.toString(System.nanoTime(), 36);
		Path dir = storageRoot.resolve(path);
		Files.createDirectories(dir);
		writeFile(dir, "photo.jpg");
		indexerService.indexDirectory(path, null);

		indexerService.batchIndex(auth, "reset-index", List.of(path));
		assertThat(directoryRepository.findByPath(path)).isEmpty();
		assertThat(Files.exists(dir.resolve("photo.jpg"))).isTrue();

		indexerService.indexDirectory(path, null);
		indexerService.batchIndex(auth, "delete", List.of(path));
		assertThat(directoryRepository.findByPath(path)).isEmpty();
		assertThat(Files.exists(dir)).isFalse();
	}

	@Test
	void indexRecursively_batchPublishesEveryDirectoryWithImagesUnderRoot() throws IOException {
		String base = "2026/08/publish-" + Long.toString(System.nanoTime(), 36);
		Path top = storageRoot.resolve(base);
		Path nested = top.resolve("trip").resolve("day1");
		Files.createDirectories(nested);
		writeFile(top, "cover.jpg");
		writeFile(nested, "d1.jpg");
		Files.createDirectories(top.resolve("trip").resolve("empty-day"));

		BatchIndexResult result = indexerService.indexRecursively(auth, base);

		assertThat(result.succeeded()).containsExactlyInAnyOrder(base, base + "/trip/day1");
		assertThat(result.failed()).isEmpty();
		assertThat(directoryRepository.findByPath(base)).isPresent();
		assertThat(directoryRepository.findByPath(base + "/trip/day1")).isPresent();
		assertThat(directoryRepository.findByPath(base + "/trip/empty-day")).isEmpty();
	}

	// A real, decodable JPEG - indexDirectory() runs Thumbnailator against
	// whatever's on disk, same as GalleryServiceTest's uploads.
	private static void writeFile(Path dir, String name) throws IOException {
		BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
		ImageIO.write(img, "jpg", dir.resolve(name).toFile());
	}

	/** Same real-fixture discipline as {@link ExifGpsReaderTest} - a genuine GPS EXIF tag via a lossless rewrite, not asserted-but-never-read metadata. */
	private static void writeFileWithGps(Path dir, String name, double longitude, double latitude) throws Exception {
		ByteArrayOutputStream plainJpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", plainJpeg);

		TiffOutputSet outputSet = new TiffOutputSet();
		outputSet.setGpsInDegrees(longitude, latitude);

		try (OutputStream out = Files.newOutputStream(dir.resolve(name))) {
			new ExifRewriter().updateExifMetadataLossless(plainJpeg.toByteArray(), out, outputSet);
		}
	}
}
