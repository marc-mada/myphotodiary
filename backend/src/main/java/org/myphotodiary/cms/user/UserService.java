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

import java.time.LocalDate;
import java.util.List;

import org.myphotodiary.cms.user.dto.CreateRoleAssignmentRequest;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.myphotodiary.cms.user.dto.MeResponse;
import org.myphotodiary.cms.user.dto.RoleAssignmentResponse;
import org.myphotodiary.cms.user.dto.UpdateMeRequest;
import org.myphotodiary.cms.user.dto.UpdateUserRequest;
import org.myphotodiary.cms.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User/group/role CRUD - the Spring Data JPA + {@code @Transactional}
 * replacement for the legacy {@code ModelFactory} (Design.md §16.3). No
 * manual EntityManager/EntityTransaction lifecycle to get right at every
 * call site (the legacy closeEm/closeTx booleans this eliminates), and two
 * bugs found in that legacy code are deliberately *not* reproduced here -
 * see the comments on {@link #updateUser} and {@link #removeRoleAssignment}.
 */
@Service
public class UserService {

	private static final String DEFAULT_GROUP = "public";
	private static final Role DEFAULT_ROLE = Role.LOWER;

	private final UserRepository userRepository;
	private final GroupRepository groupRepository;
	private final RoleAssignmentRepository roleAssignmentRepository;
	private final PasswordEncoder passwordEncoder;

	public UserService(UserRepository userRepository, GroupRepository groupRepository,
			RoleAssignmentRepository roleAssignmentRepository, PasswordEncoder passwordEncoder) {
		this.userRepository = userRepository;
		this.groupRepository = groupRepository;
		this.roleAssignmentRepository = roleAssignmentRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional(readOnly = true)
	public List<UserResponse> listUsers() {
		return userRepository.findAll().stream().map(UserResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public UserResponse getUser(String userName) {
		return UserResponse.from(findUserOrThrow(userName));
	}

	@Transactional
	public UserResponse createUser(CreateUserRequest request) {
		if (userRepository.existsById(request.userName())) {
			throw new UserAlreadyExistsException(request.userName());
		}

		User user = new User(request.userName(), request.longName(), LocalDate.now());
		String rawPassword = request.password() == null ? "" : request.password();
		// Always hashed before it ever reaches the entity/DB - unlike the
		// legacy app, which persisted whatever ParamMapper's reflection
		// happened to set on the raw field (Design.md §16.8.1).
		user.setPasswordHash(passwordEncoder.encode(rawPassword));

		String groupName = blankToNull(request.primaryGroupName()) == null ? DEFAULT_GROUP : request.primaryGroupName();
		Role role = blankToNull(request.primaryRoleName()) == null ? DEFAULT_ROLE : Role.valueOf(request.primaryRoleName());
		Group group = findOrCreateGroup(groupName);

		user.addRoleAssignment(new RoleAssignment(user, group, role, true));

		return UserResponse.from(userRepository.save(user));
	}

	@Transactional
	public UserResponse updateUser(String userName, UpdateUserRequest request) {
		User user = findUserOrThrow(userName);

		if (request.longName() != null) {
			user.setLongName(request.longName());
		}
		// Blank/absent password means "leave it unchanged" - the same rule
		// the legacy BCrypt fix had to retrofit (Design.md §16.8.1, to stop a
		// blank form field from wiping out the existing password on an
		// unrelated edit). Here from the start, not a retrofit.
		if (blankToNull(request.password()) != null) {
			user.setPasswordHash(passwordEncoder.encode(request.password()));
		}
		if (blankToNull(request.primaryRoleName()) != null) {
			RoleAssignment primary = user.getPrimaryRoleAssignment()
					.orElseThrow(() -> new IllegalStateException("User has no primary role assignment: " + userName));
			primary.setRole(Role.valueOf(request.primaryRoleName()));
		}

		return UserResponse.from(user);
	}

	@Transactional
	public void deleteUser(String userName) {
		userRepository.delete(findUserOrThrow(userName));
	}

	/**
	 * The ADMIN-picks-a-user counterpart of {@link MeController#me} (29/08/2026)
	 * - same {@link MeResponse} shape reused as-is rather than a parallel DTO,
	 * since it's genuinely the same snapshot, just for a caller-chosen
	 * {@code userName} instead of the caller themselves.
	 */
	@Transactional(readOnly = true)
	public MeResponse getSettings(String userName) {
		return MeResponse.from(findUserOrThrow(userName));
	}

	/** The ADMIN-picks-a-user counterpart of {@link MeController#update} - see {@link UserSettingsUpdater} for the shared validation. */
	@Transactional
	public MeResponse updateSettings(String userName, UpdateMeRequest request) {
		User user = findUserOrThrow(userName);
		UserSettingsUpdater.apply(user, request);
		return MeResponse.from(user);
	}

	@Transactional(readOnly = true)
	public List<RoleAssignmentResponse> listRoleAssignments(String userName) {
		findUserOrThrow(userName);
		return roleAssignmentRepository.findByUser_UserName(userName).stream().map(RoleAssignmentResponse::from).toList();
	}

	@Transactional
	public RoleAssignmentResponse addRoleAssignment(String userName, CreateRoleAssignmentRequest request) {
		User user = findUserOrThrow(userName);
		if (roleAssignmentRepository.findByUser_UserNameAndGroup_GroupName(userName, request.groupName()).isPresent()) {
			throw new RoleAssignmentAlreadyExistsException(userName, request.groupName());
		}
		Group group = findOrCreateGroup(request.groupName());
		RoleAssignment ra = new RoleAssignment(user, group, Role.valueOf(request.role()), false);
		// `user` is already managed (loaded above) and its roleAssignments
		// collection cascades ALL - adding to the collection is enough for
		// Hibernate's dirty-checking to persist `ra` on flush, no explicit
		// save() needed (and calling both was actually causing a
		// NonUniqueObjectException - the explicit persist() and the
		// cascade-on-flush persist raced on the same not-yet-flushed id).
		user.addRoleAssignment(ra);
		return RoleAssignmentResponse.from(ra);
	}

	@Transactional
	public void removeRoleAssignment(String userName, String groupName) {
		RoleAssignment ra = roleAssignmentRepository.findByUser_UserNameAndGroup_GroupName(userName, groupName)
				.orElseThrow(() -> new RoleAssignmentNotFoundException(userName, groupName));
		if (ra.isPrimary()) {
			throw new IllegalArgumentException("Cannot remove a user's primary role assignment - change it instead, or delete the user");
		}
		// Removing directly via the repository is exactly the trap found in
		// the legacy app's deleteRole (Design.md §16.3): with the owning User
		// already loaded in this persistence context (it was, just above),
		// its in-memory roleAssignments collection still references this
		// row unless removed from the collection too - otherwise
		// orphanRemoval's cascade silently re-inserts it on flush and the
		// delete never actually takes effect. Both sides of the bidirectional
		// association kept in sync on principle, not just because User's
		// happens to be the one already loaded here.
		ra.getUser().getRoleAssignments().remove(ra);
		ra.getGroup().getRoleAssignments().remove(ra);
	}

	private Group findOrCreateGroup(String groupName) {
		return groupRepository.findById(groupName)
				.orElseGet(() -> groupRepository.save(new Group(groupName, "", LocalDate.now())));
	}

	private User findUserOrThrow(String userName) {
		return userRepository.findById(userName).orElseThrow(() -> new UserNotFoundException(userName));
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}
}
