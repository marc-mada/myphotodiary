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
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for a real, pre-existing legacy data-quality bug found
 * live (31/08/2026, see DuplicateDirectoryMerger's own javadoc):
 * legacy holds two {@code Directory} rows for the same real folder (paths
 * differing only by leading/trailing slashes), which collide once this
 * app's own path-normalization fix runs, unless merged first.
 *
 * The fixture below deliberately exercises every branch of the merge policy
 * in one pass, not just the happy path: a winner picked by description, a
 * filename collision resolved by "richer wins" in the direction that moves
 * content from the loser into the surviving row (the harder direction to
 * get right - the easy direction is a no-op), a non-colliding image that
 * simply gets reassigned, attribute unions on both directory and image
 * level, and an unrelated, non-duplicated directory confirmed untouched
 * except for its own path normalization.
 */
class DuplicateDirectoryMergerTest {

	@Test
	void dryRun_leavesDatabaseCompletelyUnchanged(@TempDir Path tempDir) throws Exception {
		String dbPath = tempDir.resolve("target-db").toString();
		createFixture(dbPath);

		DuplicateDirectoryMerger.main(new String[] {dbPath, "--dry-run"});

		try (Connection c = connect(dbPath)) {
			assertThat(countRows(c, "directory")).isEqualTo(3);
			assertThat(countRows(c, "image")).isEqualTo(3);
			// Still un-normalized - nothing committed.
			assertThat(pathOf(c, 1)).isEqualTo("/1990/04/Corse");
		}
	}

	@Test
	void realRun_mergesDuplicatesAndNormalizesEveryPath(@TempDir Path tempDir) throws Exception {
		String dbPath = tempDir.resolve("target-db").toString();
		createFixture(dbPath);

		DuplicateDirectoryMerger.main(new String[] {dbPath});

		try (Connection c = connect(dbPath)) {
			// The duplicate pair (ids 1/2, both "1990/04/Corse" once
			// normalized) merged into one row - winner is id 1 (has a real
			// description, id 2 doesn't).
			assertThat(directoryExists(c, 2)).isFalse();
			assertThat(pathOf(c, 1)).isEqualTo("1990/04/Corse");
			assertThat(descriptionOf(c, 1)).isEqualTo("Randonnée en Corse");

			// The unrelated, never-duplicated directory (id 3) survives
			// untouched except its own path normalization.
			assertThat(pathOf(c, 3)).isEqualTo("2020/01/solo");

			// Filename collision "Scan1.jpg": winner's own copy (image id
			// 10) had no content, loser's (image id 20) had a description
			// and a real rating - the surviving row must have picked those
			// up, not kept the empty one. The loser's own image row is gone.
			assertThat(imageExists(c, 20)).isFalse();
			assertThat(countImagesNamed(c, 1, "Scan1.jpg")).isEqualTo(1);
			assertThat(imageDescriptionByName(c, 1, "Scan1.jpg")).isEqualTo("Le col de Bavella");
			assertThat(imageRatingByName(c, 1, "Scan1.jpg")).isEqualTo(2);

			// Non-colliding filename "Scan2.jpg" (only under the loser) -
			// simply reassigned to the winner, not lost.
			assertThat(countImagesNamed(c, 1, "Scan2.jpg")).isEqualTo(1);

			// Directory-level tag union: winner already had "montagne",
			// loser had "corse" - both must survive on the surviving row.
			assertThat(attributeNamesOf(c, 1)).containsExactlyInAnyOrder("montagne", "corse");

			// Image-level tag union onto the surviving "Scan1.jpg" row -
			// the loser's own tag ("paysage", only ever on the loser's copy)
			// must have been unioned in, not discarded along with the row
			// it used to hang off of.
			assertThat(imageAttributeNamesByName(c, 1, "Scan1.jpg")).containsExactlyInAnyOrder("paysage");
		}
	}

	private static void createFixture(String dbPath) throws Exception {
		Flyway.configure().dataSource("jdbc:hsqldb:file:" + dbPath + ";shutdown=true", "SA", "").locations("classpath:db/migration").load().migrate();
		try (Connection c = connect(dbPath); Statement st = c.createStatement()) {
			st.execute("INSERT INTO app_group (group_name, description, creation_date) VALUES ('public', 'default', '2020-01-01')");
			st.execute("INSERT INTO app_group (group_name, description, creation_date) VALUES ('famille', 'family', '2020-01-01')");

			// The duplicate pair - same real folder, two rows. id 1 is the
			// "rich" one (real description, non-public group, fewer images);
			// id 2 is the "public" default-y one (no description, more
			// images) - winner must be id 1 despite having fewer images,
			// since description wins first in the policy.
			st.execute("INSERT INTO directory (id, path, group_name, creation_date, description, indexing_allowed) "
					+ "VALUES (1, '/1990/04/Corse', 'famille', '2020-01-01', 'Randonnée en Corse', TRUE)");
			st.execute("INSERT INTO directory (id, path, group_name, creation_date, description, indexing_allowed) "
					+ "VALUES (2, '//1990/04/Corse', 'public', '2020-01-01', NULL, TRUE)");
			// Unrelated, never-duplicated directory - only normalization
			// should touch it.
			st.execute("INSERT INTO directory (id, path, group_name, creation_date, indexing_allowed) "
					+ "VALUES (3, '/2020/01/solo', 'public', '2020-01-01', TRUE)");

			// Filename collision: id 10 (under winner id 1) has no content;
			// id 20 (under loser id 2, same real filename) has real content
			// - the richer one must win.
			st.execute("INSERT INTO image (id, name, directory_id, rating) VALUES (10, 'Scan1.jpg', 1, -1)");
			st.execute("INSERT INTO image (id, name, directory_id, description, rating) VALUES (20, 'Scan1.jpg', 2, 'Le col de Bavella', 2)");
			// Non-colliding filename, only under the loser.
			st.execute("INSERT INTO image (id, name, directory_id, rating) VALUES (21, 'Scan2.jpg', 2, -1)");

			st.execute("INSERT INTO attribute (id, name) VALUES (100, 'montagne')");
			st.execute("INSERT INTO attribute (id, name) VALUES (101, 'corse')");
			st.execute("INSERT INTO attribute (id, name) VALUES (102, 'paysage')");
			st.execute("INSERT INTO directory_attribute (directory_id, attribute_id) VALUES (1, 100)");
			st.execute("INSERT INTO directory_attribute (directory_id, attribute_id) VALUES (2, 101)");
			st.execute("INSERT INTO image_attribute (image_id, attribute_id) VALUES (20, 102)");

			st.execute("SHUTDOWN");
		}
	}

	private static Connection connect(String dbPath) throws Exception {
		return DriverManager.getConnection("jdbc:hsqldb:file:" + dbPath + ";shutdown=true", "SA", "");
	}

	private static int countRows(Connection c, String table) throws Exception {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private static boolean directoryExists(Connection c, long id) throws Exception {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM directory WHERE id = " + id)) {
			rs.next();
			return rs.getInt(1) > 0;
		}
	}

	private static boolean imageExists(Connection c, long id) throws Exception {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM image WHERE id = " + id)) {
			rs.next();
			return rs.getInt(1) > 0;
		}
	}

	private static String pathOf(Connection c, long id) throws Exception {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT path FROM directory WHERE id = " + id)) {
			rs.next();
			return rs.getString(1);
		}
	}

	private static String descriptionOf(Connection c, long id) throws Exception {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT description FROM directory WHERE id = " + id)) {
			rs.next();
			return rs.getString(1);
		}
	}

	private static int countImagesNamed(Connection c, long directoryId, String name) throws Exception {
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM image WHERE directory_id = " + directoryId + " AND name = '" + name + "'")) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private static String imageDescriptionByName(Connection c, long directoryId, String name) throws Exception {
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT description FROM image WHERE directory_id = " + directoryId + " AND name = '" + name + "'")) {
			rs.next();
			return rs.getString(1);
		}
	}

	private static int imageRatingByName(Connection c, long directoryId, String name) throws Exception {
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT rating FROM image WHERE directory_id = " + directoryId + " AND name = '" + name + "'")) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private static java.util.List<String> attributeNamesOf(Connection c, long directoryId) throws Exception {
		java.util.List<String> names = new java.util.ArrayList<>();
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
				"SELECT a.name FROM attribute a JOIN directory_attribute da ON da.attribute_id = a.id WHERE da.directory_id = " + directoryId)) {
			while (rs.next()) names.add(rs.getString(1));
		}
		return names;
	}

	private static java.util.List<String> imageAttributeNamesByName(Connection c, long directoryId, String imageName) throws Exception {
		java.util.List<String> names = new java.util.ArrayList<>();
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
				"SELECT a.name FROM attribute a JOIN image_attribute ia ON ia.attribute_id = a.id JOIN image i ON i.id = ia.image_id "
						+ "WHERE i.directory_id = " + directoryId + " AND i.name = '" + imageName + "'")) {
			while (rs.next()) names.add(rs.getString(1));
		}
		return names;
	}
}
