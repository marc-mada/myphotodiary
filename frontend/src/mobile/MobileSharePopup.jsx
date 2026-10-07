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
 * The mobile footer's new middle "Share" button opens this (25/09/2026,
 * explicit ask - "a simple form with two buttons: Share current image /
 * Share current sequence"). A small centered popup, not one of the
 * existing left-side MobileSlidePanel screens - those are anchored to a
 * *left*-side corner button (Menu/Go) and slide in from that edge; this
 * one opens from a *middle* button, so a left-anchored panel would be a
 * spatial mismatch. Backdrop and card are siblings, not parent/child - same
 * reason as desktop's own Popup.jsx (opacity on a parent applies to its
 * whole rendered subtree as one group, so nesting the card inside a dimmed
 * backdrop would dim the card too, not just what's behind it) - but this is
 * a new, mobile-specific component rather than a reuse of that desktop one
 * (decision #9 - dedicated components per presentation, not a shared widget
 * stretched to fit both).
 *
 * `error` is shown *inside* the popup, not MobileApp's own top-level error
 * line - same reasoning already applied to MobileNavPanel's own `error`
 * prop (13/09/2026): a message rendered behind an open overlay is
 * invisible, and this popup itself is exactly such an overlay. `status` is
 * a separate, non-error confirmation slot (styled plainly, not red) - used
 * only by the Web Share API's absence fallback (MobileApp's own
 * `shareLink`), where copying to the clipboard has no other visible
 * feedback of its own the way handing off to the OS share sheet does.
 */
export function MobileSharePopup({ open, onClose, onShareImage, onShareSequence, canShareImage, canShareSequence, busy, error, status, sequenceLabel }) {
	const { t } = useTranslation();
	if (!open) return null;

	return (
		<>
			<div className="mobile-share-backdrop" onClick={onClose} />
			<div className="mobile-share-popup">
				<button type="button" className="mobile-share-close" onClick={onClose}>
					{t('common.close')}
				</button>
				{error && <p className="form-error">{error}</p>}
				{status && <p className="mobile-share-status">{status}</p>}
				<button type="button" onClick={onShareImage} disabled={!canShareImage || busy}>
					<MobileIcon name="share" /> {t('mobile.shareCurrentImage')}
				</button>
				<button type="button" onClick={onShareSequence} disabled={!canShareSequence || busy}>
					<MobileIcon name="share" /> {sequenceLabel ?? t('mobile.shareCurrentSequence')}
				</button>
			</div>
		</>
	);
}
