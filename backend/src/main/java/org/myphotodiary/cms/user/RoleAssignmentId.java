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

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Embeddable;

/**
 * Composite key for RoleAssignment, used with {@code @EmbeddedId} +
 * {@code @MapsId} on the two owning relationships (see RoleAssignment) -
 * the modern JPA equivalent of the legacy app's {@code @IdClass} +
 * {@code insertable=false, updatable=false} workaround (Design.md §16.2).
 * Field names ("user"/"group") must match the {@code @MapsId(...)} values
 * on RoleAssignment exactly.
 */
@Embeddable
public class RoleAssignmentId implements Serializable {

	private String user;
	private String group;

	protected RoleAssignmentId() {
		// JPA
	}

	public RoleAssignmentId(String user, String group) {
		this.user = user;
		this.group = group;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof RoleAssignmentId other)) return false;
		return Objects.equals(user, other.user) && Objects.equals(group, other.group);
	}

	@Override
	public int hashCode() {
		return Objects.hash(user, group);
	}
}
