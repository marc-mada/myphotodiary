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

package org.myphotodiary.cms.backup.dto;

import java.util.List;

/**
 * @param markerFile    the configured marker file name, or {@code null} when
 *                      none is configured (MPD_BACKUP_MARKER_FILE)
 * @param markerPresent {@code true} when no marker is configured, or when it
 *                      exists in the backup root
 */
public record BackupStatusResponse(boolean backupRootMounted, String markerFile, boolean markerPresent, List<DbBackupSnapshotResponse> dbBackups, ImageBackupStatusResponse imageBackup) {
}
