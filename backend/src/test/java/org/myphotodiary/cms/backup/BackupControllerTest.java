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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.backup.dto.BackupStatusResponse;
import org.myphotodiary.cms.backup.dto.BackupTriggerResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * {@code @EnableMethodSecurity} enforces {@code BackupController}'s own
 * class-level {@code @PreAuthorize("hasRole('ADMIN')")} even on a plain
 * direct call - same pattern as {@code GroupControllerTest}. Runs against
 * the shared {@code mem:testdb} Spring context (not a file-mode database
 * like {@link DatabaseBackupServiceTest}) on purpose for the trigger test
 * below - it's a real demonstration that a database type unable to run
 * {@code BACKUP DATABASE} at all comes back as a normal, readable failure
 * response, never an uncaught 500.
 */
@SpringBootTest
class BackupControllerTest {

	@Autowired
	private BackupController backupController;

	@Test
	@WithMockUser(roles = "WRITER")
	void status_deniedForNonAdmin() {
		assertThatThrownBy(backupController::status).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void status_allowedForAdmin_reportsUnmountedBackupRoot_andNoImageBackupYet() {
		// Default test config (application.yml under src/test/resources
		// doesn't override `backup.*`) - backup.root defaults to a plain
		// local directory, never a real mount in this test environment, and
		// no backup-images.sh has ever run here.
		BackupStatusResponse status = backupController.status();

		assertThat(status.backupRootMounted()).isFalse();
		// No marker configured in the test config: nothing to report missing.
		assertThat(status.markerFile()).isNull();
		assertThat(status.markerPresent()).isTrue();
		assertThat(status.imageBackup().timestamp()).isNull();
	}

	@Test
	@WithMockUser(roles = "WRITER")
	void triggerDbBackup_deniedForNonAdmin() {
		assertThatThrownBy(backupController::triggerDbBackup).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void triggerDbBackup_allowedForAdmin_failsGracefully_becauseTestDatabaseIsInMemory() {
		// mem:testdb can't run BACKUP DATABASE at all (HSQLDB's own
		// documented limitation - see DatabaseBackupServiceTest's own
		// javadoc) - proves this comes back as a normal JSON response with
		// success=false, not an uncaught exception turned into a 500 by
		// Spring's default error handling.
		BackupTriggerResponse response = backupController.triggerDbBackup();

		assertThat(response.success()).isFalse();
		assertThat(response.message()).isNotBlank();
	}
}
