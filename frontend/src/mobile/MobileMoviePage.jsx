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
import { MobileImportForm } from './MobileImportForm';
import { MobilePage } from './MobilePage';

/**
 * `#movie-page` - "Filmer", a new menu entry with no legacy equivalent at
 * all (legacy never had video - Design.md §15). Added (03/09/2026, explicit ask) as its
 * own separate page/form rather than folded into `MobileCameraPage`, once
 * that page's own `accept="image/*"` fix (same day, real bug: combining
 * `image/*` and `video/mp4` in one `accept` broke `capture`'s direct-camera
 * launch entirely) made clear that photo capture and video capture can't
 * safely share one `<input>` - each media type needs its own unambiguous
 * `accept` for `capture` to reliably launch the right native capture UI.
 * `accept="video/mp4"` alone (matching this project's own MP4-only policy,
 * `VideoFormatValidator`) + `capture="environment"` is the video-only
 * mirror of `MobileCameraPage`'s own photo-only pair - not verified in the
 * same way that pair now has been (a device wasn't available to confirm
 * this one actually launches video-recording mode rather than yet another
 * silent fallback), but it's the one combination that's actually
 * consistent with what just got proven to matter (a single, unambiguous
 * media type in `accept` alongside `capture`), rather than another guess.
 */
export function MobileMoviePage({ currentDirectoryPath, onImported, onHome, onLogout }) {
	const { t } = useTranslation();
	return (
		<MobilePage title={t('mobile.filmTitle')} onHome={onHome} onLogout={onLogout}>
			<MobileImportForm
				capture="environment"
				accept="video/mp4"
				buttonLabel={t('mobile.recordVideo')}
				buttonIcon="video"
				currentDirectoryPath={currentDirectoryPath}
				onImported={onImported}
			/>
		</MobilePage>
	);
}
