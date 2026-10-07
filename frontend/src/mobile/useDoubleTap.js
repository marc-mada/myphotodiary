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

import { useRef } from 'react';

// Two taps this close together (in time) count as one double-tap
// (09/09/2026). No particular legacy or platform convention to match - this
// is a genuinely new gesture, on both the screens that use it - 300ms is
// just a comfortable, unhurried window for two deliberate taps without also
// catching two unrelated, separately-intended taps as a false double-tap.
const DOUBLE_TAP_THRESHOLD_MS = 300;

/**
 * Shared double-tap timing, extracted (09/09/2026) once MobileMosaicView
 * needed the exact same "toggle the header/footer chrome" gesture
 * MobileImageViewer already had - a single tap means something different,
 * and unambiguous, on each of those two screens (nothing on the
 * single-image screen; select a thumbnail on the mosaic screen), but
 * *double*-tap means the same thing on both (reveal/hide the corner-button
 * chrome), so the timing logic belongs in one place rather than two
 * near-identical copies of the same timestamp comparison.
 *
 * Returns a single `registerTap()` function - call it once a caller has
 * already decided a touch sequence was a plain tap (not a swipe, not part
 * of a pinch); it holds a timestamp across calls and fires `onDoubleTap`
 * only when the current call lands within `DOUBLE_TAP_THRESHOLD_MS` of the
 * previous one, consuming both so a third tap starts counting fresh rather
 * than immediately re-firing on every tap after the first pair.
 */
export function useDoubleTap(onDoubleTap) {
	const lastTapTime = useRef(0);

	return function registerTap() {
		const now = Date.now();
		if (now - lastTapTime.current <= DOUBLE_TAP_THRESHOLD_MS) {
			lastTapTime.current = 0;
			onDoubleTap?.();
		} else {
			lastTapTime.current = now;
		}
	};
}
