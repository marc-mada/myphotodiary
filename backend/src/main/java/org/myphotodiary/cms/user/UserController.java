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

import org.myphotodiary.cms.user.dto.CreateRoleAssignmentRequest;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.myphotodiary.cms.user.dto.MeResponse;
import org.myphotodiary.cms.user.dto.RoleAssignmentResponse;
import org.myphotodiary.cms.user.dto.UpdateMeRequest;
import org.myphotodiary.cms.user.dto.UpdateUserRequest;
import org.myphotodiary.cms.user.dto.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Replaces the legacy UserAdminSvr (Design.md §16.5) - same operations
 * (list/create/update/delete users, list/create/delete role assignments),
 * REST-shaped instead of the legacy jTable-flavored single-servlet
 * dispatch-by-query-param.
 *
 * Authorization: ADMIN only, same as the legacy `Action.createUser`/
 * `Action.deleteUser` checks (Design.md §16.4) - checked against the caller's
 * *primary* role specifically (see SecurityConfig's UserDetailsService),
 * not "whichever role assignment happens to be first" the way the legacy
 * global checkAuthorization overload did (a quirk, not a deliberate design,
 * per Design.md §16.3).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping("/api/users")
	public List<UserResponse> listUsers() {
		return userService.listUsers();
	}

	@GetMapping("/api/users/{userName}")
	public UserResponse getUser(@PathVariable String userName) {
		return userService.getUser(userName);
	}

	@PostMapping("/api/users")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
		return userService.createUser(request);
	}

	@PutMapping("/api/users/{userName}")
	public UserResponse updateUser(@PathVariable String userName, @RequestBody UpdateUserRequest request) {
		return userService.updateUser(userName, request);
	}

	@DeleteMapping("/api/users/{userName}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteUser(@PathVariable String userName) {
		userService.deleteUser(userName);
	}

	@GetMapping("/api/users/{userName}/roles")
	public List<RoleAssignmentResponse> listRoleAssignments(@PathVariable String userName) {
		return userService.listRoleAssignments(userName);
	}

	@PostMapping("/api/users/{userName}/roles")
	@ResponseStatus(HttpStatus.CREATED)
	public RoleAssignmentResponse addRoleAssignment(@PathVariable String userName, @Valid @RequestBody CreateRoleAssignmentRequest request) {
		return userService.addRoleAssignment(userName, request);
	}

	@DeleteMapping("/api/users/{userName}/roles/{groupName}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeRoleAssignment(@PathVariable String userName, @PathVariable String groupName) {
		userService.removeRoleAssignment(userName, groupName);
	}

	/**
	 * ADMIN edits a chosen user's own preferences (29/08/2026) - the
	 * Admin/Configure panel's "User settings" form is only reachable by an
	 * ADMIN (this whole controller is ADMIN-only), so without an explicit
	 * target-user choice here, a non-admin would have no UI path at all to
	 * their own settings even though {@link MeController}'s {@code /api/me}
	 * already supports any authenticated role self-service. This pair lets
	 * an admin pick and edit *any* user's settings from that one screen;
	 * {@code /api/me} is unchanged and still self-only - see MeController's
	 * own javadoc for how the two relate.
	 */
	@GetMapping("/api/users/{userName}/settings")
	public MeResponse getSettings(@PathVariable String userName) {
		return userService.getSettings(userName);
	}

	@PatchMapping("/api/users/{userName}/settings")
	public MeResponse updateSettings(@PathVariable String userName, @RequestBody UpdateMeRequest request) {
		return userService.updateSettings(userName, request);
	}
}
