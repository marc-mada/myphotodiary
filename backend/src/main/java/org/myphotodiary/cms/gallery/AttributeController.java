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

import org.myphotodiary.cms.gallery.dto.AttributeResponse;
import org.myphotodiary.cms.gallery.dto.CreateAttributeRequest;
import org.myphotodiary.cms.gallery.dto.UpdateAttributeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Attributes (tags) aren't group-scoped like directories/images - there's no
 * per-resource {@code Group} to check a {@link org.myphotodiary.cms.user.RoleAssignment}
 * against, so tree *management* (create/rename/delete) is gated the same
 * coarse way as {@code UserController}: ADMIN-only, matching the frontend's
 * own Tags panel already living inside the Admin screen. Listing stays open
 * to any authenticated user - Search's attribute filters and
 * DirectoryDetailPanel/ImageEditForm's tag checkboxes (assigning an
 * *existing* tag to a directory/image, via GalleryController/
 * DirectoryController's own EDIT_SEQUENCE/EDIT_IMAGE checks, not this
 * controller) need to read the tag list regardless of role.
 */
@RestController
public class AttributeController {

	private final AttributeService attributeService;

	public AttributeController(AttributeService attributeService) {
		this.attributeService = attributeService;
	}

	/** Flat list - powers search/tagging pickers where the hierarchy doesn't matter. */
	@GetMapping("/api/attributes")
	public List<AttributeResponse> listAll() {
		return attributeService.listAll();
	}

	/** Lazy-loaded tree level, mirroring the legacy AttributeTreeSvr contract: omit `parent` for root attributes. */
	@GetMapping("/api/attribute-tree")
	public List<AttributeResponse> listChildren(@RequestParam(required = false) String parent) {
		return attributeService.listChildren(parent);
	}

	@PostMapping("/api/attributes")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('ADMIN')")
	public AttributeResponse create(@Valid @RequestBody CreateAttributeRequest request) {
		return attributeService.create(request);
	}

	@PatchMapping("/api/attributes/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public AttributeResponse update(@PathVariable Long id, @RequestBody UpdateAttributeRequest request) {
		return attributeService.update(id, request);
	}

	@DeleteMapping("/api/attributes/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	public void delete(@PathVariable Long id) {
		attributeService.delete(id);
	}
}
