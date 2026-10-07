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

package org.myphotodiary.cms.user.dto;

import org.myphotodiary.cms.user.User;

public record MeResponse(
		String userName,
		String longName,
		int maxQueryLength,
		int slideShowInterval,
		double defaultLatitude,
		double defaultLongitude,
		int postItFadeDelay,
		// Added 28/08/2026 for the frontend's own Admin-tab access check
		// (App.jsx) - reuses the exact same primary-role resolution
		// UserResponse.from already uses, rather than a second, divergent
		// notion of "the" role. Deliberately on /api/me (self-service, any
		// authenticated role - see this class's own controller javadoc) and
		// not gated behind UserController's ADMIN-only endpoints: a non-admin
		// user needs to learn their own role is not ADMIN precisely so the
		// frontend can hide the Admin screen from them, which would be
		// circular if that answer only came from an ADMIN-only endpoint.
		String primaryRoleName) {
	public static MeResponse from(User user) {
		String primaryRole = user.getPrimaryRoleAssignment().map(ra -> ra.getRole().name()).orElse(null);
		return new MeResponse(
				user.getUserName(),
				user.getLongName(),
				user.getMaxQueryLength(),
				user.getSlideShowInterval(),
				user.getDefaultLatitude(),
				user.getDefaultLongitude(),
				user.getPostItFadeDelay(),
				primaryRole);
	}
}
