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
import { StarRating } from '../gallery/StarRating';
import { MobilePage } from './MobilePage';

/**
 * `#img-edit-page` - description, plus rating (explicit ask, 28/08/2026 -
 * a deliberate addition beyond what mindex.jsp itself has: legacy's mobile
 * edit form never touches rating at all, only description - see the
 * decision #9 audit). Still no date/attributes - those weren't asked for,
 * and legacy's own `postit.persistImgData` forces `imgData.date = null`
 * before persisting regardless ("date is not captured on mobile GUI").
 *
 * Rating is tracked locally and only sent along with the description when
 * "Save" is tapped - not persisted immediately on each tap the way
 * desktop's `ImageEditForm` does (`onChange={(rating) => onUpdate(...)}`).
 * Consistent with this mobile UI's own explicit-action model elsewhere
 * (MobileImportForm's separate "Upload" button, added for the same
 * reason) rather than autosaving on every interaction.
 */
export function MobileImageEditPage({ image, onSave, onHome, onLogout }) {
	const { t } = useTranslation();
	const [description, setDescription] = useState(image?.description ?? '');
	const [rating, setRating] = useState(image?.rating ?? -1);

	function handleSave() {
		onSave({ description, rating });
	}

	return (
		<MobilePage title={image?.name ?? ''} onHome={onHome} onLogout={onLogout}>
			<label>
				{t('mobile.pictureComment')}
				<textarea autoFocus value={description} onChange={(e) => setDescription(e.target.value)} rows={6} />
			</label>
			<label>
				{t('mobile.rating')}
				<StarRating rating={rating} onChange={setRating} />
			</label>
			<button type="button" onClick={handleSave}>
				{t('mobile.save')}
			</button>
		</MobilePage>
	);
}
