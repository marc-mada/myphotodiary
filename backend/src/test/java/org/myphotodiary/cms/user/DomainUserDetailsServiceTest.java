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

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Regression test for a real bug found live (not by reading the code):
 * loadUserByUsername() originally had no transaction of its own, so reading
 * User.roleAssignments (lazy) inside it threw LazyInitializationException -
 * which Spring Security silently turned into an ordinary 401, not something
 * that looked broken. Only surfaced by actually logging in against the
 * running app (README-style verification, same discipline as the legacy
 * fixes) - a @DataJpaTest/mocked-repository test would not have caught this,
 * since it never exercises Spring Security's real authentication call path.
 *
 * Deliberately NOT @Transactional at the class level: that would keep one
 * ambient session open for the whole test method regardless of whether
 * loadUserByUsername() opens its own, masking exactly the bug being tested
 * for. createUser() commits and closes its own transaction first (it's
 * @Transactional itself), so loadUserByUsername() is genuinely called with
 * no session already open - the real runtime shape (called from Spring
 * Security's filter chain, no surrounding transaction of its own).
 */
@SpringBootTest
class DomainUserDetailsServiceTest {

	@Autowired
	private DomainUserDetailsService userDetailsService;
	@Autowired
	private UserService userService;

	@Test
	void loadUserByUsername_doesNotThrowOnLazyRoleAssignments() {
		userService.createUser(new CreateUserRequest("kate", null, "pw", "home", "WRITER"));

		UserDetails details = userDetailsService.loadUserByUsername("kate");

		assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_WRITER");
	}

	@Test
	void loadUserByUsername_usesPrimaryRoleSpecifically() {
		userService.createUser(new CreateUserRequest("liam", null, "pw", "home", "READER"));
		userService.addRoleAssignment("liam", new org.myphotodiary.cms.user.dto.CreateRoleAssignmentRequest("vacation", "ADMIN"));

		// The secondary assignment is ADMIN, but authentication must still
		// reflect the *primary* one (READER) - not "whichever comes first",
		// the legacy quirk this deliberately doesn't reproduce (Design.md §16.3).
		UserDetails details = userDetailsService.loadUserByUsername("liam");

		assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_READER");
	}
}
