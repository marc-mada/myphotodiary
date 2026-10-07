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
 * The Sign in/Sign out decoration (explicit ask, 28/08/2026) - same power-
 * symbol shape as the mobile tree's own "power" icon (MobileIcon.jsx,
 * matching legacy's `data-icon="power"`), duplicated here rather than
 * imported across trees: it's a two-line SVG, and desktop/mobile are
 * deliberately separate presentation trees (decision #9) that don't
 * otherwise share components.
 */
export function PowerIcon() {
	return (
		<svg className="power-icon" viewBox="0 0 24 24" fill="none" stroke="black" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
			<line x1="12" y1="2" x2="12" y2="12" />
			<path d="M18.36 6.64a9 9 0 1 1-12.73 0" />
		</svg>
	);
}
