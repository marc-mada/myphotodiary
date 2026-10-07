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

import { useCallback, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from './api/client';

/**
 * How long a temporary, self-clearing UI message stays visible before
 * disappearing on its own - one shared value for every such message in
 * this app, not a per-screen choice. First established by
 * MobileSearchPage's own "fill in search criteria" message (its own
 * constant now imports this one instead of redefining it - see that
 * file's own comment) and reused here for the "Forbidden access" message
 * below (13/09/2026, explicit ask - "same duration for all such error
 * messages already used in the UI") - a future change to "how long" only
 * has this one place to edit.
 */
export const TEMPORARY_MESSAGE_TIMEOUT_MS = 3000;

/**
 * Wraps a screen's own `setError` state setter so a 403 (an `ApiError`
 * thrown by `apiFetch` - GalleryAuthorizationService's per-group RBAC,
 * both the read-side BROWSE checks added 12/09/2026 and the older
 * write-action ones, plus every ADMIN-only `@PreAuthorize` endpoint) shows
 * a translated, temporary "Forbidden access" message instead of raw
 * backend text - explicit ask, 13/09/2026. Every other error keeps this
 * app's existing behavior (set once, stays until something else replaces
 * or clears it) - this only special-cases 403, nothing else.
 *
 * Worth noting explicitly: a plain, uncaught `AccessDeniedException` in
 * this backend is never actually handled by `GalleryApiExceptionHandler`
 * (no `@ExceptionHandler` for it there) - it falls through to Spring
 * Security's own default `AccessDeniedHandler`, whose JSON body's own
 * `error` field is already just the generic English word "Forbidden", not
 * the exception's real message (confirmed live against the real dev
 * backend before writing this, not assumed) - so this was never a
 * risk of leaking an internal group name or similar detail to the
 * screen; the raw text was already generic, just untranslated and
 * persistent until this fix.
 *
 * A ref (not state) tracks the pending clear-timeout, the same pattern
 * MobileSearchPage's own criteria-message timeout already used, so a
 * second 403 shown while the first message is still fading doesn't leave
 * two timers racing to clear the same state.
 */
export function useForbiddenAwareError(setError) {
	const { t } = useTranslation();
	const timeoutRef = useRef(null);

	useEffect(
		() => () => {
			if (timeoutRef.current != null) window.clearTimeout(timeoutRef.current);
		},
		[],
	);

	return useCallback(
		(err) => {
			if (err instanceof ApiError && err.status === 403) {
				if (timeoutRef.current != null) window.clearTimeout(timeoutRef.current);
				setError(t('common.forbidden'));
				timeoutRef.current = window.setTimeout(() => setError(null), TEMPORARY_MESSAGE_TIMEOUT_MS);
				return;
			}
			setError(err.message);
		},
		[setError, t],
	);
}
