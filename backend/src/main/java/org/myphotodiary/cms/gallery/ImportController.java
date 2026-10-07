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

import org.myphotodiary.cms.gallery.dto.BatchIndexResult;
import org.myphotodiary.cms.gallery.dto.ImportResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Replaces the legacy ImportSvr (Design.md §16.5) - the upload form's
 * "Classer les photos par date (EXIF)" / "Créer une nouvelle séquence"
 * options, see ImportService's own javadoc for the full behavior. Defaults
 * match the legacy form's checkboxes, which are both checked by default.
 */
@RestController
public class ImportController {

	private final ImportService importService;
	private final StagingImportService stagingImportService;

	public ImportController(ImportService importService, StagingImportService stagingImportService) {
		this.importService = importService;
		this.stagingImportService = stagingImportService;
	}

	@PostMapping("/api/import")
	@ResponseStatus(HttpStatus.CREATED)
	public ImportResponse importImage(
			Authentication authentication,
			@RequestParam("file") MultipartFile file,
			@RequestParam(defaultValue = "true") boolean autoSort,
			@RequestParam(defaultValue = "true") boolean createSubDir,
			@RequestParam(required = false) String subDirName,
			@RequestParam(required = false) String dirPath,
			@RequestParam(required = false) String groupName,
			// The uploading browser's own File.lastModified (epoch millis) -
			// see ExifDateReader's own javadoc (fallback #5) for why this is
			// asked for now: a real signal this endpoint discarded before,
			// found while chasing a live report of photos landing on the
			// upload date instead of their real one. Optional - an older
			// client that doesn't send it just gets the pre-existing behavior.
			@RequestParam(required = false) Long clientLastModifiedEpochMillis) {
		return importService.importImage(authentication, file, autoSort, createSubDir, subDirName, dirPath, groupName, clientLastModifiedEpochMillis);
	}

	/** Legacy's real Batch Publish (01/09/2026) - see StagingImportService's own javadoc. */
	@PostMapping("/api/import/staging")
	public BatchIndexResult importStagingBatch(
			Authentication authentication,
			@RequestParam String path,
			@RequestParam(required = false) Integer defaultYear,
			@RequestParam(required = false) Integer defaultMonth,
			@RequestParam(required = false) String groupName,
			@RequestParam(defaultValue = "false") boolean useFolderDates) {
		return stagingImportService.importBatch(authentication, path, defaultYear, defaultMonth, groupName, useFolderDates);
	}
}
