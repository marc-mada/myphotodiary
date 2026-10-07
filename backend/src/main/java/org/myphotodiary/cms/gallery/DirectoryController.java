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

import java.util.List;

import org.myphotodiary.cms.gallery.dto.BatchIndexRequest;
import org.myphotodiary.cms.gallery.dto.BatchIndexResult;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.DirectoryTreeNode;
import org.myphotodiary.cms.gallery.dto.RenameDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.UpdateDirectoryDetailRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Replaces the legacy SubDirListSvr/DirIndexSvr/DirDataSvr (Design.md
 * §16.5/§16.9 step 3). Kept separate from {@link GalleryController} (which owns
 * plain directory create/list + images) since this controller is
 * specifically the filesystem-scanning/indexing/rename surface - a
 * different concern, even though both sit on the same `Directory` entity.
 */
@RestController
public class DirectoryController {

	private final DirectoryIndexerService indexerService;

	public DirectoryController(DirectoryIndexerService indexerService) {
		this.indexerService = indexerService;
	}

	/** Immediate subdirectories under `path` (root if omitted), scanned from disk - powers the navigation tree. */
	@GetMapping("/api/directory-tree")
	public List<DirectoryTreeNode> listSubdirectories(@RequestParam(required = false) String path) {
		return indexerService.listSubdirectories(path);
	}

	/** Every directory under `path` (root if omitted), flattened - powers the Admin screen's bulk index-management table (legacy's dirIndexPane). */
	@GetMapping("/api/directory-tree/all")
	public List<DirectoryTreeNode> listAllSubdirectoriesRecursive(@RequestParam(required = false) String path) {
		return indexerService.listAllSubdirectoriesRecursive(path);
	}

	@PostMapping("/api/directory-index")
	public DirectoryResponse indexDirectory(@RequestParam String path, @RequestParam(required = false) String group) {
		return indexerService.indexDirectory(path, group);
	}

	/** `deleteFiles=true` also deletes the files themselves (irreversible) - default is DB-index-only, matching the legacy "reset index" vs. "delete" distinction. */
	@DeleteMapping("/api/directory-index")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeDirectoryIndex(Authentication authentication, @RequestParam String path, @RequestParam(defaultValue = "false") boolean deleteFiles) {
		if (deleteFiles) {
			indexerService.deleteDirectoryIndexAndFiles(authentication, path);
		} else {
			indexerService.resetDirectoryIndex(authentication, path);
		}
	}

	/** Bulk index/reset-index/delete over several directories at once - legacy's dirIndexPane checkbox table. Best-effort, see BatchIndexResult. */
	@PostMapping("/api/directory-index/batch")
	public BatchIndexResult batchIndex(Authentication authentication, @RequestBody BatchIndexRequest request) {
		return indexerService.batchIndex(authentication, request.cmd(), request.paths());
	}

	/** Recursively indexes `path` and every directory under it that has images - legacy's server-side "Batch Publish" (idxAdmin.js), distinct from the browser upload flow. */
	@PostMapping("/api/directory-index/batch-publish")
	public BatchIndexResult batchPublish(Authentication authentication, @RequestParam String path) {
		return indexerService.indexRecursively(authentication, path);
	}

	@GetMapping("/api/directories/{id}")
	public DirectoryResponse getDirectory(Authentication authentication, @PathVariable Long id) {
		return indexerService.getDirectory(authentication, id);
	}

	@PatchMapping("/api/directories/{id}")
	public DirectoryResponse updateDetail(Authentication authentication, @PathVariable Long id, @RequestBody UpdateDirectoryDetailRequest request) {
		return indexerService.updateDirectoryDetail(authentication, id, request);
	}

	@PostMapping("/api/directories/{id}/rename")
	public DirectoryResponse rename(Authentication authentication, @PathVariable Long id, @Valid @RequestBody RenameDirectoryRequest request) {
		return indexerService.renameDirectory(authentication, id, request);
	}
}
