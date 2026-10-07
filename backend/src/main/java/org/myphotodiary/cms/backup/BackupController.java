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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.myphotodiary.cms.backup.dto.BackupStatusResponse;
import org.myphotodiary.cms.backup.dto.BackupTriggerResponse;
import org.myphotodiary.cms.backup.dto.DbBackupSnapshotResponse;
import org.myphotodiary.cms.backup.dto.ImageBackupStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Admin-only, both endpoints - same as {@code UserController}/{@code
 * AttributeController}'s write side, not split like {@code
 * AppSettingsController} (nothing here is a personal preference any
 * authenticated user should read). {@code GET} is deliberately read-only
 * for *both* halves of the backup feature (DB and image-tree), even though
 * only the DB half has a matching {@code POST} to trigger it - see {@link
 * ImageBackupStatusResponse}'s own comment for why the image-tree backup
 * has no trigger endpoint at all.
 */
@RestController
@RequestMapping("/api/admin/backups")
@PreAuthorize("hasRole('ADMIN')")
public class BackupController {

	private static final Logger log = LoggerFactory.getLogger(BackupController.class);

	private final DatabaseBackupService databaseBackupService;
	private final BackupProperties properties;
	private final BackupMountGuard mountGuard;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public BackupController(DatabaseBackupService databaseBackupService, BackupProperties properties, BackupMountGuard mountGuard) {
		this.databaseBackupService = databaseBackupService;
		this.properties = properties;
		this.mountGuard = mountGuard;
	}

	@GetMapping
	public BackupStatusResponse status() {
		Path root = Path.of(properties.getRoot());
		// Only reported as "not mounted" when a mount is actually required -
		// otherwise (MPD_BACKUP_REQUIRE_MOUNT=false, as in the Docker image)
		// backups do run, and the marker file is the check that matters.
		boolean mounted = !properties.isRequireMount() || mountGuard.isMounted(root);
		List<DbBackupSnapshotResponse> dbBackups = databaseBackupService.listSnapshots().stream()
				.map(timestamp -> new DbBackupSnapshotResponse(timestamp, databaseBackupService.snapshotSizeBytes(timestamp)))
				.toList();
		String markerFile = properties.hasMarkerFile() ? properties.getMarkerFile() : null;
		boolean markerPresent = markerFile == null || mountGuard.markerPresent(root, markerFile);
		return new BackupStatusResponse(mounted, markerFile, markerPresent, dbBackups, readImageBackupStatus(root));
	}

	@PostMapping("/db")
	public BackupTriggerResponse triggerDbBackup() {
		return BackupTriggerResponse.from(databaseBackupService.backupNow());
	}

	/**
	 * {@code backup-images.sh} writes this file (plain JSON, hand-rolled on
	 * the shell side - see that script's own comment) after every run,
	 * success or failure. Read defensively: a missing/malformed file just
	 * means "no image backup has run yet" or "can't be parsed right now",
	 * never a 500 on this admin status page.
	 */
	private ImageBackupStatusResponse readImageBackupStatus(Path root) {
		Path statusFile = root.resolve("images").resolve("status.json");
		if (!Files.isRegularFile(statusFile)) {
			return ImageBackupStatusResponse.none();
		}
		try {
			return objectMapper.readValue(statusFile.toFile(), ImageBackupStatusResponse.class);
		} catch (IOException e) {
			log.warn("readImageBackupStatus: could not parse {}", statusFile, e);
			return ImageBackupStatusResponse.none();
		}
	}
}
