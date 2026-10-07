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

import { useTranslation } from 'react-i18next';
import { MobileIcon } from './MobileIcon';

/**
 * `#nav-panel` content (mindex.jsp) - "Back" plus the current directory's
 * direct subdirectories (`explorer.initSubDir`), single-level browse-in/
 * back-out like `explorer`, not a full expandable tree.
 *
 * Selecting an entry never closes this panel itself - it just asks the
 * parent to navigate there (`onSelect`). Real feedback from testing on a
 * handset: tapping a year or month (which has no pictures of its own, only
 * further sub-directories) used to close the panel immediately, and since
 * the picture pane had nothing new to show either, a single tap looked
 * like it had done nothing at all - the user then had to reopen the panel
 * by hand just to see the next level, tap after tap. MobileApp now decides
 * whether to close the panel, based on whether the destination actually
 * has pictures to show (see its `navigateTo`) - an intermediate level with
 * no pictures instead refreshes this same list in place to show what's
 * one level deeper, so a single tap always visibly does something.
 *
 * A leading `{currentPath}/...` row always heads the list, above the
 * subdirectories - orientation for exactly the situation the previous
 * paragraph describes: the list refreshes in place as you drill down, so
 * without some indication of where you currently are, a few single-tap
 * steps in a row can leave you unsure which level you're now looking at.
 * Not a button - it's a label, not a destination (you're already there).
 * Replaces the old "No sub-directories." message for an empty list -
 * this row alone already makes clear where you are; a redundant "there's
 * nothing else here" line added nothing on top of that.
 *
 * `error` (new, 13/09/2026 - explicit ask) - this panel is a fully opaque
 * overlay (`.mobile-side-panel`, `background: black`, z-index above the
 * header/footer chrome, see its own doc comment) sitting directly on top
 * of `.mobile-home-main`'s own `.form-error` paragraph - a RBAC BROWSE
 * denial from tapping a subdirectory here (`onSelect` -> `navigateTo` ->
 * a 403, e.g. no role at all in that sequence's group) called
 * `reportError` exactly as before, but the message rendered invisibly
 * behind this panel, which stays open throughout (selecting an entry
 * never closes it, see the paragraph above) - reported live: the tap
 * silently appeared to do nothing. `MobileApp` now passes the same error
 * text here specifically while this panel is open, rendered at the very
 * top so it's visible without scrolling past the subdirectory list.
 */
export function MobileNavPanel({ currentPath, subdirectories, onBack, onSelect, error }) {
	const { t } = useTranslation();
	return (
		<>
			{error && <p className="form-error mobile-panel-error">{error}</p>}
			<button type="button" className="mobile-panel-back" onClick={onBack} disabled={!currentPath}>
				<MobileIcon name="caretLeft" /> {t('mobile.back')}
			</button>
			<ul className="mobile-panel-list">
				<li className="mobile-panel-current-path">
					{currentPath}
					{t('mobile.currentPathSuffix')}
				</li>
				{subdirectories.map((node) => (
					<li key={node.path}>
						<button type="button" onClick={() => onSelect(node)}>
							{node.name}
						</button>
					</li>
				))}
			</ul>
		</>
	);
}
