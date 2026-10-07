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
 * Mirrors DirectoryController - replaces the legacy SubDirListSvr/
 * DirIndexSvr/DirDataSvr (Design.md §16.5).
 *
 * No more `authHeader` param anywhere in this file (18/09/2026, session-
 * cookie auth - client.js's own doc comment has the full story) - every
 * caller of every function here dropped it too.
 */
export const directoryTreeApi = {
	listChildren: (path) => apiFetch(`/api/directory-tree?path=${encodeURIComponent(path ?? '')}`),

	byPath: (path) => apiFetch(`/api/directories/by-path?path=${encodeURIComponent(path)}`),

	// Resolves an image's owning sequence (ImageResponse.directoryId) - used
	// where a directory isn't already in hand, e.g. Search results.
	byId: (id) => apiFetch(`/api/directories/${id}`),

	index: (path, groupName) =>
		apiFetch(`/api/directory-index?path=${encodeURIComponent(path)}${groupName ? `&group=${encodeURIComponent(groupName)}` : ''}`, {
			method: 'POST',
		}),

	// Single-path reset/delete used to be called directly from the Gallery/
	// Search sequence-edit popups - removed (explicit ask, 28/08/2026) along
	// with those two buttons, in favor of the one remaining entry point:
	// Admin -> Index management's own bulk table (batchIndex below, called
	// with a single path selected works just as well). If a single-path
	// client call is needed again, it's the same request batchIndex already
	// makes with a one-element paths array.

	updateDetail: (directoryId, request) => apiFetch(`/api/directories/${directoryId}`, { method: 'PATCH', body: request }),

	// `date` ({year, month}, optional - 05/10/2026): also move a year/month/name
	// sequence to another date (DirectoryIndexerService.renameDirectory).
	rename: (directoryId, newName, date = null) =>
		apiFetch(`/api/directories/${directoryId}/rename`, { method: 'POST', body: { newName, year: date?.year ?? null, month: date?.month ?? null } }),

	// Every directory under `path` (root if omitted), flattened - powers the
	// Admin screen's bulk index-management table (legacy's dirIndexPane).
	listAllRecursive: (path) => apiFetch(`/api/directory-tree/all?path=${encodeURIComponent(path ?? '')}`),

	// cmd is one of "index", "reset-index", "delete" - same three commands as
	// legacy's dirIndexPane checkbox columns.
	batchIndex: (cmd, paths) => apiFetch('/api/directory-index/batch', { method: 'POST', body: { cmd, paths } }),

	// Recursively re-indexes `path` and every directory under it with images
	// already on disk - not called by any button any more (01/09/2026: the
	// Admin "Batch Publish" box now calls importApi.stagingImport below
	// instead, legacy's real behavior, which this recursive re-index never
	// actually was - see StagingImportService's own javadoc). The endpoint
	// itself is unchanged and still real - batch-publish-by-year.sh drives
	// it directly for the one-time historical thumbnail backfill - kept
	// here too in case a future admin tool wants it again.
	batchPublish: (path) => apiFetch(`/api/directory-index/batch-publish?path=${encodeURIComponent(path)}`, { method: 'POST' }),
};

/**
 * Legacy's real Batch Publish (01/09/2026) - StagingImportService's own
 * javadoc has the full story. defaultYear/defaultMonth are optional and
 * must be given together or not at all (mirrors the backend's own
 * validation) - only used as the EXIF fallback date when a file has none of
 * its own.
 */
export const importApi = {
	// `useFolderDates` (06/10/2026): scans sorted by hand into
	// yyyy/mm/<sequence> folders - the folder's year/month is the date.
	stagingImport: (path, defaultYear, defaultMonth, groupName, useFolderDates = false) => {
		const params = new URLSearchParams({ path });
		if (useFolderDates) params.set('useFolderDates', 'true');
		if (defaultYear != null && defaultMonth != null) {
			params.set('defaultYear', defaultYear);
			params.set('defaultMonth', defaultMonth);
		}
		if (groupName) params.set('groupName', groupName);
		return apiFetch(`/api/import/staging?${params.toString()}`, { method: 'POST' });
	},
};

/** Mirrors AttributeController - replaces the legacy AttributeTreeSvr/AttributeListSvr (Design.md §16.5). */
export const attributeApi = {
	listAll: () => apiFetch('/api/attributes'),

	listChildren: (parentName) => apiFetch(`/api/attribute-tree${parentName ? `?parent=${encodeURIComponent(parentName)}` : ''}`),

	create: (request) => apiFetch('/api/attributes', { method: 'POST', body: request }),

	update: (id, request) => apiFetch(`/api/attributes/${id}`, { method: 'PATCH', body: request }),

	remove: (id) => apiFetch(`/api/attributes/${id}`, { method: 'DELETE' }),
};

/** Mirrors MeController - replaces the legacy SessionConfigurationSvr (Design.md §16.5). */
export const meApi = {
	get: () => apiFetch('/api/me'),

	update: (request) => apiFetch('/api/me', { method: 'PATCH', body: request }),

	// The caller's own {groupName, role} rows (12/09/2026, sequence group
	// picker) - a WRITER's dropdown is built by filtering this to role ===
	// 'WRITER' client-side; see groupApi.list below for the ADMIN-only full
	// list this deliberately doesn't replace.
	groups: () => apiFetch('/api/me/groups'),
};

/** ADMIN-only full group list (12/09/2026, sequence group picker) - GroupController's own javadoc explains why this stays admin-only rather than open to every role like attributeApi.listAll above. */
export const groupApi = {
	list: () => apiFetch('/api/groups'),
};

/**
 * Replaces the legacy QuerySvr (Design.md §16.5) - paginated, see
 * backend/.../GalleryService.search. `text` (03/09/2026, explicit ask) -
 * free-text filter, matched against the sequence name/sequence description/
 * image description (any one, an OR of the three) and ANDed with every
 * other criterion here - see GalleryService.search's own doc for the exact
 * logic; this is just the one extra query param that carries it.
 */
export const searchApi = {
	search: ({ attributeNames, minRating, fromDate, toDate, text, page = 0, pageSize } = {}) => {
		const params = new URLSearchParams();
		(attributeNames ?? []).forEach((name) => params.append('attribute', name));
		if (minRating != null) params.set('minRating', minRating);
		if (fromDate) params.set('fromDate', fromDate);
		if (toDate) params.set('toDate', toDate);
		if (text) params.set('text', text);
		params.set('page', page);
		if (pageSize != null) params.set('pageSize', pageSize);
		return apiFetch(`/api/images/search?${params.toString()}`);
	},
};
