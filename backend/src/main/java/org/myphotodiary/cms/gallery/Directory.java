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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.myphotodiary.cms.user.Group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A gallery directory - the new-stack equivalent of the legacy Directory
 * entity (Design.md §16.2). Path is arbitrary, not just year/month/name
 * (Design.md decision #6/#7 - the direct filesystem-drop usage this
 * preserves needs that).
 *
 * `description` (a sequence-level comment, distinct from the per-image
 * post-it) and `attributes` were deliberately left out of the first pass
 * (V2__create_gallery_schema.sql) and added here in V3, once the
 * navigation/indexing screen that actually surfaces them was underway - see
 * Design.md §15 (legacy feature-parity audit), which is where this
 * specific gap was caught.
 */
@Entity
@Table(name = "directory")
public class Directory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String path;

	private String description;

	private Double latitude;

	private Double longitude;

	@Column(name = "indexing_allowed", nullable = false)
	private boolean indexingAllowed = true;

	@ManyToOne(optional = false)
	@JoinColumn(name = "group_name")
	private Group group;

	@Column(name = "creation_date", nullable = false, updatable = false)
	private LocalDate creationDate;

	/**
	 * The sequence's own nominal date - distinct from {@code creationDate}
	 * (when this DB row was created). Ported in V5 alongside the faithful
	 * import/index EXIF behavior (ExifDateReader, PathDateGuesser) - see
	 * that migration's comment for why it was missed in V2/V3.
	 */
	@Column(name = "sequence_date")
	private LocalDate sequenceDate;

	@ManyToMany
	@JoinTable(
			name = "directory_attribute",
			joinColumns = @JoinColumn(name = "directory_id"),
			inverseJoinColumns = @JoinColumn(name = "attribute_id"))
	private List<Attribute> attributes = new ArrayList<>();

	protected Directory() {
		// JPA
	}

	public Directory(String path, Group group, LocalDate creationDate) {
		this.path = path;
		this.group = group;
		this.creationDate = creationDate;
	}

	public Long getId() {
		return id;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Double getLatitude() {
		return latitude;
	}

	public void setLatitude(Double latitude) {
		this.latitude = latitude;
	}

	public Double getLongitude() {
		return longitude;
	}

	public void setLongitude(Double longitude) {
		this.longitude = longitude;
	}

	public boolean isIndexingAllowed() {
		return indexingAllowed;
	}

	public void setIndexingAllowed(boolean indexingAllowed) {
		this.indexingAllowed = indexingAllowed;
	}

	public Group getGroup() {
		return group;
	}

	public void setGroup(Group group) {
		this.group = group;
	}

	public LocalDate getCreationDate() {
		return creationDate;
	}

	public LocalDate getSequenceDate() {
		return sequenceDate;
	}

	public void setSequenceDate(LocalDate sequenceDate) {
		this.sequenceDate = sequenceDate;
	}

	public List<Attribute> getAttributes() {
		return attributes;
	}
}
