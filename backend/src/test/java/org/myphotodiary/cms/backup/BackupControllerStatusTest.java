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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.backup.dto.BackupStatusResponse;

/**
 * The status shown in Admin -> Backup when the mount check is switched off
 * and a marker file is used instead (the Docker image's configuration).
 * Plain unit test: no security or database involved in what's checked.
 */
class BackupControllerStatusTest {

	private BackupStatusResponse status(Path root, boolean requireMount) {
		BackupProperties props = new BackupProperties();
		props.setRoot(root.toString());
		props.setRequireMount(requireMount);
		props.setMarkerFile(".myphotodiary-backup-target");
		return new BackupController(mock(DatabaseBackupService.class), props, new BackupMountGuard()).status();
	}

	@Test
	void mountNotRequired_reportsTheMissingMarker_notAMissingMount(@TempDir Path root) {
		BackupStatusResponse status = status(root, false);

		assertThat(status.backupRootMounted()).isTrue();
		assertThat(status.markerFile()).isEqualTo(".myphotodiary-backup-target");
		assertThat(status.markerPresent()).isFalse();
	}

	@Test
	void mountNotRequired_markerPresent_reportsReady(@TempDir Path root) throws Exception {
		Files.createFile(root.resolve(".myphotodiary-backup-target"));

		BackupStatusResponse status = status(root, false);

		assertThat(status.backupRootMounted()).isTrue();
		assertThat(status.markerPresent()).isTrue();
	}

	@Test
	void mountRequired_plainFolder_stillReportsNotMounted(@TempDir Path root) {
		assertThat(status(root, true).backupRootMounted()).isFalse();
	}
}
