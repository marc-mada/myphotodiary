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
 * Shared full-screen page shell - every legacy `data-role="page"` other
 * than the home page (`#upload-page`, `#camera-page`, `#img-edit-page`,
 * `#dir-edit-page`, `#query-page`) has the exact same header layout and
 * icons: a "Home" button on the left (`data-icon="home"`, `data-transition=
 * "slide" data-direction="reverse"`, i.e. slide back out rather than in -
 * a `home` icon, not a back-caret, matching legacy's own choice there) and
 * "Quit" (`data-icon="power"`) on the right. Rendered as a plain overlay
 * replacing the visible content rather than an actual jQuery Mobile
 * pagecontainer transition - same simplification this project already made
 * for the Admin screen's modal (no page-transition library pulled in for a
 * solo-maintained app).
 */
export function MobilePage({ title, onHome, onLogout, children }) {
	const { t } = useTranslation();
	return (
		<div className="mobile-page">
			<header className="mobile-page-header">
				<button type="button" onClick={onHome}>
					<MobileIcon name="home" /> {t('mobile.home')}
				</button>
				<span className="mobile-page-title">{title}</span>
				<button type="button" onClick={onLogout}>
					{t('mobile.quit')} <MobileIcon name="power" />
				</button>
			</header>
			<div className="mobile-page-content">{children}</div>
		</div>
	);
}
