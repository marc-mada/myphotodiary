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

import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { MobilePage } from './MobilePage';

/**
 * `#dir-edit-page` - description only, same reasoning as
 * MobileImageEditPage: legacy's `postit.persistDirData` forces
 * `dirData.dirDate = null` before persisting and the page has no
 * geolocation, group, rename, or attribute controls at all. Distinct from
 * desktop's `DirectoryDetailPanel`, which has all of those - not a reduced
 * copy of it.
 */
export function MobileSequenceEditPage({ directory, onSave, onHome, onLogout }) {
	const { t } = useTranslation();
	const [description, setDescription] = useState(directory?.description ?? '');

	function handleSave() {
		onSave({ description });
	}

	return (
		<MobilePage title={t('mobile.sequence')} onHome={onHome} onLogout={onLogout}>
			<label>
				{t('mobile.sequenceComment')}
				<textarea autoFocus value={description} onChange={(e) => setDescription(e.target.value)} rows={6} />
			</label>
			<button type="button" onClick={handleSave}>
				{t('mobile.save')}
			</button>
		</MobilePage>
	);
}
