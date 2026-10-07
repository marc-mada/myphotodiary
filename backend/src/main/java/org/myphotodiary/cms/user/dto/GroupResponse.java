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

import org.myphotodiary.cms.user.Group;

/** The full group list (GET /api/groups) - ADMIN-only, backing the sequence group picker's unrestricted dropdown (12/09/2026). */
public record GroupResponse(String groupName, String description) {
	public static GroupResponse from(Group group) {
		return new GroupResponse(group.getGroupName(), group.getDescription());
	}
}
