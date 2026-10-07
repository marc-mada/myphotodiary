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

package org.myphotodiary.cms.settings;

import org.myphotodiary.cms.settings.dto.AppSettingsResponse;
import org.myphotodiary.cms.settings.dto.UpdateAppSettingsRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * App-wide settings - unlike {@code UserController} (ADMIN end to end) or
 * {@code MeController} (self-service end to end), this one is genuinely
 * split: any authenticated user can read {@code GET} (the upload form needs
 * to know the current video size cap to validate/display it, same as every
 * other browse/read endpoint in this app staying open per
 * GalleryAuthorizationService's own doc), but only ADMIN can change it
 * ({@code PATCH}) - it protects the server's own disk, not a personal
 * preference (see AppSettings/V10's own comments).
 */
@RestController
public class AppSettingsController {

	private final AppSettingsService service;

	public AppSettingsController(AppSettingsService service) {
		this.service = service;
	}

	@GetMapping("/api/app-settings")
	public AppSettingsResponse get() {
		return AppSettingsResponse.from(service.get());
	}

	@PatchMapping("/api/app-settings")
	@PreAuthorize("hasRole('ADMIN')")
	public AppSettingsResponse update(@RequestBody UpdateAppSettingsRequest request) {
		if (request.maxVideoSizeBytes() != null) {
			service.updateMaxVideoSizeBytes(request.maxVideoSizeBytes());
		}
		if (request.targetUploadSeconds() != null) {
			service.updateTargetUploadSeconds(request.targetUploadSeconds());
		}
		if (request.maxShareSize() != null) {
			service.updateMaxShareSize(request.maxShareSize());
		}
		return AppSettingsResponse.from(service.get());
	}
}
