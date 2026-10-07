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

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "role_assignment")
public class RoleAssignment {

	@EmbeddedId
	private RoleAssignmentId id;

	@ManyToOne
	@MapsId("user")
	@JoinColumn(name = "user_name")
	private User user;

	@ManyToOne
	@MapsId("group")
	@JoinColumn(name = "group_name")
	private Group group;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(name = "is_primary", nullable = false)
	private boolean primary;

	protected RoleAssignment() {
		// JPA
	}

	public RoleAssignment(User user, Group group, Role role, boolean primary) {
		this.id = new RoleAssignmentId(user.getUserName(), group.getGroupName());
		this.user = user;
		this.group = group;
		this.role = role;
		this.primary = primary;
	}

	public User getUser() {
		return user;
	}

	public Group getGroup() {
		return group;
	}

	public Role getRole() {
		return role;
	}

	public void setRole(Role role) {
		this.role = role;
	}

	public boolean isPrimary() {
		return primary;
	}
}
