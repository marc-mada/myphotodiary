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
 */
public record RenameDirectoryRequest(@NotBlank String newName, Integer year, Integer month) {

	public RenameDirectoryRequest(String newName) {
		this(newName, null, null);
	}

}
