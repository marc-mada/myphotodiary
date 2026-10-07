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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A single photo (or, since 28/08/2026, video - see {@link MediaType} and
 * V9's own comment) within a {@link Directory} - the new-stack equivalent of
 * the legacy Image entity (Design.md §16.2). No `orientation` field: see
 * V2__create_gallery_schema.sql for why. `description` is the "post-it"
 * comment (Design.md decision #8 - preserved as a distinct visual element on
 * the gallery screen, not just a plain form field).
 */
@Entity
@Table(name = "image", uniqueConstraints = @UniqueConstraint(columnNames = { "name", "directory_id" }))
public class Image {

	public static final int NOT_RATED = -1;
	public static final int POOR = 0;
	public static final int MEDIUM = 1;
	public static final int GOOD = 2;
	public static final int VERY_GOOD = 3;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	private String description;

	@Column(nullable = false)
	private int rating = NOT_RATED;

	@Column(name = "capture_date")
	private LocalDateTime captureDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "media_type", nullable = false)
	private MediaType mediaType = MediaType.IMAGE;

	// GPS location (16/09/2026, explicit ask) - read from EXIF at index/
	// import time (ExifGpsReader), null when the photo carries no GPS tag
	// at all - never set to a placeholder like 0/0, and never editable
	// directly (explicit ask: only the sequence's own pin is user-movable,
	// not an individual image's).
	private Double latitude;
	private Double longitude;

	@ManyToOne(optional = false)
	@JoinColumn(name = "directory_id")
	private Directory directory;

	@ManyToMany
	@JoinTable(
			name = "image_attribute",
			joinColumns = @JoinColumn(name = "image_id"),
			inverseJoinColumns = @JoinColumn(name = "attribute_id"))
	private List<Attribute> attributes = new ArrayList<>();

	protected Image() {
		// JPA
	}

	public Image(String name, Directory directory, LocalDateTime captureDate) {
		this(name, directory, captureDate, MediaType.IMAGE);
	}

	public Image(String name, Directory directory, LocalDateTime captureDate, MediaType mediaType) {
		this.name = name;
		this.directory = directory;
		this.captureDate = captureDate;
		this.mediaType = mediaType;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public int getRating() {
		return rating;
	}

	public void setRating(int rating) {
		this.rating = rating;
	}

	public LocalDateTime getCaptureDate() {
		return captureDate;
	}

	public void setCaptureDate(LocalDateTime captureDate) {
		this.captureDate = captureDate;
	}

	public MediaType getMediaType() {
		return mediaType;
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

	public Directory getDirectory() {
		return directory;
	}

	public List<Attribute> getAttributes() {
		return attributes;
	}
}
