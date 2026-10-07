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
 * `#upload-page` ("Publish pictures" menu entry) - `<input type="file"
 * accept="image/*,video/mp4" multiple>`, no `capture` attribute (mindex.jsp
 * l.156), so the browser offers its full library picker (gallery/library,
 * camera, files) rather than jumping straight into the camera - both
 * photos and videos (see MobileImportForm's own comment on why `accept`
 * widened beyond legacy's own photo-only `image/*`). Previously reused
 * the desktop `Uploader` (FilePond) - wrong on a touch device (drag-and-
 * drop UI that doesn't apply, and its file input only ever opened the
 * camera, never the gallery) - see MobileImportForm's own comment for the
 * full reasoning. "check" is legacy's own icon for this button
 * (`data-icon="check"` on the "1. Select files" step).
 */
export function MobileUploadPage({ currentDirectoryPath, onImported, onHome, onLogout }) {
	const { t } = useTranslation();
	return (
		<MobilePage title={t('mobile.publishPicturesTitle')} onHome={onHome} onLogout={onLogout}>
			<MobileImportForm
				multiple
				buttonLabel={t('mobile.selectPictures')}
				buttonIcon="check"
				currentDirectoryPath={currentDirectoryPath}
				onImported={onImported}
			/>
		</MobilePage>
	);
}
