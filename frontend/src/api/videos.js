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
 * Mirrors VideoController (28/08/2026, video support) - just the token
 * issuance; the actual streaming URL it returns is fetched directly by
 * `<video src>` in AuthVideo.jsx, not through apiFetch (that endpoint isn't
 * even behind session auth - see VideoController/SecurityConfig's own
 * comments on why streaming needs its own token-based auth instead).
 */
export const videoApi = {
	getToken: (imageId) => apiFetch(`/api/videos/${imageId}/token`),
};
