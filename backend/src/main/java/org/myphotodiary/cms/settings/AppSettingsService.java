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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppSettingsService {

	private final AppSettingsRepository repository;

	public AppSettingsService(AppSettingsRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public AppSettings get() {
		// The single row is seeded by V10 - a missing row here would mean a
		// broken migration, not a normal runtime state, so this deliberately
		// doesn't self-heal with an orElseGet default the way e.g.
		// GalleryService.findOrCreateGroup does for a Group.
		return repository.findById(AppSettings.ID).orElseThrow(() -> new IllegalStateException("app_settings row missing - migration V10 didn't run?"));
	}

	@Transactional
	public AppSettings updateMaxVideoSizeBytes(long maxVideoSizeBytes) {
		if (maxVideoSizeBytes <= 0) {
			throw new IllegalArgumentException("maxVideoSizeBytes must be positive");
		}
		AppSettings settings = get();
		settings.setMaxVideoSizeBytes(maxVideoSizeBytes);
		return settings;
	}

	public static final int MIN_TARGET_UPLOAD_SECONDS = 1;
	public static final int MAX_TARGET_UPLOAD_SECONDS = 600;

	/**
	 * "Target average upload time per file" (26/09/2026, explicit ask, V13) -
	 * not enforced server-side at all: the browser reads it (GET
	 * /api/app-settings, open to any signed-in user) and shrinks a photo
	 * before upload only when its predicted transfer time on the measured
	 * connection exceeds it, by just enough to fit (adaptiveShrink.js).
	 */
	@Transactional
	public AppSettings updateTargetUploadSeconds(int targetUploadSeconds) {
		if (targetUploadSeconds < MIN_TARGET_UPLOAD_SECONDS || targetUploadSeconds > MAX_TARGET_UPLOAD_SECONDS) {
			throw new IllegalArgumentException("targetUploadSeconds must be between " + MIN_TARGET_UPLOAD_SECONDS + " and "
					+ MAX_TARGET_UPLOAD_SECONDS + ", got " + targetUploadSeconds);
		}
		AppSettings settings = get();
		settings.setTargetUploadSeconds(targetUploadSeconds);
		return settings;
	}

	public static final int MIN_MAX_SHARE_SIZE = 1;
	public static final int MAX_MAX_SHARE_SIZE = 1000;

	/**
	 * Most pictures one shared search result may hold (04/10/2026, explicit
	 * ask, V14, default 50) - SharedSearchService freezes at most this many,
	 * the first ones in search order, and reports when the result was cut.
	 */
	@Transactional
	public AppSettings updateMaxShareSize(int maxShareSize) {
		if (maxShareSize < MIN_MAX_SHARE_SIZE || maxShareSize > MAX_MAX_SHARE_SIZE) {
			throw new IllegalArgumentException("maxShareSize must be between " + MIN_MAX_SHARE_SIZE + " and " + MAX_MAX_SHARE_SIZE + ", got " + maxShareSize);
		}
		AppSettings settings = get();
		settings.setMaxShareSize(maxShareSize);
		return settings;
	}
}
