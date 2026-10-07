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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attribute (tag) CRUD + hierarchy - replaces the legacy AttributeTreeSvr/
 * AttributeListSvr (Design.md §16.5/§16.9 step 3), which were two servlets for
 * what's really one resource (a lazily-loaded tree vs. a flat list of the
 * same data) - REST-shaped into one here, same "one clean resource instead
 * of the legacy's per-purpose servlet split" pattern as UserController.
 */
@Service
public class AttributeService {

	private final AttributeRepository attributeRepository;

	public AttributeService(AttributeRepository attributeRepository) {
		this.attributeRepository = attributeRepository;
	}

	@Transactional(readOnly = true)
	public List<AttributeResponse> listAll() {
		return attributeRepository.findAll().stream().map(AttributeResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<AttributeResponse> listChildren(String parentName) {
		List<Attribute> children = (parentName == null || parentName.isBlank())
				? attributeRepository.findByParentIsNullOrderByNameAsc()
				: attributeRepository.findByParent_NameOrderByNameAsc(parentName);
		return children.stream().map(AttributeResponse::from).toList();
	}

	@Transactional
	public AttributeResponse create(CreateAttributeRequest request) {
		String name = request.name().trim();
		if (attributeRepository.findByName(name).isPresent()) {
			throw new AttributeAlreadyExistsException(name);
		}
		Attribute parent = resolveParent(request.parentName());
		return AttributeResponse.from(attributeRepository.save(new Attribute(name, parent)));
	}

	@Transactional
	public AttributeResponse update(Long id, UpdateAttributeRequest request) {
		Attribute attribute = requireAttribute(id);
		if (request.name() != null && !request.name().isBlank()) {
			String newName = request.name().trim();
			attributeRepository.findByName(newName).filter(a -> !a.getId().equals(id))
					.ifPresent(a -> {
						throw new AttributeAlreadyExistsException(newName);
					});
			attribute.setName(newName);
		}
		if (request.parentName() != null) {
			Attribute newParent = resolveParent(request.parentName());
			if (newParent != null && (newParent.getId().equals(id) || isDescendantOf(newParent, attribute))) {
				throw new IllegalArgumentException("Cannot move an attribute under itself or one of its own descendants");
			}
			attribute.setParent(newParent);
		}
		return AttributeResponse.from(attribute);
	}

	@Transactional
	public void delete(Long id) {
		Attribute attribute = requireAttribute(id);
		// Legacy re-parents children to the deleted attribute's own parent
		// (idxAdmin.js attribute tree) rather than deleting the whole
		// subtree - kept here for the same reason: deleting a broad tag like
		// "vacation" shouldn't silently wipe out everything indexed under it.
		attributeRepository.findByParent_NameOrderByNameAsc(attribute.getName())
				.forEach(child -> child.setParent(attribute.getParent()));
		attributeRepository.delete(attribute);
	}

	private boolean isDescendantOf(Attribute candidate, Attribute ancestor) {
		Attribute current = candidate.getParent();
		while (current != null) {
			if (current.getId().equals(ancestor.getId())) return true;
			current = current.getParent();
		}
		return false;
	}

	private Attribute resolveParent(String parentName) {
		if (parentName == null || parentName.isBlank()) {
			return null;
		}
		return attributeRepository.findByName(parentName.trim()).orElseThrow(() -> new AttributeNotFoundException(parentName));
	}

	private Attribute requireAttribute(Long id) {
		return attributeRepository.findById(id).orElseThrow(() -> new AttributeNotFoundException(id));
	}
}
