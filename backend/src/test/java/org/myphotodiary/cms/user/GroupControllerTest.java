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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.user.dto.GroupResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code @EnableMethodSecurity} enforces the controller's own class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} (12/09/2026, sequence group
 * picker) even on a plain direct method call like these tests make - no
 * MockMvc/HTTP layer needed for that part, {@code @WithMockUser} (already a
 * test dependency, {@code spring-security-test}) populates the
 * SecurityContext a real request would have. Found live while writing this
 * class, not assumed: the first version had no {@code @WithMockUser} at all
 * and failed with "An Authentication object was not found in the
 * SecurityContext" - proof the annotation is genuinely wired, not just
 * documentation.
 */
@SpringBootTest
@Transactional
class GroupControllerTest {

	@Autowired
	private GroupController groupController;
	@Autowired
	private GroupRepository groupRepository;

	@Test
	@WithMockUser(roles = "ADMIN")
	void list_returnsEveryGroup() {
		String groupName = "group-controller-test-" + System.nanoTime();
		groupRepository.save(new Group(groupName, "A test group", LocalDate.now()));

		List<GroupResponse> groups = groupController.list();

		assertThat(groups).extracting(GroupResponse::groupName).contains(groupName);
		assertThat(groups).extracting(GroupResponse::description).contains("A test group");
	}

	@Test
	@WithMockUser(roles = "WRITER")
	void list_deniedForNonAdmin() {
		assertThatThrownBy(() -> groupController.list()).isInstanceOf(AccessDeniedException.class);
	}
}
