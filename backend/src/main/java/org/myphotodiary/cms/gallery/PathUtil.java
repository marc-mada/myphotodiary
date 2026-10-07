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

package org.myphotodiary.cms.gallery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Path-traversal guard for the new gallery storage - the same contract as
 * the legacy fix (`org.myphotodiary.util.PathUtil.isUnderRoot`,
 * Design.md §16.8.2), reimplemented with {@code java.nio.file.Path} instead
 * of {@code java.io.File}. Directory paths here are arbitrary (Design.md
 * decision #6/#7 - the direct filesystem-drop indexing feature needs that),
 * so the check has to be "resolves to somewhere under root", not a fixed
 * year/month/name pattern.
 */
final class PathUtil {

	private PathUtil() {
	}

	static Path resolveUnderRoot(Path root, String directoryPath, String fileName) {
		// Resolve root to its real path ONCE, then build the candidate
		// directly off that resolved root and merely normalize() the rest
		// lexically, rather than canonicalizing root and candidate
		// separately: candidate typically doesn't exist yet (upload target,
		// not yet written), so toRealPath() can't be used on it, and mixing
		// a real-path root against a lexically-normalized candidate breaks
		// the startsWith check on any host where the root traverses a
		// symlink - e.g. macOS, where /tmp/... (and JUnit's @TempDir) is
		// really /private/var/... under the hood. Building the candidate
		// from the same already-resolved root sidesteps that mismatch
		// instead of trying to keep two independent resolutions in sync.
		Path resolvedRoot = canonicalizeRoot(root);

		// Path.resolve(String) silently DISCARDS the base and returns the
		// argument as-is if that argument parses as absolute (e.g.
		// "/etc/passwd", or "C:\..." on Windows) - stripping a leading
		// separator first closes that off before the ".."-escape check
		// below even runs; without it, an absolute directoryPath would
		// bypass "under root" entirely rather than merely attempt to escape it.
		Path candidate = resolvedRoot.resolve(stripLeadingSeparators(directoryPath)).resolve(stripLeadingSeparators(fileName)).normalize();

		if (!candidate.equals(resolvedRoot) && !candidate.startsWith(resolvedRoot)) {
			throw new IllegalArgumentException("Path escapes storage root: " + directoryPath + "/" + fileName);
		}
		return candidate;
	}

	private static Path canonicalizeRoot(Path root) {
		try {
			return root.toRealPath();
		} catch (IOException e) {
			// First run, root not created yet - safe to create eagerly here
			// since it's the app's own storage root, not user input.
			try {
				java.nio.file.Files.createDirectories(root);
				return root.toRealPath();
			} catch (IOException e2) {
				throw new UncheckedIOException(e2);
			}
		}
	}

	private static String stripLeadingSeparators(String value) {
		int start = 0;
		while (start < value.length() && (value.charAt(start) == '/' || value.charAt(start) == '\\')) {
			start++;
		}
		return value.substring(start);
	}
}
