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

// Same 4 levels as the legacy Image entity (Design.md §16.2: POOR..VERY_GOOD -
// NOT_RATED itself isn't a selectable level, just "no star reaches this far").
const LEVELS = [0, 1, 2, 3];

/**
 * Hand-rolled replacement for barrating.js (Design.md decision #8 corollary -
 * jQuery DOM-manipulating widgets are rewritten, not reused). Visually
 * identical to the legacy `.br-widget`: same 4-frame sprite
 * (public/legacy/star.png, copied byte-for-byte from the legacy WebContent),
 * same background-position swap on hover/active - see app.css's `.br-widget`
 * rules, ported from idx.css rather than redrawn.
 */
export function StarRating({ rating, onChange, readOnly }) {
	const { t } = useTranslation();
	const [hoverLevel, setHoverLevel] = useState(null);
	const effective = hoverLevel ?? rating;

	return (
		<div className="br-widget">
			{LEVELS.map((level) => (
				<a
					key={level}
					className={effective >= level ? 'br-active' : ''}
					role={readOnly ? undefined : 'button'}
					aria-label={t('imageEdit.rateLevel', { level: level + 1 })}
					onMouseEnter={() => !readOnly && setHoverLevel(level)}
					onMouseLeave={() => !readOnly && setHoverLevel(null)}
					onClick={() => !readOnly && onChange(level)}
				/>
			))}
		</div>
	);
}
