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

package org.myphotodiary.cms.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real regression test for a real bug (31/08/2026, found running the actual
 * migration against a real production-sized legacy database, not a unit
 * test): {@code --dry-run} claimed
 * ("Rolled back - nothing was actually written") but silently committed
 * everything anyway, because {@code resetIdentityCounters}'s own
 * {@code ALTER TABLE ... RESTART WITH} DDL statements ran unconditionally,
 * before the dry-run/commit branch - HSQLDB (like most databases) implicitly
 * commits DDL regardless of {@code setAutoCommit(false)}, making the
 * subsequent {@code target.rollback()} a no-op with nothing left to undo.
 *
 * No test existed for this class at all before this - it's a standalone
 * JDBC tool outside the Spring context (this class's own javadoc), so it
 * had only ever been exercised manually. This builds a minimal but real
 * legacy-shaped fixture (plain JDBC {@code CREATE TABLE}/{@code INSERT},
 * not a hand-written {@code .script} file) and a real Flyway-migrated
 * target (the same migrations {@code CmsApplication} itself applies, via
 * Flyway's own fluent API, no Spring context needed), then asserts the
 * exact invariant the bug violated.
 */
class LegacyDataImporterTest {

	@Test
	void dryRun_leavesTargetDatabaseCompletelyUnchanged(@TempDir Path tempDir) throws Exception {
		String legacyPath = tempDir.resolve("legacy/photoindex").toString();
		String targetPath = tempDir.resolve("target/dev-db").toString();

		createLegacyFixture(legacyPath);
		migrateTargetSchema(targetPath);

		LegacyDataImporter.main(new String[] {legacyPath, targetPath, "--dry-run"});

		// The exact assertion the bug violated: every table the importer
		// writes to must be back to its pre-import state (just the
		// BootstrapAdminInitializer-equivalent seed this test never ran,
		// so genuinely empty) - not "looks empty from the printed summary",
		// queried directly against the target database itself.
		try (Connection target = DriverManager.getConnection("jdbc:hsqldb:file:" + targetPath + ";shutdown=true", "SA", "")) {
			assertThat(countRows(target, "app_group")).isZero();
			assertThat(countRows(target, "app_user")).isZero();
			assertThat(countRows(target, "role_assignment")).isZero();
			assertThat(countRows(target, "directory")).isZero();
			assertThat(countRows(target, "attribute")).isZero();
			assertThat(countRows(target, "image")).isZero();
			assertThat(countRows(target, "directory_attribute")).isZero();
			assertThat(countRows(target, "image_attribute")).isZero();
		}
	}

	@Test
	void realRun_actuallyPersistsEverythingIncludingJoinTables(@TempDir Path tempDir) throws Exception {
		String legacyPath = tempDir.resolve("legacy/photoindex").toString();
		String targetPath = tempDir.resolve("target/dev-db").toString();

		createLegacyFixture(legacyPath);
		migrateTargetSchema(targetPath);

		LegacyDataImporter.main(new String[] {legacyPath, targetPath});

		// The join tables specifically - directory_attribute/image_attribute
		// - are the ones whose "already exists, skip silently" branch (no
		// upsert, no counter) is what made the dry-run bug's leaked data
		// invisible in the printed summary on a second run. Asserted here
		// directly against real row counts, not the tool's own report.
		try (Connection target = DriverManager.getConnection("jdbc:hsqldb:file:" + targetPath + ";shutdown=true", "SA", "")) {
			assertThat(countRows(target, "directory")).isEqualTo(1);
			assertThat(countRows(target, "image")).isEqualTo(1);
			assertThat(countRows(target, "attribute")).isEqualTo(2);
			assertThat(countRows(target, "directory_attribute")).isEqualTo(1);
			assertThat(countRows(target, "image_attribute")).isEqualTo(1);
			// The real bug (31/08/2026): the leading "/" legacy always stores
			// must NOT survive into this app's own schema - a byPath lookup
			// for "2024/01/trip" (this app's own convention, never a leading
			// slash) has to actually find this row.
			try (var st = target.createStatement(); var rs = st.executeQuery("SELECT path FROM directory WHERE id = 1")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString("path")).isEqualTo("2024/01/trip");
			}
		}
	}

	private static int countRows(Connection c, String table) throws Exception {
		try (Statement st = c.createStatement()) {
			var rs = st.executeQuery("SELECT COUNT(*) FROM " + table);
			rs.next();
			return rs.getInt(1);
		}
	}

	/** Just enough of the legacy schema/data for one row to flow through every phase this importer has, including both join tables. */
	private static void createLegacyFixture(String legacyPath) throws Exception {
		try (Connection c = DriverManager.getConnection("jdbc:hsqldb:file:" + legacyPath + ";shutdown=true", "SA", "");
				Statement st = c.createStatement()) {
			st.execute("CREATE SCHEMA PHOTOINDEX AUTHORIZATION DBA");
			st.execute("SET SCHEMA PHOTOINDEX");
			st.execute("CREATE TABLE \"GROUP\" (GROUPNAME VARCHAR(255) PRIMARY KEY, CREATIONDATE DATE, DESCRIPTION VARCHAR(255))");
			st.execute("INSERT INTO \"GROUP\" VALUES ('public', '2020-01-01', 'legacy public group')");

			st.execute("CREATE TABLE USERCONFIGURATION (ID BIGINT PRIMARY KEY, MAXQUERYLENGTH INT, SLIDESHOWINTERVAL INT, DEFAULTLAT DOUBLE, DEFAULTLNG DOUBLE)");
			st.execute("INSERT INTO USERCONFIGURATION VALUES (1, 10, 5, 48.85, 2.35)");
			st.execute("CREATE TABLE \"USER\" (USERNAME VARCHAR(255) PRIMARY KEY, CREATIONDATE DATE, LONGNAME VARCHAR(255), PASSWORD VARCHAR(255), CONFIGURATIONID BIGINT)");
			st.execute("INSERT INTO \"USER\" VALUES ('alice', '2020-01-01', 'Alice A.', '$2a$10$fakebcryptfakebcryptfakebcryptfakebcryptfakebcrypt', 1)");

			st.execute("CREATE TABLE ROLEASSIGNMENT (GROUPNAME VARCHAR(255), USERNAME VARCHAR(255), ROLE VARCHAR(255), ISPRIMARY BOOLEAN)");
			st.execute("INSERT INTO ROLEASSIGNMENT VALUES ('public', 'alice', 'WRITER', TRUE)");

			st.execute("CREATE TABLE DIRECTORY (ID BIGINT PRIMARY KEY, \"DATE\" TIMESTAMP, DESCRIPTION VARCHAR(255), INDEXINGALLOWEDBOOLEAN BOOLEAN, "
					+ "LATITUDE DOUBLE, LONGITUDE DOUBLE, PATH VARCHAR(255), GROUPNAME VARCHAR(255))");
			// Leading "/" deliberately, matching real legacy data exactly (a
			// real production .script line, found live 31/08/2026: literal
			// '/2026/06/Bouquetins et Chamois') - not a synthetic
			// simplification. See LegacyDataImporter.normalizeLegacyPath's own
			// javadoc for the bug this fixture now guards against: copied
			// verbatim, this made the migrated row permanently unreachable by
			// path and caused this app's own first-browse-indexes fallback to
			// silently create a second, empty duplicate instead.
			st.execute("INSERT INTO DIRECTORY VALUES (1, '2024-01-01 00:00:00', 'a sequence', TRUE, 48.85, 2.35, '/2024/01/trip', 'public')");

			st.execute("CREATE TABLE ATTRIBUTE (ID BIGINT PRIMARY KEY, NAME VARCHAR(255), PARENT BIGINT)");
			st.execute("INSERT INTO ATTRIBUTE VALUES (1, 'root', NULL)");
			st.execute("INSERT INTO ATTRIBUTE VALUES (2, 'child', 1)");

			st.execute("CREATE TABLE IMAGE (ID BIGINT PRIMARY KEY, NAME VARCHAR(255), DESCRIPTION VARCHAR(255), RATING INT, \"DATE\" TIMESTAMP, DIRECTORY_ID BIGINT)");
			st.execute("INSERT INTO IMAGE VALUES (1, 'photo.jpg', 'a photo', 2, '2024-01-01 12:00:00', 1)");

			st.execute("CREATE TABLE DIRECTORY_ATTRIBUTE (DIRECTORY_ID BIGINT, ATTRIBUTE_ID BIGINT)");
			st.execute("INSERT INTO DIRECTORY_ATTRIBUTE VALUES (1, 1)");
			st.execute("CREATE TABLE IMAGE_ATTRIBUTE (IMAGE_ID BIGINT, ATTRIBUTE_ID BIGINT)");
			st.execute("INSERT INTO IMAGE_ATTRIBUTE VALUES (1, 2)");

			st.execute("SHUTDOWN");
		}
	}

	private static void migrateTargetSchema(String targetPath) {
		Flyway.configure()
				.dataSource("jdbc:hsqldb:file:" + targetPath + ";shutdown=true", "SA", "")
				.locations("classpath:db/migration")
				.load()
				.migrate();
	}
}
