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
 * `navigator.clipboard` (02/09/2026, desktop's image-link/sequence-link
 * buttons - the first caller) only exists in a "secure context" - HTTPS, or
 * `localhost` specifically - real production (HTTPS behind Apache, Operation
 * Procedures.md) is fine, but a plain-HTTP LAN dev server reached by its IP
 * address (e.g. `http://192.168.x.x:5183`, this project's own documented way
 * to test the mobile presentation on a real handset) is not:
 * `navigator.clipboard` is simply `undefined` there, not a rejected promise -
 * confirmed live (a real click threw "Cannot read properties of undefined
 * (reading 'writeText')" before this fix, not a caught/handled error).
 * `document.execCommand('copy')` is deprecated but has no such secure-context
 * restriction, so it still works as a fallback in exactly the contexts the
 * modern API doesn't.
 *
 * Extracted out of GalleryScreen.jsx (25/09/2026) once MobileApp's own share
 * feature needed the exact same fallback logic as its own last resort (after
 * the Web Share API, its own preferred option, and before that even exists
 * to check) - shared rather than duplicated.
 */
export async function copyTextToClipboard(text) {
	if (navigator.clipboard?.writeText) {
		await navigator.clipboard.writeText(text);
		return;
	}
	const textarea = document.createElement('textarea');
	textarea.value = text;
	// Kept in the document flow (not display:none, which some browsers
	// exclude from selection) but visually and interactively invisible.
	textarea.style.position = 'fixed';
	textarea.style.top = '0';
	textarea.style.left = '0';
	textarea.style.opacity = '0';
	document.body.appendChild(textarea);
	textarea.focus();
	textarea.select();
	try {
		if (!document.execCommand('copy')) {
			throw new Error('Copy command was not successful');
		}
	} finally {
		document.body.removeChild(textarea);
	}
}
