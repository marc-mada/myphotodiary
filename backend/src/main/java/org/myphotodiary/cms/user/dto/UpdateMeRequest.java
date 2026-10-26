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

package org.myphotodiary.cms.user.dto;

/** Optional - present a value to change it. */
public record UpdateMeRequest(
		Integer maxQueryLength, Integer slideShowInterval, Double defaultLatitude, Double defaultLongitude, Integer postItFadeDelay,
		/** Desktop presentation id (18/10/2026) - see UserSettingsUpdater.PRESENTATIONS. */
		String presentation,
		/** Mobile Camera/Film menu entries shown (26/10/2026). */
		Boolean onlineCapture) {

	public UpdateMeRequest(Integer maxQueryLength, Integer slideShowInterval, Double defaultLatitude, Double defaultLongitude, Integer postItFadeDelay, String presentation) {
		this(maxQueryLength, slideShowInterval, defaultLatitude, defaultLongitude, postItFadeDelay, presentation, null);
	}

	public UpdateMeRequest(Integer maxQueryLength, Integer slideShowInterval, Double defaultLatitude, Double defaultLongitude, Integer postItFadeDelay) {
		this(maxQueryLength, slideShowInterval, defaultLatitude, defaultLongitude, postItFadeDelay, null);
	}
}
