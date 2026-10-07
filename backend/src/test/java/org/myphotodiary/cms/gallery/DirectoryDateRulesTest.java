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

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Same merge rule as the legacy DirectoryIndexer.updateDirDate (Design.md §16.7). */
class DirectoryDateRulesTest {

	@Test
	void nullCurrentDate_takesCandidateDirectly() {
		assertThat(DirectoryDateRules.updateDirDate(null, LocalDate.of(2026, 9, 15))).isEqualTo(LocalDate.of(2026, 9, 15));
	}

	@Test
	void nullCandidate_keepsCurrentDate() {
		assertThat(DirectoryDateRules.updateDirDate(LocalDate.of(2026, 9, 15), null)).isEqualTo(LocalDate.of(2026, 9, 15));
	}

	@Test
	void laterDateWithinSameMonth_wins() {
		LocalDate result = DirectoryDateRules.updateDirDate(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20));
		assertThat(result).isEqualTo(LocalDate.of(2026, 9, 20));
	}

	@Test
	void laterDateInADifferentMonth_doesNotOverride() {
		// Matches legacy exactly: a candidate past the current date's own
		// month-end is treated as suspect and ignored, not adopted.
		LocalDate result = DirectoryDateRules.updateDirDate(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 5));
		assertThat(result).isEqualTo(LocalDate.of(2026, 9, 10));
	}

	@Test
	void earlierDate_doesNotOverride() {
		LocalDate result = DirectoryDateRules.updateDirDate(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 10));
		assertThat(result).isEqualTo(LocalDate.of(2026, 9, 20));
	}
}
