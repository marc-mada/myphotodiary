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

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * A sequence that was merged into another one (17/10/2026, V16): keeps the
 * pictures that came from it, so its share links still show exactly those
 * (SequenceShareResolver). The id is the removed sequence's own id - what
 * its share tokens carry. Identity values are never reused, so it can't
 * clash with a live directory id.
 */
@Entity
@Table(name = "merged_sequence")
public class MergedSequence {

	@Id
	@Column(name = "old_directory_id")
	private Long oldDirectoryId;

	/** Where its pictures are now - kept up to date if that sequence is merged again. */
	@Column(name = "target_directory_id", nullable = false)
	private Long targetDirectoryId;

	private String description;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "merged_sequence_image", joinColumns = @JoinColumn(name = "old_directory_id"))
	@Column(name = "image_id", nullable = false)
	private Set<Long> imageIds = new HashSet<>();

	protected MergedSequence() {
		// JPA
	}

	public MergedSequence(Long oldDirectoryId, Long targetDirectoryId, String description, Set<Long> imageIds) {
		this.oldDirectoryId = oldDirectoryId;
		this.targetDirectoryId = targetDirectoryId;
		this.description = description;
		this.imageIds = new HashSet<>(imageIds);
	}

	public Long getOldDirectoryId() {
		return oldDirectoryId;
	}

	public Long getTargetDirectoryId() {
		return targetDirectoryId;
	}

	public void setTargetDirectoryId(Long targetDirectoryId) {
		this.targetDirectoryId = targetDirectoryId;
	}

	public String getDescription() {
		return description;
	}

	public Set<Long> getImageIds() {
		return imageIds;
	}
}
