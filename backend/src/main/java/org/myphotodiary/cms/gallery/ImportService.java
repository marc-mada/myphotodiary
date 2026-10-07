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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.ImportResponse;
import org.myphotodiary.cms.settings.AppSettingsService;
import org.myphotodiary.cms.user.Action;
import org.myphotodiary.cms.user.Group;
import org.myphotodiary.cms.user.GroupRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * The upload form's real behavior - faithfully ported from the legacy
 * ImportSvr (Design.md §16.5), not the simplified fixed-directory upload this
 * screen started with (GalleryService.uploadImage, still used for the
 * simpler "upload into the directory I have open" case). Two form options
 * drive where a file actually lands, exactly as in index.jsp's import popup:
 *
 * - "Classer les photos par date (EXIF)" ({@code autoSort}, legacy default
 *   checked): the file's own EXIF capture date decides its {year}/{month}
 *   destination, clamped to not be in the future. Off: the caller-supplied
 *   {@code dirPath} is used as-is (the frontend's currently-selected
 *   directory, mirroring the legacy field bound to the current browse
 *   location).
 * - "Créer une nouvelle séquence" ({@code createSubDir}, legacy default
 *   checked) + a sequence name: appends one more path segment under
 *   whichever directory the option above produced. Off: no subfolder, the
 *   file lands directly in the year/month (or manually chosen) directory.
 *
 * Because auto-sort computes a destination per file from that file's own
 * date, two files in the same batch can legitimately end up in different
 * directories - this method handles exactly one file per call for that
 * reason (the frontend loops per file, same as legacy's per-file multipart
 * handling in ImportSvr).
 */
@Service
public class ImportService {

	private static final String DEFAULT_GROUP = "public";

	// Strict H.264-only policy decided during the video talk (28/08/2026) -
	// codec, not container: VideoFormatValidator's own real, codec-level
	// check (ffprobe) is what actually enforces H.264(+AAC), and it was
	// always container-agnostic (its own javadoc already anticipated a
	// `.mov` container - "an iPhone's default HEVC recording is commonly
	// muxed in a .mov container"). This set only decides *which* path a
	// file takes in the first place, so a container extension missing here
	// never reaches that check at all - it silently falls through to
	// MediaType.IMAGE instead (detectMediaType's own default branch) and
	// crashes trying to decode video bytes as a picture.
	//
	// "mov" added 03/09/2026 - real bug found live on iPad: MobileMoviePage
	// records via the device's own native camera app (triggered by
	// `capture`), which produces a `.mov` QuickTime container regardless of
	// this app's own `accept="video/mp4"` hint (that attribute only steers
	// *picker* file-type filtering, never the format a device's camera
	// itself records in) - exactly the case this class's own
	// VideoFormatValidator was already written to expect, just never
	// reachable for it before this fix. Package-private, not private
	// (01/09/2026) - StagingImportService reuses this to decide which
	// staging files are import candidates, same reason as
	// ImageStorageService.IMAGE_EXTENSIONS/hasImageExtension.
	static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov");

	// One lock per directory path, created on first use and never removed
	// (deliberately - see resolveOrCreateDirectory's own javadoc for why a
	// bounded, slow-growing map is an acceptable, simpler tradeoff than
	// correctly ref-counted removal for this app's real scale). Guards the
	// check-then-create race on directory.path (UNIQUE, V2's own schema) -
	// real bug, signalled 22/09/2026 ("when we drop several files together,
	// most of the time, the first one in the list fails to upload; pressing
	// retry usually works" - react-filepond's own maxParallelUploads: 2
	// default genuinely fires up to two concurrent /api/import requests,
	// and the common case, several files landing in the same not-yet-
	// existing directory, used to race two separate transactions' INSERTs).
	private final ConcurrentHashMap<String, ReentrantLock> directoryCreationLocks = new ConcurrentHashMap<>();

	private final DirectoryRepository directoryRepository;
	private final ImageRepository imageRepository;
	private final GroupRepository groupRepository;
	private final ImageStorageService storageService;
	private final ExifDateReader exifDateReader;
	private final ExifGpsReader exifGpsReader;
	private final GalleryAuthorizationService authorizationService;
	private final AppSettingsService appSettingsService;
	private final VideoFormatValidator videoFormatValidator;

	public ImportService(DirectoryRepository directoryRepository, ImageRepository imageRepository, GroupRepository groupRepository,
			ImageStorageService storageService, ExifDateReader exifDateReader, ExifGpsReader exifGpsReader,
			GalleryAuthorizationService authorizationService, AppSettingsService appSettingsService, VideoFormatValidator videoFormatValidator) {
		this.directoryRepository = directoryRepository;
		this.imageRepository = imageRepository;
		this.groupRepository = groupRepository;
		this.storageService = storageService;
		this.exifDateReader = exifDateReader;
		this.exifGpsReader = exifGpsReader;
		this.authorizationService = authorizationService;
		this.appSettingsService = appSettingsService;
		this.videoFormatValidator = videoFormatValidator;
	}

	/**
	 * Convenience overload for callers with no client-reported last-modified
	 * time - every existing call site/test predating fallback #5 of
	 * {@link ExifDateReader}, left alone rather than touched everywhere just
	 * to pass null.
	 */
	@Transactional
	public ImportResponse importImage(Authentication authentication, MultipartFile file, boolean autoSort, boolean createSubDir,
			String subDirName, String dirPath, String groupName) {
		return importImage(authentication, file, autoSort, createSubDir, subDirName, dirPath, groupName, null);
	}

	/**
	 * @param clientLastModifiedEpochMillis the uploading browser's own
	 *        {@code File.lastModified} for this file (epoch millis), or null
	 *        if the caller doesn't have one - see {@link ExifDateReader}'s
	 *        own javadoc (fallback #5) for why this exists: a real,
	 *        previously-discarded signal found while chasing a live
	 *        production report of photos landing on the upload date instead
	 *        of their real one.
	 */
	@Transactional
	public ImportResponse importImage(Authentication authentication, MultipartFile file, boolean autoSort, boolean createSubDir,
			String subDirName, String dirPath, String groupName, Long clientLastModifiedEpochMillis) {
		String originalFilename = file.getOriginalFilename();
		if (originalFilename == null || originalFilename.isBlank()) {
			throw new IllegalArgumentException("Uploaded file has no name");
		}
		// Checked up front, before the upload is processed at all (same rule
		// as Rename - SequenceNames' own doc).
		if (createSubDir && subDirName != null && !subDirName.isBlank()) {
			SequenceNames.requireValid(normalize(subDirName));
		}

		MediaType mediaType = detectMediaType(originalFilename);
		LocalDateTime clientLastModified = clientLastModifiedEpochMillis == null
				? null
				: LocalDateTime.ofInstant(Instant.ofEpochMilli(clientLastModifiedEpochMillis), ZoneId.systemDefault());

		// Buffered to a real temp file, not read straight from the multipart
		// stream: EXIF has to be read *before* the final destination is even
		// known (auto-sort depends on it), matching how the legacy servlet
		// already had the upload as a temp file (MultipartMap) before
		// ImportSvr.doPost ever looked at its EXIF data. Video piggybacks on
		// the same buffering (needed regardless for format validation/frame
		// extraction to run against a real file, not a streaming multipart body).
		Path tempFile = createTempFile(originalFilename);
		try {
			file.transferTo(tempFile);
			validateVideoIfNeeded(tempFile, mediaType);

			// No EXIF in a video file - ExifDateReader's own generic
			// metadata-extractor read simply finds no ExifSubIFDDirectory and
			// falls through its existing chain unmodified: the WhatsApp
			// filename pattern already matches a "VID-" prefix (it was ported
			// from legacy verbatim, before this project had any video support
			// at all), and file creation time is a sensible final fallback
			// either way - verified live, not assumed.
			LocalDateTime imgDate = exifDateReader.readCaptureDate(tempFile, originalFilename, clientLastModified, null);
			if (imgDate == null) {
				imgDate = LocalDateTime.now(); // legacy's own final fallback when even EXIF/WhatsApp/client-time/file-time all come up empty
			}

			String targetPath = computeTargetPath(autoSort, createSubDir, subDirName, dirPath, imgDate);
			return storeImportedFile(authentication, originalFilename, mediaType, autoSort, imgDate, targetPath, tempFile, groupName, true);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to import " + originalFilename, e);
		} finally {
			try {
				Files.deleteIfExists(tempFile); // no-op once storeFromTempFile has moved it
			} catch (IOException ignored) {
				// best-effort cleanup only
			}
		}
	}

	/**
	 * The staging-import counterpart of {@link #importImage} (01/09/2026) -
	 * see {@link StagingImportService}'s own javadoc for the feature this
	 * serves. Always auto-sorts by EXIF date exactly like a regular Publish
	 * (same {@link ExifDateReader#readCaptureDate} call, same fallback chain)
	 * - the one difference from a regular per-file upload is
	 * {@code sequenceDefaultDate}, which a regular upload never has
	 * (always {@code null} there) but staging import can, from the form's
	 * optional year/month selector - passed straight through to
	 * {@code readCaptureDate} exactly the way directory-scan indexing already
	 * uses {@code PathDateGuesser}'s own guess for the same parameter, so an
	 * EXIF-less file falls back to it instead of "now" whenever it's given.
	 *
	 * <p>{@code sourceFile} is <b>copied</b> into the real tree (via
	 * {@link ImageStorageService#storeFromStagingFile}), not moved -
	 * corrected 01/09/2026, found live in review: an earlier version of this
	 * moved, reasoning "matches legacy, no leftover clutter" - wrong. Unlike
	 * a regular upload's own disposable temp file, a staging file typically
	 * lives on a different disk the admin deliberately keeps separate (a
	 * camera's SD card, a backup drive); silently deleting someone's own
	 * retained source photos as a side effect of importing them was never
	 * actually asked for. Left in staging either way, success or failure -
	 * cleaning it up (or not) afterward is the admin's own call, not this
	 * method's.
	 *
	 * <p>Deliberately public and called through the Spring proxy from a
	 * <i>different</i> bean ({@link StagingImportService}, never
	 * {@code this.importFromStagingFile(...)}) - this method's own
	 * {@code @Transactional} boundary only takes effect that way. Calling it
	 * from within the same class the way {@code DirectoryIndexerService
	 * .batchIndex} calls {@code indexDirectory} internally would make this
	 * annotation inert (Spring's proxy never sees a self-invocation) and run
	 * an entire staging batch inside one Hibernate session for its whole
	 * duration - the exact bottleneck already flagged as a backlog item
	 * (Design.md §12, single-threaded bulk indexing) for the *existing* recursive batch-publish;
	 * not repeating it here is deliberate, not an oversight.
	 */
	@Transactional
	public ImportResponse importFromStagingFile(Authentication authentication, Path sourceFile, String originalFilename, String sequenceName,
			LocalDateTime fallbackDate, String groupName) {
		return importFromStagingFile(authentication, sourceFile, originalFilename, sequenceName, fallbackDate, null, groupName);
	}

	/**
	 * {@code fallbackDate} (the form's month selector) is used only for a
	 * file with no date of its own - never as a ceiling on a real EXIF date
	 * (fixed 06/10/2026: it used to be passed as ExifDateReader's
	 * sequenceDefaultDate, which pulled later EXIF dates back to it).
	 * {@code forcedDate} (06/10/2026, scanned photos sorted by hand into
	 * yyyy/mm/sequence folders): when given, it IS the date - the file's own
	 * EXIF, name and file dates are ignored entirely.
	 */
	@Transactional
	public ImportResponse importFromStagingFile(Authentication authentication, Path sourceFile, String originalFilename, String sequenceName,
			LocalDateTime fallbackDate, LocalDateTime forcedDate, String groupName) {
		MediaType mediaType = detectMediaType(originalFilename);
		try {
			validateVideoIfNeeded(sourceFile, mediaType);

			LocalDateTime imgDate = forcedDate != null
					? forcedDate
					: exifDateReader.readCaptureDate(sourceFile, originalFilename, null, null, fallbackDate);
			if (imgDate == null) {
				// Same last-resort fallback as a regular Publish (no date of its
				// own, no fallback date given, no readable file creation time).
				imgDate = LocalDateTime.now();
			}

			String targetPath = computeTargetPath(true, true, sequenceName, null, imgDate);
			return storeImportedFile(authentication, originalFilename, mediaType, true, imgDate, targetPath, sourceFile, groupName, false);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to import " + originalFilename, e);
		}
	}

	private void validateVideoIfNeeded(Path file, MediaType mediaType) throws IOException {
		if (mediaType != MediaType.VIDEO) return;
		long maxBytes = appSettingsService.get().getMaxVideoSizeBytes();
		long actualBytes = Files.size(file);
		if (actualBytes > maxBytes) {
			throw new IllegalArgumentException("Video (" + actualBytes + " bytes) exceeds the configured maximum of " + maxBytes + " bytes");
		}
		// Codec-level check (not just the .mp4 extension already used to route
		// here) - see VideoFormatValidator's own javadoc for why the extension
		// alone can't tell H.264 and HEVC apart.
		videoFormatValidator.validate(file);
	}

	/**
	 * Shared by {@link #importImage} and {@link #importFromStagingFile} -
	 * everything from "does this destination directory already exist" (and
	 * the RBAC check that depends on that answer) through actually persisting
	 * the row and placing the file. {@code autoSort} only affects a
	 * brand-new directory's own {@code sequenceDate} (see {@code importImage}
	 * 's own javadoc on that distinction) - staging import always passes
	 * {@code true}, since it always auto-sorts. {@code moveSource}
	 * distinguishes a regular upload's disposable temp file (moved - nothing
	 * else has any use for it) from a staging file (copied - see
	 * {@link ImageStorageService#storeFromStagingFile}'s own javadoc for why
	 * that one must never delete the admin's own source file).
	 */
	private ImportResponse storeImportedFile(Authentication authentication, String originalFilename, MediaType mediaType, boolean autoSort,
			LocalDateTime imgDate, String targetPath, Path sourceFile, String groupName, boolean moveSource) throws IOException {
		// Checked before any persistence, and against whichever group
		// actually applies (the resolved target directory's own group if
		// it already exists, or the group this file *would* create it
		// under otherwise) - same distinction as legacy's ImportSvr
		// (createSequence for a brand-new directory, editSequence for an
		// existing one), and the same reason a LOWER-role account could
		// upload before this existed: nothing here checked at all.
		Optional<Directory> existingDirectory = directoryRepository.findByPath(targetPath);
		boolean isNewDirectory = existingDirectory.isEmpty();
		Directory directory;
		if (existingDirectory.isPresent()) {
			directory = existingDirectory.get();
			authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.EDIT_SEQUENCE);
		} else {
			ResolvedDirectory resolved = resolveOrCreateDirectory(authentication, targetPath, groupName);
			directory = resolved.directory();
			isNewDirectory = resolved.created();
		}

		if (imageRepository.existsByDirectory_IdAndName(directory.getId(), originalFilename)) {
			throw new ImageAlreadyExistsException(originalFilename, directory.getId());
		}

		directory.setSequenceDate(isNewDirectory
				? (autoSort ? imgDate.toLocalDate() : PathDateGuesser.guess(targetPath))
				: DirectoryDateRules.updateDirDate(directory.getSequenceDate(), imgDate.toLocalDate()));

		Image image = new Image(originalFilename, directory, imgDate, mediaType);
		// null for a video (no GPS tag to find via this generic image-EXIF
		// read, same as ExifDateReader's own unbranched read above) or a
		// photo with no GPS tag at all - never a placeholder like 0/0.
		ExifGpsReader.Coordinates gps = exifGpsReader.readGpsCoordinates(sourceFile);
		if (gps != null) {
			image.setLatitude(gps.latitude());
			image.setLongitude(gps.longitude());
		}
		image = imageRepository.save(image);
		// Stored only after the row is persisted (same ordering as
		// GalleryService.uploadImage) - a unique-constraint violation
		// never leaves an orphaned file behind.
		if (mediaType == MediaType.VIDEO) {
			if (moveSource) {
				storageService.storeVideoFromTempFile(targetPath, originalFilename, sourceFile);
			} else {
				storageService.storeVideoFromStagingFile(targetPath, originalFilename, sourceFile);
			}
		} else {
			if (moveSource) {
				storageService.storeFromTempFile(targetPath, originalFilename, sourceFile);
			} else {
				storageService.storeFromStagingFile(targetPath, originalFilename, sourceFile);
			}
		}

		return new ImportResponse(ImageResponse.from(image), DirectoryResponse.from(directory));
	}

	private record ResolvedDirectory(Directory directory, boolean created) {
	}

	/**
	 * The check-then-create half of {@code storeImportedFile}'s directory
	 * resolution, only reached once the fast-path plain read already came
	 * up empty - see {@code storeImportedFile}'s own comment for why that
	 * first read isn't locked (existing-directory imports, by far the
	 * common case, pay no lock overhead at all).
	 *
	 * <p>Guarded by an in-JVM {@link ReentrantLock}, one per distinct
	 * target path ({@link #directoryCreationLocks}), held from here until
	 * <b>this transaction actually commits</b> - not released the moment
	 * this method returns. That distinction is the entire point: a
	 * {@code /api/import} request runs {@code storeImportedFile} inside a
	 * single {@code @Transactional} method (EXIF/GPS reads, the file write,
	 * the {@code Image} row - still ahead of it when this method returns),
	 * and the new {@code Directory} row this method may insert stays
	 * uncommitted, invisible to any *other* connection's own plain read,
	 * until that whole method finishes. Releasing the lock any earlier
	 * would just move the exact same race to a different pair of
	 * requests - a second one could sail straight past this method's own
	 * lock, find nothing yet, and lose to the same unique-constraint
	 * violation this class exists to prevent. Registering the unlock
	 * against this transaction's own {@code afterCompletion} callback
	 * ({@link TransactionSynchronizationManager}) is what actually
	 * guarantees the next waiter's own re-read - once unblocked - is
	 * running against durably committed data, not a race against another
	 * in-flight transaction's uncommitted write.
	 *
	 * <p>Deliberately <b>not</b> a {@code REQUIRES_NEW} nested transaction
	 * on a separate connection (a first version of this fix used exactly
	 * that, via a small helper bean) - found live, not assumed, while
	 * verifying this fix: HSQLDB's lock-based transaction manager
	 * ({@code TransactionManager2PL}, confirmed via a thread dump of a
	 * genuinely hung test run) self-deadlocks a {@code REQUIRES_NEW} call
	 * made from a thread that already has an open transaction touching the
	 * same table - exactly what every {@code @Transactional} test in this
	 * project already does, and a real risk for any future caller of this
	 * method that isn't a bare top-level HTTP request either. The
	 * suspended outer transaction can never release the lock the nested
	 * one is waiting for, because the very same thread is synchronously
	 * blocked *inside* that nested call - a same-thread deadlock a DB-side
	 * timeout can't meaningfully resolve. A plain in-JVM lock in the
	 * *same* transaction/connection has no such cross-connection lock
	 * contention to deadlock on, and - unlike the {@code REQUIRES_NEW}
	 * version - never hands back a detached entity either (everything
	 * here stays in this method's own ambient persistence context, so
	 * {@code storeImportedFile}'s later {@code directory.setSequenceDate}
	 * mutation is tracked and flushed normally).
	 */
	private ResolvedDirectory resolveOrCreateDirectory(Authentication authentication, String targetPath, String groupName) {
		ReentrantLock lock = directoryCreationLocks.computeIfAbsent(targetPath, path -> new ReentrantLock());
		lock.lock();
		boolean unlockNow = true;
		try {
			// Re-read now that we hold the lock - another thread may have
			// committed (and, per this method's own javadoc, only then
			// released this same lock) the winning row while we were
			// waiting for it.
			Optional<Directory> nowExisting = directoryRepository.findByPath(targetPath);
			if (nowExisting.isPresent()) {
				Directory directory = nowExisting.get();
				authorizationService.checkAuthorization(authentication, directory.getGroup(), Action.EDIT_SEQUENCE);
				return new ResolvedDirectory(directory, false);
			}

			String targetGroupName = (groupName == null || groupName.isBlank()) ? DEFAULT_GROUP : groupName;
			authorizationService.checkAuthorization(authentication, targetGroupName, Action.CREATE_SEQUENCE);
			Directory directory = directoryRepository.save(new Directory(targetPath, findOrCreateGroup(targetGroupName), LocalDate.now()));
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					lock.unlock();
				}
			});
			unlockNow = false; // released above instead, once this transaction is actually done
			return new ResolvedDirectory(directory, true);
		} finally {
			if (unlockNow) {
				lock.unlock();
			}
		}
	}

	private Group findOrCreateGroup(String groupName) {
		return groupRepository.findById(groupName).orElseGet(() -> groupRepository.save(new Group(groupName, "", LocalDate.now())));
	}

	private static MediaType detectMediaType(String filename) {
		String extension = suffixOf(filename).replace(".", "").toLowerCase(Locale.ROOT);
		return VIDEO_EXTENSIONS.contains(extension) ? MediaType.VIDEO : MediaType.IMAGE;
	}

	private String computeTargetPath(boolean autoSort, boolean createSubDir, String subDirName, String dirPath, LocalDateTime imgDate) {
		String path;
		if (autoSort) {
			LocalDateTime now = LocalDateTime.now();
			LocalDateTime clamped = imgDate.isAfter(now) ? now : imgDate;
			path = String.format("%04d/%02d", clamped.getYear(), clamped.getMonthValue());
		} else {
			path = normalize(dirPath);
		}
		if (createSubDir && subDirName != null && !subDirName.isBlank()) {
			String segment = normalize(subDirName);
			path = path.isEmpty() ? segment : path + "/" + segment;
		}
		return path;
	}

	private Path createTempFile(String originalFilename) {
		try {
			return Files.createTempFile("mpd-import-", suffixOf(originalFilename));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String suffixOf(String filename) {
		int dot = filename.lastIndexOf('.');
		return dot >= 0 ? filename.substring(dot) : ".tmp";
	}

	private static String normalize(String path) {
		if (path == null) return "";
		String trimmed = path.trim();
		while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
		while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
		return trimmed;
	}
}
