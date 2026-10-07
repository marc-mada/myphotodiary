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

import { useEffect, useState } from 'react';
import { isIOSDevice, isMobileDevice } from './deviceDetection';

/**
 * Landscape auto-fullscreen (mobile only, 03/09/2026, explicit ask; all
 * iOS - iPad *and* iPhone - abandoned entirely, see below): in portrait
 * the phone browser's own address/tool bars are a small enough
 * fraction of a tall viewport to live with (unchanged) - in landscape
 * they eat a much bigger share of an already-short viewport, visibly
 * shrinking the picture. No CSS layout change can reclaim space the
 * *browser itself* is occupying outside the page - only the Fullscreen
 * API can, so that's the actual mechanism here, not a bigger version of
 * the `100dvh` trick already used elsewhere in this file for the
 * portrait address-bar case.
 *
 * Can't be truly automatic, on principle, not because of a gap in this
 * app: every browser requires a real user gesture (a tap/click) to grant
 * `requestFullscreen()` - an orientation-change event alone never counts,
 * by design, in any browser (a page silently taking over the whole
 * screen the instant you tilt your phone would be a genuine abuse
 * vector). So this arms itself in landscape and fires on the very next
 * tap anywhere - not scoped to the picture specifically, e.g. tapping a
 * button or a text field also counts - simplest rule that still delivers
 * "rotate, tap once, you're fullscreen" without adding any dedicated
 * button/gesture of its own. `exitFullscreen()` needs no such gesture, so
 * rotating back to portrait can leave fullscreen immediately and
 * automatically, no tap required.
 *
 * All iOS excluded entirely (`isIOSDevice()`, iPad *and* iPhone, Chrome
 * and Safari alike - real device testing on iPad, not assumed; iPhone
 * abandoned the same day on the strength of running the identical WebKit
 * engine under the identical App Store constraint, not separately
 * retested): the Fullscreen API itself *is* available on iPad
 * (correcting an earlier assumption here that iOS never supports it) but
 * behaved unreliably enough to be worse than doing nothing - an
 * orientation change back to portrait didn't reliably auto-exit
 * fullscreen (Safari's own `matchMedia`/orientation-change events are a
 * known-flaky area of WebKit), the viewport visibly shifted/wasn't
 * stable while fullscreen, and the browser's own exit-fullscreen icon
 * landed exactly on top of this app's own Menu button, with no way for
 * page code to know where the browser will draw its own chrome to avoid
 * it. None of this reproduced on Android (confirmed on a real Galaxy and
 * Xiaomi, Chrome and Firefox) - narrowed to iOS rather than disabled
 * everywhere. `requestFullscreen` simply not existing at all (some other,
 * untested mobile browser) is still checked for separately and treated
 * the same way - nothing to do, not an error. The landscape CSS layout
 * (app.css's own `@media (orientation: landscape)` block) still applies
 * on iOS regardless - it's plain layout, none of the instability above
 * applies to it, so iOS still gets that half of the improvement, just
 * never the fullscreen half.
 *
 * Mounted once, at the very top of the component tree (App.jsx, above
 * the Mobile/Desktop branch and above the share-link route's own early
 * return) so it covers every mobile screen - the ordinary app, the
 * sign-in page, and the public share viewer alike - not just MobileApp's
 * own home screen. Desktop is entirely unaffected: guarded by
 * `isMobileDevice()` internally rather than requiring every call site to
 * remember to skip it, consistent with how `deviceDetection.js` itself is
 * already used everywhere else in this project.
 *
 * Returns whether fullscreen is currently active, in case a caller ever
 * wants to reflect that in its own UI (e.g. hide a now-redundant control) -
 * unused today, kept for that reason rather than a bare side-effect hook.
 */
export function useLandscapeFullscreen() {
	const [isFullscreen, setIsFullscreen] = useState(() => (typeof document !== 'undefined' ? !!document.fullscreenElement : false));

	useEffect(() => {
		if (!isMobileDevice()) return undefined;
		if (isIOSDevice()) return undefined;
		if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;

		const landscapeQuery = window.matchMedia('(orientation: landscape)');
		// Guards against a second concurrent requestFullscreen() call before
		// the first one has resolved (e.g. a fast double-tap) - the browser
		// would otherwise reject the second with an error, harmless since
		// it's caught below, but this avoids it outright rather than relying
		// on the catch alone.
		let requestInFlight = false;

		function requestFullscreenIfArmed() {
			if (!landscapeQuery.matches || document.fullscreenElement || requestInFlight) return;
			const root = document.documentElement;
			if (typeof root.requestFullscreen !== 'function') return;
			requestInFlight = true;
			root
				.requestFullscreen()
				.catch(() => {
					// Denied, or this particular tap didn't carry enough "user
					// activation" for the browser's liking - simply try again
					// on the next tap, same as if this handler had never fired.
				})
				.finally(() => {
					requestInFlight = false;
				});
		}

		function handleFullscreenChange() {
			setIsFullscreen(!!document.fullscreenElement);
		}

		function handleOrientationChange() {
			if (!landscapeQuery.matches && document.fullscreenElement) {
				document.exitFullscreen?.().catch(() => {});
			}
		}

		// `click` covers a mouse/trackpad/assistive-tech tap on a device that
		// happens to support one of those alongside touch; `touchend` is the
		// real, always-present mobile gesture. Capture phase so a component
		// calling stopPropagation() on its own tap handler further down the
		// tree still lets this fire first - simplest way to keep this global
		// without threading a prop through every mobile screen/button.
		document.addEventListener('click', requestFullscreenIfArmed, { capture: true });
		document.addEventListener('touchend', requestFullscreenIfArmed, { capture: true });
		document.addEventListener('fullscreenchange', handleFullscreenChange);
		landscapeQuery.addEventListener('change', handleOrientationChange);

		return () => {
			document.removeEventListener('click', requestFullscreenIfArmed, { capture: true });
			document.removeEventListener('touchend', requestFullscreenIfArmed, { capture: true });
			document.removeEventListener('fullscreenchange', handleFullscreenChange);
			landscapeQuery.removeEventListener('change', handleOrientationChange);
		};
	}, []);

	return isFullscreen;
}
