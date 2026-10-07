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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;

/**
 * A photo's capture date - faithfully ported from the legacy
 * {@code DirectoryIndexer.getImgExifData} (Design.md §16.7), same library
 * (metadata-extractor) and same fallback chain, kept exact rather than
 * approximated per explicit direction: EXIF wasn't originally scoped into
 * this screen's first pass (a documented simplification - file mtime stood
 * in), then it turned out this exact mechanism drives real legacy UI
 * behavior (the upload form's "Classer les photos par date (EXIF)" /
 * "Créer une nouvelle séquence" options - ImportSvr), so it's ported
 * properly rather than left approximated.
 *
 * Fallback chain, in order:
 * 1. EXIF DateTimeOriginal (0x9003, ExifSubIFDDirectory)
 * 2. EXIF DateTimeDigitized (0x9004, ExifSubIFDDirectory)
 * 3. EXIF DateTime (0x0132, ExifIFD0Directory - the main image IFD, a
 *    different metadata-extractor directory class than the two tags
 *    above, not just a third field on the same one) - added 01/09/2026,
 *    a real gap found live in production: a "Publish into a new sequence"
 *    landed some photos on the upload date instead of their real capture
 *    date, despite the file genuinely carrying EXIF data (confirmed by
 *    the user, not assumed) - this tag (306 decimal, verified against the
 *    actual metadata-extractor 2.18.0 jar on the classpath) is populated
 *    by some cameras/phones as the only date field when
 *    DateTimeOriginal/DateTimeDigitized are absent, and was never read at
 *    all before this fix - falling straight through to the WhatsApp
 *    filename pattern, then the file's own creation time, which for a
 *    freshly-uploaded browser temp file is essentially "now", not
 *    anything derived from the original photo.
 * 4. A WhatsApp-style date encoded in the filename ({@code IMG-20260315-WA0001.jpg})
 * 5. The browser's own {@code File.lastModified} for the upload that
 *    produced this file, if the caller has one (regular browser/mobile
 *    Publish only - see {@code clientLastModified} below) - added
 *    01/09/2026, alongside fallback #3, for the same production report:
 *    once #3 was shipped, the specific file it was chasing turned out to
 *    carry *no* EXIF at all (confirmed live via `exiftool`), and its name
 *    didn't match the WhatsApp pattern either - the true previous "last
 *    resort" (#6 below) landed it on the upload moment, discarding a
 *    perfectly real, already-available signal: the device's own
 *    last-modified timestamp for the file, which the File API exposes to
 *    the browser and which this project simply never asked for before.
 *    Ranked below the WhatsApp filename pattern deliberately - a filename
 *    genuinely encoding the send date is a stronger signal than a
 *    last-modified timestamp that copying/re-saving can bump forward.
 * 6. The file's filesystem creation time - the true last resort, and for
 *    a browser upload's disposable server-side temp file this is always
 *    "now" (freshly written), never anything derived from the original
 *    photo; #5 exists specifically to be reached before this one usually
 *    has to be.
 *
 * Fallback #3 and #5 are both deliberate, acknowledged departures from the
 * legacy `DirectoryIndexer.getImgExifData` chain this class otherwise
 * ports faithfully (see class-level note above) - legacy never read
 * either signal, so both are genuine improvements over the port, not
 * fidelity slips; done because the bugs they fix are real and reported
 * live, not because the legacy chain was wrong to omit them at the time.
 *
 * If {@code sequenceDefaultDate} is given (only the directory-scan indexing
 * path passes one - ImportSvr always passes none), the legacy "an image
 * date after its sequence's default date is treated as suspect and the
 * default wins" rule applies: {@code imgDate = (imgDate == null ||
 * imgDate.isAfter(defaultDate)) ? defaultDate : imgDate}.
 */
@Component
public class ExifDateReader {

	private static final Logger log = LoggerFactory.getLogger(ExifDateReader.class);

	// Matches the legacy WhatsApp pattern exactly (DirectoryIndexer's waImgDatePattern).
	private static final Pattern WA_PATTERN = Pattern.compile("^(IMG-|VID-)([0-9]{4})([0-9]{2})([0-9]{2})(-WA.*)");

	/**
	 * Convenience overload for callers with no client-reported last-modified
	 * time (staging import - the file was never a browser upload, so there's
	 * no client to ask; and every existing call site/test predating fallback
	 * #5, left alone rather than touched everywhere just to pass null).
	 */
	public LocalDateTime readCaptureDate(Path imageFile, String originalFilename, LocalDateTime sequenceDefaultDate) {
		return readCaptureDate(imageFile, originalFilename, null, sequenceDefaultDate);
	}

	/**
	 * @param imageFile the file on disk (used for the EXIF read and, as a last resort, its creation time)
	 * @param originalFilename the file's name (used for the WhatsApp filename fallback - may differ from imageFile's own name for a not-yet-moved upload)
	 * @param clientLastModified the uploading browser's own {@code File.lastModified} for this file, converted by the caller, or null if unavailable/not applicable (fallback #5 above)
	 * @param sequenceDefaultDate the owning sequence's nominal date, or null (ImportSvr never supplies one; directory-scan indexing does)
	 */
	public LocalDateTime readCaptureDate(Path imageFile, String originalFilename, LocalDateTime clientLastModified, LocalDateTime sequenceDefaultDate) {
		return readCaptureDate(imageFile, originalFilename, clientLastModified, sequenceDefaultDate, null);
	}

	/**
	 * {@code fallbackDate} (06/10/2026 - Batch Publish's month selector): used
	 * only when the file has no date of its own (no EXIF, no WhatsApp-style
	 * name, no browser-supplied date), in place of the file's creation time
	 * on disk - and, unlike {@code sequenceDefaultDate}, never as a ceiling
	 * on a real date. The selector used to be passed as
	 * {@code sequenceDefaultDate}, which wrongly pulled later EXIF dates back
	 * to the selected month.
	 */
	public LocalDateTime readCaptureDate(Path imageFile, String originalFilename, LocalDateTime clientLastModified, LocalDateTime sequenceDefaultDate,
			LocalDateTime fallbackDate) {
		// Logged at INFO (01/09/2026, added while chasing a real production
		// report - "Publish into a new sequence" landing on today's date for
		// a file the user insisted carried a real date) rather than DEBUG:
		// a publish is a rare, human-triggered event, not a hot path, so this
		// never floods the log, and it's exactly the kind of "why did this
		// one file resolve the way it did" question that's otherwise only
		// answerable by re-deriving the whole fallback chain by hand against
		// a copy of the file, as happened for that report. `source` tracks
		// which branch actually produced the pre-clamp date, not just the
		// final value, since two different files can share the same
		// resolved date for entirely different reasons.
		String source = "none";
		LocalDateTime imgDate = null;
		try (InputStream in = Files.newInputStream(imageFile)) {
			Metadata metadata = ImageMetadataReader.readMetadata(in);
			ExifSubIFDDirectory exifSubDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
			Date date = null;
			if (exifSubDir != null) {
				date = exifSubDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
				if (date != null) {
					source = "DateTimeOriginal";
				} else {
					date = exifSubDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED);
					if (date != null) {
						source = "DateTimeDigitized";
					}
				}
			}
			if (date == null) {
				// DateTime (0x0132) lives in the main image IFD, not the Exif
				// SubIFD the two tags above come from - a genuinely different
				// metadata-extractor directory class, not just a third field
				// to also check on exifSubDir.
				ExifIFD0Directory exifIfd0Dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
				if (exifIfd0Dir != null) {
					date = exifIfd0Dir.getDate(ExifIFD0Directory.TAG_DATETIME);
					if (date != null) {
						source = "DateTime(IFD0)";
					}
				}
			}
			if (date != null) {
				imgDate = exifToLocalDateTime(date);
			}
			if (imgDate == null) {
				imgDate = fromWhatsAppFilename(originalFilename);
				if (imgDate != null) {
					source = "WhatsAppFilename";
				}
			}
			if (imgDate == null && clientLastModified != null) {
				imgDate = clientLastModified;
				source = "ClientLastModified";
			}
			if (imgDate == null && fallbackDate != null) {
				imgDate = fallbackDate;
				source = "FallbackDate";
			}
			if (imgDate == null) {
				imgDate = fromCreationTime(imageFile);
				if (imgDate != null) {
					source = "FileCreationTime";
				}
			}
		} catch (ImageProcessingException | IOException e) {
			// Matches the legacy try/catch: a read failure just means no
			// EXIF-derived date, not a hard error - the caller still has the
			// sequence default (or its own final fallback) to fall back on.
			source = "readError(" + e.getClass().getSimpleName() + ")";
		}
		if (imgDate == null && fallbackDate != null) {
			imgDate = fallbackDate;
			source = "FallbackDate";
		}

		if (sequenceDefaultDate != null && (imgDate == null || imgDate.isAfter(sequenceDefaultDate))) {
			log.info("readCaptureDate: file={} originalFilename={} preClampSource={} preClampDate={} -> clamped to sequenceDefaultDate={}",
					imageFile.getFileName(), originalFilename, source, imgDate, sequenceDefaultDate);
			imgDate = sequenceDefaultDate;
		} else {
			log.info("readCaptureDate: file={} originalFilename={} source={} result={}", imageFile.getFileName(), originalFilename, source, imgDate);
		}
		return imgDate;
	}

	private LocalDateTime fromWhatsAppFilename(String filename) {
		if (filename == null) return null;
		Matcher m = WA_PATTERN.matcher(filename);
		if (!m.find()) return null;
		int year = Integer.parseInt(m.group(2));
		int month = Integer.parseInt(m.group(3));
		int day = Integer.parseInt(m.group(4));
		Calendar cal = new GregorianCalendar(year, month - 1, day); // legacy Calendar convention: January = 0
		return toLocalDateTime(cal.getTime());
	}

	private LocalDateTime fromCreationTime(Path imageFile) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(imageFile, BasicFileAttributes.class);
			return LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault());
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * An EXIF date/time has no time zone - it's the camera's own wall-clock
	 * time. metadata-extractor's {@code getDate(tag)} (no TimeZone argument)
	 * builds the {@link Date} by reading that value <em>as UTC</em>, so it
	 * must be turned back into a wall-clock time with UTC too. The old code
	 * used the server's zone here instead (26/09/2026 fix): on a Paris server
	 * every EXIF-dated photo came out 1h (winter) or 2h (summer) late - and
	 * one taken late on a month's last evening landed in the next month.
	 * UTC also has no daylight-saving gaps, so no local time is ever skipped
	 * or shifted by the conversion. Dates already stored with the old shift
	 * are not corrected by this change (explicit choice, 27/09/2026) - a
	 * re-index of a directory does rewrite its photos' dates, though.
	 */
	private static LocalDateTime exifToLocalDateTime(Date date) {
		return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
	}

	/** For a {@link Date} built in the server's own zone (the WhatsApp-filename Calendar above). */
	private static LocalDateTime toLocalDateTime(Date date) {
		return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
	}
}
