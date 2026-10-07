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

import org.myphotodiary.cms.user.dto.MeResponse;
import org.myphotodiary.cms.user.dto.MyGroupResponse;
import org.myphotodiary.cms.user.dto.UpdateMeRequest;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own settings - replaces the legacy SessionConfigurationSvr
 * (`/json/config`, Design.md §16.5), ported field by field as each one's own
 * screen gets built (search page size - V4; slideshow delay - V6; default
 * map center - V7) rather than the legacy UserConfiguration's full field set
 * up front - `style`/`defaultLang` still aren't ported, no screen reads them
 * yet. `postItFadeDelay` (V8) is the one field here with no legacy
 * counterpart at all - a new preference, not a port (see V8's own comment).
 * Deliberately separate from
 * {@link UserController}: that controller is ADMIN-only end to end, but
 * every authenticated user - any role - needs to read and change their own
 * preferences, same as the legacy servlet (any logged-in user, not an
 * admin-only one).
 *
 * Stays self-only even now that {@link UserController} also exposes
 * {@code GET}/{@code PATCH .../{userName}/settings} for the same fields
 * (29/08/2026) - that pair is how an ADMIN edits a chosen *other* user's
 * preferences (the frontend's Admin/Configure panel has a user picker, since
 * that screen is ADMIN-only and would otherwise give a non-admin no way at
 * all to reach their own settings); this endpoint remains how any user,
 * admin or not, reaches their own, with no user-selection possible - the two
 * share validation via {@link UserSettingsUpdater}, not a fallback of one to
 * the other.
 */
@RestController
public class MeController {

	private final UserRepository userRepository;
	private final RoleAssignmentRepository roleAssignmentRepository;

	public MeController(UserRepository userRepository, RoleAssignmentRepository roleAssignmentRepository) {
		this.userRepository = userRepository;
		this.roleAssignmentRepository = roleAssignmentRepository;
	}

	@GetMapping("/api/me")
	@Transactional(readOnly = true)
	public MeResponse me(Authentication authentication) {
		return MeResponse.from(currentUser(authentication));
	}

	/**
	 * The caller's own group memberships (12/09/2026, sequence group
	 * picker) - see {@link MyGroupResponse}'s own doc for why this is
	 * self-service rather than folded into the ADMIN-only
	 * {@code GET /api/groups}.
	 */
	@GetMapping("/api/me/groups")
	@Transactional(readOnly = true)
	public List<MyGroupResponse> myGroups(Authentication authentication) {
		return roleAssignmentRepository.findByUser_UserName(authentication.getName()).stream().map(MyGroupResponse::from).toList();
	}

	@PatchMapping("/api/me")
	@Transactional
	public MeResponse update(Authentication authentication, @RequestBody UpdateMeRequest request) {
		User user = currentUser(authentication);
		UserSettingsUpdater.apply(user, request);
		return MeResponse.from(user);
	}

	private User currentUser(Authentication authentication) {
		// authentication.getName() is trusted here - it's the username Spring
		// Security already verified via DomainUserDetailsService, not
		// request input.
		return userRepository.findById(authentication.getName()).orElseThrow(() -> new UserNotFoundException(authentication.getName()));
	}
}
