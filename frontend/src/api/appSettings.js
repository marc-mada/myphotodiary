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
 * Mirrors AppSettingsController - app-wide (not per-user) settings, first
 * of their kind in this codebase (28/08/2026, video support - see
 * AppSettings.java's own comment for why maxVideoSizeBytes lives here
 * rather than as a new field on the per-user meApi). GET is open to any
 * authenticated user (the upload form needs to know/validate against the
 * current cap); PATCH is ADMIN-only, enforced server-side - this client
 * doesn't gate the call itself, ConfigPanel's own UI just doesn't render
 * the field for a non-admin (this screen is ADMIN-only end to end already).
 */
export const appSettingsApi = {
	get: () => apiFetch('/api/app-settings'),

	update: (request) => apiFetch('/api/app-settings', { method: 'PATCH', body: request }),

	// {version} of the backend actually running on the server (VersionController,
	// 26/09/2026) - not the frontend bundle's own package.json version.
	version: () => apiFetch('/api/version'),
};
