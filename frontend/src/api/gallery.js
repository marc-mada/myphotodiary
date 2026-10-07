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

import { apiFetch, ApiError, getCsrfHeader } from './client';

/**
 * Shared by every "preview" endpoint below (previewCrop/previewRotate/
 * previewTransform, 25/09/2026 - Crop/Rotate brought onto the same preview/
 * Save/Cancel workflow Transform already had) - doesn't go through apiFetch
 * (same reason uploadImage above doesn't): the response body is real image
 * bytes, not JSON, so this does its own fetch + blob() rather than
 * apiFetch's built-in JSON parsing. `body` is optional (previewRotate has
 * none - a plain POST, same as its own commit endpoint).
 */
async function fetchPreview(url, body) {
	const response = await fetch(url, {
		method: 'POST',
		credentials: 'same-origin',
		headers: body ? { 'Content-Type': 'application/json', ...getCsrfHeader() } : getCsrfHeader(),
		body: body ? JSON.stringify(body) : undefined,
	});
	if (!response.ok) {
		let message = `Request failed: ${response.status}`;
		try {
			const data = await response.json();
			if (data?.error) message = data.error;
		} catch {
			// Best effort - an error body that isn't JSON (unlikely) just
			// falls back to the generic message above.
		}
		throw new ApiError(response.status, message);
	}
	return response.blob();
}

/**
 * Mirrors GalleryController (backend/src/main/java/.../gallery/GalleryController.java) -
 * replaces the legacy ImportSvr/ImageDataSvr/ImgListSvr/ThuListSvr
 * (Design.md §16.5). Upload doesn't go through apiFetch: that helper always
 * JSON-encodes the body, but file upload needs multipart/form-data (the
 * browser sets the boundary itself when given a FormData body and no
 * explicit Content-Type - setting one manually here would omit the boundary
 * and break the request).
 *
 * No more `authHeader` param anywhere in this file (18/09/2026, session-
 * cookie auth - client.js's own doc comment has the full story) -
 * `uploadImage`'s own raw `fetch` still needs `credentials`/the CSRF header
 * by hand, same as `apiFetch` does internally, since it doesn't go through
 * that helper.
 */
export const galleryApi = {
	listDirectories: () => apiFetch('/api/directories'),

	createDirectory: (request) => apiFetch('/api/directories', { method: 'POST', body: request }),

	listImages: (directoryId) => apiFetch(`/api/directories/${directoryId}/images`),

	async uploadImage(directoryId, file) {
		const form = new FormData();
		form.append('file', file);
		const response = await fetch(`/api/directories/${directoryId}/images`, {
			method: 'POST',
			headers: getCsrfHeader(),
			credentials: 'same-origin',
			body: form,
		});
		const text = await response.text();
		const data = text ? JSON.parse(text) : null;
		if (!response.ok) {
			throw new ApiError(response.status, data?.error ?? `Request failed: ${response.status}`);
		}
		return data;
	},

	updateImage: (imageId, request) => apiFetch(`/api/images/${imageId}`, { method: 'PATCH', body: request }),

	removeImage: (imageId) => apiFetch(`/api/images/${imageId}`, { method: 'DELETE' }),

	// 90-degree clockwise rotation (02/09/2026) - no request body, no
	// response body (GalleryController.rotateImage's own doc: nothing
	// persisted changes for the caller to see back) - the caller
	// cache-busts its own already-known image URLs to see the rotated
	// bytes, see GalleryScreen.handleRotate.
	rotateImage: (imageId, quarterTurns = 1) =>
		apiFetch(`/api/images/${imageId}/rotate?quarterTurns=${quarterTurns}`, { method: 'POST' }),

	// "Rotate right" - a read-only preview of the 90-degree rotation
	// (25/09/2026, GalleryController.previewRotate's own doc: nothing is
	// written to disk, only rendered and streamed back) - see fetchPreview
	// above.
	// quarterTurns (1..3, 26/09/2026): consecutive clicks accumulated by the
	// editor popup, previewed/committed as one rotation.
	previewRotate: (imageId, quarterTurns = 1) =>
		fetchPreview(`/api/images/${imageId}/rotate/preview?quarterTurns=${quarterTurns}`),

	// Crop, in the true original's own pixel coordinates - {x, y, width,
	// height} (25/09/2026, rudimentary image editor's "Crop" half). Same
	// no-response-body shape as rotateImage - see GalleryController.cropImage's
	// own doc; the caller cache-busts its own already-known image URLs the
	// same way.
	cropImage: (imageId, request) => apiFetch(`/api/images/${imageId}/crop`, { method: 'POST', body: request }),

	// "Crop" - a read-only preview of the crop rectangle (25/09/2026,
	// GalleryController.previewCrop's own doc: nothing is written to disk) -
	// see fetchPreview above.
	previewCrop: (imageId, request) => fetchPreview(`/api/images/${imageId}/crop/preview`, request),

	// "Transform" - a read-only preview of the perspective warp
	// (GalleryController.previewTransform's own doc: nothing is written to
	// disk, only rendered and streamed back) - see fetchPreview above.
	previewTransform: (imageId, request) => fetchPreview(`/api/images/${imageId}/transform/preview`, request),

	// "Save" - commits the exact warp previewTransform would have
	// rendered (GalleryController.transformImage's own doc). Same
	// no-response-body shape as cropImage/rotateImage above.
	transformImage: (imageId, request) => apiFetch(`/api/images/${imageId}/transform`, { method: 'POST', body: request }),
};
