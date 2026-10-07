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

/**
 * Thin fetch wrapper - the React equivalent of the legacy idxdata.js data
 * layer (Design.md decision #9): one shared place that knows how to talk to
 * the REST API, kept separate from any presentation component so it can be
 * reused by both a Desktop and a Mobile tree on screens where that applies.
 * Admin has no mobile variant (Design.md decision #9), but this file is written the
 * same way regardless, for consistency with screens that will need it.
 *
 * Session-cookie auth (18/09/2026, replaces HTTP Basic - see backend
 * SecurityConfig's own javadoc for the full rationale). No more
 * `authHeader` param anywhere in this file or its callers - the browser
 * attaches the session cookie automatically on every same-origin request
 * (`credentials: 'same-origin'`, explicit rather than relying on the
 * fetch spec's own same-origin default, so this doesn't silently change if
 * that default ever does). A mutating request also needs the CSRF header
 * Spring Security now requires (`getCsrfCookie()`/`X-XSRF-TOKEN` below) -
 * GET/HEAD are exempt, matching Spring's own default (safe methods aren't
 * CSRF-protected in the first place, so there's nothing to attach for
 * those).
 */
export class ApiError extends Error {
	constructor(status, message) {
		super(message);
		this.status = status;
	}
}

const SAFE_METHODS = new Set(['GET', 'HEAD']);

function getCsrfCookie() {
	const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
	return match ? decodeURIComponent(match[1]) : null;
}

/**
 * Exported for the handful of callers that need a mutating raw `fetch()`
 * of their own instead of going through `apiFetch` below (multipart file
 * uploads, which can't be JSON-encoded) - `{'X-XSRF-TOKEN': token}` if a
 * token cookie exists, `{}` otherwise (mirrors `apiFetch`'s own
 * best-effort behavior: never throws just because the cookie isn't there
 * yet, e.g. a request that races the very first CSRF-cookie-issuing
 * response).
 */
export function getCsrfHeader() {
	const csrfToken = getCsrfCookie();
	return csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {};
}

export async function apiFetch(path, { method = 'GET', body } = {}) {
	const headers = { Accept: 'application/json' };
	if (body !== undefined) headers['Content-Type'] = 'application/json';
	if (!SAFE_METHODS.has(method)) {
		Object.assign(headers, getCsrfHeader());
	}

	const response = await fetch(path, {
		method,
		headers,
		credentials: 'same-origin',
		body: body !== undefined ? JSON.stringify(body) : undefined,
	});

	if (response.status === 204) {
		return null;
	}

	const text = await response.text();
	const data = text ? JSON.parse(text) : null;

	if (!response.ok) {
		const message = data?.error ?? `Request failed: ${response.status}`;
		throw new ApiError(response.status, message);
	}

	return data;
}
