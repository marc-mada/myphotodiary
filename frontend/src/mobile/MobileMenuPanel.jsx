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
 * `#menu-panel` content (mindex.jsp) - Home / Go / Search / Camera / Filmer /
 * Publish. The first four and the last match the legacy hamburger menu's
 * own five entries, order, and icons (`data-icon`: home/bullets/search/
 * camera/action) minus Quit (see below); "Filmer" (03/09/2026) is new, no
 * legacy equivalent at all - inserted between Camera and Publish, exactly
 * as asked, once Camera's own `accept="image/*"` fix made clear photo and
 * video capture need their own separate `<input>`/page each (see
 * MobileMoviePage's own doc). "Admin" has no entry here: the legacy source
 * has no mobile Admin screen at all (decision #9 - `admin.jsp` never had an
 * `madmin.jsp` counterpart), so this menu doesn't invent one.
 *
 * Unlike MobileNavPanel, every entry here is a complete, one-shot action
 * (not a drill-down step), so closing the panel after any of them is
 * correct - except "Go", which switches straight to the Navigation panel
 * (`onGo` itself moves `activePanel` from 'menu' to 'nav' in MobileApp) -
 * closing on top of that would immediately undo it, since both are the
 * same piece of state changing twice in one event handler.
 *
 * Quit removed (01/09/2026, explicit ask - a deliberate departure from
 * legacy's own six-entry menu, not an oversight) - redundant with
 * `.mobile-home-header`'s own always-reachable Quit button, top-right on
 * this same home page underneath this panel. Only this menu loses it;
 * the other full-screen pages (Search/Publish/Camera/edit forms) still
 * take their own `onLogout` from `MobileApp` for their own header's Quit
 * button - a different, non-redundant context, out of scope here.
 */
export function MobileMenuPanel({ onClose, onHome, onGo, onSearch, onCamera, onFilm, onPublish }) {
	const { t } = useTranslation();
	function item(action) {
		return () => {
			action();
			onClose();
		};
	}

	return (
		<ul className="mobile-panel-list">
			<li>
				<button type="button" onClick={item(onHome)}>
					<MobileIcon name="home" /> {t('mobile.home')}
				</button>
			</li>
			<li>
				<button type="button" onClick={onGo}>
					<MobileIcon name="bullets" /> {t('mobile.go')}
				</button>
			</li>
			<li>
				<button type="button" onClick={item(onSearch)}>
					<MobileIcon name="search" /> {t('mobile.search')}
				</button>
			</li>
			<li>
				<button type="button" onClick={item(onCamera)}>
					<MobileIcon name="camera" /> {t('mobile.camera')}
				</button>
			</li>
			<li>
				<button type="button" onClick={item(onFilm)}>
					<MobileIcon name="video" /> {t('mobile.film')}
				</button>
			</li>
			<li>
				<button type="button" onClick={item(onPublish)}>
					<MobileIcon name="action" /> {t('mobile.publish')}
				</button>
			</li>
		</ul>
	);
}
