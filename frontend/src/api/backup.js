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
 * Mirrors BackupController (18/09/2026) - ADMIN-only end to end, same as
 * usersApi. `triggerDb` only triggers the DB half - the image-tree backup
 * runs on its own systemd timer (backup-images.sh), never from a web
 * request (see BackupController's own javadoc for why there's no matching
 * endpoint for it, and no restore endpoint at all).
 */
export const backupApi = {
	status: () => apiFetch('/api/admin/backups'),

	triggerDb: () => apiFetch('/api/admin/backups/db', { method: 'POST' }),
};
