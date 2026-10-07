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

// Same rough scale as MobileImageViewer's own SWIPE_THRESHOLD_PX (30px) for
// a one-finger swipe, a bit larger here - two independently-moving fingers
// naturally drift apart/together a little before a deliberate pinch is
// obvious, so a smaller threshold would fire on ordinary two-finger
// fidgeting (e.g. briefly resting a second finger on the screen while
// scrolling with the first).
const PINCH_THRESHOLD_PX = 40;

function distanceBetween(touches) {
	const [a, b] = touches;
	return Math.hypot(b.clientX - a.clientX, b.clientY - a.clientY);
}

/**
 * Bare two-finger pinch detection (09/09/2026, mosaic screen - explicit
 * ask), shared by MobileImageViewer (pinch-out at ratio 1 -> mosaic screen)
 * and MobileMosaicView (pinch-in -> single-image screen). "Pinch in"
 * (fingers moving apart - the same gesture that zooms *in*) vs. "pinch out"
 * (fingers moving together - zooms *out*), decided once the gesture is
 * actually over (second finger lifts), the same way MobileImageViewer's own
 * swipe is only resolved at touchend rather than streamed on every
 * touchmove.
 *
 * Unlike a one-finger swipe, though, the *distance* between two fingers only
 * has a meaningful start/end once both are down and then one lifts -
 * touchend's own `changedTouches` lists only the finger(s) that just lifted,
 * never the one still down, so there's no way to read "the distance right
 * before release" from touchend alone. This tracks the last-seen distance on
 * every touchmove instead, and only turns it into a decision once
 * `onTouchEnd` sees fewer than two touches remaining.
 *
 * Returns plain handlers to compose into whatever touchstart/touchmove/
 * touchend handling a caller already has on the same element (a swipe, a
 * tap, paging between mosaic pages) - not a self-contained
 * `useEffect`+`addEventListener` the way `useLandscapeFullscreen` is, since
 * this has to share the exact same DOM element and touch sequence as that
 * other handling, not listen globally. Each of the three returned functions
 * reports whether the current touch sequence is a pinch (or just became
 * one) - `true` means "this event belongs to a pinch gesture, don't also
 * treat it as a swipe/tap", mirroring how a one-finger swipe already
 * suppresses being treated as a tap once it crosses its own threshold.
 * Deliberately never calls `preventDefault()` anywhere: the whole point is
 * to observe a pinch *alongside* the browser's own native pinch-to-zoom
 * (index.html's viewport meta), not to take it over.
 */
export function usePinchGesture({ onPinchIn, onPinchOut } = {}) {
	const startDistance = useRef(null);
	const lastDistance = useRef(null);

	function handleTouchStart(e) {
		if (e.touches.length === 2) {
			startDistance.current = distanceBetween(e.touches);
			lastDistance.current = startDistance.current;
			return true;
		}
		return false;
	}

	function handleTouchMove(e) {
		if (startDistance.current != null && e.touches.length === 2) {
			lastDistance.current = distanceBetween(e.touches);
			return true;
		}
		return startDistance.current != null;
	}

	function handleTouchEnd(e) {
		if (startDistance.current == null) return false;
		if (e.touches.length >= 2) return true; // one of >2 fingers lifted - gesture isn't over yet
		const delta = lastDistance.current - startDistance.current;
		startDistance.current = null;
		lastDistance.current = null;
		if (delta >= PINCH_THRESHOLD_PX) onPinchIn?.();
		else if (delta <= -PINCH_THRESHOLD_PX) onPinchOut?.();
		return true;
	}

	return { onTouchStart: handleTouchStart, onTouchMove: handleTouchMove, onTouchEnd: handleTouchEnd };
}
