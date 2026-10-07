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

/**
 * Same regex as every legacy web.xml's `dateFormat` context-param
 * (Design.md §16.7, DirectoryIndexer.getDirDate) - reproduced here to prove
 * the port matches before it's trusted by ImportService/DirectoryIndexerService.
 */
class PathDateGuesserTest {

	@Test
	void yearMonthDay_parsesExactDate() {
		assertThat(PathDateGuesser.guess("2026/09/15/vacation")).isEqualTo(LocalDate.of(2026, 9, 15));
	}

	@Test
	void yearMonthOnly_defaultsToLastDayOfMonth() {
		// Not the 1st - matches legacy's actualMaximum(DAY_OF_MONTH) exactly.
		assertThat(PathDateGuesser.guess("2026/09/mountain-trip")).isEqualTo(LocalDate.of(2026, 9, 30));
	}

	@Test
	void yearOnly_defaultsToDecemberLastDay() {
		assertThat(PathDateGuesser.guess("2026/misc-photos")).isEqualTo(LocalDate.of(2026, 12, 31));
	}

	@Test
	void noYearInPath_returnsNull() {
		assertThat(PathDateGuesser.guess("vacation/beach")).isNull();
	}

	@Test
	void acceptsColonAndSpaceSeparatorsToo() {
		assertThat(PathDateGuesser.guess("2026:09:15")).isEqualTo(LocalDate.of(2026, 9, 15));
		assertThat(PathDateGuesser.guess("2026 09 15")).isEqualTo(LocalDate.of(2026, 9, 15));
	}
}
