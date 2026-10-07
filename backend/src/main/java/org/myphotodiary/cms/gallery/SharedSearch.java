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

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * A search result shared through an external link (04/10/2026, V14) -
 * frozen at creation: the ids of the pictures the sharer saw, in search
 * order. A picture deleted afterwards disappears from {@code imageIds}
 * through the database's own ON DELETE CASCADE (V14's own comment), which
 * can leave a gap in {@code sort_order} - Hibernate then puts a
 * {@code null} at that position when loading the list, so readers must
 * skip nulls (SharedSearchService does).
 */
@Entity
@Table(name = "shared_search")
public class SharedSearch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "created_by", nullable = false)
	private String createdBy;

	/** How many shareable pictures the search matched when shared - more than {@code imageIds} when it was cut at the maximum. */
	@Column(name = "match_count", nullable = false)
	private int matchCount;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "shared_search_image", joinColumns = @JoinColumn(name = "shared_search_id"))
	@OrderColumn(name = "sort_order")
	@Column(name = "image_id", nullable = false)
	private List<Long> imageIds = new ArrayList<>();

	protected SharedSearch() {
		// JPA
	}

	public SharedSearch(LocalDateTime createdAt, LocalDateTime expiresAt, String createdBy, int matchCount, List<Long> imageIds) {
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.createdBy = createdBy;
		this.matchCount = matchCount;
		this.imageIds = new ArrayList<>(imageIds);
	}

	public Long getId() {
		return id;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public int getMatchCount() {
		return matchCount;
	}

	/** May contain {@code null}s where a picture was deleted since - see the class doc. */
	public List<Long> getImageIds() {
		return imageIds;
	}
}
