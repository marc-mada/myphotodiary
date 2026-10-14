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

package org.myphotodiary.cms.gallery.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code year}/{@code month} (05/10/2026, optional, together): also move a
 * {@code year/month/name} sequence to another date - see
 * DirectoryIndexerService#renameDirectory.
 *
 * {@code merge} (10/10/2026, optional): when the new name/date is already
 * taken by another sequence, merge into it instead of refusing. Without it
 * the rename answers 409 with SequenceMergeRequiredException's details, so
 * the user can be asked to confirm first.
 */
public record RenameDirectoryRequest(@NotBlank String newName, Integer year, Integer month, Boolean merge) {

	public RenameDirectoryRequest(String newName) {
		this(newName, null, null, null);
	}

	public RenameDirectoryRequest(String newName, Integer year, Integer month) {
		this(newName, year, month, null);
	}

}
