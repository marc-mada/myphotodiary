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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.myphotodiary.cms.gallery.dto.BatchIndexResult;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Legacy's real Batch Publish (01/09/2026) - an admin points this at a
 * staging path (typically an external disk mounted there), and every image/
 * video under it is sorted by EXIF date into the tree, exactly like a
 * regular Publish - the same {@link ImportService} logic, same fallback
 * chain, same auto-sort. What the "Batch Publish" button in Admin -> Index
 * management had actually become by this point in the project (a recursive
 * re-index of the *existing* tree, {@link DirectoryIndexerService
 * #indexRecursively}) was never this; that confusion is exactly what led
 * here (01/09/2026).
 * {@code indexRecursively} itself is unchanged and still exists - it's still
 * what {@code batch-publish-by-year.sh} drives for the one-time historical
 * thumbnail backfill - only the UI's own "Batch Publish" box now calls this
 * service instead.
 *
 * <h2>The one thing legacy's version didn't need to decide: the sequence name</h2>
 * Legacy read a whole external volume structured as
 * {@code <input path>/<sequence name>/<files>} and used exactly that -
 * ported verbatim here: the sequence name for any given file is its own
 * immediate parent folder's name, whatever the nesting depth under the
 * staging path. A file sitting directly in the staging path itself (no
 * enclosing sequence folder) has nothing to derive a name from and is
 * reported as a failure rather than guessed.
 *
 * <h2>Four conditions this was built against (explicit, 01/09/2026)</h2>
 * <ol>
 *   <li>No DB schema change - none needed, this only adds application code
 *       and one new {@code staging.root} config property.</li>
 *   <li>Sorted by EXIF exactly like a regular Publish, sequence name from
 *       the containing folder - see above and {@link ImportService
 *       #importFromStagingFile}.</li>
 *   <li>An optional year/month selector supplies the default date when EXIF
 *       is absent - {@code defaultYear}/{@code defaultMonth} below, passed
 *       as {@code ExifDateReader}'s {@code fallbackDate}: only for a file
 *       with no date of its own, never a ceiling on a real one (corrected
 *       06/10/2026 - it was passed as {@code sequenceDefaultDate}, which
 *       pulled later EXIF dates back to the selected month).</li>
 *   <li>EXIF precedence: the photo's own capture date first, then the same
 *       fallback chain a regular Publish already uses - literally the same
 *       {@code ExifDateReader.readCaptureDate} call either way, not a
 *       parallel implementation.</li>
 * </ol>
 *
 * <h2>Why this class exists separately from {@link ImportService}</h2>
 * Deliberately NOT {@code @Transactional} itself, and deliberately calling
 * {@code importService.importFromStagingFile(...)} as a genuine call to a
 * <i>different</i> Spring bean rather than folding this loop into
 * {@code ImportService} and calling it via {@code this.importFromStagingFile
 * (...)}. That self-invocation distinction is the exact one already
 * diagnosed as the root cause of {@code DirectoryIndexerService
 * .batchIndex}/{@code indexDirectory} running an entire recursive batch
 * inside one giant Hibernate session (Design.md §12,
 * 31/08/2026) - calling through the proxy here means every file genuinely
 * gets its own transaction, so one failed file can never poison any other
 * file's already-committed work, and no single staging batch, however large,
 * holds one session open for its whole duration the way the existing
 * recursive re-index still does.
 */
@Service
public class StagingImportService {

	private final Path stagingRoot;
	private final ImportService importService;

	public StagingImportService(StagingProperties properties, ImportService importService) {
		this.stagingRoot = Path.of(properties.getRoot()).toAbsolutePath().normalize();
		this.importService = importService;
	}

	/**
	 * @param path staging-relative subpath to scan recursively (traversal-checked against {@link #stagingRoot}, same guard as {@link PathUtil})
	 * @param defaultYear   the year half of the form's optional year/month selector - both or neither, never one alone
	 * @param defaultMonth  the month half - see {@code defaultYear}
	 * @param groupName     same meaning as {@link ImportService#importImage}'s own groupName - null/blank defaults to "public"
	 */
	public BatchIndexResult importBatch(Authentication authentication, String path, Integer defaultYear, Integer defaultMonth, String groupName) {
		return importBatch(authentication, path, defaultYear, defaultMonth, groupName, false);
	}

	/**
	 * {@code useFolderDates} (06/10/2026, explicit ask - old photos scanned
	 * years later, whose EXIF, file dates and names are all useless): every
	 * file must sit at {@code yyyy/mm/<sequence>/<file>} under the scanned
	 * folder, sorted there by hand, and that year/month IS its date - stored
	 * as the month's last day, the same convention a later re-index of that
	 * folder would produce (PathDateGuesser + ExifDateReader's clamp), so a
	 * re-index doesn't shift it. A file anywhere else is reported as a
	 * failure. The month selector doesn't apply in this mode.
	 */
	public BatchIndexResult importBatch(Authentication authentication, String path, Integer defaultYear, Integer defaultMonth, String groupName,
			boolean useFolderDates) {
		if ((defaultYear == null) != (defaultMonth == null)) {
			throw new IllegalArgumentException("defaultYear and defaultMonth must be given together, or not at all");
		}
		// End of month, not the 1st - matches PathDateGuesser's own established
		// convention for "a year/month with no day" elsewhere in this codebase
		// (its own javadoc: "matches legacy exactly, not the 1st"), and avoids
		// ExifDateReader's own "an image date after the default is suspect"
		// clamp misfiring on a perfectly legitimate EXIF date later in the
		// selected month.
		LocalDateTime fallbackDate =
				defaultYear != null ? YearMonth.of(defaultYear, defaultMonth).atEndOfMonth().atStartOfDay() : null;

		Path resolvedDir = PathUtil.resolveUnderRoot(stagingRoot, path == null ? "" : path, "");

		List<String> succeeded = new ArrayList<>();
		List<BatchIndexResult.BatchFailure> failed = new ArrayList<>();
		for (Path file : listCandidateFiles(resolvedDir)) {
			String originalFilename = file.getFileName().toString();
			// The file's own immediate parent folder name, not the full
			// relative path under the staging root - condition 2's own
			// wording ("the folder containing the image"), ported as
			// literally as legacy's real "<input path>/<sequence>/<files>"
			// layout, whatever the nesting depth above that one folder. A
			// file sitting directly in the scanned directory itself (parent
			// == resolvedDir) has no enclosing sequence folder to name it
			// after.
			Path parent = file.getParent();
			String sequenceName = parent.equals(resolvedDir) ? null : parent.getFileName().toString();
			if (sequenceName == null) {
				failed.add(new BatchIndexResult.BatchFailure(originalFilename, "No enclosing sequence folder for this file"));
				continue;
			}
			LocalDateTime forcedDate = null;
			if (useFolderDates) {
				forcedDate = folderDate(resolvedDir.relativize(file));
				if (forcedDate == null) {
					failed.add(new BatchIndexResult.BatchFailure(resolvedDir.relativize(file).toString(),
							"Not in a yyyy/mm/<sequence> folder (expected year/month/sequence/file)"));
					continue;
				}
			}
			try {
				// Same rule as Rename/Publish (SequenceNames) - a folder name
				// can't contain "/", but it could start with a dot.
				SequenceNames.requireValid(sequenceName);
				importService.importFromStagingFile(authentication, file, originalFilename, sequenceName, fallbackDate, forcedDate, groupName);
				succeeded.add(sequenceName + "/" + originalFilename);
			} catch (RuntimeException e) {
				failed.add(new BatchIndexResult.BatchFailure(sequenceName + "/" + originalFilename, e.getMessage()));
			}
		}
		return new BatchIndexResult(succeeded, failed);
	}

	/**
	 * The date of a file at {@code yyyy/mm/<sequence>/<file>} (relative to the
	 * scanned folder): the last day of that month, or {@code null} when the
	 * file isn't exactly there or the year/month isn't valid.
	 */
	static LocalDateTime folderDate(Path relative) {
		if (relative.getNameCount() != 4) {
			return null;
		}
		String year = relative.getName(0).toString();
		String month = relative.getName(1).toString();
		if (!year.matches("\\d{4}") || !month.matches("\\d{1,2}")) {
			return null;
		}
		int y = Integer.parseInt(year);
		int m = Integer.parseInt(month);
		if (y < 1000 || m < 1 || m > 12) {
			return null;
		}
		return YearMonth.of(y, m).atEndOfMonth().atStartOfDay();
	}

	private List<Path> listCandidateFiles(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile)
					// Hidden files *and* anything inside a hidden folder
					// (06/10/2026 - only hidden file names used to be skipped, so a
					// copied ".thumbnails" or a card's ".Trashes" got imported as a
					// sequence of that name, invisible in the tree).
					.filter(p -> {
						for (Path part : dir.relativize(p)) {
							if (part.toString().startsWith(".")) return false;
						}
						return true;
					})
					.filter(StagingImportService::isMediaFile)
					.sorted()
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static boolean isMediaFile(Path file) {
		String name = file.getFileName().toString();
		if (ImageStorageService.hasImageExtension(name)) return true;
		int dot = name.lastIndexOf('.');
		return dot >= 0 && ImportService.VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}
}
