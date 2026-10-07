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

import org.myphotodiary.cms.user.RoleAssignment;

/**
 * The caller's own group memberships (GET /api/me/groups, 12/09/2026) -
 * self-service like the rest of {@code MeController}, any authenticated
 * role. Backs the sequence group picker's WRITER-filtered dropdown (only
 * the rows where {@code role == "WRITER"} - "one of its own groups as a
 * WRITER", explicit ask) without exposing the full group list the way
 * ADMIN-only {@code GET /api/groups} does.
 */
public record MyGroupResponse(String groupName, String role) {
	public static MyGroupResponse from(RoleAssignment roleAssignment) {
		return new MyGroupResponse(roleAssignment.getGroup().getGroupName(), roleAssignment.getRole().name());
	}
}
