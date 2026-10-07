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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * App-wide (not per-user) settings - a single row, id fixed at {@link #ID}
 * (seeded by V10__create_app_settings.sql), not a key-value store. The first
 * setting here (28/08/2026, video upload: "have a talk about a new topic not
 * in the legacy") is operational rather than a personal preference - see
 * V10's own comment for why that puts it here rather than as a new
 * {@code User} column alongside slideShowInterval/maxQueryLength/etc.
 */
@Entity
@Table(name = "app_settings")
public class AppSettings {

	public static final long ID = 1L;

	@Id
	private Long id;

	@Column(name = "max_video_size_bytes", nullable = false)
	private long maxVideoSizeBytes;

	/** V13 - see AppSettingsService#updateTargetUploadSeconds. */
	@Column(name = "target_upload_seconds", nullable = false)
	private int targetUploadSeconds;

	/** V14 - see AppSettingsService#updateMaxShareSize. */
	@Column(name = "max_share_size", nullable = false)
	private int maxShareSize;

	protected AppSettings() {
		// JPA
	}

	public Long getId() {
		return id;
	}

	public long getMaxVideoSizeBytes() {
		return maxVideoSizeBytes;
	}

	public void setMaxVideoSizeBytes(long maxVideoSizeBytes) {
		this.maxVideoSizeBytes = maxVideoSizeBytes;
	}

	public int getTargetUploadSeconds() {
		return targetUploadSeconds;
	}

	public void setTargetUploadSeconds(int targetUploadSeconds) {
		this.targetUploadSeconds = targetUploadSeconds;
	}

	public int getMaxShareSize() {
		return maxShareSize;
	}

	public void setMaxShareSize(int maxShareSize) {
		this.maxShareSize = maxShareSize;
	}
}
