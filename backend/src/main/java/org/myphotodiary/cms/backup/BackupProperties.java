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

package org.myphotodiary.cms.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backup feature configuration (18/09/2026, explicit ask - "work on the
 * backup/restore feature"). Same shape as {@code StorageProperties}/
 * {@code StagingProperties} deliberately - a plain configurable root,
 * {@code MPD_BACKUP_ROOT} overriding the default the same way
 * {@code MPD_STORAGE_ROOT}/{@code MPD_STAGING_ROOT} already do.
 *
 * <p>{@code requireMount} defaults to {@code true} on purpose, and is the
 * single most important setting here: at the time this was built, the real
 * production server has exactly one physical disk (Migration Procedure.md's
 * own storage/disk layout decision) and the intended backup target is a NAS
 * share that isn't mounted yet. Writing a "backup" into an unmounted local
 * directory at the configured path would silently succeed, produce
 * something that looks exactly like a real backup, and protect against
 * nothing - the exact disk failure a backup exists to survive would take
 * both the primary data and the "backup" with it. {@link BackupMountGuard}
 * refuses to proceed unless the configured root is genuinely a distinct
 * filesystem from its parent, so this feature is safe to ship and enable
 * *before* the NAS exists: it will cleanly no-op (visible in the Admin
 * Backup panel/logs, not a silent false "success") until the mount is
 * actually there. Set to {@code false} deliberately overrides this - local
 * dev/testing, or an operator who's made an informed choice to accept
 * same-disk-only backups for now.
 */
@ConfigurationProperties(prefix = "backup")
public class BackupProperties {

	private String root = "./data/backups";
	private boolean enabled = true;
	private boolean requireMount = true;
	// Optional (06/10/2026, Docker image): when set, a file of this name must
	// exist directly in the backup root - see BackupMountGuard#markerPresent.
	private String markerFile = "";
	private int retentionDays = 30;
	// Spring cron expression (6 fields: second minute hour day month weekday).
	private String dbCron = "0 0 2 * * *";

	public String getRoot() {
		return root;
	}

	public void setRoot(String root) {
		this.root = root;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isRequireMount() {
		return requireMount;
	}

	public void setRequireMount(boolean requireMount) {
		this.requireMount = requireMount;
	}

	public String getMarkerFile() {
		return markerFile;
	}

	public void setMarkerFile(String markerFile) {
		this.markerFile = markerFile;
	}

	/** @return {@code true} when a marker file name is configured. */
	public boolean hasMarkerFile() {
		return markerFile != null && !markerFile.isBlank();
	}

	public int getRetentionDays() {
		return retentionDays;
	}

	public void setRetentionDays(int retentionDays) {
		this.retentionDays = retentionDays;
	}

	public String getDbCron() {
		return dbCron;
	}

	public void setDbCron(String dbCron) {
		this.dbCron = dbCron;
	}
}
