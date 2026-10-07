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
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guesses a sequence's nominal date from its path string - faithfully ported
 * from the legacy {@code DirectoryIndexer.getDirDate}, same regex (the
 * {@code dateFormat} context-param in every legacy {@code web.xml}: a
 * year, optionally followed by a month, optionally followed by a day,
 * separated by {@code -}, space, {@code :}, or {@code /}). Used as the
 * directory's own default date when a sequence is created without EXIF
 * driving it (Design.md §16.7, ImportSvr with "Classer par date" off), and as
 * the per-image clamp default during directory-scan indexing
 * (ExifDateReader's sequenceDefaultDate).
 */
final class PathDateGuesser {

	private static final Pattern DATE_PATTERN =
			Pattern.compile(".*(?<year>\\d{4})((-| |:|/)(?<month>\\d{1,2})((-| |:|/)(?<day>\\d{1,2}))?)?.*");

	private PathDateGuesser() {
	}

	/** No day in the path -> the LAST day of the guessed month (matches legacy exactly, not the 1st). */
	static LocalDate guess(String path) {
		Matcher matcher = DATE_PATTERN.matcher(path);
		if (!matcher.matches()) {
			return null;
		}
		try {
			int year = Integer.parseInt(matcher.group("year"));
			String monthGroup = matcher.group("month");
			int month = monthGroup != null ? Integer.parseInt(monthGroup) : 12;
			String dayGroup = matcher.group("day");
			if (dayGroup == null) {
				return YearMonth.of(year, month).atEndOfMonth();
			}
			return LocalDate.of(year, month, Integer.parseInt(dayGroup));
		} catch (Exception e) {
			// Out-of-range groups (e.g. month=13) - legacy's Calendar
			// silently rolls over instead of throwing; matching that exactly
			// isn't worth the complexity here, so this just declines to guess.
			return null;
		}
	}
}
