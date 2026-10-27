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

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface VideoJobRepository extends JpaRepository<VideoJob, Long> {

	/** The next job to run: every playback copy (priority 0) before any compaction (1), oldest first. */
	@Query("select j from VideoJob j where j.state = org.myphotodiary.cms.gallery.VideoJob.State.PENDING order by j.priority asc, j.id asc")
	List<VideoJob> findNextPending(Limit limit);

	default Optional<VideoJob> findNextPending() {
		return findNextPending(Limit.of(1)).stream().findFirst();
	}

	List<VideoJob> findByImageId(Long imageId);

	long countByState(VideoJob.State state);

	/** Jobs left RUNNING by a stop or crash go back to the queue (at start-up). */
	@Modifying
	@Query("update VideoJob j set j.state = org.myphotodiary.cms.gallery.VideoJob.State.PENDING where j.state = org.myphotodiary.cms.gallery.VideoJob.State.RUNNING")
	int requeueRunning();
}
