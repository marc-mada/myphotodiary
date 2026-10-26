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

import { isMobileDevice } from './mobile/deviceDetection';

/**
 * Desktop presentations (18/10/2026, explicit ask) - the user picks one in
 * User settings (stored per user, `presentation` in /api/me). Each id maps
 * to a `:root[data-presentation=...]` block in app.css that redefines the
 * --mpd-* colour variables; 'dark' (the original look) has no attribute at
 * all, so app.css's defaults apply exactly as before.
 *
 * Desktop only: on mobile this does nothing, the mobile UI keeps its own
 * look. The last choice is also remembered in this browser, so the sign-in
 * screen (shown before the user's own setting is known) already matches.
 */
export const PRESENTATIONS = ['dark', 'bright', 'titanium', 'green-wilderness', 'old-rose', 'deep-blue', 'lagoon'];
export const DEFAULT_PRESENTATION = 'dark';

/** Presentations with light/medium bars, where the banner needs its dark-lettered copy (AppBanner). */
export const LIGHT_PRESENTATIONS = ['bright', 'titanium', 'old-rose', 'lagoon'];

const STORAGE_KEY = 'mpd-presentation';

let current = DEFAULT_PRESENTATION;
const listeners = new Set();

/** The presentation applied right now ('dark' on mobile). */
export function getPresentation() {
	return current;
}

/** Calls `listener` on every change; returns the unsubscribe function (for useSyncExternalStore). */
export function subscribePresentation(listener) {
	listeners.add(listener);
	return () => listeners.delete(listener);
}

export function applyPresentation(id) {
	if (isMobileDevice()) return;
	const presentation = PRESENTATIONS.includes(id) ? id : DEFAULT_PRESENTATION;
	if (presentation !== current) {
		current = presentation;
		listeners.forEach((listener) => listener());
	}
	if (presentation === DEFAULT_PRESENTATION) {
		delete document.documentElement.dataset.presentation;
	} else {
		document.documentElement.dataset.presentation = presentation;
	}
	try {
		localStorage.setItem(STORAGE_KEY, presentation);
	} catch {
		// Private window / blocked storage: just not remembered for next time.
	}
}

/** At start-up: the presentation last used in this browser, if any. */
export function applyRememberedPresentation() {
	let remembered = null;
	try {
		remembered = localStorage.getItem(STORAGE_KEY);
	} catch {
		// see applyPresentation
	}
	if (remembered) applyPresentation(remembered);
}
