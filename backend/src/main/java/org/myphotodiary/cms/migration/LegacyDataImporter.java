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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a legacy myPhotoDiary HSQLDB database (Hibernate `hbm2ddl.auto=update`
 * schema, catalog {@code PHOTOINDEX}) into this backend's own Flyway-managed
 * schema (V1-V11). A standalone tool, not part of the running app - a plain
 * {@code main()} using raw JDBC against two HSQLDB file-mode databases, run
 * once per real migration, not a Spring bean. See {@code backend/scripts/
 * import-legacy-data.sh} for the one-line terminal wrapper, and below for
 * the "is the schema different?" analysis this exists to answer with real
 * code, not just yes.
 *
 * <h2>Why the schema needed converting, not just copying</h2>
 * The legacy schema was never versioned SQL at all - Hibernate generated it
 * from JPA-annotated entities at every startup (Design.md §16.2). This
 * project's own schema (Flyway) is a deliberate fresh design (see V1/V2's
 * own migration comments), not a port:
 * <ul>
 *   <li>Every table/column renamed (UPPERCASE legacy names ->
 *       {@code snake_case}; {@code USER}/{@code GROUP} specifically avoided
 *       as table names - both are reserved words that caused real
 *       schema-qualification friction in the legacy app, Design.md §16.2).</li>
 *   <li>{@code role_assignment} now has a real foreign key to the user table
 *       too (legacy only ever enforced one to {@code GROUP}, not {@code USER}
 *       - this importer skips, rather than blindly copies, any role
 *       assignment for a username that doesn't resolve after the user import
 *       phase).</li>
 *   <li>Per-user settings (search page size, slideshow interval, default map
 *       center) moved from a separate joined {@code USERCONFIGURATION} row
 *       onto {@code app_user} itself directly (V4/V6/V7) - this importer
 *       follows that same join once, at read time, rather than keeping a
 *       separate settings table.</li>
 *   <li>{@code Image.orientation} is gone entirely - Thumbnailator bakes EXIF
 *       rotation into the pixels it writes (Design.md §16.7), so there is
 *       nothing left to persist for it. Nothing to migrate here either:
 *       correct orientation happens automatically once the real image files
 *       are copied onto disk and re-indexed through the running app (see the
 *       "what this tool does NOT do" section below) - not something this
 *       importer needs to read or fix in the database itself.</li>
 *   <li>{@code app_settings} (video size cap, V10/V11) has no legacy source
 *       at all - seeded with its own default by Flyway already, untouched
 *       here.</li>
 * </ul>
 *
 * <h2>A second, genuinely legacy table found while comparing schemas</h2>
 * The legacy database also has a {@code CONFIGURATION} table (mapped from
 * {@code SessionConfiguration.java}, {@code @Table(name="configuration")}) -
 * distinct from {@code USERCONFIGURATION}, keyed by a plain {@code user}
 * string column (not a real foreign key) and duplicating several of the same
 * fields (including, oddly, a second copy of the password - {@code pwd}).
 * Reading its source confirms it backs {@code SessionConfigurationSvr}'s
 * {@code /json/config} self-service settings endpoint as a request-scoped
 * DTO-shaped entity, not the canonical per-user settings record - that's
 * {@code USERCONFIGURATION}, joined via {@code USER.CONFIGURATIONID}, which
 * {@code ModelFactory.createUser} actually wires up at account creation.
 * <b>{@code CONFIGURATION} is deliberately not migrated</b> - if a specific
 * production user's settings only ever exist there and never made it back
 * into {@code USERCONFIGURATION}, this importer's own summary report won't
 * catch that; worth a manual spot-check against a real production database
 * before relying on this for a real cutover, not just this project's own
 * dev/test data.
 *
 * <h2>What this tool does NOT do</h2>
 * Only the HSQLDB persistence files - table rows - as literally asked. It
 * does not touch image/video files on disk at all, and does not generate
 * thumbnails. Both legacy and this backend already use the same storage
 * model (server filesystem, {@code {root}/{directory.path}/{image.name}} -
 * Design.md decision #6), so after running this tool the real remaining
 * steps are:
 * <ol>
 *   <li>Copy (e.g. {@code rsync -a}) the legacy {@code imageRootPath} tree
 *       into this backend's own {@code storage.root} - same relative
 *       directory/file layout, since {@code directory.path} is carried over
 *       unchanged by this importer.</li>
 *   <li>Start this backend and use Admin -&gt; Index management's own "Batch
 *       Publish" (recursive index) on the copied tree - it already knows how
 *       to (re)generate thumbnails for files that exist on disk with a
 *       matching DB row (exactly what this importer just created), reusing
 *       the exact same Thumbnailator pipeline every other upload goes
 *       through - correct EXIF-baked orientation included, with no need for
 *       this importer to read or re-derive the legacy {@code ORIENTATION}
 *       column at all.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>
 * mvn -q exec:java -Dexec.mainClass=org.myphotodiary.cms.migration.LegacyDataImporter \
 *     -Dexec.args="/path/to/legacy/photoindex /path/to/new/dev-db"
 * </pre>
 * Both paths are HSQLDB file-mode database prefixes (no {@code .script}
 * extension - matching {@code jdbc:hsqldb:file:<path>}), the same convention
 * this project's own {@code application.yml} uses for {@code storage.root}/
 * {@code MPD_DB_PATH}. <b>Stop both the legacy Tomcat app and this backend
 * first</b> - HSQLDB file-mode locks its database to one JVM process at a
 * time (the same constraint that keeps this backend's own dev database
 * separate from the legacy one, Design.md decision #4).
 * Add {@code --dry-run} to see the same counts/warnings this tool would
 * report without writing anything - everything runs in one transaction on
 * the target database regardless, rolled back instead of committed in that
 * mode.
 */
public final class LegacyDataImporter {

	private LegacyDataImporter() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Usage: LegacyDataImporter <legacy-db-path> <new-db-path> [--dry-run] [--legacy-user=SA] [--legacy-password=]");
			System.exit(1);
		}
		String legacyPath = args[0];
		String newPath = args[1];
		boolean dryRun = false;
		String legacyUser = "SA";
		String legacyPassword = "";
		for (int i = 2; i < args.length; i++) {
			String arg = args[i];
			if ("--dry-run".equals(arg)) {
				dryRun = true;
			} else if (arg.startsWith("--legacy-user=")) {
				legacyUser = arg.substring("--legacy-user=".length());
			} else if (arg.startsWith("--legacy-password=")) {
				legacyPassword = arg.substring("--legacy-password=".length());
			} else {
				System.err.println("Unknown argument: " + arg);
				System.exit(1);
			}
		}

		System.out.println((dryRun ? "[DRY RUN] " : "") + "Importing " + legacyPath + " -> " + newPath);

		try (Connection legacy = DriverManager.getConnection("jdbc:hsqldb:file:" + legacyPath + ";shutdown=true;readonly=true", legacyUser, legacyPassword);
				Connection target = DriverManager.getConnection("jdbc:hsqldb:file:" + newPath + ";shutdown=true", "sa", "")) {
			target.setAutoCommit(false);
			requireMigratedSchema(target);

			try {
				Report report = new Report();
				Map<String, String> groupsSeen = importGroups(legacy, target, report);
				Set<String> usersImported = importUsers(legacy, target, report);
				importRoleAssignments(legacy, target, usersImported, report);
				Set<Long> directoriesImported = importDirectories(legacy, target, groupsSeen, report);
				Set<Long> attributesImported = importAttributes(legacy, target, report);
				Set<Long> imagesImported = importImages(legacy, target, directoriesImported, report);
				importDirectoryAttributes(legacy, target, directoriesImported, attributesImported, report);
				importImageAttributes(legacy, target, imagesImported, attributesImported, report);

				if (dryRun) {
					// resetIdentityCounters deliberately NOT called here (31/08/2026,
					// a real bug found running this against a real production-sized
					// database, not a unit test) - it runs
					// `ALTER TABLE ... RESTART WITH`, which is DDL, and HSQLDB (like
					// most databases) implicitly commits DDL regardless of
					// setAutoCommit(false). Calling it before this rollback made the
					// rollback a no-op with nothing left to undo - dry-run was
					// silently committing everything. There's nothing to reset for a
					// run whose data is about to be discarded anyway.
					target.rollback();
					System.out.println("\n[DRY RUN] Rolled back - nothing was actually written.");
				} else {
					resetIdentityCounters(target);
					target.commit();
					System.out.println("\nCommitted.");
				}
				report.print();
			} catch (Exception e) {
				// Explicit rollback, not just relying on the implicit one most JDBC
				// drivers do on a connection close with uncommitted changes - this
				// keeps a failed run's "did anything get written?" answer an
				// unambiguous "no" regardless of driver behavior, and re-running
				// this tool after fixing whatever failed is always safe.
				target.rollback();
				System.err.println("\nImport failed and was rolled back - nothing was written. Safe to fix the issue and re-run.");
				throw e;
			}
		}
	}

	/** Fails fast with a clear message rather than a confusing mid-import SQL error if the target hasn't been migrated (V1-V11) yet. */
	private static void requireMigratedSchema(Connection target) throws SQLException {
		try (Statement st = target.createStatement()) {
			st.executeQuery("SELECT 1 FROM app_settings"); // only exists from V10 onward - the last table this importer's own schema comparison covers
		} catch (SQLException e) {
			throw new IllegalStateException(
					"Target database doesn't look like it has this backend's own schema (V1-V11) applied yet - "
							+ "start this backend once against it (Flyway runs automatically on startup) before running this importer.",
					e);
		}
	}

	// ------------------------------------------------------------------
	// Groups
	// ------------------------------------------------------------------

	/**
	 * @return every group_name that exists in the target after this phase (imported from legacy, or already there).
	 * Upserts, not a plain INSERT: the target database's own first-run
	 * bootstrap ({@code BootstrapAdminInitializer}) already seeds a default
	 * "public" group (and an "admin" user, see importUsers) before this tool
	 * ever runs - found by actually running this against a freshly-migrated
	 * scratch database, not assumed. Legacy's own real "public" group row
	 * (real creation date/description) should win over that placeholder
	 * default, not collide with it.
	 */
	private static Map<String, String> importGroups(Connection legacy, Connection target, Report report) throws SQLException {
		Map<String, String> imported = new LinkedHashMap<>();
		try (Statement st = legacy.createStatement();
				ResultSet rs = st.executeQuery("SELECT GROUPNAME, CREATIONDATE, DESCRIPTION FROM PHOTOINDEX.\"GROUP\"")) {
			while (rs.next()) {
				String name = rs.getString("GROUPNAME");
				upsertGroup(target, name, rs.getString("DESCRIPTION"), rs.getDate("CREATIONDATE"));
				imported.put(name, name);
				report.groupsImported++;
			}
		}
		return imported;
	}

	/** Auto-creates a group referenced by a directory/role assignment but missing from PHOTOINDEX.GROUP itself - legacy never enforced that foreign key, this backend's schema does. */
	private static void ensureGroupExists(Connection target, Map<String, String> groupsSeen, String groupName, Report report) throws SQLException {
		if (groupName == null || groupsSeen.containsKey(groupName)) {
			return;
		}
		upsertGroup(target, groupName, "Auto-created by LegacyDataImporter - referenced but missing from the legacy GROUP table", null);
		groupsSeen.put(groupName, groupName);
		report.groupsAutoCreated.add(groupName);
	}

	private static void upsertGroup(Connection target, String groupName, String description, java.sql.Date creationDate) throws SQLException {
		if (rowExists(target, "app_group", "group_name", groupName)) {
			try (PreparedStatement upd = target.prepareStatement("UPDATE app_group SET description = ?, creation_date = ? WHERE group_name = ?")) {
				upd.setString(1, description);
				upd.setObject(2, creationDate != null ? creationDate : java.sql.Date.valueOf(LocalDate.now()));
				upd.setString(3, groupName);
				upd.executeUpdate();
			}
			return;
		}
		try (PreparedStatement ins = target.prepareStatement(
				"INSERT INTO app_group (group_name, description, creation_date) VALUES (?, ?, ?)")) {
			ins.setString(1, groupName);
			ins.setString(2, description);
			ins.setObject(3, creationDate != null ? creationDate : java.sql.Date.valueOf(LocalDate.now()));
			ins.executeUpdate();
		}
	}

	private static boolean rowExists(Connection target, String table, String keyColumn, String keyValue) throws SQLException {
		try (PreparedStatement st = target.prepareStatement("SELECT 1 FROM " + table + " WHERE " + keyColumn + " = ?")) {
			st.setString(1, keyValue);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next();
			}
		}
	}

	// ------------------------------------------------------------------
	// Users (+ their USERCONFIGURATION settings, joined at read time)
	// ------------------------------------------------------------------

	/**
	 * @return every user_name actually imported - role_assignment import uses this to skip rows referencing a user that didn't make it in.
	 * Upserts, same reason as {@link #importGroups} - the target's own
	 * bootstrap already seeds a default "admin" user before this tool runs;
	 * legacy's real row (with its real password hash, role, settings) should
	 * win over that placeholder.
	 */
	private static Set<String> importUsers(Connection legacy, Connection target, Report report) throws SQLException {
		Set<String> imported = new HashSet<>();
		String sql = "SELECT u.USERNAME, u.CREATIONDATE, u.LONGNAME, u.PASSWORD, "
				+ "uc.MAXQUERYLENGTH, uc.SLIDESHOWINTERVAL, uc.DEFAULTLAT, uc.DEFAULTLNG "
				+ "FROM PHOTOINDEX.\"USER\" u LEFT JOIN PHOTOINDEX.USERCONFIGURATION uc ON u.CONFIGURATIONID = uc.ID";
		try (Statement st = legacy.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				String userName = rs.getString("USERNAME");
				String password = rs.getString("PASSWORD");
				if (password == null || !password.startsWith("$2")) {
					// Not a bcrypt hash (bcrypt hashes always start with $2a$/$2b$/$2y$) - a
					// user who hasn't logged in since the legacy password-hashing fix
					// (that fix's own lazy rehash-on-login) would still be plaintext
					// here. Copying it as-is would never authenticate against this
					// backend's own plain BCryptPasswordEncoder (no legacy-compatible
					// fallback exists here - UserService/SecurityConfig, checked before
					// writing this importer). Imported anyway (so the account/role/
					// directory data isn't lost), but flagged for a forced reset.
					report.usersNeedingPasswordReset.add(userName);
				}
				int maxQueryLength = rs.getInt("MAXQUERYLENGTH");
				Integer maxQueryLengthOrNull = rs.wasNull() ? null : maxQueryLength;
				int slideShowInterval = rs.getInt("SLIDESHOWINTERVAL");
				Integer slideShowIntervalOrNull = rs.wasNull() ? null : slideShowInterval;
				double defaultLat = rs.getDouble("DEFAULTLAT");
				Double defaultLatOrNull = rs.wasNull() ? null : defaultLat;
				double defaultLng = rs.getDouble("DEFAULTLNG");
				Double defaultLngOrNull = rs.wasNull() ? null : defaultLng;

				upsertUser(target, userName, rs.getString("LONGNAME"), password, rs.getDate("CREATIONDATE"), maxQueryLengthOrNull,
						slideShowIntervalOrNull, defaultLatOrNull, defaultLngOrNull);
				imported.add(userName);
				report.usersImported++;
			}
		}
		return imported;
	}

	private static void upsertUser(Connection target, String userName, String longName, String password, java.sql.Date creationDate,
			Integer maxQueryLength, Integer slideShowInterval, Double defaultLat, Double defaultLng) throws SQLException {
		int mql = maxQueryLength != null ? maxQueryLength : 10;
		int ssi = slideShowInterval != null ? slideShowInterval : 5;
		double lat = defaultLat != null ? defaultLat : 48.8567;
		double lng = defaultLng != null ? defaultLng : 2.3508;
		java.sql.Date created = creationDate != null ? creationDate : java.sql.Date.valueOf(LocalDate.now());

		if (rowExists(target, "app_user", "user_name", userName)) {
			// postit_fade_delay deliberately left untouched here (no legacy
			// source at all - V8's own comment, a new preference, not a port),
			// whatever value the target row already has (its own column
			// default, most likely) stays as-is.
			try (PreparedStatement upd = target.prepareStatement(
					"UPDATE app_user SET long_name = ?, password = ?, creation_date = ?, max_query_length = ?, slide_show_interval = ?, "
							+ "default_latitude = ?, default_longitude = ? WHERE user_name = ?")) {
				upd.setString(1, longName);
				upd.setString(2, password);
				upd.setObject(3, created);
				upd.setInt(4, mql);
				upd.setInt(5, ssi);
				upd.setDouble(6, lat);
				upd.setDouble(7, lng);
				upd.setString(8, userName);
				upd.executeUpdate();
			}
			return;
		}
		try (PreparedStatement ins = target.prepareStatement(
				"INSERT INTO app_user (user_name, long_name, password, creation_date, max_query_length, slide_show_interval, "
						+ "default_latitude, default_longitude) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
			ins.setString(1, userName);
			ins.setString(2, longName);
			ins.setString(3, password);
			ins.setObject(4, created);
			ins.setInt(5, mql);
			ins.setInt(6, ssi);
			ins.setDouble(7, lat);
			ins.setDouble(8, lng);
			// postit_fade_delay: no legacy source at all - left to its column default (8).
			ins.executeUpdate();
		}
	}

	// ------------------------------------------------------------------
	// Role assignments
	// ------------------------------------------------------------------

	private static void importRoleAssignments(Connection legacy, Connection target, Set<String> usersImported, Report report) throws SQLException {
		try (Statement st = legacy.createStatement();
				ResultSet rs = st.executeQuery("SELECT GROUPNAME, USERNAME, ROLE, ISPRIMARY FROM PHOTOINDEX.ROLEASSIGNMENT")) {
			while (rs.next()) {
				String userName = rs.getString("USERNAME");
				String groupName = rs.getString("GROUPNAME");
				String role = rs.getString("ROLE");
				if (!usersImported.contains(userName)) {
					// Legacy only ever enforced a foreign key to GROUP here, never to
					// USER (confirmed from the actual generated DDL, not assumed) - a
					// role assignment for a since-deleted or never-really-existing user
					// is possible there in a way it can't be here.
					report.roleAssignmentsSkippedMissingUser.add(userName + "/" + groupName);
					continue;
				}
				if (role == null) {
					report.roleAssignmentsSkippedNullRole.add(userName + "/" + groupName);
					continue;
				}
				boolean isPrimary = rs.getBoolean("ISPRIMARY"); // getBoolean() on a SQL NULL returns false - the right coalesce here, same as the column's own NOT NULL default intent

				// Upsert, same reason as importGroups/importUsers - a re-run
				// against a target this same tool already populated (confirmed
				// live: running this importer twice in a row against the same
				// target failed on this exact table's primary key before this
				// fix) must not fail just because the row is already there.
				if (rowExistsCompositeKey(target, "role_assignment", "user_name", userName, "group_name", groupName)) {
					try (PreparedStatement upd = target.prepareStatement(
							"UPDATE role_assignment SET role = ?, is_primary = ? WHERE user_name = ? AND group_name = ?")) {
						upd.setString(1, role);
						upd.setBoolean(2, isPrimary);
						upd.setString(3, userName);
						upd.setString(4, groupName);
						upd.executeUpdate();
					}
				} else {
					try (PreparedStatement ins = target.prepareStatement(
							"INSERT INTO role_assignment (user_name, group_name, role, is_primary) VALUES (?, ?, ?, ?)")) {
						ins.setString(1, userName);
						ins.setString(2, groupName);
						ins.setString(3, role);
						ins.setBoolean(4, isPrimary);
						ins.executeUpdate();
					}
				}
				report.roleAssignmentsImported++;
			}
		}
	}

	private static boolean rowExistsCompositeKey(Connection target, String table, String col1, String val1, String col2, String val2) throws SQLException {
		try (PreparedStatement st = target.prepareStatement("SELECT 1 FROM " + table + " WHERE " + col1 + " = ? AND " + col2 + " = ?")) {
			st.setString(1, val1);
			st.setString(2, val2);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next();
			}
		}
	}

	// ------------------------------------------------------------------
	// Directories
	// ------------------------------------------------------------------

	/**
	 * @return every legacy directory id actually imported - image import uses this to skip rows pointing at a directory that didn't make it in.
	 * Upserts by id (same "safe to re-run" reasoning as importGroups/
	 * importUsers/importRoleAssignments - confirmed live that a second run
	 * against an already-populated target fails on a plain INSERT).
	 */
	private static Set<Long> importDirectories(Connection legacy, Connection target, Map<String, String> groupsSeen, Report report) throws SQLException {
		Set<Long> imported = new HashSet<>();
		String sql = "SELECT ID, DATE, DESCRIPTION, INDEXINGALLOWEDBOOLEAN, LATITUDE, LONGITUDE, PATH, GROUPNAME FROM PHOTOINDEX.DIRECTORY";
		try (Statement st = legacy.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				long id = rs.getLong("ID");
				String groupName = rs.getString("GROUPNAME");
				if (groupName == null || groupName.isBlank()) {
					groupName = "public"; // same default this backend's own GalleryService/ImportService fall back to
				}
				ensureGroupExists(target, groupsSeen, groupName, report);

				Timestamp legacyDate = rs.getTimestamp("DATE");
				LocalDate sequenceDate = legacyDate != null ? legacyDate.toLocalDateTime().toLocalDate() : null;
				// creation_date has no real legacy source (Directory never recorded a
				// row-creation timestamp separately from its own nominal DATE) - the
				// sequence's own date is the closest honest proxy, falling back to
				// "today" (the import run) only when even that is null, rather than
				// leaving a NOT NULL column to guess at some other invented value.
				LocalDate creationDate = sequenceDate != null ? sequenceDate : LocalDate.now();
				// Legacy stores every path with a leading "/" (confirmed directly in
				// a real production .script file, 31/08/2026: literal
				// '/2026/06/Bouquetins et Chamois') - this app's own filesystem-
				// driven convention never has one (PathUtil.resolveUnderRoot,
				// every tree/byPath lookup throughout this codebase). Copied
				// verbatim before this fix: the real migrated row became
				// permanently unreachable by path (`directory.path` is UNIQUE),
				// and the first browse to that same logical sequence silently
				// created a SECOND, empty row instead of 404-ing loudly - found
				// live by comparing a real .script line against the API's own
				// response for what looked like "the same" directory and getting
				// a different id back with description: null.
				String path = normalizeLegacyPath(rs.getString("PATH"));
				String description = rs.getString("DESCRIPTION");
				Double latitude = nullableDouble(rs, "LATITUDE");
				Double longitude = nullableDouble(rs, "LONGITUDE");
				boolean indexingAllowed = rs.getBoolean("INDEXINGALLOWEDBOOLEAN"); // NULL -> false; legacy's own column has no NOT NULL/default either, so no better signal exists to prefer true

				if (rowExistsById(target, "directory", id)) {
					try (PreparedStatement upd = target.prepareStatement(
							"UPDATE directory SET path = ?, group_name = ?, creation_date = ?, description = ?, latitude = ?, longitude = ?, "
									+ "indexing_allowed = ?, sequence_date = ? WHERE id = ?")) {
						upd.setString(1, path);
						upd.setString(2, groupName);
						upd.setObject(3, creationDate);
						upd.setString(4, description);
						setNullableDouble(upd, 5, latitude);
						setNullableDouble(upd, 6, longitude);
						upd.setBoolean(7, indexingAllowed);
						upd.setObject(8, sequenceDate);
						upd.setLong(9, id);
						upd.executeUpdate();
					}
				} else {
					try (PreparedStatement ins = target.prepareStatement(
							"INSERT INTO directory (id, path, group_name, creation_date, description, latitude, longitude, indexing_allowed, sequence_date) "
									+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
						ins.setLong(1, id);
						ins.setString(2, path);
						ins.setString(3, groupName);
						ins.setObject(4, creationDate);
						ins.setString(5, description);
						setNullableDouble(ins, 6, latitude);
						setNullableDouble(ins, 7, longitude);
						ins.setBoolean(8, indexingAllowed);
						ins.setObject(9, sequenceDate);
						ins.executeUpdate();
					}
				}
				imported.add(id);
				report.directoriesImported++;
			}
		}
		return imported;
	}

	// ------------------------------------------------------------------
	// Attributes (self-referencing hierarchy - two passes so insert order
	// never has to match parent/child order)
	// ------------------------------------------------------------------

	private static Set<Long> importAttributes(Connection legacy, Connection target, Report report) throws SQLException {
		Set<Long> imported = new HashSet<>();
		Map<Long, Long> parentByChild = new LinkedHashMap<>();
		try (Statement st = legacy.createStatement(); ResultSet rs = st.executeQuery("SELECT ID, NAME, PARENT FROM PHOTOINDEX.ATTRIBUTE")) {
			while (rs.next()) {
				long id = rs.getLong("ID");
				String name = rs.getString("NAME");
				// parent_id always NULL in this first pass (upsert-by-id, same "safe
				// to re-run" reasoning as directory/group/user/role_assignment above)
				// - the second pass below sets every real parent_id only once every
				// attribute row is guaranteed to already exist, so insert order never
				// has to match parent/child order either way.
				if (rowExistsById(target, "attribute", id)) {
					try (PreparedStatement upd = target.prepareStatement("UPDATE attribute SET name = ?, parent_id = NULL WHERE id = ?")) {
						upd.setString(1, name);
						upd.setLong(2, id);
						upd.executeUpdate();
					}
				} else {
					try (PreparedStatement ins = target.prepareStatement("INSERT INTO attribute (id, name, parent_id) VALUES (?, ?, NULL)")) {
						ins.setLong(1, id);
						ins.setString(2, name);
						ins.executeUpdate();
					}
				}
				imported.add(id);
				report.attributesImported++;
				long parent = rs.getLong("PARENT");
				if (!rs.wasNull()) {
					parentByChild.put(id, parent);
				}
			}
		}
		try (PreparedStatement upd = target.prepareStatement("UPDATE attribute SET parent_id = ? WHERE id = ?")) {
			for (Map.Entry<Long, Long> entry : parentByChild.entrySet()) {
				if (!imported.contains(entry.getValue())) {
					report.attributeParentsSkippedMissing.add(entry.getKey() + " -> " + entry.getValue());
					continue;
				}
				upd.setLong(1, entry.getValue());
				upd.setLong(2, entry.getKey());
				upd.executeUpdate();
			}
		}
		return imported;
	}

	// ------------------------------------------------------------------
	// Images
	// ------------------------------------------------------------------

	private static Set<Long> importImages(Connection legacy, Connection target, Set<Long> directoriesImported, Report report) throws SQLException {
		Set<Long> imported = new HashSet<>();
		String sql = "SELECT ID, NAME, DESCRIPTION, RATING, DATE, DIRECTORY_ID FROM PHOTOINDEX.IMAGE";
		try (Statement st = legacy.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				long id = rs.getLong("ID");
				String name = rs.getString("NAME");
				long directoryId = rs.getLong("DIRECTORY_ID");
				boolean directoryIdNull = rs.wasNull();
				if (name == null || name.isBlank()) {
					// New schema requires image.name NOT NULL - legacy's own column
					// never did. Shouldn't happen in real data, but this importer
					// can't invent a filename that would still resolve to a real file
					// on disk, so this row is skipped rather than guessed at.
					report.imagesSkippedNoName.add(String.valueOf(id));
					continue;
				}
				if (directoryIdNull || !directoriesImported.contains(directoryId)) {
					report.imagesSkippedMissingDirectory.add(id + " (" + name + ")");
					continue;
				}
				String description = rs.getString("DESCRIPTION");
				int ratingValue = rs.getInt("RATING");
				int rating = rs.wasNull() ? -1 : ratingValue; // -1 = NOT_RATED, same sentinel this backend's own Image entity already uses
				Timestamp captureDate = rs.getTimestamp("DATE");

				if (rowExistsById(target, "image", id)) {
					try (PreparedStatement upd = target.prepareStatement(
							"UPDATE image SET name = ?, description = ?, rating = ?, capture_date = ?, directory_id = ? WHERE id = ?")) {
						upd.setString(1, name);
						upd.setString(2, description);
						upd.setInt(3, rating);
						upd.setObject(4, captureDate);
						upd.setLong(5, directoryId);
						upd.setLong(6, id);
						upd.executeUpdate();
					}
				} else {
					try (PreparedStatement ins = target.prepareStatement(
							"INSERT INTO image (id, name, description, rating, capture_date, directory_id, media_type) VALUES (?, ?, ?, ?, ?, ?, 'IMAGE')")) {
						ins.setLong(1, id);
						ins.setString(2, name);
						ins.setString(3, description);
						ins.setInt(4, rating);
						ins.setObject(5, captureDate);
						ins.setLong(6, directoryId);
						ins.executeUpdate();
					}
				}
				imported.add(id);
				report.imagesImported++;
			}
		}
		return imported;
	}

	// ------------------------------------------------------------------
	// directory_attribute / image_attribute join tables - legacy declared
	// neither a primary key nor foreign keys on these two tables at all (the
	// real generated DDL has none - confirmed, not assumed), so duplicate
	// rows are possible there in a way the new schema's own composite
	// primary key would reject outright.
	// ------------------------------------------------------------------

	private static void importDirectoryAttributes(Connection legacy, Connection target, Set<Long> directoriesImported, Set<Long> attributesImported, Report report) throws SQLException {
		Set<String> seen = new HashSet<>();
		try (Statement st = legacy.createStatement();
				ResultSet rs = st.executeQuery("SELECT DIRECTORY_ID, ATTRIBUTE_ID FROM PHOTOINDEX.DIRECTORY_ATTRIBUTE")) {
			while (rs.next()) {
				long directoryId = rs.getLong("DIRECTORY_ID");
				long attributeId = rs.getLong("ATTRIBUTE_ID");
				String key = directoryId + "/" + attributeId;
				if (!seen.add(key)) {
					report.directoryAttributesDeduplicated++;
					continue;
				}
				if (!directoriesImported.contains(directoryId) || !attributesImported.contains(attributeId)) {
					report.directoryAttributesSkippedMissingRef++;
					continue;
				}
				// No columns beyond the pair itself to update on a re-run - skip
				// (not upsert) if the association is already there.
				if (rowExistsCompositeKeyLong(target, "directory_attribute", "directory_id", directoryId, "attribute_id", attributeId)) {
					continue;
				}
				try (PreparedStatement ins = target.prepareStatement("INSERT INTO directory_attribute (directory_id, attribute_id) VALUES (?, ?)")) {
					ins.setLong(1, directoryId);
					ins.setLong(2, attributeId);
					ins.executeUpdate();
				}
				report.directoryAttributesImported++;
			}
		}
	}

	private static void importImageAttributes(Connection legacy, Connection target, Set<Long> imagesImported, Set<Long> attributesImported, Report report) throws SQLException {
		Set<String> seen = new HashSet<>();
		try (Statement st = legacy.createStatement();
				ResultSet rs = st.executeQuery("SELECT IMAGE_ID, ATTRIBUTE_ID FROM PHOTOINDEX.IMAGE_ATTRIBUTE")) {
			while (rs.next()) {
				long imageId = rs.getLong("IMAGE_ID");
				long attributeId = rs.getLong("ATTRIBUTE_ID");
				String key = imageId + "/" + attributeId;
				if (!seen.add(key)) {
					report.imageAttributesDeduplicated++;
					continue;
				}
				if (!imagesImported.contains(imageId) || !attributesImported.contains(attributeId)) {
					report.imageAttributesSkippedMissingRef++;
					continue;
				}
				if (rowExistsCompositeKeyLong(target, "image_attribute", "image_id", imageId, "attribute_id", attributeId)) {
					continue;
				}
				try (PreparedStatement ins = target.prepareStatement("INSERT INTO image_attribute (image_id, attribute_id) VALUES (?, ?)")) {
					ins.setLong(1, imageId);
					ins.setLong(2, attributeId);
					ins.executeUpdate();
				}
				report.imageAttributesImported++;
			}
		}
	}

	private static boolean rowExistsCompositeKeyLong(Connection target, String table, String col1, long val1, String col2, long val2) throws SQLException {
		try (PreparedStatement st = target.prepareStatement("SELECT 1 FROM " + table + " WHERE " + col1 + " = ? AND " + col2 + " = ?")) {
			st.setLong(1, val1);
			st.setLong(2, val2);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next();
			}
		}
	}

	// ------------------------------------------------------------------
	// Legacy row IDs are preserved as-is (directory/attribute/image are all
	// `GENERATED BY DEFAULT AS IDENTITY`, which - unlike `GENERATED ALWAYS` -
	// permits an explicit value on INSERT) so every foreign key/join-table
	// reference carried over from legacy still points at the right row
	// without this importer needing to remap a single id anywhere above.
	// Once every row is in, each sequence needs restarting past the highest
	// imported id, or the very next row this backend's own app creates
	// normally would collide with one just imported here.
	// ------------------------------------------------------------------
	private static void resetIdentityCounters(Connection target) throws SQLException {
		restartIdentity(target, "directory", "id");
		restartIdentity(target, "attribute", "id");
		restartIdentity(target, "image", "id");
	}

	private static void restartIdentity(Connection target, String table, String column) throws SQLException {
		long max;
		try (Statement st = target.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(" + column + ") FROM " + table)) {
			rs.next();
			max = rs.getLong(1); // 0 if the table ended up empty - rs.wasNull() not needed, RESTART WITH 1 is still correct in that case
		}
		try (Statement st = target.createStatement()) {
			st.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " RESTART WITH " + (max + 1));
		}
	}

	// ------------------------------------------------------------------
	// Small JDBC null-coalescing helpers - HSQLDB's own getDouble returns 0
	// (not SQL NULL) for a SQL NULL, and directory.latitude/longitude are
	// genuinely nullable in both schemas (no default to coalesce to, unlike
	// app_user's own default_latitude/default_longitude - see upsertUser).
	// ------------------------------------------------------------------

	/**
	 * Strips a leading/trailing {@code "/"} from a legacy directory path
	 * (31/08/2026 - see {@link #importDirectories}'s own comment for the
	 * real bug this fixes). Legacy's root directory is stored as {@code "/"}
	 * itself, which this reduces to {@code ""} - this app's own convention
	 * for root (GalleryScreen.handleSelectRoot, MobileApp's root case),
	 * not a special case here.
	 */
	private static String normalizeLegacyPath(String path) {
		if (path == null) return null;
		int start = 0;
		int end = path.length();
		while (start < end && path.charAt(start) == '/') start++;
		while (end > start && path.charAt(end - 1) == '/') end--;
		return path.substring(start, end);
	}

	private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private static void setNullableDouble(PreparedStatement st, int index, Double value) throws SQLException {
		if (value == null) {
			st.setNull(index, java.sql.Types.DOUBLE);
		} else {
			st.setDouble(index, value);
		}
	}

	private static boolean rowExistsById(Connection target, String table, long id) throws SQLException {
		try (PreparedStatement st = target.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
			st.setLong(1, id);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next();
			}
		}
	}

	/** Everything printed at the end - counts plus every warning that needs a human to look at it, not buried in scrolled-past log lines. */
	private static final class Report {
		int groupsImported;
		int usersImported;
		int roleAssignmentsImported;
		int directoriesImported;
		int attributesImported;
		int imagesImported;
		int directoryAttributesImported;
		int imageAttributesImported;
		int directoryAttributesDeduplicated;
		int imageAttributesDeduplicated;
		int directoryAttributesSkippedMissingRef;
		int imageAttributesSkippedMissingRef;

		final List<String> groupsAutoCreated = new ArrayList<>();
		final List<String> usersNeedingPasswordReset = new ArrayList<>();
		final List<String> roleAssignmentsSkippedMissingUser = new ArrayList<>();
		final List<String> roleAssignmentsSkippedNullRole = new ArrayList<>();
		final List<String> imagesSkippedNoName = new ArrayList<>();
		final List<String> imagesSkippedMissingDirectory = new ArrayList<>();
		final List<String> attributeParentsSkippedMissing = new ArrayList<>();

		void print() {
			System.out.println("\n=== Import summary ===");
			System.out.println("Groups:              " + groupsImported + (groupsAutoCreated.isEmpty() ? "" : " (+" + groupsAutoCreated.size() + " auto-created)"));
			System.out.println("Users:                " + usersImported);
			System.out.println("Role assignments:     " + roleAssignmentsImported);
			System.out.println("Directories:          " + directoriesImported);
			System.out.println("Attributes:           " + attributesImported);
			System.out.println("Images:               " + imagesImported);
			System.out.println("Directory attributes: " + directoryAttributesImported
					+ (directoryAttributesDeduplicated > 0 ? " (" + directoryAttributesDeduplicated + " duplicate rows in legacy skipped)" : ""));
			System.out.println("Image attributes:     " + imageAttributesImported
					+ (imageAttributesDeduplicated > 0 ? " (" + imageAttributesDeduplicated + " duplicate rows in legacy skipped)" : ""));

			boolean anyWarning = !groupsAutoCreated.isEmpty() || !usersNeedingPasswordReset.isEmpty() || !roleAssignmentsSkippedMissingUser.isEmpty()
					|| !roleAssignmentsSkippedNullRole.isEmpty() || !imagesSkippedNoName.isEmpty() || !imagesSkippedMissingDirectory.isEmpty()
					|| !attributeParentsSkippedMissing.isEmpty() || directoryAttributesSkippedMissingRef > 0 || imageAttributesSkippedMissingRef > 0;
			if (!anyWarning) {
				System.out.println("\nNo warnings.");
				return;
			}
			System.out.println("\n=== Warnings - review before treating this migration as done ===");
			printIfAny("Groups auto-created (referenced by a directory/role but missing from legacy GROUP)", groupsAutoCreated);
			printIfAny("Users copied with a NON-bcrypt password - MUST have their password reset before they can log in", usersNeedingPasswordReset);
			printIfAny("Role assignments skipped - username not found among imported users", roleAssignmentsSkippedMissingUser);
			printIfAny("Role assignments skipped - NULL role in legacy data", roleAssignmentsSkippedNullRole);
			printIfAny("Images skipped - no name in legacy data", imagesSkippedNoName);
			printIfAny("Images skipped - directory not found among imported directories", imagesSkippedMissingDirectory);
			printIfAny("Attribute parent links skipped - parent id not found among imported attributes", attributeParentsSkippedMissing);
			if (directoryAttributesSkippedMissingRef > 0) {
				System.out.println("- " + directoryAttributesSkippedMissingRef + " directory_attribute row(s) skipped - directory or attribute not found among imported rows");
			}
			if (imageAttributesSkippedMissingRef > 0) {
				System.out.println("- " + imageAttributesSkippedMissingRef + " image_attribute row(s) skipped - image or attribute not found among imported rows");
			}
			System.out.println(
					"\nRemember: this tool only migrates database rows. Copy the actual image/video files from the legacy "
							+ "imageRootPath into this backend's own storage.root (same relative paths), then use Admin -> Index "
							+ "management's Batch Publish to regenerate thumbnails - see this class's own javadoc.");
		}

		private static void printIfAny(String label, List<String> items) {
			if (items.isEmpty()) return;
			System.out.println("- " + label + " (" + items.size() + "):");
			for (String item : items) {
				System.out.println("    " + item);
			}
		}
	}
}
