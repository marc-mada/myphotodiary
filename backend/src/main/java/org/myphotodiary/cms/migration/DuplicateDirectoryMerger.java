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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-off repair tool for a real, pre-existing legacy data-quality bug, found
 * live (31/08/2026) while chasing a completely different report ("post-it
 * comments are empty even though the DB has them"). Not caused by this migration: legacy's own {@code DIRECTORY
 * .PATH} column stores every path with a leading {@code "/"}
 * ({@code LegacyDataImporter.normalizeLegacyPath} strips this on import now,
 * fixed the same day), but for 68 real folders out of 1746 (confirmed via
 * direct SQL against the real migrated data, not assumed), legacy itself
 * holds <b>two</b> {@code Directory} rows for the exact same physical
 * folder - one path prefixed with a single {@code "/"}, another with a
 * double {@code "//"} (or a trailing {@code "/"}) - each carrying a
 * different subset of that folder's real images, sometimes different
 * descriptions, and often different {@code group_name} ownership
 * (frequently {@code famille} vs the generic {@code public} default).
 * Filename cross-checks (e.g. identical {@code ScanNNNNN.JPG} names present
 * under both rows for the same real folder) confirm these are genuinely the
 * same real photos indexed twice by legacy at different points in its long
 * history, not two unrelated sequences that coincidentally share a name.
 *
 * <p>This app's own schema enforces {@code directory.path UNIQUE}
 * (legacy's apparently didn't) - normalizing the leading-slash convention
 * without first resolving these pairs would collide. This tool resolves
 * every pair/group <i>before</i> that normalization runs (as its own final
 * step here, once no more collisions are possible), rather than requiring a
 * second manual pass.
 *
 * <h2>Merge policy (agreed explicitly before this was built, not assumed)</h2>
 * For each group of directory rows sharing the same slash-normalized path:
 * <ol>
 *   <li>Winner = the row with a non-empty {@code description}; if that's
 *       tied, the row whose {@code group_name} isn't the generic
 *       {@code "public"} default; if still tied, the row with more images;
 *       if still tied, the lowest id (deterministic, not arbitrary).</li>
 *   <li>A loser's own non-empty description, if different from the winner's,
 *       is <b>appended</b> to the winner's rather than discarded - real
 *       content on both sides of a pair is a real (if rare) case, found
 *       live in 5 of the 68 groups.</li>
 *   <li>Every image under a loser is reassigned to the winner directory. On
 *       a filename collision (the same real photo indexed under both rows -
 *       confirmed the common case), the copy with real content (a
 *       description or a rating - {@code image.rating <> -1}, matching
 *       {@code Image.NOT_RATED}) wins; the other is discarded after its own
 *       tag assignments are unioned onto the surviving row. No collision -
 *       the image is simply reassigned.</li>
 *   <li>Directory-level tag assignments are unioned the same way.</li>
 *   <li>{@code latitude}/{@code longitude}: the winner's own non-null value
 *       wins, falling back to a loser's if the winner has none.
 *       {@code indexing_allowed}: true if any row in the group allows it -
 *       never let a merge make a still-real directory less indexable than
 *       it already was. {@code sequence_date}: the latest of the group (a
 *       real re-index after this tool runs recomputes it properly from EXIF
 *       anyway, per {@code DirectoryIndexerService}'s own "recompute
 *       everything" policy - this is just a reasonable interim value).</li>
 *   <li>Losers are deleted only after every one of their images has been
 *       moved or deleted - {@code image.directory_id} cascades on
 *       {@code ON DELETE}, so deleting a loser first would silently destroy
 *       whatever images hadn't been reassigned yet.</li>
 * </ol>
 * Finally, once no more collisions are possible, every directory's path is
 * normalized ({@code TRIM(BOTH '/' FROM path)}) in one pass - the same fix
 * {@link LegacyDataImporter#normalizeLegacyPath} applies to future imports,
 * applied here to data already imported before that fix existed.
 *
 * <p>{@code --dry-run} reports exactly what would happen (same discipline as
 * {@link LegacyDataImporter}) without committing - read the report before
 * ever running for real.
 */
public class DuplicateDirectoryMerger {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("Usage: DuplicateDirectoryMerger <db-path> [--dry-run]");
			System.exit(1);
		}
		String dbPath = args[0];
		boolean dryRun = args.length > 1 && "--dry-run".equals(args[1]);

		try (Connection conn = DriverManager.getConnection("jdbc:hsqldb:file:" + dbPath + ";shutdown=true", "SA", "")) {
			conn.setAutoCommit(false);
			Report report = new Report();
			try {
				merge(conn, report);
				normalizeAllPaths(conn, report);
				if (dryRun) {
					conn.rollback();
					System.out.println("\n[DRY RUN] Rolled back - nothing was actually written.");
				} else {
					conn.commit();
					System.out.println("\nCommitted.");
				}
			} catch (Exception e) {
				conn.rollback();
				throw e;
			}
			report.print();
		}
	}

	private static void merge(Connection conn, Report report) throws SQLException {
		Map<String, List<DirRow>> groups = loadGroups(conn);
		for (Map.Entry<String, List<DirRow>> entry : groups.entrySet()) {
			List<DirRow> rows = entry.getValue();
			if (rows.size() < 2) continue;

			DirRow winner = pickWinner(rows);
			report.groupsMerged++;
			report.log.add("Group \"" + entry.getKey() + "\": winner id=" + winner.id + " (path=" + winner.path + "), "
					+ (rows.size() - 1) + " loser(s) merged in");

			String mergedDescription = winner.description;
			Double lat = winner.latitude;
			Double lng = winner.longitude;
			boolean indexingAllowed = winner.indexingAllowed;
			LocalDate sequenceDate = winner.sequenceDate;

			for (DirRow row : rows) {
				if (row.id.equals(winner.id)) continue;
				if (hasContent(row.description) && !normalizedText(row.description).equals(normalizedText(mergedDescription))) {
					mergedDescription = hasContent(mergedDescription) ? mergedDescription + "\n---\n" + row.description : row.description;
					report.descriptionsMerged++;
				}
				if (lat == null) lat = row.latitude;
				if (lng == null) lng = row.longitude;
				indexingAllowed = indexingAllowed || row.indexingAllowed;
				if (row.sequenceDate != null && (sequenceDate == null || row.sequenceDate.isAfter(sequenceDate))) {
					sequenceDate = row.sequenceDate;
				}

				mergeImages(conn, row.id, winner.id, report);
				unionAttributes(conn, "directory_attribute", "directory_id", row.id, winner.id);
				deleteDirectoryRow(conn, row.id);
				report.directoriesDeleted++;
			}

			updateDirectoryFields(conn, winner.id, mergedDescription, lat, lng, indexingAllowed, sequenceDate);
		}
	}

	/** Every image under {@code loserDirId} is reassigned to {@code winnerDirId}, resolving filename collisions in favor of whichever copy has real content. */
	private static void mergeImages(Connection conn, long loserDirId, long winnerDirId, Report report) throws SQLException {
		Map<String, ImgRow> winnerImagesByName = loadImagesByName(conn, winnerDirId);
		for (ImgRow loserImg : loadImages(conn, loserDirId)) {
			ImgRow existing = winnerImagesByName.get(loserImg.name);
			if (existing == null) {
				try (PreparedStatement st = conn.prepareStatement("UPDATE image SET directory_id = ? WHERE id = ?")) {
					st.setLong(1, winnerDirId);
					st.setLong(2, loserImg.id);
					st.executeUpdate();
				}
				winnerImagesByName.put(loserImg.name, loserImg);
				report.imagesReassigned++;
			} else {
				if (!hasRealContent(existing) && hasRealContent(loserImg)) {
					try (PreparedStatement st = conn
							.prepareStatement("UPDATE image SET description = ?, rating = ?, capture_date = ? WHERE id = ?")) {
						st.setString(1, loserImg.description);
						st.setInt(2, loserImg.rating);
						st.setObject(3, loserImg.captureDate);
						st.setLong(4, existing.id);
						st.executeUpdate();
					}
				}
				unionAttributes(conn, "image_attribute", "image_id", loserImg.id, existing.id);
				try (PreparedStatement st = conn.prepareStatement("DELETE FROM image WHERE id = ?")) {
					st.setLong(1, loserImg.id);
					st.executeUpdate();
				}
				report.imagesDeduplicated++;
			}
		}
	}

	/** Copies every {@code (fromId, attribute_id)} row in {@code table} onto {@code toId}, skipping ones already present (the join tables' own composite PK would otherwise reject the duplicate). */
	private static void unionAttributes(Connection conn, String table, String ownerColumn, long fromId, long toId) throws SQLException {
		List<Long> attributeIds = new ArrayList<>();
		try (PreparedStatement st = conn.prepareStatement("SELECT attribute_id FROM " + table + " WHERE " + ownerColumn + " = ?")) {
			st.setLong(1, fromId);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) attributeIds.add(rs.getLong(1));
			}
		}
		for (Long attributeId : attributeIds) {
			boolean exists;
			try (PreparedStatement st = conn.prepareStatement("SELECT 1 FROM " + table + " WHERE " + ownerColumn + " = ? AND attribute_id = ?")) {
				st.setLong(1, toId);
				st.setLong(2, attributeId);
				try (ResultSet rs = st.executeQuery()) {
					exists = rs.next();
				}
			}
			if (!exists) {
				try (PreparedStatement st = conn.prepareStatement("INSERT INTO " + table + " (" + ownerColumn + ", attribute_id) VALUES (?, ?)")) {
					st.setLong(1, toId);
					st.setLong(2, attributeId);
					st.executeUpdate();
				}
			}
		}
	}

	private static void deleteDirectoryRow(Connection conn, long id) throws SQLException {
		// Every image under this row must already be reassigned/deleted by
		// mergeImages before this runs - image.directory_id cascades on
		// delete, so calling this first would silently destroy anything
		// still pointing here instead of preserving it via reassignment.
		try (PreparedStatement st = conn.prepareStatement("DELETE FROM directory WHERE id = ?")) {
			st.setLong(1, id);
			st.executeUpdate();
		}
	}

	private static void updateDirectoryFields(Connection conn, long id, String description, Double lat, Double lng, boolean indexingAllowed,
			LocalDate sequenceDate) throws SQLException {
		try (PreparedStatement st = conn
				.prepareStatement("UPDATE directory SET description = ?, latitude = ?, longitude = ?, indexing_allowed = ?, sequence_date = ? WHERE id = ?")) {
			st.setString(1, description);
			if (lat == null) st.setNull(2, java.sql.Types.DOUBLE);
			else st.setDouble(2, lat);
			if (lng == null) st.setNull(3, java.sql.Types.DOUBLE);
			else st.setDouble(3, lng);
			st.setBoolean(4, indexingAllowed);
			st.setObject(5, sequenceDate);
			st.setLong(6, id);
			st.executeUpdate();
		}
	}

	/** Final pass, once no group can collide any more: strips the leading/trailing "/" every legacy path was imported with (LegacyDataImporter's own fix, applied here retroactively). */
	private static void normalizeAllPaths(Connection conn, Report report) throws SQLException {
		try (PreparedStatement st = conn.prepareStatement("UPDATE directory SET path = TRIM(BOTH '/' FROM path) WHERE path <> TRIM(BOTH '/' FROM path)")) {
			report.pathsNormalized = st.executeUpdate();
		}
	}

	private static Map<String, List<DirRow>> loadGroups(Connection conn) throws SQLException {
		Map<String, List<DirRow>> groups = new LinkedHashMap<>();
		String sql = "SELECT id, path, TRIM(BOTH '/' FROM path) AS norm, group_name, description, latitude, longitude, indexing_allowed, sequence_date, "
				+ "(SELECT COUNT(*) FROM image WHERE directory_id = directory.id) AS image_count FROM directory ORDER BY id";
		try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				DirRow row = new DirRow();
				row.id = rs.getLong("id");
				row.path = rs.getString("path");
				row.groupName = rs.getString("group_name");
				row.description = rs.getString("description");
				row.latitude = nullableDouble(rs, "latitude");
				row.longitude = nullableDouble(rs, "longitude");
				row.indexingAllowed = rs.getBoolean("indexing_allowed");
				java.sql.Date d = rs.getDate("sequence_date");
				row.sequenceDate = d != null ? d.toLocalDate() : null;
				row.imageCount = rs.getInt("image_count");
				groups.computeIfAbsent(rs.getString("norm"), k -> new ArrayList<>()).add(row);
			}
		}
		return groups;
	}

	private static DirRow pickWinner(List<DirRow> rows) {
		DirRow winner = null;
		for (DirRow row : rows) {
			if (winner == null || better(row, winner)) {
				winner = row;
			}
		}
		return winner;
	}

	/** True if {@code candidate} should replace {@code current} as the winner, per the policy in this class's own javadoc. */
	private static boolean better(DirRow candidate, DirRow current) {
		boolean candidateHasDesc = hasContent(candidate.description);
		boolean currentHasDesc = hasContent(current.description);
		if (candidateHasDesc != currentHasDesc) return candidateHasDesc;

		boolean candidateNonPublic = !"public".equals(candidate.groupName);
		boolean currentNonPublic = !"public".equals(current.groupName);
		if (candidateNonPublic != currentNonPublic) return candidateNonPublic;

		if (candidate.imageCount != current.imageCount) return candidate.imageCount > current.imageCount;

		return candidate.id < current.id;
	}

	private static Map<String, ImgRow> loadImagesByName(Connection conn, long directoryId) throws SQLException {
		Map<String, ImgRow> byName = new HashMap<>();
		for (ImgRow img : loadImages(conn, directoryId)) {
			byName.put(img.name, img);
		}
		return byName;
	}

	private static List<ImgRow> loadImages(Connection conn, long directoryId) throws SQLException {
		List<ImgRow> images = new ArrayList<>();
		try (PreparedStatement st = conn
				.prepareStatement("SELECT id, name, description, rating, capture_date FROM image WHERE directory_id = ?")) {
			st.setLong(1, directoryId);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					ImgRow img = new ImgRow();
					img.id = rs.getLong("id");
					img.name = rs.getString("name");
					img.description = rs.getString("description");
					img.rating = rs.getInt("rating");
					img.captureDate = rs.getTimestamp("capture_date") != null ? rs.getTimestamp("capture_date").toLocalDateTime() : null;
					images.add(img);
				}
			}
		}
		return images;
	}

	private static boolean hasRealContent(ImgRow img) {
		// -1 is Image.NOT_RATED (V2 migration's own column default), not 0 -
		// a 0 rating would be a real (if low) rating, not "unrated".
		return hasContent(img.description) || img.rating != -1;
	}

	private static boolean hasContent(String s) {
		return s != null && !s.isBlank();
	}

	private static String normalizedText(String s) {
		return s == null ? "" : s.trim();
	}

	private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private static class DirRow {
		Long id;
		String path;
		String groupName;
		String description;
		Double latitude;
		Double longitude;
		boolean indexingAllowed;
		LocalDate sequenceDate;
		int imageCount;
	}

	private static class ImgRow {
		Long id;
		String name;
		String description;
		int rating;
		java.time.LocalDateTime captureDate;
	}

	private static class Report {
		int groupsMerged;
		int directoriesDeleted;
		int imagesReassigned;
		int imagesDeduplicated;
		int descriptionsMerged;
		int pathsNormalized;
		List<String> log = new ArrayList<>();

		void print() {
			System.out.println("\n=== Duplicate directory merge summary ===");
			for (String line : log) System.out.println(line);
			System.out.println("Groups merged:          " + groupsMerged);
			System.out.println("Directory rows deleted: " + directoriesDeleted);
			System.out.println("Images reassigned:      " + imagesReassigned);
			System.out.println("Images deduplicated:    " + imagesDeduplicated);
			System.out.println("Descriptions merged:    " + descriptionsMerged);
			System.out.println("Paths normalized:       " + pathsNormalized);
		}
	}
}
