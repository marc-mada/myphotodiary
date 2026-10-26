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

import java.util.Comparator;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageRepository extends JpaRepository<Image, Long> {
	// @EntityGraph added (31/08/2026, found live: entering a directory with
	// many pictures took 10-20s in production) - `Image.attributes` is a
	// plain (lazy-by-default) @ManyToMany with no fetch join here, so
	// ImageResponse.from's own `image.getAttributes()` call was triggering
	// one extra SELECT per image, every time this method ran - a real N+1,
	// though a secondary contributor next to the frontend's own eager
	// per-thumbnail fetch burst (Filmstrip.jsx, fixed the same day) which
	// is the dominant cost for a directory with many pictures. Fetching
	// `attributes` eagerly here, in the one query, costs nothing extra a
	// directory listing wasn't already going to need anyway.
	@EntityGraph(attributePaths = "attributes")
	List<Image> findByDirectory_IdOrderByNameAsc(Long directoryId);

	// Display order of a sequence (26/10/2026): capture date, oldest first,
	// then file name. Name order alone breaks as soon as a sequence mixes
	// cameras or phones - "20261022_101433.jpg" sorts before "PC253561.jpeg"
	// whatever the dates. A picture without a date goes last.
	@EntityGraph(attributePaths = "attributes")
	@Query("select i from Image i where i.directory.id = :directoryId order by i.captureDate asc nulls last, i.name asc, i.id asc")
	List<Image> findSequenceInDisplayOrder(@Param("directoryId") Long directoryId);

	/** Same order as findSequenceInDisplayOrder, for pictures already loaded. */
	Comparator<Image> DISPLAY_ORDER = Comparator
			.comparing(Image::getCaptureDate, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(Image::getName)
			.thenComparing(Image::getId);

	/** Earliest capture date of each of these folders' pictures - [path, LocalDateTime] rows, folders without dated pictures omitted. */
	@Query("select i.directory.path, min(i.captureDate) from Image i where i.directory.path in :paths and i.captureDate is not null group by i.directory.path")
	List<Object[]> findEarliestCaptureDates(@Param("paths") List<String> paths);

	boolean existsByDirectory_IdAndName(Long directoryId, String name);

	// Search (replaces QuerySvr, Design.md §16.5): an attribute can tag an
	// image directly, or its containing directory - matching either counts,
	// same "own or inherited from directory" rule QuerySvr's JP-QL used.
	// Two separate queries merged in the service (rather than one JPQL
	// UNION) keeps this portable across HSQLDB/PostgreSQL without relying on
	// Hibernate's HQL UNION support.
	@Query("select distinct i from Image i join i.attributes a where a.name = :name")
	List<Image> findByOwnAttributeName(@Param("name") String name);

	@Query("select distinct i from Image i join i.directory.attributes a where a.name = :name")
	List<Image> findByDirectoryAttributeName(@Param("name") String name);
}
