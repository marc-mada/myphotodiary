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

import { apiFetch } from './client';

/**
 * Mirrors UserController exactly (backend/src/main/java/.../user/UserController.java) -
 * one function per endpoint, same shapes as the request/response DTOs.
 *
 * No more `authHeader` param anywhere in this file (18/09/2026, session-
 * cookie auth - client.js's own doc comment has the full story).
 */
export const usersApi = {
	list: () => apiFetch('/api/users'),

	get: (userName) => apiFetch(`/api/users/${encodeURIComponent(userName)}`),

	create: (request) => apiFetch('/api/users', { method: 'POST', body: request }),

	update: (userName, request) => apiFetch(`/api/users/${encodeURIComponent(userName)}`, { method: 'PUT', body: request }),

	remove: (userName) => apiFetch(`/api/users/${encodeURIComponent(userName)}`, { method: 'DELETE' }),

	listRoles: (userName) => apiFetch(`/api/users/${encodeURIComponent(userName)}/roles`),

	addRole: (userName, request) => apiFetch(`/api/users/${encodeURIComponent(userName)}/roles`, { method: 'POST', body: request }),

	removeRole: (userName, groupName) =>
		apiFetch(`/api/users/${encodeURIComponent(userName)}/roles/${encodeURIComponent(groupName)}`, { method: 'DELETE' }),

	// ADMIN-picks-a-user counterpart of api/navigation.js's own meApi
	// (29/08/2026) - same MeResponse/UpdateMeRequest shape, just for a
	// caller-chosen userName instead of the signed-in admin themselves. See
	// UserController's own javadoc for why /api/me still exists separately.
	getSettings: (userName) => apiFetch(`/api/users/${encodeURIComponent(userName)}/settings`),

	updateSettings: (userName, request) => apiFetch(`/api/users/${encodeURIComponent(userName)}/settings`, { method: 'PATCH', body: request }),
};
