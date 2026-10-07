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

/**
 * A sequence name is one folder name - the last segment of a sequence's
 * path, typed by a user in two places: the sequence popup's Rename form
 * (DirectoryIndexerService#renameDirectory) and the Publish forms'
 * sequence-name field (ImportService#importImage).
 *
 * Neither place used to check it (found 02/10/2026): a "/" silently created
 * nested folders, and a ".." segment was worse - the folder on disk moved
 * where the normalized path pointed (PathUtil only refuses paths escaping
 * the storage root entirely), while the database stored the raw,
 * un-normalized text, so the two disagreed and the sequence's photos,
 * comments and tags could no longer be reached through the tree.
 *
 * Refused: empty, any "/" or "\", and a leading "." - which covers "." and
 * "..", and also names like ".thumbnails"/".webimg": the tree skips
 * dot-folders (they hold derived images), so such a sequence would vanish
 * from view. Moving a sequence to another year/month is deliberately not
 * possible through a name (the Rename form's separate Date field does that).
 */
final class SequenceNames {

	private SequenceNames() {
	}

	static void requireValid(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A sequence name can't be empty");
		}
		if (name.contains("/") || name.contains("\\")) {
			throw new IllegalArgumentException("A sequence name can't contain / or \\ (got \"" + name + "\")");
		}
		if (name.startsWith(".")) {
			throw new IllegalArgumentException("A sequence name can't start with a dot (got \"" + name + "\")");
		}
	}
}
