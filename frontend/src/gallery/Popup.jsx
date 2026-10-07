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
 * Centered overlay popup - idx.css's `.popup`/`.popup_background`/
 * `#popup_close` (black background, light text, box-shadow, close icon
 * floating at the top-right corner), reused for every gallery-screen form
 * that used to be an always-visible panel: sequence description
 * (dirEditPopup), sequence geolocation (mapPopup), picture description
 * (imageEditPopup) and the upload form (importPopup). A direct ask - free up
 * as much surface as possible for the image itself, these forms are
 * invisible until their trigger (a post-it area or a toolbar button) is
 * clicked, and appear centered over everything, not inline taking up
 * permanent space. Distinct from the Admin screen's `.modal`/
 * `.modal-backdrop` (UserFormModal) - that one is intentionally a white
 * surface (Design.md decision #8's white-surface convention), this one is black,
 * matching idx.css's actual `.popup` on the gallery screen.
 */
import { useTranslation } from 'react-i18next';

export function Popup({ open, onClose, title, children, className }) {
	const { t } = useTranslation();
	if (!open) return null;

	// Backdrop and popup are SIBLINGS, not parent/child - same as legacy's
	// separate #popup_background/#popup divs. CSS `opacity` applies to an
	// element's whole rendered subtree as one group, so nesting the popup
	// inside the (opacity: 0.6) backdrop would fade the popup itself along
	// with it, not just dim what's behind it.
	//
	// `className` (25/09/2026, optional) - every caller before the image
	// editor fit comfortably inside the default 50%/70%/max-640px box
	// (idx.css's own `.popup` sizing); a crop tool needs real room to drag
	// handles precisely, so this lets one caller opt into a wider/taller
	// variant (`.image-editor-popup`, app.css) via a second class on the
	// same element rather than every other form popup growing too.
	return (
		<>
			<div className="gallery-popup-backdrop" onClick={onClose} />
			<div className={className ? `gallery-popup ${className}` : 'gallery-popup'}>
				<img className="gallery-popup-close" src="/legacy/close-icon.png" alt={t('common.close')} onClick={onClose} />
				<div className="gallery-popup-body">
					{title && <h3>{title}</h3>}
					{children}
				</div>
			</div>
		</>
	);
}
