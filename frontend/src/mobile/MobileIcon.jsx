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
 * Icons for the mobile menus/buttons, matching legacy's own `data-icon`
 * choice per button (mindex.jsp) one-for-one - `bars` (Menu), `power`
 * (Quit), `bullets` (Go/Navigate - a list, jQuery Mobile's own icon for
 * "list of items"), `comment` (Edit, a speech bubble - the post-it is a
 * comment), `home`, `carat-l` (Back), `search`, `camera`, `action` (Publish
 * - a generic "do it" icon), `check` (the file-picker step).
 *
 * Legacy's are jQuery Mobile's own built-in icon-font glyphs, not custom
 * artwork of this app's own (unlike the desktop screen's delete/play/
 * full-screen/star/film PNGs, which are real legacy assets copied
 * byte-for-byte per decision #8) - there's nothing to copy here, only the
 * same icon *shapes* to reproduce, so these are small hand-drawn inline
 * SVGs (no icon-font dependency) rather than ported files.
 *
 * `video` (03/09/2026, new "Filmer" menu entry - no legacy equivalent at
 * all, legacy never had video) - same camcorder-body-plus-lens-flag shape
 * as `camera`'s own aperture-body-plus-lens-bump silhouette, so the two
 * read as a clearly related pair at a glance, not two unrelated glyphs.
 *
 * `share` (25/09/2026, new footer "Share" button - no legacy equivalent,
 * legacy never had a share feature at all) - Feather Icons' own real
 * "share-2" glyph (three connected circles), not hand-invented - the same
 * icon family/style (stroke-based, 24x24, strokeWidth 2) every other icon
 * here already reproduces the shape of, so reusing a real Feather glyph
 * keeps this one visually consistent with the rest for free.
 */
const PATHS = {
	bars: (
		<>
			<line x1="4" y1="7" x2="20" y2="7" />
			<line x1="4" y1="12" x2="20" y2="12" />
			<line x1="4" y1="17" x2="20" y2="17" />
		</>
	),
	power: (
		<>
			<line x1="12" y1="2" x2="12" y2="12" />
			<path d="M18.36 6.64a9 9 0 1 1-12.73 0" />
		</>
	),
	bullets: (
		<>
			<circle cx="4" cy="7" r="1" />
			<line x1="8" y1="7" x2="20" y2="7" />
			<circle cx="4" cy="12" r="1" />
			<line x1="8" y1="12" x2="20" y2="12" />
			<circle cx="4" cy="17" r="1" />
			<line x1="8" y1="17" x2="20" y2="17" />
		</>
	),
	comment: <path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />,
	home: (
		<>
			<path d="M3 11l9-8 9 8" />
			<path d="M5 10v10h14V10" />
		</>
	),
	caretLeft: <polyline points="15 6 9 12 15 18" />,
	search: (
		<>
			<circle cx="11" cy="11" r="7" />
			<line x1="21" y1="21" x2="16.65" y2="16.65" />
		</>
	),
	camera: (
		<>
			<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
			<circle cx="12" cy="13" r="4" />
		</>
	),
	action: (
		<>
			<circle cx="12" cy="12" r="9" />
			<line x1="12" y1="8" x2="12" y2="16" />
			<line x1="8" y1="12" x2="16" y2="12" />
		</>
	),
	check: <polyline points="20 6 9 17 4 12" />,
	video: (
		<>
			<polygon points="23 7 16 12 23 17 23 7" />
			<rect x="1" y="5" width="15" height="14" rx="2" ry="2" />
		</>
	),
	share: (
		<>
			<circle cx="18" cy="5" r="3" />
			<circle cx="6" cy="12" r="3" />
			<circle cx="18" cy="19" r="3" />
			<line x1="8.59" y1="13.51" x2="15.42" y2="17.49" />
			<line x1="15.41" y1="6.51" x2="8.59" y2="10.49" />
		</>
	),
};

export function MobileIcon({ name }) {
	return (
		<svg className="mobile-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
			{PATHS[name]}
		</svg>
	);
}
