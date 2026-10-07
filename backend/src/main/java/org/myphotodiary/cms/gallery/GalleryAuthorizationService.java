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

import java.util.Set;
import java.util.stream.Collectors;

import org.myphotodiary.cms.user.Action;
import org.myphotodiary.cms.user.Group;
import org.myphotodiary.cms.user.RoleAssignment;
import org.myphotodiary.cms.user.RoleAssignmentRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Per-group RBAC for gallery/directory write actions - ports the legacy
 * {@code AccessController.checkAuthorization(Directory/path, Action)}
 * (Design.md §16.4, "role assigned by group"), which this backend never had:
 * {@code GalleryController}/{@code ImportController}/{@code DirectoryController}
 * only ever required plain authentication (see {@code SecurityConfig}), so
 * any authenticated role - including LOWER - could upload, edit, or delete.
 * Found live (28/08/2026): a LOWER-role test account ("dummy2") successfully
 * uploaded a picture, which the legacy {@link org.myphotodiary.cms.user.Role}
 * matrix (ported verbatim to {@link org.myphotodiary.cms.user.Role#isPermitted})
 * would never have permitted (LOWER is browse-only).
 *
 * A user must hold an explicit {@link RoleAssignment} for the resource's own
 * group - unlike the legacy fix's {@code checkUnindexedPathAuthorization},
 * there is no fallback to a different group's role here, because every
 * {@code Directory}/{@code Image} in this schema already always has a real
 * {@code Group} (defaulted to "public" at creation - see
 * {@code GalleryService}/{@code ImportService}/{@code DirectoryIndexerService}'s
 * own {@code findOrCreateGroup}), so there's no "not persisted yet" case to
 * special-case the way legacy's filesystem-path-first model needed to.
 *
 * <b>Global ADMIN bypass (12/09/2026, added while implementing the sequence
 * group picker and the {@link Action#BROWSE} enforcement below)</b> - a real
 * latent bug found before either of those could work correctly with more
 * than one group: this class used to require an explicit per-group
 * {@link RoleAssignment} row even for a global admin, because
 * {@code ROLE_ADMIN} (derived from a user's *primary* role assignment only -
 * see {@code DomainUserDetailsService}) was never consulted here at all. The
 * bootstrap admin only ever gets a row in the "public" group
 * ({@code BootstrapAdminInitializer}) - so as soon as a second group existed,
 * an admin with no explicit row there would have been denied every action on
 * it, contradicting {@code Role.ADMIN.isPermitted() == true} ("Admin has all
 * permissions on a resource") and the group picker's own "ADMIN can pick up
 * any group" requirement. {@link #isGlobalAdmin} short-circuits both
 * {@link #checkAuthorization} and the new read-side helpers below, exactly
 * mirroring that unconditional-ADMIN semantics one level up (per-account,
 * not per-group-row) rather than duplicating an admin special case at every
 * call site.
 *
 * <b>{@link Action#BROWSE} enforcement (12/09/2026, explicit ask)</b> -
 * previously never checked at all here (see the old version of this javadoc,
 * preserved in git history): every browse/read endpoint stayed open to any
 * authenticated user. Now enforced for the endpoints that actually expose a
 * sequence's own content (its description/geolocation, its image list, and
 * the image bytes themselves) via {@link #checkAuthorization} with
 * {@link Action#BROWSE}, or - for endpoints that must *filter* a
 * heterogeneous list spanning several groups (search) rather than reject a
 * single request outright - {@link #readableGroupNames}. Directory-tree
 * *navigation* ({@code DirectoryController.listSubdirectories} et al.) is a
 * deliberate, explicit exception, not an oversight: unlike legacy's own
 * {@code SubDirListSvr} (which filtered unauthorized child names out of the
 * tree too), this app's tree stays fully open regardless of rights - an
 * explicit ask, so a user can always see a sequence's *name* and structure
 * even where they can't see its contents.
 */
@Service
public class GalleryAuthorizationService {

	private final RoleAssignmentRepository roleAssignmentRepository;

	public GalleryAuthorizationService(RoleAssignmentRepository roleAssignmentRepository) {
		this.roleAssignmentRepository = roleAssignmentRepository;
	}

	public void checkAuthorization(Authentication authentication, Group group, Action action) {
		checkAuthorization(authentication, group.getGroupName(), action);
	}

	public void checkAuthorization(Authentication authentication, String groupName, Action action) {
		if (isGlobalAdmin(authentication)) {
			return;
		}
		String userName = authentication.getName();
		RoleAssignment roleAssignment = roleAssignmentRepository.findByUser_UserNameAndGroup_GroupName(userName, groupName)
				.orElseThrow(() -> new AccessDeniedException(
						"User " + userName + " has no role assignment in group " + groupName + ", required for " + action));
		if (!roleAssignment.getRole().isPermitted(action)) {
			throw new AccessDeniedException(
					"User " + userName + " (role " + roleAssignment.getRole() + " in group " + groupName + ") is not permitted to " + action);
		}
	}

	/**
	 * The set of group names the caller may browse - or {@code null} to mean
	 * "every group" (a global admin), letting a caller (search) skip
	 * filtering entirely rather than test membership in a set that would
	 * certainly contain everything anyway. Every non-admin role permits
	 * {@link Action#BROWSE} once a {@link RoleAssignment} row exists at all
	 * ({@link org.myphotodiary.cms.user.Role#isPermitted} - WRITER/READER/LOWER
	 * all include it), so this is really "every group this user has *any*
	 * row in" - the {@code isPermitted} check is kept anyway, defensively,
	 * rather than assumed, in case that ever stops being true for some role.
	 * One query total (not one per candidate image/directory), unlike
	 * repeatedly calling {@link #checkAuthorization} in a loop would be.
	 */
	public Set<String> readableGroupNames(Authentication authentication) {
		if (isGlobalAdmin(authentication)) {
			return null;
		}
		return roleAssignmentRepository.findByUser_UserName(authentication.getName()).stream()
				.filter(ra -> ra.getRole().isPermitted(Action.BROWSE))
				.map(ra -> ra.getGroup().getGroupName())
				.collect(Collectors.toSet());
	}

	private boolean isGlobalAdmin(Authentication authentication) {
		return authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
	}
}
