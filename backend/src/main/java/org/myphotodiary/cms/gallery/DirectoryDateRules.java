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

/**
 * Merges a candidate image date into a directory's own {@code sequenceDate}
 * - faithfully ported from the legacy {@code DirectoryIndexer.updateDirDate}
 * (Design.md §16.7): take the latest image date, but never let it jump the
 * directory's date past the end of the month it's already in. Time-of-day is
 * dropped (legacy compares {@code java.util.Date} down to the millisecond;
 * this backend stores dates only, see V5) - immaterial to the day-level
 * comparison the rule actually makes.
 */
final class DirectoryDateRules {

	private DirectoryDateRules() {
	}

	static LocalDate updateDirDate(LocalDate currentDate, LocalDate candidateDate) {
		if (candidateDate == null) {
			return currentDate;
		}
		if (currentDate == null) {
			return candidateDate;
		}
		LocalDate lastDayOfMonth = YearMonth.from(currentDate).atEndOfMonth();
		if (candidateDate.isAfter(currentDate) && candidateDate.isBefore(lastDayOfMonth)) {
			return candidateDate;
		}
		return currentDate;
	}
}
