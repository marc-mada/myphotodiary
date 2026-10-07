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

package org.myphotodiary.cms.user;

import org.myphotodiary.cms.user.dto.UpdateMeRequest;

/**
 * The validate-then-apply logic shared by {@link MeController#update} (self,
 * any authenticated role) and {@link UserService#updateSettings} (ADMIN
 * acting on an explicitly chosen user, 29/08/2026 - see UserController's own
 * comment on why this exists). Extracted here rather than duplicated once a
 * second caller needed the exact same bound checks - a single source of
 * truth for the legacy-matched ranges (admin.jsp's own config form) plus
 * postItFadeDelay's own new-preference range (V8 migration).
 */
final class UserSettingsUpdater {

	private UserSettingsUpdater() {
	}

	static void apply(User user, UpdateMeRequest request) {
		if (request.maxQueryLength() != null) {
			if (request.maxQueryLength() < 1) {
				throw new IllegalArgumentException("maxQueryLength must be at least 1");
			}
			user.setMaxQueryLength(request.maxQueryLength());
		}
		if (request.slideShowInterval() != null) {
			// Same 2..60 second bound as the legacy admin.jsp config form
			// (<input type="number" min="2" max="60" step="1">).
			if (request.slideShowInterval() < 2 || request.slideShowInterval() > 60) {
				throw new IllegalArgumentException("slideShowInterval must be between 2 and 60");
			}
			user.setSlideShowInterval(request.slideShowInterval());
		}
		if (request.defaultLatitude() != null) {
			// Same -90..90 bound as the legacy admin.jsp config form.
			if (request.defaultLatitude() < -90 || request.defaultLatitude() > 90) {
				throw new IllegalArgumentException("defaultLatitude must be between -90 and 90");
			}
			user.setDefaultLatitude(request.defaultLatitude());
		}
		if (request.defaultLongitude() != null) {
			// Same -180..180 bound as the legacy admin.jsp config form.
			if (request.defaultLongitude() < -180 || request.defaultLongitude() > 180) {
				throw new IllegalArgumentException("defaultLongitude must be between -180 and 180");
			}
			user.setDefaultLongitude(request.defaultLongitude());
		}
		if (request.postItFadeDelay() != null) {
			// No legacy bound to match (no legacy field at all - see V8
			// migration) - same 2..60 second range as slideShowInterval,
			// a reasonable bound for a display-timing setting of this kind.
			if (request.postItFadeDelay() < 2 || request.postItFadeDelay() > 60) {
				throw new IllegalArgumentException("postItFadeDelay must be between 2 and 60");
			}
			user.setPostItFadeDelay(request.postItFadeDelay());
		}
	}
}
