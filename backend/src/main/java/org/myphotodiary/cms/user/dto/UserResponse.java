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

package org.myphotodiary.cms.user.dto;

import java.time.LocalDate;

import org.myphotodiary.cms.user.RoleAssignment;
import org.myphotodiary.cms.user.User;

/** No password field, on purpose - see the note on the User entity itself. */
public record UserResponse(
		String userName,
		String longName,
		LocalDate creationDate,
		String primaryGroupName,
		String primaryRoleName) {

	public static UserResponse from(User user) {
		String primaryGroup = null;
		String primaryRole = null;
		var primary = user.getPrimaryRoleAssignment();
		if (primary.isPresent()) {
			RoleAssignment ra = primary.get();
			primaryGroup = ra.getGroup().getGroupName();
			primaryRole = ra.getRole().name();
		}
		return new UserResponse(user.getUserName(), user.getLongName(), user.getCreationDate(), primaryGroup, primaryRole);
	}
}
