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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The DB half of the backup feature (18/09/2026 - see {@link BackupProperties}
 * for the full design rationale, in particular the mount-guard reasoning).
 * Uses HSQLDB's own online-backup SQL command - {@code BACKUP DATABASE TO
 * '<dir>' BLOCKING AS FILES} - rather than copying the live {@code .data}/
 * {@code .script}/{@code .properties}/{@code .log} files by hand: it briefly
 * suspends writes (a silent {@code CHECKPOINT}, milliseconds for a
 * metadata-only database with no BLOBs - this app's images/videos live on
 * the filesystem, never in the DB) and copies a guaranteed-consistent
 * snapshot, with no service downtime required. {@code AS FILES} produces a
 * plain directory of the database's own native files (not a compressed
 * archive), so restore is just "stop the service, replace the live db/
 * directory with the chosen snapshot, restart" - no special HSQLDB restore
 * command needed (see the Installation Guide, §9.4).
 *
 * <p><b>Not supported against an in-memory ({@code mem:}) database</b> -
 * HSQLDB's own documented limitation, not a bug here: an in-memory catalog
 * has no files to snapshot. This backend's tests run against {@code
 * mem:testdb} (application.yml under {@code src/test/resources}), so {@link
 * DatabaseBackupServiceTest} constructs its own file-mode HSQLDB directly
 * rather than going through the shared {@code @SpringBootTest} context -
 * but {@link #backupNow()} itself still needs to fail *gracefully* (a
 * caught {@link SQLException}, not an uncaught 500) when pointed at a
 * database type that doesn't support this command, since that's a real
 * scenario this service can hit in this project's own test suite via
 * {@code BackupControllerTest} exercising the real {@code mem:} datasource
 * through the normal Spring wiring - and, in principle, on a
 * not-yet-migrated PostgreSQL target post-phase-2 (see class note below).
 *
 * <p>HSQLDB-specific by design, not because the seam wasn't considered:
 * {@link BackupOutcome}/the scheduling/the retention/the controller are all
 * database-engine-agnostic already. A PostgreSQL-based implementation
 * ({@code pg_dump}, phase 2) would only need to replace the body of {@link
 * #backupNow()} - everything around it stays the same.
 */
@Service
public class DatabaseBackupService {

	private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);
	// Colon isn't a safe filename character on every filesystem this backup
	// root might eventually live on (e.g. exFAT/NTFS NAS shares) - dashes
	// throughout, still lexicographically sortable, which the retention
	// pruning and "latest snapshot" logic both rely on.
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

	private final DataSource dataSource;
	private final BackupProperties properties;
	private final BackupMountGuard mountGuard;

	public DatabaseBackupService(DataSource dataSource, BackupProperties properties, BackupMountGuard mountGuard) {
		this.dataSource = dataSource;
		this.properties = properties;
		this.mountGuard = mountGuard;
	}

	public record BackupOutcome(boolean success, String message, String timestamp, Long sizeBytes) {
		static BackupOutcome skipped(String message) {
			return new BackupOutcome(false, message, null, null);
		}

		static BackupOutcome failed(String message) {
			return new BackupOutcome(false, message, null, null);
		}

		static BackupOutcome ok(String timestamp, long sizeBytes) {
			return new BackupOutcome(true, "ok", timestamp, sizeBytes);
		}
	}

	/**
	 * Nightly, on {@link BackupProperties#getDbCron()} - the first scheduled
	 * task in this project ({@code @EnableScheduling}, {@code
	 * CmsApplication}). Failures are logged, never thrown out of the
	 * scheduler thread (an uncaught exception here would silently kill all
	 * future scheduled runs, not just this one - Spring's default
	 * single-threaded scheduler behavior).
	 */
	// Inline default (matching BackupProperties' own) rather than a bare
	// "${backup.db-cron}" - found live while testing: this project's test
	// classpath resolves a single application.yml (src/test/resources'
	// own, which shadows src/main/resources' entirely rather than merging
	// with it - a Maven classpath-ordering detail, not a Spring one), so
	// without this fallback @Scheduled fails to even construct this bean
	// under @SpringBootTest with "Could not resolve placeholder
	// 'backup.db-cron'".
	@Scheduled(cron = "${backup.db-cron:0 0 2 * * *}")
	public void scheduledBackup() {
		BackupOutcome outcome = backupNow();
		if (!outcome.success()) {
			log.warn("scheduledBackup: {}", outcome.message());
		}
	}

	/**
	 * @return the outcome - never throws; a mount-guard refusal, a database
	 *         that doesn't support this command, or any other failure all
	 *         come back as {@code success=false} with a human-readable
	 *         {@code message}, for {@code BackupController} to surface as-is
	 *         rather than a generic 500.
	 */
	public BackupOutcome backupNow() {
		if (!properties.isEnabled()) {
			return BackupOutcome.skipped("backup.enabled is false");
		}

		Path root = Path.of(properties.getRoot());
		if (properties.isRequireMount() && !mountGuard.isMounted(root)) {
			return BackupOutcome.skipped(
					"backup root " + root + " is not a mounted filesystem - refusing to write a local-only \"backup\" silently "
							+ "(set backup.require-mount=false / MPD_BACKUP_REQUIRE_MOUNT=false to override deliberately)");
		}
		if (properties.hasMarkerFile() && !mountGuard.markerPresent(root, properties.getMarkerFile())) {
			return BackupOutcome.skipped(
					"backup root " + root + " has no marker file " + properties.getMarkerFile() + " - is the backup disk/NAS mounted? "
							+ "(create that file once on the real backup target)");
		}

		String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
		Path dbBackupRoot = root.resolve("db");
		Path snapshotDir = dbBackupRoot.resolve(timestamp);

		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			Files.createDirectories(dbBackupRoot);
			// HSQLDB requires the target path to end with "/" for AS FILES
			// (found live, not assumed - a first run failed with "Unsupported
			// suffix in file name ''. (Supported suffixes: /)"). Single-quote
			// doubling is HSQLDB/standard-SQL string-literal escaping -
			// BACKUP DATABASE is DDL-shaped, not a regular statement, so
			// there's no PreparedStatement parameter to bind the path into
			// instead.
			String escapedPath = (snapshotDir.toAbsolutePath() + "/").replace("'", "''");
			statement.execute("BACKUP DATABASE TO '" + escapedPath + "' BLOCKING AS FILES");
		} catch (SQLException | IOException e) {
			log.warn("backupNow: BACKUP DATABASE failed", e);
			return BackupOutcome.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
		}

		long sizeBytes = directorySize(snapshotDir);
		pruneOldSnapshots(dbBackupRoot, timestamp);
		log.info("backupNow: snapshot {} ({} bytes)", snapshotDir, sizeBytes);
		return BackupOutcome.ok(timestamp, sizeBytes);
	}

	/**
	 * Existing DB snapshot timestamps, most recent first - for the Admin
	 * Backup panel, {@code BackupController}. Same "only what this service's
	 * own naming recognizes" filter as {@link #pruneOldSnapshots} - a
	 * directory under {@code db/} that isn't one of this service's own
	 * timestamped snapshots (however it got there) is neither pruned nor
	 * listed here.
	 */
	public List<String> listSnapshots() {
		Path dbBackupRoot = Path.of(properties.getRoot()).resolve("db");
		if (!Files.isDirectory(dbBackupRoot)) {
			return List.of();
		}
		try (Stream<Path> entries = Files.list(dbBackupRoot)) {
			List<String> names = new ArrayList<>();
			entries.filter(Files::isDirectory)
					.map(p -> p.getFileName().toString())
					.filter(name -> parseTimestamp(name) != null)
					.forEach(names::add);
			names.sort(Comparator.reverseOrder());
			return names;
		} catch (IOException e) {
			return List.of();
		}
	}

	public long snapshotSizeBytes(String timestamp) {
		return directorySize(Path.of(properties.getRoot()).resolve("db").resolve(timestamp));
	}

	private void pruneOldSnapshots(Path dbBackupRoot, String justCreatedTimestamp) {
		LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
		try (Stream<Path> entries = Files.list(dbBackupRoot)) {
			entries.filter(Files::isDirectory)
					.filter(p -> !p.getFileName().toString().equals(justCreatedTimestamp))
					.forEach(p -> {
						LocalDateTime snapshotTime = parseTimestamp(p.getFileName().toString());
						// A directory name that doesn't parse as one of this
						// service's own timestamps is left alone rather than
						// guessed at - never delete something this code
						// didn't create itself just because retention pruning
						// is running.
						if (snapshotTime != null && snapshotTime.isBefore(cutoff)) {
							deleteRecursively(p);
						}
					});
		} catch (IOException e) {
			log.warn("pruneOldSnapshots: could not list {}", dbBackupRoot, e);
		}
	}

	private static LocalDateTime parseTimestamp(String name) {
		try {
			return LocalDateTime.parse(name, TIMESTAMP_FORMAT);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private void deleteRecursively(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (IOException | UncheckedIOException e) {
			log.warn("deleteRecursively: could not fully remove {}", dir, e);
		}
	}

	private static long directorySize(Path dir) {
		if (!Files.isDirectory(dir)) {
			return 0L;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0L;
				}
			}).sum();
		} catch (IOException e) {
			return 0L;
		}
	}
}
