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
 * Shared shell for the two left-side panels (`#nav-panel`/`#menu-panel`,
 * MobileNavPanel/MobileMenuPanel). Absolutely positioned over
 * `.mobile-home-main`, sliding in via `transform` rather than a `width`
 * animation - a deliberate reversal (28/08/2026, explicit ask) of the
 * original flex-sibling/width-push design: pushing the picture pane by
 * shrinking it worked, but visibly squeezed/distorted the image, worst in
 * portrait where there's little width to spare. `.mobile-home-content`
 * (MobileApp) now shifts by the same `translateX` instead, staying at its
 * full rendered size - part of it can end up past the screen edge, clipped
 * by `.mobile-home-main`'s own `overflow: hidden` there rather than
 * resized to fit, which is the explicitly preferred trade-off here. The
 * panel sits above the header/footer chrome in z-index, not below it -
 * found and fixed the same day it was first set the other way around,
 * which hid the panel's own "Back" button entirely under the header (the
 * header spans the full screen width, not just the strip actually showing
 * Menu/Quit, so it covered the panel's top-left corner too). Quit/Edit
 * (the header/footer's *right*-side buttons) stay reachable regardless,
 * since the panel's own width never reaches that far right.
 */
export function MobileSlidePanel({ open, children }) {
	return (
		<div className={`mobile-side-panel${open ? ' open' : ''}`}>
			<div className="mobile-side-panel-inner">{children}</div>
		</div>
	);
}
