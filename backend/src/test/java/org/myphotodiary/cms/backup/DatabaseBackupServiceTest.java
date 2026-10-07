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

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.backup.DatabaseBackupService.BackupOutcome;

/**
 * Deliberately NOT {@code @SpringBootTest} - this project's shared test
 * context runs against {@code jdbc:hsqldb:mem:testdb} (src/test/resources/
 * application.yml), and {@code BACKUP DATABASE} is a documented HSQLDB
 * no-op/error against an in-memory catalog (no files to snapshot at all) -
 * confirmed live, not assumed (see the last test method below). A real
 * file-mode HSQLDB, built by hand against a {@code @TempDir}, is the only
 * way to exercise the actual online-backup mechanism this service exists
 * for - same reasoning as writing real EXIF fixtures instead of mocking
 * metadata-extractor elsewhere in this project.
 */
class DatabaseBackupServiceTest {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

	/** A minimal real {@link DataSource} - every call just opens a fresh JDBC connection via {@link DriverManager}, exactly like a real connection pool would hand out one, just without the pooling. */
	private static DataSource fileDataSource(Path dbDir) {
		String url = "jdbc:hsqldb:file:" + dbDir.resolve("testdb") + ";shutdown=true";
		return new DataSource() {
			@Override
			public Connection getConnection() throws SQLException {
				return DriverManager.getConnection(url, "sa", "");
			}

			@Override
			public Connection getConnection(String username, String password) throws SQLException {
				return DriverManager.getConnection(url, username, password);
			}

			@Override
			public java.io.PrintWriter getLogWriter() {
				return null;
			}

			@Override
			public void setLogWriter(PrintWriter out) {
			}

			@Override
			public void setLoginTimeout(int seconds) {
			}

			@Override
			public int getLoginTimeout() {
				return 0;
			}

			@Override
			public Logger getParentLogger() {
				return null;
			}

			@Override
			public <T> T unwrap(Class<T> iface) {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean isWrapperFor(Class<?> iface) {
				return false;
			}
		};
	}

	private static DataSource memDataSource() {
		String url = "jdbc:hsqldb:mem:backup-svc-test-" + System.nanoTime();
		return new DataSource() {
			@Override
			public Connection getConnection() throws SQLException {
				return DriverManager.getConnection(url, "sa", "");
			}

			@Override
			public Connection getConnection(String username, String password) throws SQLException {
				return DriverManager.getConnection(url, username, password);
			}

			@Override
			public java.io.PrintWriter getLogWriter() {
				return null;
			}

			@Override
			public void setLogWriter(PrintWriter out) {
			}

			@Override
			public void setLoginTimeout(int seconds) {
			}

			@Override
			public int getLoginTimeout() {
				return 0;
			}

			@Override
			public Logger getParentLogger() {
				return null;
			}

			@Override
			public <T> T unwrap(Class<T> iface) {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean isWrapperFor(Class<?> iface) {
				return false;
			}
		};
	}

	private static BackupProperties properties(Path backupRoot, boolean requireMount) {
		BackupProperties props = new BackupProperties();
		props.setRoot(backupRoot.toString());
		props.setRequireMount(requireMount);
		props.setRetentionDays(14);
		return props;
	}

	@Test
	void backupNow_refusesWhenBackupRootIsNotAMountedFilesystem(@TempDir Path tempDir) throws Exception {
		Path dbDir = tempDir.resolve("db-source");
		Files.createDirectories(dbDir);
		seedRow(fileDataSource(dbDir));

		Path backupRoot = tempDir.resolve("backups"); // same filesystem as tempDir's parent - never a real mount
		DatabaseBackupService service = new DatabaseBackupService(fileDataSource(dbDir), properties(backupRoot, true), new BackupMountGuard());

		BackupOutcome outcome = service.backupNow();

		assertThat(outcome.success()).isFalse();
		assertThat(outcome.message()).contains("not a mounted filesystem");
		assertThat(Files.exists(backupRoot.resolve("db"))).isFalse();
	}

	@Test
	void backupNow_producesARealRestorableSnapshot_whenMountNotRequired(@TempDir Path tempDir) throws Exception {
		Path dbDir = tempDir.resolve("db-source");
		Files.createDirectories(dbDir);
		DataSource sourceDataSource = fileDataSource(dbDir);
		seedRow(sourceDataSource);

		Path backupRoot = tempDir.resolve("backups");
		DatabaseBackupService service = new DatabaseBackupService(sourceDataSource, properties(backupRoot, false), new BackupMountGuard());

		BackupOutcome outcome = service.backupNow();

		assertThat(outcome.success()).isTrue();
		assertThat(outcome.timestamp()).isNotNull();
		assertThat(outcome.sizeBytes()).isGreaterThan(0);

		Path snapshotDir = backupRoot.resolve("db").resolve(outcome.timestamp());
		assertThat(snapshotDir).isDirectory();

		// The real proof this is a working, restorable snapshot - not just
		// "some files got created" - open a brand new connection against
		// the *copied* database and read back the real row.
		String restoredUrl = "jdbc:hsqldb:file:" + snapshotDir.resolve("testdb") + ";shutdown=true";
		try (Connection restored = DriverManager.getConnection(restoredUrl, "sa", "");
				Statement st = restored.createStatement();
				var rs = st.executeQuery("SELECT NAME FROM BACKUP_PROBE")) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getString("NAME")).isEqualTo("real-row");
		}

		assertThat(service.listSnapshots()).containsExactly(outcome.timestamp());
		assertThat(service.snapshotSizeBytes(outcome.timestamp())).isEqualTo(outcome.sizeBytes());
	}

	@Test
	void backupNow_prunesSnapshotsOlderThanRetention_butKeepsRecentOnes(@TempDir Path tempDir) throws Exception {
		Path dbDir = tempDir.resolve("db-source");
		Files.createDirectories(dbDir);
		DataSource sourceDataSource = fileDataSource(dbDir);
		seedRow(sourceDataSource);

		Path backupRoot = tempDir.resolve("backups");
		BackupProperties props = properties(backupRoot, false);
		props.setRetentionDays(14);
		DatabaseBackupService service = new DatabaseBackupService(sourceDataSource, props, new BackupMountGuard());

		BackupOutcome first = service.backupNow();
		assertThat(first.success()).isTrue();

		// A directory this service would recognize as its own (parseable
		// timestamp name) but old enough that retention should remove it -
		// built directly rather than by waiting 14 real days, same
		// discipline as backdating fixtures elsewhere in this project.
		String ancientTimestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now().minusDays(40));
		Path ancientDir = backupRoot.resolve("db").resolve(ancientTimestamp);
		Files.createDirectories(ancientDir);
		Files.writeString(ancientDir.resolve("dummy.properties"), "not a real backup, just a retention-pruning fixture");

		// A name this service did NOT create (no timestamp shape at all) -
		// must never be touched by pruning, whatever else is in that
		// directory.
		Path unrelatedDir = backupRoot.resolve("db").resolve("not-a-timestamp");
		Files.createDirectories(unrelatedDir);

		// Force a distinct timestamp for the second real snapshot - two
		// calls landing in the same wall-clock second would otherwise both
		// resolve to the same directory name.
		Thread.sleep(1100);
		BackupOutcome second = service.backupNow();
		assertThat(second.success()).isTrue();
		assertThat(second.timestamp()).isNotEqualTo(first.timestamp());

		List<String> snapshots = service.listSnapshots();
		// containsExactly, not just contains - proves the ancient (pruned)
		// and unrelated (never this service's own naming) directories are
		// both excluded from the listing, not merely that the two real
		// snapshots happen to be present somewhere in it.
		assertThat(snapshots).containsExactly(second.timestamp(), first.timestamp()); // newest first
		assertThat(Files.exists(ancientDir)).isFalse(); // actually pruned from disk, not just excluded from the list
		assertThat(Files.exists(unrelatedDir)).isTrue(); // untouched - not this service's own naming
	}

	@Test
	void backupNow_failsGracefully_ratherThanThrowing_whenDatabaseDoesNotSupportOnlineBackup(@TempDir Path tempDir) throws Exception {
		// BACKUP DATABASE is a documented HSQLDB no-op/error against an
		// in-memory catalog - exactly the situation BackupControllerTest
		// exercises for real through the shared mem:testdb Spring context,
		// and in principle a real scenario against a not-yet-ported
		// database engine post-phase-2 (see this class's own javadoc).
		DataSource memDataSource = memDataSource();
		seedRow(memDataSource);

		Path backupRoot = tempDir.resolve("backups");
		DatabaseBackupService service = new DatabaseBackupService(memDataSource, properties(backupRoot, false), new BackupMountGuard());

		BackupOutcome outcome = service.backupNow();

		assertThat(outcome.success()).isFalse();
		assertThat(outcome.message()).isNotBlank();
	}

	private static void seedRow(DataSource dataSource) throws SQLException {
		try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
			st.execute("CREATE TABLE BACKUP_PROBE (NAME VARCHAR(50))");
			st.execute("INSERT INTO BACKUP_PROBE (NAME) VALUES ('real-row')");
			st.execute("CHECKPOINT");
		}
	}

	@Test
	void backupNow_refusesWhenMarkerFileIsConfiguredButMissing(@TempDir Path tempDir) throws Exception {
		Path dbDir = tempDir.resolve("db-source");
		Files.createDirectories(dbDir);
		seedRow(fileDataSource(dbDir));

		// The Docker case: the mount check proves nothing there, so the mount
		// requirement is off and the marker is the only safeguard.
		Path backupRoot = tempDir.resolve("backups");
		Files.createDirectories(backupRoot);
		BackupProperties props = properties(backupRoot, false);
		props.setMarkerFile(".myphotodiary-backup-target");
		DatabaseBackupService service = new DatabaseBackupService(fileDataSource(dbDir), props, new BackupMountGuard());

		BackupOutcome outcome = service.backupNow();

		assertThat(outcome.success()).isFalse();
		assertThat(outcome.message()).contains("no marker file .myphotodiary-backup-target");
		assertThat(Files.exists(backupRoot.resolve("db"))).isFalse();
	}

	@Test
	void backupNow_runsWhenMarkerFileIsPresent(@TempDir Path tempDir) throws Exception {
		Path dbDir = tempDir.resolve("db-source");
		Files.createDirectories(dbDir);
		seedRow(fileDataSource(dbDir));

		Path backupRoot = tempDir.resolve("backups");
		Files.createDirectories(backupRoot);
		Files.createFile(backupRoot.resolve(".myphotodiary-backup-target"));
		BackupProperties props = properties(backupRoot, false);
		props.setMarkerFile(".myphotodiary-backup-target");
		DatabaseBackupService service = new DatabaseBackupService(fileDataSource(dbDir), props, new BackupMountGuard());

		BackupOutcome outcome = service.backupNow();

		assertThat(outcome.success()).isTrue();
		assertThat(backupRoot.resolve("db").resolve(outcome.timestamp())).isDirectory();
	}
}
