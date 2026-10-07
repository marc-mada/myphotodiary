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

/**
 * Same 4 roles as the legacy app (Design.md §16.4), carried over as-is.
 *
 * {@link #isPermitted} started as a verbatim port of the legacy
 * {@code org.myphotodiary.model.Role#isPermitted(Role, Action)} matrix, for
 * the {@link Action} subset this backend actually checks (gallery/directory
 * write actions - see {@link Action}'s own javadoc for what's covered
 * elsewhere instead). Found to be missing entirely (28/08/2026) -
 * GalleryController et al. only ever required plain authentication, so any
 * authenticated role, including LOWER, could upload/edit/delete - see
 * {@link org.myphotodiary.cms.gallery.GalleryAuthorizationService} for the
 * per-group enforcement built around this matrix.
 *
 * One deliberate deviation from strict legacy fidelity, decided the same
 * day once the strict port's own consequence was noticed live: legacy's
 * WRITER can edit an existing sequence but not create a brand-new one
 * (CREATE_SEQUENCE was ADMIN-only) - faithfully ported at first, then found
 * to surprisingly block a WRITER account from publishing to a
 * never-before-used location (root included, if nothing had been published
 * there yet) - "Writer can't start anything new" doesn't match what most
 * people expect that role name to mean. WRITER now also gets
 * CREATE_SEQUENCE; DELETE_SEQUENCE/DELETE_IMAGE stay ADMIN-only (not asked
 * for, and deletion is the more consequential action to keep restricted).
 */
public enum Role {
	ADMIN, WRITER, READER, LOWER;

	public boolean isPermitted(Action action) {
		return switch (this) {
			// Admin has all permissions on a resource.
			case ADMIN -> true;
			// Writer can create and edit sequences/images, and browse, but
			// not delete a sequence or an image - deletion stays ADMIN-only.
			case WRITER -> action == Action.CREATE_SEQUENCE || action == Action.EDIT_SEQUENCE || action == Action.EDIT_IMAGE
					|| action == Action.BROWSE;
			// Reader and Lower are both browse-only for gallery actions in
			// the legacy matrix (their extra changePwd/changeConfiguration
			// permissions are handled separately - see Action's javadoc).
			case READER, LOWER -> action == Action.BROWSE;
		};
	}
}
