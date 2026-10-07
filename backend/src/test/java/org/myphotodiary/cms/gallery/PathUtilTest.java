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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Same contract as the legacy fix (org.myphotodiary.util.PathUtil.isUnderRoot,
 * Design.md §16.8.2), reproduced here for the new nio-based implementation
 * before it's trusted in ImageStorageService - "characterize before fixing"
 * carried over from the legacy security work, applied to new code this time.
 */
class PathUtilTest {

	@TempDir
	Path root;

	// resolveUnderRoot returns paths built off root.toRealPath() (see its
	// comment - needed to stay consistent on hosts like macOS where the
	// temp dir traverses a symlink, e.g. /tmp -> /private/tmp), so
	// expectations here are compared against that same real path rather
	// than the raw @TempDir value, which can legitimately differ from it.
	private Path realRoot() throws Exception {
		return root.toRealPath();
	}

	@Test
	void resolvesArbitraryLegitimateNestedPath() throws Exception {
		// Arbitrary paths, not just year/month/name (Design.md decision #6/#7)
		Path resolved = PathUtil.resolveUnderRoot(root, "some/deeply/nested/path", "photo.jpg");
		assertThat(resolved).isEqualTo(realRoot().resolve("some/deeply/nested/path/photo.jpg"));
	}

	@Test
	void rejectsDotDotEscape() {
		assertThatThrownBy(() -> PathUtil.resolveUnderRoot(root, "../../etc", "passwd"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsDotDotEscapeInFileName() {
		assertThatThrownBy(() -> PathUtil.resolveUnderRoot(root, "2026/08", "../../../etc/passwd"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void absoluteLookingDirectoryPathIsNeutralizedRatherThanEscapingRoot() throws Exception {
		// Path.resolve(String) would otherwise DISCARD `root` entirely and
		// return this absolute path as-is, bypassing the root check below it
		// completely rather than merely attempting to escape it - stripping
		// the leading separator first (PathUtil) closes that off by turning
		// it into an ordinary nested subpath instead.
		Path outside = Files.createTempDirectory("outside");
		Path resolved = PathUtil.resolveUnderRoot(root, outside.toString(), "passwd");
		assertThat(resolved.startsWith(realRoot())).isTrue();
	}

	@Test
	void acceptsRootItself() throws Exception {
		Path resolved = PathUtil.resolveUnderRoot(root, "", "photo.jpg");
		assertThat(resolved).isEqualTo(realRoot().resolve("photo.jpg"));
	}
}
