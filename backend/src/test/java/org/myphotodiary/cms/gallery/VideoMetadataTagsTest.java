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

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parsing of the date and place tags ffprobe reports (VideoFormatValidator, 28/10/2026). */
class VideoMetadataTagsTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private JsonNode tags(String json) throws Exception {
		return mapper.readTree(json);
	}

	@Test
	void duplicateCreationTimes_firstIsUsed() throws Exception {
		LocalDateTime date = VideoFormatValidator.recordedAt(tags("{\"creation_time\":\"2025-06-14T10:20:30.000000Z;2020-01-01T00:00:00Z\"}"));
		assertThat(date).isNotNull();
		assertThat(date.getYear()).isEqualTo(2025);
	}

	@Test
	void unsetHeaderDate_isIgnored() throws Exception {
		assertThat(VideoFormatValidator.recordedAt(tags("{\"creation_time\":\"1904-01-01T00:00:00.000000Z\"}"))).isNull();
		assertThat(VideoFormatValidator.recordedAt(tags("{\"creation_time\":\"1970-01-01T00:00:00.000000Z\"}"))).isNull();
		assertThat(VideoFormatValidator.recordedAt(tags("{}"))).isNull();
	}

	@Test
	void appleCreationDate_keepsCameraClockTime() throws Exception {
		LocalDateTime date = VideoFormatValidator.recordedAt(tags(
				"{\"creation_time\":\"2025-06-14T10:20:30Z\",\"com.apple.quicktime.creationdate\":\"2025-06-14T12:20:30+0200\"}"));
		assertThat(date).isEqualTo(LocalDateTime.of(2025, 6, 14, 12, 20, 30));
	}

	@Test
	void zeroOrInvalidLocation_isIgnored() throws Exception {
		assertThat(VideoFormatValidator.location(tags("{\"location\":\"+00.0000+000.0000/\"}"))).isNull();
		assertThat(VideoFormatValidator.location(tags("{\"location\":\"+95.0000+010.0000/\"}"))).isNull();
		assertThat(VideoFormatValidator.location(tags("{\"location\":\"somewhere\"}"))).isNull();
	}

	@Test
	void appleLocation_preferredOverGenericOne() throws Exception {
		ExifGpsReader.Coordinates c = VideoFormatValidator.location(tags(
				"{\"location\":\"+48.8567+002.3508/\",\"com.apple.quicktime.location.ISO6709\":\"+43.5804+007.1251+012.000/\"}"));
		assertThat(c).isEqualTo(new ExifGpsReader.Coordinates(43.5804, 7.1251));
	}
}
