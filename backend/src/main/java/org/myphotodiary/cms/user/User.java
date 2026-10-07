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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * Note what's deliberately absent compared to the legacy entity: no
 * {@code @JsonInclude}/{@code @JsonIgnore} annotations at all here, because
 * this entity is never serialized directly - the controller always maps to
 * a DTO (UserResponse) that has no password field. The legacy app got this
 * wrong (Design.md §16.8.1: password was @JsonInclude and went straight to
 * the browser in list/create responses) by serializing the entity itself;
 * the DTO boundary here makes that class of mistake structurally harder to
 * make again, not just something to remember not to do.
 */
@Entity
@Table(name = "app_user")
public class User {

	@Id
	@Column(name = "user_name")
	private String userName;

	@Column(name = "long_name")
	private String longName;

	/** Always a bcrypt hash - never set directly from a raw request value, see UserService. */
	@Column(nullable = false)
	private String password;

	@Column(name = "creation_date", nullable = false, updatable = false)
	private LocalDate creationDate;

	/** Search result page size (legacy UserConfiguration.maxQueryLength) - see V4 migration for why only this one preference field was ported so far. */
	@Column(name = "max_query_length", nullable = false)
	private int maxQueryLength = 10;

	/** Gallery slideshow delay in seconds (legacy UserConfiguration.slideShowInterval) - see V6 migration. */
	@Column(name = "slide_show_interval", nullable = false)
	private int slideShowInterval = 5;

	/** Default map center for a sequence with no location yet (legacy UserConfiguration.defaultLat/defaultLng) - see V7 migration. */
	@Column(name = "default_latitude", nullable = false)
	private double defaultLatitude = 48.8567;

	@Column(name = "default_longitude", nullable = false)
	private double defaultLongitude = 2.3508;

	/** Desktop post-it auto-fade delay in seconds - a new preference, no legacy field behind it (see V8 migration). */
	@Column(name = "postit_fade_delay", nullable = false)
	private int postItFadeDelay = 8;

	@OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<RoleAssignment> roleAssignments = new ArrayList<>();

	protected User() {
		// JPA
	}

	public User(String userName, String longName, LocalDate creationDate) {
		this.userName = userName;
		this.longName = longName;
		this.creationDate = creationDate;
	}

	public String getUserName() {
		return userName;
	}

	public String getLongName() {
		return longName;
	}

	public void setLongName(String longName) {
		this.longName = longName;
	}

	public String getPasswordHash() {
		return password;
	}

	public void setPasswordHash(String bcryptHash) {
		this.password = bcryptHash;
	}

	public LocalDate getCreationDate() {
		return creationDate;
	}

	public int getMaxQueryLength() {
		return maxQueryLength;
	}

	public void setMaxQueryLength(int maxQueryLength) {
		this.maxQueryLength = maxQueryLength;
	}

	public int getSlideShowInterval() {
		return slideShowInterval;
	}

	public void setSlideShowInterval(int slideShowInterval) {
		this.slideShowInterval = slideShowInterval;
	}

	public double getDefaultLatitude() {
		return defaultLatitude;
	}

	public void setDefaultLatitude(double defaultLatitude) {
		this.defaultLatitude = defaultLatitude;
	}

	public double getDefaultLongitude() {
		return defaultLongitude;
	}

	public void setDefaultLongitude(double defaultLongitude) {
		this.defaultLongitude = defaultLongitude;
	}

	public int getPostItFadeDelay() {
		return postItFadeDelay;
	}

	public void setPostItFadeDelay(int postItFadeDelay) {
		this.postItFadeDelay = postItFadeDelay;
	}

	public List<RoleAssignment> getRoleAssignments() {
		return roleAssignments;
	}

	public void addRoleAssignment(RoleAssignment roleAssignment) {
		roleAssignments.add(roleAssignment);
	}

	/** The role assignment marked primary, if any - a user should have exactly one. */
	public Optional<RoleAssignment> getPrimaryRoleAssignment() {
		return roleAssignments.stream().filter(RoleAssignment::isPrimary).findFirst();
	}
}
