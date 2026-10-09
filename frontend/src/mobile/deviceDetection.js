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
 * Decides Desktop vs. Mobile presentation (Design.md decision #9) - the
 * "detection to be settled when we get here" point that decision explicitly
 * left open. Ports the legacy regex verbatim
 * (AuthenticationFilter's `isMobilePattern` init-param, every web.xml
 * variant: "Mobi|mobi|Tablet|tablet|Android|android") tested against
 * `navigator.userAgent` with `.test()` - the JS equivalent of the Java
 * `Matcher.find()` legacy uses: an unanchored substring search, not a full
 * match.
 *
 * Chosen over the other option decision #9 named (viewport-width
 * detection): the two presentations differ by more than layout size - no
 * keyboard shortcuts, no slideshow/full-screen/delete, reduced edit forms,
 * a native camera-capture entry - none of which make sense to toggle just
 * because a desktop window happens to be narrow (or a phone happens to be
 * in landscape and momentarily wide). Device type, not window size, is
 * what actually determines which set of interactions is coherent, and
 * that's exactly what the legacy UA sniff was already deciding.
 *
 * Computed once per page load, not reactively on resize - same as legacy,
 * which only ever branches on User-Agent at request time in
 * AuthenticationFilter, never re-evaluates per viewport change.
 */
const MOBILE_USER_AGENT_PATTERN = /Mobi|mobi|Tablet|tablet|Android|android/;

export function isMobileDevice() {
	if (typeof navigator === 'undefined' || !navigator.userAgent) return false;
	return MOBILE_USER_AGENT_PATTERN.test(navigator.userAgent);
}

/**
 * True on any iOS/iPadOS device (iPhone included, not just iPad),
 * regardless of browser branding (Safari/Chrome/Firefox on iOS are all
 * WKWebView underneath - Apple requires it - so they all report the same
 * platform/touch capabilities no matter what their own UA string claims) -
 * a narrower, different question than `isMobileDevice()` above, which
 * decides Desktop vs. Mobile *presentation* and isn't iOS-specific at all.
 *
 * Added 03/09/2026 to gate `useLandscapeFullscreen`'s actual
 * `requestFullscreen()` call off - real device testing on iPad (Chrome and
 * Safari) found the Fullscreen API itself unreliable there (see that
 * hook's own comment for the concrete symptoms: an orientation-change that
 * didn't reliably auto-exit fullscreen, a viewport that visibly shifted/
 * wasn't stable, and the browser's own exit-fullscreen icon landing
 * exactly on top of this app's own Menu button) - not reproducible on
 * Android, where the same feature works as designed (confirmed on a real
 * Galaxy and Xiaomi, Chrome and Firefox). Explicit follow-up the same day:
 * the feature is abandoned for **all** iOS, iPhone included, not narrowed
 * to "iPad specifically" - only ever verified on an iPad in practice, but
 * iPhone runs the exact same WebKit engine under the exact same App Store
 * constraint (Safari and every other iOS browser alike), so there was
 * never a reason to expect it to fare any better there - confirmed the
 * detection itself already covered both (`isIOSDevice()` was never
 * iPad-only to begin with, `/iPad|iPhone|iPod/` matches either), so this
 * was a documentation correction, not a logic change.
 *
 * `navigator.userAgent` alone can't tell an iPad apart from a Mac since
 * iPadOS 13 - by default it reports as desktop Safari on a "Macintosh",
 * no "iPad"/"Mobile" token at all. The standard, reliable workaround: a
 * real Mac reports 0-1 touch points; an iPad (any browser on it) reports
 * more than one. `/iPad|iPhone|iPod/` is checked first regardless, for a
 * browser/OS combination that *does* still self-identify (or a future
 * iPadOS that changes its masquerading default back).
 */
export function isIOSDevice() {
	if (typeof navigator === 'undefined') return false;
	if (/iPad|iPhone|iPod/.test(navigator.userAgent)) return true;
	return navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1;
}

/**
 * True on a tablet-sized screen (08/10/2026): the physical screen's short
 * side is at least 700 CSS px. Drives the larger mobile corner buttons/menu/
 * banner (`.mpd-large-screen` in app.css, set once in main.jsx).
 *
 * `screen.width/height` rather than a CSS media query on the viewport: the
 * viewport shrinks by whatever the browser's own bars take, which differs
 * per browser and orientation - an iPad Air (1180x820) in landscape has
 * less than 700px of page height left under Safari/Chrome's bars, so a
 * viewport test made its buttons shrink when rotated (reported live), while
 * a width-only test enlarged a phone's buttons in landscape (1080x2400 phone
 * is ~915px wide). The screen's short side is the same in both orientations:
 * ~360-450px on a phone, 750px+ on a 10"+ tablet.
 */
export function isLargeScreen() {
	if (typeof screen === 'undefined') return false;
	return Math.min(screen.width, screen.height) >= 700;
}
