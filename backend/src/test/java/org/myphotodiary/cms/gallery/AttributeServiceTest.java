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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.gallery.dto.AttributeResponse;
import org.myphotodiary.cms.gallery.dto.CreateAttributeRequest;
import org.myphotodiary.cms.gallery.dto.UpdateAttributeRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@Transactional
class AttributeServiceTest {

	@Autowired
	private AttributeService attributeService;
	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void create_rootAttribute_hasNoParent() {
		AttributeResponse vacation = attributeService.create(new CreateAttributeRequest("vacation-" + System.nanoTime(), null));

		assertThat(vacation.parentName()).isNull();
	}

	@Test
	void create_duplicateName_throwsConflict() {
		String name = "beach-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(name, null));

		assertThatThrownBy(() -> attributeService.create(new CreateAttributeRequest(name, null)))
				.isInstanceOf(AttributeAlreadyExistsException.class);
	}

	@Test
	void listChildren_returnsOnlyDirectChildrenOfGivenParent() {
		String root = "trip-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(root, null));
		String child = "beach-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(child, root));
		attributeService.create(new CreateAttributeRequest("unrelated-" + System.nanoTime(), null));

		assertThat(attributeService.listChildren(root)).extracting(AttributeResponse::name).containsExactly(child);
	}

	@Test
	void update_reparenting_movesAttributeUnderNewParent() {
		String parentA = "parentA-" + System.nanoTime();
		String parentB = "parentB-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(parentA, null));
		attributeService.create(new CreateAttributeRequest(parentB, null));
		AttributeResponse child = attributeService.create(new CreateAttributeRequest("child-" + System.nanoTime(), parentA));

		AttributeResponse moved = attributeService.update(child.id(), new UpdateAttributeRequest(null, parentB));

		assertThat(moved.parentName()).isEqualTo(parentB);
	}

	@Test
	void update_reparentingUnderOwnDescendant_throws() {
		AttributeResponse root = attributeService.create(new CreateAttributeRequest("root-" + System.nanoTime(), null));
		AttributeResponse child = attributeService.create(new CreateAttributeRequest("child-" + System.nanoTime(), root.name()));

		// Trying to move "root" under its own child would create a cycle.
		assertThatThrownBy(() -> attributeService.update(root.id(), new UpdateAttributeRequest(null, child.name())))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void delete_reparentsChildrenToDeletedAttributesOwnParent() {
		// Direct ask (26/09/2026): does deleting a tag properly unlink its
		// children and relink them to their grandparent? AttributeService
		// .delete's own comment already claims yes (legacy idxAdmin.js
		// behavior, ported) - checked here for real rather than trusted,
		// same discipline as the sibling dereference tests in
		// GalleryServiceTest/DirectoryIndexerServiceTest.
		//
		// This one is a genuinely sharper check than "does the returned DTO
		// look right": attribute.parent_id's own foreign key (V3 migration)
		// has no ON DELETE clause at all - unlike image_attribute/
		// directory_attribute's ON DELETE CASCADE, it defaults to RESTRICT.
		// If the reparenting UPDATE were ever flushed *after* (or never
		// flushed at all before) the DELETE, this wouldn't silently orphan
		// data - it would throw a real SQL foreign key violation, since the
		// database would still see a child row pointing at the id being
		// deleted. The flush()/clear() pair before the delete call forces
		// exactly that ordering question to be answered by the real
		// database, not by whatever Hibernate's in-memory object graph
		// happens to already agree on within one shared test session (the
		// same TransientObjectException risk already documented on the
		// sibling tests' own flush()/clear() comment). The pair after
		// proves the read afterward is genuinely fresh too, not the same
		// managed Java objects this test itself already mutated.
		String grandparent = "grandparent-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(grandparent, null));
		AttributeResponse parent = attributeService.create(new CreateAttributeRequest("parent-" + System.nanoTime(), grandparent));
		AttributeResponse child = attributeService.create(new CreateAttributeRequest("child-" + System.nanoTime(), parent.name()));
		entityManager.flush();
		entityManager.clear();

		attributeService.delete(parent.id());
		entityManager.flush();
		entityManager.clear();

		AttributeResponse reparentedChild = attributeService.listAll().stream()
				.filter(a -> a.id().equals(child.id())).findFirst().orElseThrow();
		assertThat(reparentedChild.parentName()).isEqualTo(grandparent);
		// The deleted tag itself is really gone, not just hidden - and the
		// grandparent is unaffected (still has no parent of its own).
		assertThat(attributeService.listAll()).extracting(AttributeResponse::name).doesNotContain(parent.name());
		AttributeResponse reloadedGrandparent = attributeService.listAll().stream()
				.filter(a -> a.name().equals(grandparent)).findFirst().orElseThrow();
		assertThat(reloadedGrandparent.parentName()).isNull();
	}

	@Test
	void delete_reparentsChildrenToTopLevel_whenTheDeletedAttributeHadNoParentOfItsOwn() {
		// The other half of the same question - deleting a *root* tag (no
		// grandparent to fall back to) must promote its children to
		// top-level themselves, not leave them dangling or delete them
		// along with it. Same flush()/clear() rigor as the test above, for
		// the same reason (the RESTRICT foreign key on attribute.parent_id
		// would throw a real constraint violation here too if the ordering
		// were ever wrong).
		String root = "root-tag-" + System.nanoTime();
		attributeService.create(new CreateAttributeRequest(root, null));
		AttributeResponse child = attributeService.create(new CreateAttributeRequest("child-" + System.nanoTime(), root));
		entityManager.flush();
		entityManager.clear();

		AttributeResponse rootAttribute = attributeService.listAll().stream().filter(a -> a.name().equals(root)).findFirst().orElseThrow();
		attributeService.delete(rootAttribute.id());
		entityManager.flush();
		entityManager.clear();

		AttributeResponse reparentedChild = attributeService.listAll().stream()
				.filter(a -> a.id().equals(child.id())).findFirst().orElseThrow();
		assertThat(reparentedChild.parentName()).isNull();
		assertThat(attributeService.listAll()).extracting(AttributeResponse::name).doesNotContain(root);
	}
}
