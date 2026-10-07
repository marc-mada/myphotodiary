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
 * Client-side echo of the backend's SequenceNames rule (02/10/2026) - a
 * sequence name is one folder name: not empty, no "/" or "\", not starting
 * with "." (covers "." and "..", and hidden names like ".thumbnails").
 * The server enforces it regardless; this only gives an immediate,
 * translated message in the Rename and Publish forms instead of an English
 * server error. Expects the already-trimmed name; slashes at both ends are
 * stripped first, as the server does for a Publish sequence name.
 */
export function isValidSequenceName(name) {
	const trimmed = name.trim().replace(/^\/+|\/+$/g, '');
	return trimmed.length > 0 && !/[\/\\]/.test(trimmed) && !trimmed.startsWith('.');
}
