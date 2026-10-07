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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import net.coobird.thumbnailator.Thumbnails;

/**
 * File I/O for gallery images. Thumbnailator (Design.md §3)
 * replaces the legacy hand-rolled Java2D resize+EXIF-rotation code
 * (Design.md §16.7) - it reads EXIF orientation and bakes the correction into
 * the pixels it writes, so nothing downstream needs to know an image was
 * ever rotated.
 *
 * Path resolution goes through {@link PathUtil}, exactly like the legacy
 * fix (Design.md §16.8.2): directory paths are arbitrary (Design.md decision
 * #6/#7), not just year/month/name, so the guard has to be "stays under
 * root after resolving `..`", not a fixed pattern.
 *
 * <p><b>Layout matches legacy exactly (restructured 31/08/2026)</b>:
 * {@code .thumbnails}/{@code .webimg} live <i>inside each sequence directory
 * itself</i>, as siblings of the originals they derive from - e.g.
 * {@code <root>/2026/08/vacation/.thumbnails/photo.jpg} for
 * {@code <root>/2026/08/vacation/photo.jpg}, not a separate tree mirroring
 * the whole structure elsewhere. Confirmed against legacy's real source
 * (the legacy {@code util/DirectoryIndexer.java}:
 * {@code new File(dir, Configuration.thumbDirName)}) rather than assumed -
 * an earlier version of this class centralized both into a single mirror
 * tree at the storage root instead, found live/reported as unexpected on the
 * real server. {@link #listSubdirectoryNames}'s own dot-prefix filter
 * already assumed this exact per-directory layout when it was written
 * (its own comment: "Skips the internal .thumbnails cache") - the
 * centralized version this replaces was the actual deviation from that
 * intent, not a second valid design.
 */
@Component
public class ImageStorageService {

	private static final int THUMBNAIL_SIZE = 128; // matches idx.css .focusThumb/.noFocusThumb width exactly
	// Matches legacy Configuration.webImgSize exactly (util/Configuration.java)
	// - the medium-resolution tier the main viewer displays instead of the
	// full original (Design.md §15, legacy feature-parity audit, 27/08/2026), reserving
	// the real original for explicit download/export.
	private static final int WEB_SIZE = 1600;

	// Same set of extensions the legacy indexer scans for (cfg.imgExtensions,
	// Design.md §16.7), so a directory dropped on disk outside the browser is
	// recognized the same way here as it was there. Package-private, not
	// private (01/09/2026) - StagingImportService reuses this exact allowlist
	// rather than duplicating it, to decide which staging files are import
	// candidates.
	static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp");

	// Legacy's own Configuration.thumbDirName/webImgDirName exactly (confirmed
	// by reading the legacy source, not guessed) - each resolved *inside*
	// a given sequence directory (see resolveThumbnail/resolveWeb below), not
	// under a separate root of their own the way this class used to.
	private static final String THUMBNAIL_DIR_NAME = ".thumbnails";
	private static final String WEBIMG_DIR_NAME = ".webimg";

	private final Path root;
	private final VideoThumbnailExtractor videoThumbnailExtractor;

	public ImageStorageService(StorageProperties properties, VideoThumbnailExtractor videoThumbnailExtractor) {
		this.root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
		this.videoThumbnailExtractor = videoThumbnailExtractor;
	}

	/**
	 * Creates an empty directory on disk (explicit ask, 28/08/2026 - bug
	 * report: a directory created via {@code POST /api/directories} never
	 * showed up in the navigation tree). Root cause found before fixing:
	 * {@code GalleryService.createDirectory} only ever wrote a
	 * {@code Directory} DB row, never touched the filesystem - and the
	 * navigation tree (DirectoryController) is filesystem-driven, not
	 * DB-driven, by design (Design.md decision #6/#7 - so a folder dropped
	 * directly on the server shows up before anything indexes it). A
	 * DB-only row with no real files was invisible to that tree, and
	 * silently made the same path unavailable to ever create for real
	 * later ({@link org.myphotodiary.cms.gallery.DirectoryAlreadyExistsException}).
	 * This closes that gap at the source, not just for the one caller -
	 * any directory backed by a DB row now always has a real folder too.
	 */
	public void createDirectory(String directoryPath) {
		try {
			Files.createDirectories(resolveDirectory(directoryPath));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create directory " + directoryPath, e);
		}
	}

	/**
	 * Moves an already-on-disk temp file into place and generates its
	 * thumbnail - used by the import flow (ImportService), which has to read
	 * a file's EXIF *before* it can know its final destination (the "Classer
	 * par date" auto-sort path depends on the photo's own capture date), so
	 * the upload is buffered to a temp file first rather than going straight
	 * through {@link #store}. Moving (not copying) is correct here
	 * specifically because {@code tempFile} is genuinely disposable scratch
	 * space this class itself created - nothing else has any use for it once
	 * it's in place. See {@link #storeFromStagingFile} for why that one
	 * copies instead - not the same situation.
	 */
	public void storeFromTempFile(String directoryPath, String imageName, Path tempFile) {
		placeOriginal(directoryPath, imageName, tempFile, true);
		generateDerivatives(directoryPath, imageName);
	}

	/**
	 * The staging-import counterpart of {@link #storeFromTempFile} - copies
	 * rather than moves (01/09/2026, found live in review - an earlier
	 * version of this feature moved, wrongly reasoning "matches legacy, no
	 * leftover clutter"). A staging file is nothing like a temp file: it
	 * typically sits on a different disk the admin deliberately keeps
	 * separate (a camera's own SD card, a backup drive), not disposable
	 * scratch space this class created for itself. Silently deleting
	 * someone's own retained source photos as a side effect of importing
	 * them was never actually asked for - it was an unwarranted assumption,
	 * corrected here rather than left in place.
	 */
	public void storeFromStagingFile(String directoryPath, String imageName, Path stagingFile) {
		placeOriginal(directoryPath, imageName, stagingFile, false);
		generateDerivatives(directoryPath, imageName);
	}

	private void placeOriginal(String directoryPath, String imageName, Path source, boolean move) {
		Path original = resolveOriginal(directoryPath, imageName);
		try {
			Files.createDirectories(original.getParent());
			if (move) {
				Files.move(source, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.copy(source, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to store image " + directoryPath + "/" + imageName, e);
		}
	}

	/**
	 * Moves an already-on-disk temp video file into place and generates its
	 * thumbnail from its first frame (28/08/2026, explicit ask) - the video
	 * counterpart of {@link #storeFromTempFile}, used by the same import flow
	 * (ImportService) once it detects {@link MediaType#VIDEO}. No "web"
	 * (1600px) derivative here: unlike a photo, the main viewer streams the
	 * video's own original bytes directly (Range-request streaming,
	 * VideoController) rather than displaying a resized copy - there's
	 * nothing to resize a video's pixels *to* the way there is for a still
	 * image.
	 */
	public void storeVideoFromTempFile(String directoryPath, String videoName, Path tempFile) {
		placeVideoOriginal(directoryPath, videoName, tempFile, true);
	}

	/** The staging-import counterpart of {@link #storeVideoFromTempFile} - see {@link #storeFromStagingFile}'s own javadoc for why this copies rather than moves. */
	public void storeVideoFromStagingFile(String directoryPath, String videoName, Path stagingFile) {
		placeVideoOriginal(directoryPath, videoName, stagingFile, false);
	}

	private void placeVideoOriginal(String directoryPath, String videoName, Path source, boolean move) {
		Path original = resolveOriginal(directoryPath, videoName);
		Path frame = null;
		try {
			Files.createDirectories(original.getParent());
			if (move) {
				Files.move(source, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.copy(source, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			frame = videoThumbnailExtractor.extractFirstFrame(original);
			// resolveVideoThumbnail, not resolveThumbnail - a video's
			// thumbnail path needs its own `.jpg` extension regardless of
			// the video's own name (`clip.mp4` -> `clip.mp4.jpg`), unlike a
			// photo thumbnail's path, which has always mirrored the
			// original's own (already-image) extension exactly. Found live
			// while testing: reusing resolveThumbnail verbatim here produces
			// a thumbnail *path* ending in `.mp4`, and Thumbnailator infers
			// its *output format* from that destination extension when none
			// is given explicitly - "No suitable ImageWriter found for mp4".
			Path thumbnail = resolveVideoThumbnail(directoryPath, videoName);
			Files.createDirectories(thumbnail.getParent());
			writeThumbnail(frame, thumbnail);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to store video " + directoryPath + "/" + videoName, e);
		} finally {
			if (frame != null) {
				try {
					Files.deleteIfExists(frame);
				} catch (IOException ignored) {
					// best-effort cleanup only, same spirit as ImportService's own temp-file cleanup
				}
			}
		}
	}

	public void store(String directoryPath, String imageName, MultipartFile file) {
		Path original = resolveOriginal(directoryPath, imageName);
		try {
			Files.createDirectories(original.getParent());
			file.transferTo(original);
			generateDerivatives(directoryPath, imageName);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to store image " + directoryPath + "/" + imageName, e);
		}
	}

	public InputStream readOriginal(String directoryPath, String imageName) {
		try {
			return Files.newInputStream(resolveOriginal(directoryPath, imageName));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Real byte size of the original file (10/09/2026, progressive image
	 * loading - GalleryController sets this as the response's Content-Length
	 * so the frontend can show real download progress on a slow connection,
	 * not just a spinner). A second {@code Files.size} call rather than
	 * returning size+stream together from one resolution - keeps every
	 * existing {@code read*} signature/caller (including tests) untouched;
	 * the tiny race this opens (the file changing between this call and the
	 * paired {@code read*} call) is the same one every other two-step
	 * resolve-then-read in this class already accepts, and a picture's own
	 * file is never edited in place (an edit replaces the row, not the file
	 * - GalleryController's own {@code serve} comment).
	 */
	public long sizeOfOriginal(String directoryPath, String imageName) {
		try {
			return Files.size(resolveOriginal(directoryPath, imageName));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Falls back to {@link #readWeb} (which itself falls back to the full
	 * original) when the pre-generated 128px file is missing, rather than a
	 * hard failure - found live (02/09/2026): a real dev database has rows
	 * indexed before the 31/08/2026 co-located-derivative restructure, whose
	 * actual thumbnail bytes still sit at the *old* mirror-tree path this
	 * class no longer looks at, so {@code resolveThumbnail} legitimately
	 * finds nothing there until that directory is re-indexed. Before this
	 * fallback, every such thumbnail request threw straight through to a
	 * bare 500 (GalleryApiExceptionHandler's own {@code UncheckedIOException}
	 * handler swallows the stack trace into a one-line JSON body, easy to
	 * miss in a log) and {@code AuthImage}'s own graceful-degradation-on-
	 * fetch-failure left the filmstrip slot as a plain empty placeholder
	 * forever - not a broken-image icon, so "the thumbnails just aren't
	 * visible" is exactly what it looked like from the UI, not an obvious
	 * network error. This doesn't replace re-indexing (Admin -> Index
	 * management, or Batch Publish) as the real fix - that's still what
	 * regenerates the actual 128px file at its new expected path
	 * (DirectoryIndexerService.indexDirectory's own comment: a re-index
	 * already regenerates every derivative unconditionally, precisely for
	 * this) - this only keeps a stale row from rendering as invisible in
	 * the meantime.
	 */
	public InputStream readThumbnail(String directoryPath, String imageName) {
		try {
			return Files.newInputStream(resolveExistingThumbnail(directoryPath, imageName));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Size counterpart of {@link #readThumbnail} - see {@link #sizeOfOriginal}'s own comment on why this is a second resolution rather than one combined stream+size call. */
	public long sizeOfThumbnail(String directoryPath, String imageName) {
		try {
			return Files.size(resolveExistingThumbnail(directoryPath, imageName));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Resolves to whichever tier {@link #readThumbnail} would actually read
	 * from (the true 128px file, or its own fallback chain) - extracted so
	 * {@link #readThumbnail} and {@link #sizeOfThumbnail} share the exact
	 * same fallback decision instead of it being duplicated (and possibly
	 * drifting) between the two.
	 */
	private Path resolveExistingThumbnail(String directoryPath, String imageName) {
		Path thumbnail = resolveThumbnail(directoryPath, imageName);
		return Files.exists(thumbnail) ? thumbnail : resolveExistingWeb(directoryPath, imageName);
	}

	/** The video counterpart of {@link #readThumbnail} - see resolveVideoThumbnail's own comment for why videos need a distinct path scheme. */
	public InputStream readVideoThumbnail(String directoryPath, String videoName) {
		try {
			return Files.newInputStream(resolveVideoThumbnail(directoryPath, videoName));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Size counterpart of {@link #readVideoThumbnail} - see {@link #sizeOfOriginal}'s own comment on why this is a second resolution rather than one combined stream+size call. */
	public long sizeOfVideoThumbnail(String directoryPath, String videoName) {
		try {
			return Files.size(resolveVideoThumbnail(directoryPath, videoName));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The medium-resolution "web" tier (legacy Configuration.webImgSize,
	 * 1600px) the main viewer displays instead of the full original. Falls
	 * back to the original itself if no web-size variant exists yet - images
	 * indexed before this tier existed, or any file that predates a given
	 * rescan - rather than a broken image.
	 */
	public InputStream readWeb(String directoryPath, String imageName) {
		try {
			return Files.newInputStream(resolveExistingWeb(directoryPath, imageName));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Size counterpart of {@link #readWeb} - see {@link #sizeOfOriginal}'s own comment on why this is a second resolution rather than one combined stream+size call. */
	public long sizeOfWeb(String directoryPath, String imageName) {
		try {
			return Files.size(resolveExistingWeb(directoryPath, imageName));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Resolves to whichever tier {@link #readWeb} would actually read from
	 * (the true "web" 1600px file, or the original as its own fallback) -
	 * extracted so {@link #readWeb}, {@link #sizeOfWeb}, and
	 * {@link #resolveExistingThumbnail}'s own further fallback all share the
	 * exact same decision.
	 */
	private Path resolveExistingWeb(String directoryPath, String imageName) {
		Path web = resolveWeb(directoryPath, imageName);
		return Files.exists(web) ? web : resolveOriginal(directoryPath, imageName);
	}

	/**
	 * Rotates the original, web, and thumbnail derivatives 90 degrees
	 * clockwise, in place, on disk - all three tiers together (explicit ask,
	 * 02/09/2026: "applied on the master image, the web image and the
	 * thumbnail"), so none of the three silently goes stale relative to the
	 * others. No DB column to update afterward - this project has never
	 * tracked width/height/orientation for an {@code Image} row
	 * (Thumbnailator already bakes EXIF orientation into pixels at import
	 * time, this class's own class-level doc - a user-triggered rotation is
	 * the same principle applied by a click instead of an EXIF tag), so this
	 * is purely a filesystem operation, consistent with the "no DB schema
	 * change" constraint this feature was built under.
	 *
	 * The web tier is skipped rather than failing if it doesn't exist yet
	 * (an image indexed before that tier existed - {@link #readWeb}'s own
	 * fallback-to-original comment covers the same case) - nothing to
	 * rotate there, and the fallback will keep serving the (now-rotated)
	 * original until a future re-index regenerates it.
	 */
	public void rotateImage(String directoryPath, String imageName) {
		rotateImage(directoryPath, imageName, 1);
	}

	/** {@link #rotateImage(String, String)} by {@code 90 * quarterTurns} degrees, one re-encode per file. */
	public void rotateImage(String directoryPath, String imageName, int quarterTurns) {
		double angle = 90.0 * quarterTurns;
		rotateInPlace(resolveOriginal(directoryPath, imageName), 1.0, angle);
		rotateInPlace(resolveWeb(directoryPath, imageName), 0.85, angle);
		rotateInPlace(resolveThumbnail(directoryPath, imageName), 0.85, angle);
	}

	/**
	 * Read-only preview of a 90-degree clockwise rotation - returns the
	 * true original rotated, as raw bytes, <b>without writing anything to
	 * disk</b> (25/09/2026, explicit ask: Crop/Rotate brought onto the same
	 * preview/Save/Cancel workflow {@link #previewTransform} already has -
	 * see {@code ImageEditorPopup.jsx}'s own doc on that shared state
	 * machine). Only the original-quality tier is rendered here - unlike
	 * {@link #rotateImage} itself, which also rewrites the web/thumbnail
	 * tiers, those only matter once an edit actually commits ({@link
	 * #generateDerivatives}, called by the real commit path), never for a
	 * preview nobody has agreed to keep yet. Same explicit-format reasoning
	 * as {@link #previewTransform}'s own doc ({@link #formatFor}).
	 */
	public byte[] previewRotate(String directoryPath, String imageName) {
		return previewRotate(directoryPath, imageName, 1);
	}

	/** {@link #previewRotate(String, String)} by {@code 90 * quarterTurns} degrees. */
	public byte[] previewRotate(String directoryPath, String imageName, int quarterTurns) {
		Path file = resolveOriginal(directoryPath, imageName);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			Thumbnails.of(file.toFile())
					.scale(1.0)
					.rotate(90.0 * quarterTurns)
					.outputFormat(formatFor(imageName))
					.outputQuality(1.0)
					.toOutputStream(buffer);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to render rotate preview for " + directoryPath + "/" + imageName, e);
		}
		return buffer.toByteArray();
	}

	/**
	 * Rewrites {@code file} to a sibling temp path first, then atomically
	 * moves it over the original - never rotates by reading and writing the
	 * same path in one Thumbnailator call, which would risk a corrupted
	 * half-written file if the process were interrupted mid-write (disk
	 * full, a crash) with no original left to fall back to. The temp
	 * filename keeps the real extension (`.rotating-<name>`, not
	 * `<name>.rotating`) specifically so Thumbnailator's own destination-
	 * extension format inference (the same mechanism {@link #writeThumbnail}
	 * relies on) still resolves to the right output format.
	 */
	private void rotateInPlace(Path file, double quality, double angle) {
		if (!Files.exists(file)) return;
		Path temp = uniqueTempSibling(file, "rotating");
		try {
			Thumbnails.of(file.toFile())
					.scale(1.0)
					.rotate(angle)
					.outputQuality(quality)
					.toFile(temp.toFile());
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			deleteQuietly(temp);
			throw new UncheckedIOException("Failed to rotate " + file, e);
		}
	}

	/**
	 * Crops the true original in place, then regenerates the web/thumbnail
	 * tiers from the newly-cropped original via {@link #generateDerivatives}
	 * (explicit ask, 25/09/2026 - "Crop" half of a rudimentary image
	 * editor). Deliberately <b>not</b> the same per-tier approach as
	 * {@link #rotateImage}: a 90-degree turn is resolution-independent (the
	 * same operation regardless of a file's own pixel size), but a crop
	 * rectangle is expressed in the true original's own pixel coordinates
	 * and has no well-defined equivalent on the already-downscaled web/
	 * thumbnail tiers without re-deriving separately-rounded rectangles
	 * three times - cropping the original once and re-deriving the other
	 * two from it (the same pipeline {@link #store}/{@link #generateThumbnail}
	 * already use) keeps all three pixel-consistent by construction instead.
	 *
	 * <p>Destructive and irreversible by design (confirmed explicitly,
	 * 25/09/2026): overwrites the original in place exactly like
	 * {@link #rotateImage} already does, relying on the existing nightly
	 * image-tree backup for recovery rather than a bespoke pre-edit copy.
	 */
	public void cropImage(String directoryPath, String imageName, int x, int y, int width, int height) {
		cropInPlace(resolveOriginal(directoryPath, imageName), x, y, width, height);
		generateDerivatives(directoryPath, imageName);
	}

	/**
	 * Read-only preview of a crop - the given rectangle applied to the true
	 * original, as raw bytes, <b>without writing anything to disk</b>
	 * (25/09/2026, explicit ask - same preview/Save/Cancel workflow as
	 * {@link #previewRotate}/{@link #previewTransform}, see those methods'
	 * own doc for why). Shares {@link #readAndCrop} with {@link
	 * #cropInPlace} - the exact same decode/bounds-check/subimage a commit
	 * would perform, so the preview is a true WYSIWYG of what "Save" would
	 * actually produce, not a separately-computed approximation.
	 */
	public byte[] previewCrop(String directoryPath, String imageName, int x, int y, int width, int height) {
		Path file = resolveOriginal(directoryPath, imageName);
		BufferedImage cropped = readAndCrop(file, x, y, width, height);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			Thumbnails.of(cropped)
					.scale(1.0)
					.outputFormat(formatFor(imageName))
					.outputQuality(1.0)
					.toOutputStream(buffer);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to render crop preview for " + directoryPath + "/" + imageName, e);
		}
		return buffer.toByteArray();
	}

	/**
	 * Same atomic-write discipline as {@link #rotateInPlace}: decode, crop,
	 * write to a sibling temp file, only then move it over the real path -
	 * never leaves a half-written original on a mid-write failure. The
	 * decode/bounds-check/subimage itself is {@link #readAndCrop}, shared
	 * with {@link #previewCrop}.
	 */
	private void cropInPlace(Path file, int x, int y, int width, int height) {
		BufferedImage cropped = readAndCrop(file, x, y, width, height);
		Path temp = uniqueTempSibling(file, "cropping");
		try {
			Thumbnails.of(cropped)
					.scale(1.0)
					.outputQuality(1.0)
					.toFile(temp.toFile());
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			deleteQuietly(temp);
			throw new UncheckedIOException("Failed to crop " + file, e);
		}
	}

	/**
	 * Decodes {@code file} and returns the {@code x,y,width,height}
	 * sub-image of it - shared by {@link #cropInPlace} (the real commit)
	 * and {@link #previewCrop} (its read-only preview), so both always
	 * apply the exact same bounds-checking and pixel selection. Bounds are
	 * checked here, against the original's real decoded dimensions (the
	 * only place they're actually known - {@link
	 * org.myphotodiary.cms.gallery.dto.CropImageRequest}'s own doc explains
	 * why this can't be validated any earlier), before
	 * {@link java.awt.image.BufferedImage#getSubimage} would otherwise
	 * throw its own less-helpful {@code RasterFormatException}.
	 */
	private static BufferedImage readAndCrop(Path file, int x, int y, int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Crop width/height must be positive: " + width + "x" + height);
		}
		BufferedImage source;
		try {
			source = ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read " + file + " for crop", e);
		}
		if (source == null) {
			throw new UncheckedIOException(new IOException("Unreadable image: " + file));
		}
		if (x < 0 || y < 0 || x + width > source.getWidth() || y + height > source.getHeight()) {
			throw new IllegalArgumentException("Crop rectangle (" + x + "," + y + "," + width + "," + height
					+ ") is out of bounds for a " + source.getWidth() + "x" + source.getHeight() + " image");
		}
		return source.getSubimage(x, y, width, height);
	}

	/**
	 * Computes the perspective-corrected warp of the true original (the
	 * given quad mapped onto a {@code destWidth}x{@code destHeight}
	 * rectangle, {@link PerspectiveTransform}'s own doc for the actual
	 * math) and returns the resulting bytes <b>without writing anything to
	 * disk</b> - the "Transform" button's own read-only preview (25/09/2026,
	 * ImageEditorPopup.jsx's own doc on the preview/Save workflow this
	 * backs: nothing is ever persisted until Save explicitly calls
	 * {@link #transformImage}, so there's no draft file to ever orphan or
	 * clean up either). Re-encoded in the original's own format from its
	 * own file extension - Thumbnailator can't infer a format from a plain
	 * {@link java.io.OutputStream} the way {@link #cropInPlace}/{@link
	 * #rotateInPlace} let it infer one from a destination File's own
	 * extension, so it has to be given explicitly here ({@link
	 * #formatFor}).
	 */
	public byte[] previewTransform(String directoryPath, String imageName, int destWidth, int destHeight, double[] quadX, double[] quadY) {
		BufferedImage warped = warp(resolveOriginal(directoryPath, imageName), destWidth, destHeight, quadX, quadY);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			Thumbnails.of(warped)
					.scale(1.0)
					.outputFormat(formatFor(imageName))
					.outputQuality(1.0)
					.toOutputStream(buffer);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to render transform preview for " + directoryPath + "/" + imageName, e);
		}
		return buffer.toByteArray();
	}

	/**
	 * Same warp as {@link #previewTransform}, but committed: writes the
	 * result over the true original in place (same atomic temp-file-then-
	 * move discipline as {@link #cropInPlace}/{@link #rotateInPlace}) and
	 * regenerates the web/thumbnail tiers from it, exactly like {@link
	 * #cropImage} - see that method's own doc for why re-deriving rather
	 * than warping each tier separately keeps all three pixel-consistent.
	 * Destructive/irreversible by design, same overwrite-in-place policy
	 * already established for Crop/Rotate (GalleryService's own doc) - the
	 * Save/Cancel preview workflow this sits behind is what makes that an
	 * acceptable trade-off here too, not a schema/backup change.
	 */
	public void transformImage(String directoryPath, String imageName, int destWidth, int destHeight, double[] quadX, double[] quadY) {
		Path original = resolveOriginal(directoryPath, imageName);
		BufferedImage warped = warp(original, destWidth, destHeight, quadX, quadY);
		Path temp = uniqueTempSibling(original, "transforming");
		try {
			Thumbnails.of(warped)
					.scale(1.0)
					.outputQuality(1.0)
					.toFile(temp.toFile());
			Files.move(temp, original, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			deleteQuietly(temp);
			throw new UncheckedIOException("Failed to transform " + original, e);
		}
		generateDerivatives(directoryPath, imageName);
	}

	/**
	 * Decodes {@code sourceFile}, then rasterizes the {@code destWidth}x
	 * {@code destHeight} output pixel by pixel: for each output pixel,
	 * {@link PerspectiveTransform} gives exactly where in the source image
	 * it came from (an inverse mapping, output-to-source - the direction a
	 * rasterizer actually needs, never the other way around), then {@link
	 * #bilinearSample} reads the real color there. Unlike {@link
	 * #cropInPlace} (a pure region copy, no resampling at all - confirmed
	 * empirically, not just by reading {@link java.awt.image.BufferedImage
	 * #getSubimage}'s own contract), a perspective warp is fundamentally
	 * non-pixel-aligned - every output pixel needs real interpolation,
	 * there's no way around it.
	 *
	 * <p>Source pixels are fetched once into a plain {@code int[]} up front
	 * ({@code BufferedImage.getRGB(...)} bulk form) rather than sampled via
	 * millions of individual {@code getRGB(x, y)} calls - real per-call
	 * overhead at multi-megapixel scale. The row loop itself runs in
	 * parallel ({@link IntStream#parallel}) - each output row only ever
	 * reads the shared, never-mutated source array and writes to its own
	 * disjoint slice of the destination array, so there's no shared
	 * mutable state to guard.
	 */
	private static BufferedImage warp(Path sourceFile, int destWidth, int destHeight, double[] quadX, double[] quadY) {
		if (destWidth <= 0 || destHeight <= 0) {
			throw new IllegalArgumentException("Transform destination width/height must be positive: " + destWidth + "x" + destHeight);
		}
		BufferedImage source;
		try {
			source = ImageIO.read(sourceFile.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read " + sourceFile + " for transform", e);
		}
		if (source == null) {
			throw new UncheckedIOException(new IOException("Unreadable image: " + sourceFile));
		}
		int srcWidth = source.getWidth();
		int srcHeight = source.getHeight();
		int[] srcPixels = source.getRGB(0, 0, srcWidth, srcHeight, null, 0, srcWidth);

		// Constructed (and validated for an in-bounds singularity, its own
		// doc) once, outside the pixel loop - every one of the destWidth *
		// destHeight calls below reuses the same 8 coefficients.
		PerspectiveTransform transform = PerspectiveTransform.rectToQuad(destWidth, destHeight, quadX, quadY);

		int[] destPixels = new int[destWidth * destHeight];
		IntStream.range(0, destHeight).parallel().forEach(py -> {
			int rowOffset = py * destWidth;
			for (int px = 0; px < destWidth; px++) {
				// Pixel *centers* (+0.5), not corners - the standard
				// resampling convention, avoids biasing the last row/column
				// toward the source's own far edge.
				double[] mapped = transform.apply(px + 0.5, py + 0.5);
				destPixels[rowOffset + px] = bilinearSample(srcPixels, srcWidth, srcHeight, mapped[0], mapped[1]);
			}
		});

		BufferedImage output = new BufferedImage(destWidth, destHeight, BufferedImage.TYPE_INT_ARGB);
		output.setRGB(0, 0, destWidth, destHeight, destPixels, 0, destWidth);
		return output;
	}

	/**
	 * Standard 4-neighbor bilinear interpolation on a packed-ARGB pixel
	 * array (the format {@code BufferedImage.getRGB(...)}'s bulk form
	 * always returns, regardless of the source image's own underlying
	 * raster type - confirmed against the JDK's own documented contract,
	 * not assumed).
	 *
	 * <p>{@code x}/{@code y} arrive in the same "pixel-corner" coordinate
	 * space every absolute pixel coordinate already uses throughout this
	 * project (0 = the image's own left/top edge, {@code width} = its
	 * right edge - the same convention {@link CropImageRequest}'s own
	 * x/y/width/height and the quad corners themselves are given in,
	 * matching {@code BufferedImage#getSubimage}'s own contract), <b>not</b>
	 * the "pixel index IS the sample point" convention a naive
	 * {@code floor(x)} would assume - the {@code - 0.5} below re-centers
	 * onto pixel index space (pixel {@code i} occupies the continuous
	 * span {@code [i, i+1)}, its own center sits at {@code i + 0.5}) before
	 * flooring. Found and fixed before ever running against a real image
	 * (worked through by hand, not by a failing test): without this
	 * adjustment, sampling at exactly a pixel's own center - as {@link
	 * #warp}'s own {@code px + 0.5} does for every output pixel - would
	 * land exactly *between* two source pixels instead of on the one it's
	 * meant to represent, so even an identity transform (quad == the
	 * destination rectangle) would uniformly blur the image by averaging
	 * each 2x2 neighborhood instead of reproducing it - confirmed by
	 * {@code GalleryServiceTest.transformImage_identityQuadReproducesTheOriginalAtTheSameSize}'s
	 * own real-pixel comparison, not just this reasoning.
	 *
	 * <p>Clamped to the source's own bounds ("clamp to edge") rather than
	 * left to go out of range - a corner handle dragged so the mapped
	 * point falls just outside the true original shouldn't produce a
	 * jagged hole of black/transparent pixels at the boundary.
	 */
	private static int bilinearSample(int[] pixels, int width, int height, double x, double y) {
		double xc = clampDouble(x - 0.5, 0, width - 1);
		double yc = clampDouble(y - 0.5, 0, height - 1);
		int x0 = (int) Math.floor(xc);
		int y0 = (int) Math.floor(yc);
		int x1 = Math.min(x0 + 1, width - 1);
		int y1 = Math.min(y0 + 1, height - 1);
		double fx = xc - x0;
		double fy = yc - y0;

		int c00 = pixels[y0 * width + x0];
		int c10 = pixels[y0 * width + x1];
		int c01 = pixels[y1 * width + x0];
		int c11 = pixels[y1 * width + x1];

		int alpha = (int) Math.round(bilerpChannel((c00 >> 24) & 0xFF, (c10 >> 24) & 0xFF, (c01 >> 24) & 0xFF, (c11 >> 24) & 0xFF, fx, fy));
		int red = (int) Math.round(bilerpChannel((c00 >> 16) & 0xFF, (c10 >> 16) & 0xFF, (c01 >> 16) & 0xFF, (c11 >> 16) & 0xFF, fx, fy));
		int green = (int) Math.round(bilerpChannel((c00 >> 8) & 0xFF, (c10 >> 8) & 0xFF, (c01 >> 8) & 0xFF, (c11 >> 8) & 0xFF, fx, fy));
		int blue = (int) Math.round(bilerpChannel(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, fx, fy));
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static double bilerpChannel(int v00, int v10, int v01, int v11, double fx, double fy) {
		double top = v00 + (v10 - v00) * fx;
		double bottom = v01 + (v11 - v01) * fx;
		return top + (bottom - top) * fy;
	}

	private static double clampDouble(double value, double min, double max) {
		return Math.min(Math.max(value, min), max);
	}

	/** The output format Thumbnailator needs explicitly when writing to a plain {@link java.io.OutputStream} (no destination filename to infer one from, unlike {@link #cropInPlace}/{@link #rotateInPlace}'s own {@code toFile(...)} calls) - the original's own real extension, defaulting to "jpg" only for the (already-impossible in practice, IMAGE_EXTENSIONS always matches first) case of a name with none. */
	private static String formatFor(String imageName) {
		int dot = imageName.lastIndexOf('.');
		return dot >= 0 ? imageName.substring(dot + 1).toLowerCase(Locale.ROOT) : "jpg";
	}

	public void delete(String directoryPath, String imageName) {
		deleteQuietly(resolveOriginal(directoryPath, imageName));
		deleteQuietly(resolveThumbnail(directoryPath, imageName));
		deleteQuietly(resolveWeb(directoryPath, imageName));
		// Unconditional, not branched on media type: this caller (GalleryService.
		// deleteImage) doesn't carry MediaType down to here, and there's no need
		// for it to - deleteQuietly is deleteIfExists, a no-op for whichever of
		// resolveThumbnail/resolveVideoThumbnail's path this particular image
		// never had in the first place.
		deleteQuietly(resolveVideoThumbnail(directoryPath, imageName));
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * A sibling temp path for the atomic write-then-move discipline shared by
	 * {@link #rotateInPlace}/{@link #cropInPlace}/{@link #transformImage} -
	 * {@code prefix} plus a random UUID plus the real filename (extension
	 * kept last, same reason {@link #rotateInPlace}'s own doc already gives:
	 * Thumbnailator infers output format from a destination File's
	 * extension). Found and fixed 25/09/2026, root-caused via a real
	 * concurrent-threads reproduction (not guessed): the three callers used
	 * to derive this path from the image's own name ALONE (e.g.
	 * {@code ".transforming-<name>"}), with no per-request uniqueness at
	 * all - two overlapping edit-commit requests on the SAME image (a
	 * double-clicked Save, a slow-network browser retry, two tabs) would
	 * both write to that identical OS path concurrently, then race each
	 * other's {@code Files.move} over it. Confirmed directly: a raw-thread
	 * (no Spring/DB) reproduction against the real production code, N
	 * threads all committing an edit to the same file at once, reliably
	 * threw {@code NoSuchFileException} moving the shared temp path out
	 * from under a thread that had already had it moved away by another -
	 * the reported "sometimes corrupts part of the image" is consistent
	 * with the same underlying unsynchronized-concurrent-write-to-one-path
	 * mechanism landing less cleanly (a still-valid-looking but partially
	 * overwritten JPEG) rather than a clean exception, depending on
	 * filesystem/timing. A random UUID per call makes every concurrent
	 * writer's own temp path unique, so they can never collide on the same
	 * path in the first place - the atomic {@code Files.move} onto the real
	 * destination already made the *destination* side safe; this makes the
	 * *source* side of that move safe too.
	 */
	private static Path uniqueTempSibling(Path file, String prefix) {
		return file.resolveSibling("." + prefix + "-" + UUID.randomUUID() + "-" + file.getFileName());
	}

	/**
	 * Generates the thumbnail (128px) and web (1600px) tiers for a file
	 * that's already on disk (a photo dropped directly into storage, not
	 * uploaded through the browser - Design.md decision #6/#7) rather than
	 * one just received via {@link #store}. Used by directory indexing (the
	 * new-stack DirectoryIndexer, Design.md §16.7). Named `generateThumbnail`
	 * historically; kept as an alias since callers only ever cared about
	 * "regenerate whatever derived images this file needs".
	 */
	public void generateThumbnail(String directoryPath, String imageName) {
		generateDerivatives(directoryPath, imageName);
	}

	private void generateDerivatives(String directoryPath, String imageName) {
		Path original = resolveOriginal(directoryPath, imageName);
		Path thumbnail = resolveThumbnail(directoryPath, imageName);
		Path web = resolveWeb(directoryPath, imageName);
		try {
			Files.createDirectories(thumbnail.getParent());
			Files.createDirectories(web.getParent());
			writeThumbnail(original, thumbnail);
			Thumbnails.of(original.toFile())
					.size(WEB_SIZE, WEB_SIZE)
					.keepAspectRatio(true)
					// Correction (02/09/2026, found empirically by a test, not
					// assumed): plain .size(w, h) *does* upscale a source
					// smaller than the target to fit that bounding box - this
					// comment previously claimed the opposite. Never actually
					// observed in practice since every real photo already
					// exceeds WEB_SIZE, but a test fixture (400x300) exposed
					// the true behavior - corrected here rather than left
					// misleading the next reader.
					.outputQuality(0.85)
					.toFile(web.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to generate derived images for " + directoryPath + "/" + imageName, e);
		}
	}

	/**
	 * The one Thumbnailator call shared by every thumbnail this class
	 * produces - a photo's own original (generateDerivatives) or a video's
	 * first extracted frame (storeVideoFromTempFile). The filmstrip
	 * (Filmstrip.jsx) reads the result identically either way. No explicit
	 * {@code .outputFormat(...)}: Thumbnailator infers it from the
	 * destination path's own extension, which for a photo has always been a
	 * real image extension (mirrors the original's own name) - and, since
	 * video's own thumbnail path is deliberately given a `.jpg` extension
	 * of its own (see resolveVideoThumbnail's comment), that inference lands
	 * on "jpg" there too, without needing to force it.
	 */
	private void writeThumbnail(Path sourceImage, Path thumbnailOutput) throws IOException {
		Thumbnails.of(sourceImage.toFile())
				.size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
				.keepAspectRatio(true)
				.outputQuality(0.85)
				.toFile(thumbnailOutput.toFile());
	}

	/** Last-modified time of an original file - the fallback capture date used when there's no EXIF extraction (see DirectoryIndexerService). */
	public Instant lastModified(String directoryPath, String imageName) {
		try {
			return Files.getLastModifiedTime(resolveOriginal(directoryPath, imageName)).toInstant();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Image-extension file names directly inside a directory on disk (not recursive), sorted - empty list if the directory doesn't exist. */
	public List<String> listImageFileNames(String directoryPath) {
		Path dir = resolveDirectory(directoryPath);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> entries = Files.list(dir)) {
			return entries
					.filter(Files::isRegularFile)
					.map(p -> p.getFileName().toString())
					.filter(ImageStorageService::hasImageExtension)
					.sorted()
					.collect(Collectors.toList());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Immediate subdirectory names on disk (not recursive), sorted - empty list if the directory doesn't exist. Skips the internal .thumbnails/.webimg caches, and any other dot-prefixed entry generically - not name-specific. */
	public List<String> listSubdirectoryNames(String directoryPath) {
		Path dir = resolveDirectory(directoryPath);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> entries = Files.list(dir)) {
			return entries
					.filter(Files::isDirectory)
					.map(p -> p.getFileName().toString())
					.filter(name -> !name.startsWith("."))
					.sorted()
					.collect(Collectors.toList());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public boolean directoryExists(String directoryPath) {
		return Files.isDirectory(resolveDirectory(directoryPath));
	}

	/**
	 * Moves a directory to a new path, the filesystem side of a sequence
	 * rename (legacy DirDataSvr.doPost - renames on disk, then the caller
	 * cascades the path change to every affected row in the DB - see
	 * GalleryService.renameDirectory). {@code .thumbnails}/{@code .webimg}
	 * need no separate handling (unlike before the 31/08/2026 restructure,
	 * when they lived in a mirror tree elsewhere and had to be moved in
	 * parallel via a now-removed moveMirrorDirIfPresent) - they're children
	 * of {@code oldDir} now, so the single {@code Files.move} below already
	 * carries them along with everything else in the subtree, descendant
	 * sequences (and their own derivative caches) included.
	 */
	/**
	 * Deletes the folder at {@code directoryPath} only if it exists and holds
	 * nothing at all - no file, no sub-folder, hidden ones included (05/10/2026,
	 * the month/year folder a sequence was moved out of). Returns whether it
	 * was deleted; never touches anything that isn't empty.
	 */
	public boolean deleteDirectoryIfEmpty(String directoryPath) {
		Path dir = resolveDirectory(directoryPath);
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (var entries = Files.list(dir)) {
			if (entries.findAny().isPresent()) {
				return false;
			}
			Files.delete(dir);
			return true;
		} catch (IOException e) {
			return false; // best effort - an empty folder left behind is harmless
		}
	}

	public void renameDirectory(String oldPath, String newPath) {
		Path oldDir = resolveDirectory(oldPath);
		Path newDir = resolveDirectory(newPath);
		if (Files.exists(newDir)) {
			throw new IllegalStateException("Cannot rename: target already exists: " + newPath);
		}
		try {
			Files.createDirectories(newDir.getParent());
			Files.move(oldDir, newDir);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to rename directory " + oldPath + " to " + newPath, e);
		}
	}

	/**
	 * Recursively deletes a directory's files - the originals and their
	 * {@code .thumbnails}/{@code .webimg} derivatives. Used only by the
	 * explicit "delete index and images" admin command
	 * (DirectoryIndexerService.deleteDirectoryIndexAndFiles) - unlike every
	 * other deletion path in this class, this one is irreversible on the
	 * filesystem itself, not just the DB index. A single recursive delete of
	 * {@code resolveDirectory(directoryPath)} is enough (unlike before the
	 * 31/08/2026 restructure, when the mirror trees needed their own explicit
	 * delete calls) - {@code .thumbnails}/{@code .webimg} are children of
	 * this same directory now, so they're walked and removed along with
	 * everything else in it.
	 */
	public void deleteDirectoryFiles(String directoryPath) {
		deleteRecursively(resolveDirectory(directoryPath));
	}

	private void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted((a, b) -> b.compareTo(a)) // children before their parent
					.forEach(p -> {
						try {
							Files.delete(p);
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// Package-private, not private (01/09/2026) - reused by
	// StagingImportService for the same reason as IMAGE_EXTENSIONS above.
	static boolean hasImageExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		if (dot < 0) return false;
		return IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private Path resolveDirectory(String directoryPath) {
		return PathUtil.resolveUnderRoot(root, directoryPath, "");
	}

	/** Package-visible for ExifDateReader callers (DirectoryIndexerService/ImportService) that need the real file, not just a stream. */
	Path resolveOriginal(String directoryPath, String imageName) {
		return PathUtil.resolveUnderRoot(root, directoryPath, imageName);
	}

	/**
	 * {@code <root>/<directoryPath>/.thumbnails/<imageName>} - co-located
	 * inside the sequence directory itself (31/08/2026 restructure, matches
	 * legacy exactly), not a separate mirror tree elsewhere. Still routed
	 * through {@link PathUtil#resolveUnderRoot} for the same traversal
	 * guard as {@link #resolveOriginal}/{@link #resolveDirectory} - the
	 * combined {@code ".thumbnails/" + imageName} is resolved and
	 * normalized as one candidate, so a hostile {@code imageName} can't
	 * escape any more than it could before.
	 */
	private Path resolveThumbnail(String directoryPath, String imageName) {
		return PathUtil.resolveUnderRoot(root, directoryPath, THUMBNAIL_DIR_NAME + "/" + imageName);
	}

	/**
	 * A video's thumbnail path is `<video's own name>.jpg` (appended, not
	 * mirrored verbatim like a photo's own thumbnail path) - found live
	 * (28/08/2026) while testing video: without a distinct, guaranteed-`.jpg`
	 * path, {@link #writeThumbnail} would infer its Thumbnailator output
	 * format from the destination filename's own extension, which for a
	 * video thumbnail would otherwise still be e.g. `.mp4` (mirroring the
	 * video's own name the way a photo thumbnail mirrors its own real image
	 * extension) - not a format Thumbnailator can write at all.
	 */
	private Path resolveVideoThumbnail(String directoryPath, String videoName) {
		return PathUtil.resolveUnderRoot(root, directoryPath, THUMBNAIL_DIR_NAME + "/" + videoName + ".jpg");
	}

	/** {@code <root>/<directoryPath>/.webimg/<imageName>} - see {@link #resolveThumbnail}'s own comment for the layout/traversal-guard reasoning, identical here. */
	private Path resolveWeb(String directoryPath, String imageName) {
		return PathUtil.resolveUnderRoot(root, directoryPath, WEBIMG_DIR_NAME + "/" + imageName);
	}
}
