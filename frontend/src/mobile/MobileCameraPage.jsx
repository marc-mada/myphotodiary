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
 * `#camera-page` - legacy's other import entry point, always a separate
 * menu item from "Publish" (`<input type="file" ... capture="camera">` vs.
 * a plain multi-file picker, mindex.jsp l.190 vs. l.156), both posting to
 * the same `importSvr`. No desktop equivalent exists (native camera
 * capture has no meaning on a machine without one). `capture="camera"` is
 * the old boolean-attribute form the legacy markup uses; the current HTML
 * standard takes a facing mode instead - `capture="environment"` (the
 * rear/outward-facing camera, the sensible default for photographing a
 * subject, as opposed to "user" for a selfie camera) - functionally the
 * same intent as legacy's plain `capture` attribute, just spelled the way
 * current browsers expect. Single-file only (no `multiple`), matching
 * legacy's own camera input.
 *
 * `accept="image/*"` explicitly (03/09/2026, real bug found live - a
 * same-day attempt to widen this to `image/*,video/mp4` alongside images,
 * mirroring Publish, broke `capture`'s own direct-camera launch on the
 * real device tested: with two different top-level media types in
 * `accept`, the browser fell back to the ordinary file/gallery chooser
 * instead of jumping into the camera) - see MobileImportForm's own doc
 * comment for the full story. Video capture through this page specifically
 * is therefore not offered; Publish still accepts a real video file
 * (library/files, or the device's own native camera app used separately).
 */
export function MobileCameraPage({ currentDirectoryPath, onImported, onHome, onLogout }) {
	const { t } = useTranslation();
	return (
		<MobilePage title={t('mobile.cameraTitle')} onHome={onHome} onLogout={onLogout}>
			<MobileImportForm
				capture="environment"
				accept="image/*"
				buttonLabel={t('mobile.takePicture')}
				buttonIcon="camera"
				currentDirectoryPath={currentDirectoryPath}
				onImported={onImported}
			/>
		</MobilePage>
	);
}
