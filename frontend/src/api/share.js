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
 * Mirrors ShareController (02/09/2026) - external, unauthenticated sharing
 * of a single image or a whole sequence. Two halves, same split as the
 * backend controller itself:
 *
 * - `getImageShareToken`/`getSequenceShareToken` go through `apiFetch`
 *   (normal session-cookie-protected endpoints, an authenticated caller
 *   only - GalleryScreen's own copy-link buttons).
 * - `getPublicImage`/`getPublicSequence` are plain `fetch()`, no cookie/
 *   credentials at all - the whole point of a share link is working for
 *   someone with no account, and the token embedded in the URL (not any
 *   auth of the caller's own) is what the backend actually validates
 *   (ShareController's own doc). Used by ShareScreen/MobileShareScreen,
 *   which never go through AuthContext/AuthProvider in the first place.
 */
export const shareApi = {
	getImageShareToken: (imageId) => apiFetch(`/api/images/${imageId}/share-token`),

	getSequenceShareToken: (directoryId) => apiFetch(`/api/directories/${directoryId}/share-token`),

	getPublicImage: (imageId, token) => publicFetch(`/api/public/images/${imageId}?token=${encodeURIComponent(token)}`),

	getPublicSequence: (directoryId, token) => publicFetch(`/api/public/sequences/${directoryId}?token=${encodeURIComponent(token)}`),

	// Shared search results (04/10/2026) - `shareSearch` freezes the result
	// of these criteria on the server ({id, token, matchCount, sharedCount,
	// truncated, maxShareSize}, see SharedSearchService); the public view
	// lists the pictures still available, each with its sequenceDescription.
	shareSearch: ({ attributeNames, minRating, fromDate, toDate, text }) =>
		apiFetch('/api/search-shares', { method: 'POST', body: { attributeNames, minRating, fromDate, toDate, text } }),

	getPublicSearchShare: (sharedSearchId, token) => publicFetch(`/api/public/search-shares/${sharedSearchId}?token=${encodeURIComponent(token)}`),
};

async function publicFetch(path) {
	const response = await fetch(path);
	const text = await response.text();
	const data = text ? JSON.parse(text) : null;
	if (!response.ok) {
		throw new Error(data?.error ?? `Request failed: ${response.status}`);
	}
	return data;
}
