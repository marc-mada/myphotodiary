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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One background video encode waiting, running or failed (27/10/2026, V19)
 * - see {@link VideoProcessingService}. Holds the video's id, never its
 * path: the video may be renamed, moved or merged while it waits, and the
 * current path is looked up when the job starts. Deleted by the database
 * with its video (ON DELETE CASCADE), and by the service when it succeeds.
 */
@Entity
@Table(name = "video_job")
public class VideoJob {

	public enum Stage {
		/** The H.264 playback copy - priority 0, always before any compaction. */
		PLAYBACK_COPY(0),
		/** Re-encoding an inefficient original to HEVC - priority 1. */
		COMPACT_ORIGINAL(1);

		private final int priority;

		Stage(int priority) {
			this.priority = priority;
		}

		public int priority() {
			return priority;
		}
	}

	public enum State {
		PENDING,
		RUNNING,
		FAILED,
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "image_id", nullable = false)
	private Long imageId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Stage stage;

	@Column(nullable = false)
	private int priority;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private State state = State.PENDING;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected VideoJob() {
	}

	public VideoJob(Long imageId, Stage stage) {
		this.imageId = imageId;
		this.stage = stage;
		this.priority = stage.priority();
		this.createdAt = LocalDateTime.now();
		this.updatedAt = this.createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getImageId() {
		return imageId;
	}

	public Stage getStage() {
		return stage;
	}

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
		this.updatedAt = LocalDateTime.now();
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public String getLastError() {
		return lastError;
	}

	public void setLastError(String lastError) {
		this.lastError = lastError == null || lastError.length() <= 1000 ? lastError : lastError.substring(lastError.length() - 1000);
	}
}
