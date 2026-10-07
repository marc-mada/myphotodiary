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

// A free-text fragment shorter than this doesn't count as a search
// criterion at all (explicit ask, 10/09/2026) - an echo of the backend's
// own real enforcement (GalleryService.search's MIN_TEXT_SEARCH_LENGTH),
// purely so neither screen fires a request, or counts "has criteria", for a
// fragment the backend would ignore anyway.
export const MIN_TEXT_SEARCH_LENGTH = 4;

/**
 * Shared between desktop's SearchScreen and mobile's MobileSearchPage
 * (extracted 10/09/2026, explicit ask: "do not launch a search process when
 * there is no search criteria" - had to mean the same thing on both
 * screens, not be judged independently by two near-identical checks that
 * could quietly drift apart over time).
 *
 * Takes the *already-normalized* criteria shape both screens build for
 * their own `searchApi`/`onSearch` call anyway (`attributeNames`: array,
 * `minRating`: number or null, `fromDate`/`toDate`: date string or null,
 * `text`: string or null) - not raw form state - so callers only need to
 * apply `MIN_TEXT_SEARCH_LENGTH` to `text` once, while building that
 * object, rather than this function re-deriving it from an untrimmed
 * string. A minimum rating of 0 (the lowest real level, not "no filter")
 * still counts - only `null`/`undefined` means "not set".
 */
export function hasSearchCriteria({ attributeNames, minRating, fromDate, toDate, text }) {
	return (attributeNames?.length ?? 0) > 0 || minRating != null || !!fromDate || !!toDate || !!text;
}
