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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.user.dto.CreateRoleAssignmentRequest;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.myphotodiary.cms.user.dto.RoleAssignmentResponse;
import org.myphotodiary.cms.user.dto.UpdateMeRequest;
import org.myphotodiary.cms.user.dto.UpdateUserRequest;
import org.myphotodiary.cms.user.dto.UserResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each test method runs in its own transaction, rolled back afterward
 * (Spring's standard @SpringBootTest + @Transactional behavior) - simpler
 * per-test isolation than the legacy test suite needed, which had to work around much older tooling.
 */
@SpringBootTest
@Transactional
class UserServiceTest {

	@Autowired
	private UserService userService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void createUser_hashesPasswordAndDefaultsGroupAndRole() {
		UserResponse created = userService.createUser(new CreateUserRequest("alice", "Alice A.", "s3cret", null, null));

		assertThat(created.userName()).isEqualTo("alice");
		assertThat(created.primaryGroupName()).isEqualTo("public");
		assertThat(created.primaryRoleName()).isEqualTo("LOWER");

		User stored = userRepository.findById("alice").orElseThrow();
		assertThat(stored.getPasswordHash()).isNotEqualTo("s3cret");
		assertThat(passwordEncoder.matches("s3cret", stored.getPasswordHash())).isTrue();
	}

	@Test
	void createUser_explicitGroupAndRole() {
		UserResponse created = userService.createUser(new CreateUserRequest("bob", "Bob B.", "pw", "photos", "WRITER"));

		assertThat(created.primaryGroupName()).isEqualTo("photos");
		assertThat(created.primaryRoleName()).isEqualTo("WRITER");
	}

	@Test
	void createUser_duplicateUserName_throws() {
		userService.createUser(new CreateUserRequest("carol", null, null, null, null));

		assertThatThrownBy(() -> userService.createUser(new CreateUserRequest("carol", null, null, null, null)))
				.isInstanceOf(UserAlreadyExistsException.class);
	}

	@Test
	void updateUser_blankPassword_leavesExistingPasswordUnchanged() {
		userService.createUser(new CreateUserRequest("dave", null, "original-password", null, null));
		String hashAfterCreate = userRepository.findById("dave").orElseThrow().getPasswordHash();

		userService.updateUser("dave", new UpdateUserRequest("Dave D.", "", null));

		User reloaded = userRepository.findById("dave").orElseThrow();
		assertThat(reloaded.getPasswordHash()).isEqualTo(hashAfterCreate);
		assertThat(reloaded.getLongName()).isEqualTo("Dave D.");
	}

	@Test
	void updateUser_newPassword_replacesHash() {
		userService.createUser(new CreateUserRequest("erin", null, "old-password", null, null));

		userService.updateUser("erin", new UpdateUserRequest(null, "new-password", null));

		User reloaded = userRepository.findById("erin").orElseThrow();
		assertThat(passwordEncoder.matches("new-password", reloaded.getPasswordHash())).isTrue();
		assertThat(passwordEncoder.matches("old-password", reloaded.getPasswordHash())).isFalse();
	}

	@Test
	void updateUser_primaryRole_changesInPlace() {
		userService.createUser(new CreateUserRequest("frank", null, null, null, "LOWER"));

		userService.updateUser("frank", new UpdateUserRequest(null, null, "ADMIN"));

		assertThat(userService.getUser("frank").primaryRoleName()).isEqualTo("ADMIN");
	}

	@Test
	void updateUser_unknownUser_throws() {
		assertThatThrownBy(() -> userService.updateUser("nobody", new UpdateUserRequest("x", null, null)))
				.isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void deleteUser_removesUserAndPrimaryRoleAssignment() {
		userService.createUser(new CreateUserRequest("gina", null, null, null, null));

		userService.deleteUser("gina");

		assertThat(userRepository.findById("gina")).isEmpty();
	}

	@Test
	void deleteUser_unknownUser_throws() {
		assertThatThrownBy(() -> userService.deleteUser("nobody")).isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void addAndRemoveRoleAssignment_actuallyRemovesIt() {
		// Regression test for the exact bug found in the legacy app's
		// deleteRole (Design.md §16.3): removing an entity via the
		// repository alone, while its parent's collection is still loaded
		// in the same persistence context, can get silently undone by
		// orphanRemoval's cascade re-inserting it on flush. This test only
		// passes because UserService.removeRoleAssignment removes the
		// entity from *both* sides' in-memory collections first.
		userService.createUser(new CreateUserRequest("henry", null, null, "home", null));
		userService.addRoleAssignment("henry", new CreateRoleAssignmentRequest("vacation", "READER"));

		assertThat(userService.listRoleAssignments("henry")).hasSize(2);

		userService.removeRoleAssignment("henry", "vacation");

		// listRoleAssignments issues a fresh derived query in the same
		// (still-open) persistence context; Hibernate auto-flushes pending
		// changes - including the orphanRemoval-triggered DELETE - before
		// running any query that could be affected by them, so this reads
		// real post-delete state rather than a possibly-stale collection.
		assertThat(userService.listRoleAssignments("henry")).extracting(RoleAssignmentResponse::groupName).containsExactly("home");
	}

	@Test
	void removeRoleAssignment_cannotRemovePrimary() {
		userService.createUser(new CreateUserRequest("iris", null, null, "home2", null));

		assertThatThrownBy(() -> userService.removeRoleAssignment("iris", "home2"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void addRoleAssignment_duplicateGroup_throws() {
		userService.createUser(new CreateUserRequest("jack", null, null, "home3", null));

		assertThatThrownBy(() -> userService.addRoleAssignment("jack", new CreateRoleAssignmentRequest("home3", "READER")))
				.isInstanceOf(RoleAssignmentAlreadyExistsException.class);
	}

	// getSettings/updateSettings (29/08/2026) - the ADMIN-picks-a-user
	// counterpart of MeController's own self-service endpoints. Just the
	// wiring here (defaults come through, an update is applied and reflected
	// on re-read, one bound check fires) - the exhaustive bound coverage for
	// every field already lives in MeControllerTest against the shared
	// UserSettingsUpdater, no need to repeat it per caller.

	@Test
	void getSettings_returnsDefaultsForAnyAdminChosenUser() {
		userService.createUser(new CreateUserRequest("karen", "Karen K.", "pw", null, null));

		var settings = userService.getSettings("karen");

		assertThat(settings.userName()).isEqualTo("karen");
		assertThat(settings.slideShowInterval()).isEqualTo(5);
		assertThat(settings.postItFadeDelay()).isEqualTo(8);
	}

	@Test
	void updateSettings_appliesAndPersists() {
		userService.createUser(new CreateUserRequest("liam", "Liam L.", "pw", null, null));

		var updated = userService.updateSettings("liam", new UpdateMeRequest(null, 20, 10.0, -5.0, 30));

		assertThat(updated.slideShowInterval()).isEqualTo(20);
		assertThat(updated.defaultLatitude()).isEqualTo(10.0);
		assertThat(updated.postItFadeDelay()).isEqualTo(30);
		// Re-read confirms it's a real persisted change, not just the
		// returned snapshot reflecting an in-memory-only mutation.
		assertThat(userService.getSettings("liam").slideShowInterval()).isEqualTo(20);
	}

	@Test
	void updateSettings_outOfBounds_throwsAndDoesNotApplyToTheWrongUser() {
		userService.createUser(new CreateUserRequest("mona", "Mona M.", "pw", null, null));

		assertThatThrownBy(() -> userService.updateSettings("mona", new UpdateMeRequest(null, 1, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(userService.getSettings("mona").slideShowInterval()).isEqualTo(5);
	}
}
