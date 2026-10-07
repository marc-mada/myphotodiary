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

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticates against a user's *primary* role assignment specifically
 * (Design.md §16.3/§16.4) - not "whichever RoleAssignment happens to come back
 * first", which is what the legacy app's global
 * {@code AccessController.checkAuthorization(request, action, em)} overload
 * actually did (a quirk of iterating an unordered collection and returning
 * on the first entry, not a deliberate "any role works" design - see
 * Design.md §16.3 for the deleteRole finding in the same class of code).
 * Deterministic here instead.
 */
@Service
public class DomainUserDetailsService implements UserDetailsService {

	private final UserRepository userRepository;

	public DomainUserDetailsService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	// Without this, User.roleAssignments (lazy) can't be read below: Spring
	// Security calls loadUserByUsername() with no transaction of its own, so
	// the persistence context/session from findById() would already be
	// closed by the time getPrimaryRoleAssignment() tries to iterate it -
	// LazyInitializationException, which Spring Security quietly turns into
	// a plain authentication failure (401), not an obviously-broken 500.
	// Found by actually logging in against the running app, not by reading
	// the code - a real trap for real requests, not a static-analysis maybe.
	@Transactional(readOnly = true)
	@Override
	public UserDetails loadUserByUsername(String userName) throws UsernameNotFoundException {
		User user = userRepository.findById(userName)
				.orElseThrow(() -> new UsernameNotFoundException("User not found: " + userName));

		String roleName = user.getPrimaryRoleAssignment()
				.map(ra -> ra.getRole().name())
				.orElse(Role.LOWER.name());

		return org.springframework.security.core.userdetails.User
				.withUsername(user.getUserName())
				.password(user.getPasswordHash())
				.authorities(List.of(new SimpleGrantedAuthority("ROLE_" + roleName)))
				.build();
	}
}
