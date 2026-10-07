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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.user.Action;
import org.myphotodiary.cms.user.Role;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateRoleAssignmentRequest;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct tests of the per-group RBAC check GalleryController/ImportController/
 * DirectoryController now go through - found missing entirely (28/08/2026: a
 * LOWER-role test account, "dummy2", could upload a picture, which the ported
 * {@link Role#isPermitted} matrix never would have allowed had anything been
 * calling it). This class exercises the matrix directly, one row at a time -
 * the write-path tests threaded through GalleryServiceTest/ImportServiceTest/
 * DirectoryIndexerServiceTest (using an always-permitted ADMIN identity) prove
 * behavior, not authorization; this class is the one that would have caught
 * the original bug (there was no authorization check to test at all).
 */
@SpringBootTest
@Transactional
class GalleryAuthorizationServiceTest {

	@Autowired
	private GalleryAuthorizationService authorizationService;
	@Autowired
	private UserService userService;

	@Test
	void lowerRole_deniedCreateSequence_reproducesTheReportedBug() {
		Authentication auth = userWithRole("dummy2-repro", "public", Role.LOWER);

		// This is exactly the reported scenario: a LOWER account uploading a
		// picture - before this service existed, nothing checked this at
		// all, so it silently succeeded.
		assertThatThrownBy(() -> authorizationService.checkAuthorization(auth, "public", Action.CREATE_SEQUENCE))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void lowerRole_deniedEditSequenceAndEditImage() {
		Authentication auth = userWithRole("lower-user", "public", Role.LOWER);

		assertThatThrownBy(() -> authorizationService.checkAuthorization(auth, "public", Action.EDIT_SEQUENCE))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> authorizationService.checkAuthorization(auth, "public", Action.EDIT_IMAGE))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void readerRole_sameRestrictionsAsLower_perLegacyMatrix() {
		Authentication auth = userWithRole("reader-user", "public", Role.READER);

		assertThatThrownBy(() -> authorizationService.checkAuthorization(auth, "public", Action.CREATE_SEQUENCE))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> authorizationService.checkAuthorization(auth, "public", Action.EDIT_SEQUENCE))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void writerRole_canCreateAndEditButNotDelete() {
		Authentication auth = userWithRole("writer-user", "public", Role.WRITER);

		// Deliberate deviation from strict legacy fidelity (28/08/2026,
		// found and changed the same day the strict port's own consequence
		// was noticed live: a WRITER account blocked from publishing to
		// root because nothing had been published there yet) - WRITER now
		// gets CREATE_SEQUENCE too, so publishing to a brand-new location
		// works. Deletion stays ADMIN-only, unchanged.
		assertThatCode(() -> authorizationService.checkAuthorization(auth, "public", Action.CREATE_SEQUENCE)).doesNotThrowAnyException();
		assertThatCode(() -> authorizationService.checkAuthorization(auth, "public", Action.EDIT_SEQUENCE)).doesNotThrowAnyException();
		assertThatCode(() -> authorizationService.checkAuthorization(auth, "public", Action.EDIT_IMAGE)).doesNotThrowAnyException();
		assertThatThrownBy(() -> authorizationService.checkAuthorization(auth, "public", Action.DELETE_SEQUENCE))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> authorizationService.checkAuthorization(auth, "public", Action.DELETE_IMAGE))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void adminRole_permittedEverything() {
		Authentication auth = userWithRole("admin-user", "public", Role.ADMIN);

		for (Action action : Action.values()) {
			assertThatCode(() -> authorizationService.checkAuthorization(auth, "public", action)).doesNotThrowAnyException();
		}
	}

	@Test
	void noRoleAssignmentInGroupAtAll_denied_forAGroupScopedAdminWhoIsNotTheGlobalAdmin() {
		// A RoleAssignment is per-group, not global - an ADMIN row in "public"
		// grants nothing in a group the user has no assignment for at all (no
		// fallback across groups, unlike the legacy unindexed-path special
		// case this backend doesn't need - every Directory/Image here already
		// always has a real Group). `userWithRole`'s own fixture never carries
		// a Spring ROLE_ADMIN authority (see its own comment) - this is
		// deliberately testing a user who merely *holds* an ADMIN-level
		// RoleAssignment row in one specific group, not the account's own
		// global admin status (that's the *next* test, a real - if
		// different - kind of "admin").
		Authentication auth = userWithRole("scoped-admin", "public", Role.ADMIN);

		assertThatThrownBy(() -> authorizationService.checkAuthorization(auth, "some-other-group", Action.EDIT_SEQUENCE))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void globalAdminAuthority_bypassesEveryPerGroupCheck_evenWithNoRoleAssignmentThere() {
		// The 12/09/2026 fix: a real latent bug found while building the
		// sequence group picker - ROLE_ADMIN (derived from a user's *primary*
		// role assignment, DomainUserDetailsService) used to never be
		// consulted here at all, so even a genuine global admin (e.g. the
		// bootstrap admin, whose only row is in "public") would have been
		// denied on any group they don't hold an explicit row in - directly
		// contradicting "ADMIN can pick up any group" and Role.ADMIN's own
		// "all permissions" semantics. No user row/RoleAssignment exists for
		// this principal at all - the bypass must short-circuit before ever
		// touching the database.
		Authentication auth = new UsernamePasswordAuthenticationToken("global-admin-user", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

		for (Action action : Action.values()) {
			assertThatCode(() -> authorizationService.checkAuthorization(auth, "a-group-with-no-row-at-all", action)).doesNotThrowAnyException();
		}
	}

	@Test
	void readableGroupNames_globalAdmin_returnsNull_meaningEveryGroupIsReadable() {
		Authentication auth = new UsernamePasswordAuthenticationToken("global-admin-user-2", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

		assertThat(authorizationService.readableGroupNames(auth)).isNull();
	}

	@Test
	void readableGroupNames_nonAdmin_returnsExactlyTheGroupsWithAnyRoleAssignment() {
		// Two groups with a role assignment (WRITER and LOWER - both permit
		// BROWSE per the matrix, confirming "any role at all" is enough, not
		// just WRITER/ADMIN), one group deliberately left with none at all.
		Authentication auth = userWithRole("multi-group-reader", "group-a", Role.WRITER);
		userService.addRoleAssignment("multi-group-reader", new CreateRoleAssignmentRequest("group-b", Role.LOWER.name()));

		assertThat(authorizationService.readableGroupNames(auth)).containsExactlyInAnyOrder("group-a", "group-b").doesNotContain("group-c-no-access");
	}

	// Deliberately NOT carrying a Spring ROLE_ADMIN authority (the two-arg
	// UsernamePasswordAuthenticationToken constructor sets no authorities at
	// all) - every test above using this helper is exercising the per-group
	// RoleAssignment-row lookup specifically, never the global-admin bypass,
	// regardless of which Role the row itself holds.
	private Authentication userWithRole(String userName, String groupName, Role role) {
		userService.createUser(new CreateUserRequest(userName, userName, "x", groupName, role.name()));
		return new UsernamePasswordAuthenticationToken(userName, null);
	}
}
