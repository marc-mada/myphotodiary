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

package org.myphotodiary.cms.user;

import java.util.List;

import org.myphotodiary.cms.user.dto.GroupResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * New (12/09/2026, sequence group picker) - the first endpoint in this
 * backend to expose the full {@link Group} list at all; every other caller
 * so far only ever needed a single named group (find-or-create by name).
 * ADMIN-only (not open to every authenticated role, unlike e.g. tag
 * listing): a WRITER's own dropdown is built instead from
 * {@code GET /api/me/groups} (MeController), which only ever reveals groups
 * that user already knows they belong to - group *names* can hint at
 * private partitioning (e.g. a family-only group), so the full list stays
 * restricted to the one role that's meant to see everything anyway.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class GroupController {

	private final GroupRepository groupRepository;

	public GroupController(GroupRepository groupRepository) {
		this.groupRepository = groupRepository;
	}

	@GetMapping("/api/groups")
	public List<GroupResponse> list() {
		return groupRepository.findAll().stream().map(GroupResponse::from).toList();
	}
}
