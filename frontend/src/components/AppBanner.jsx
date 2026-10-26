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

import { useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import { getPresentation, LIGHT_PRESENTATIONS, subscribePresentation } from '../presentation';

/**
 * The "my PhotoDiary" banner of the desktop screens (18/10/2026). Its
 * lettering is white, made for dark bars: on the light/medium presentations
 * (Bright, Titanium) the copy with dark lettering is shown instead, both on
 * a transparent background. Follows presentation changes live.
 */
export function AppBanner({ className }) {
	const { t } = useTranslation();
	const presentation = useSyncExternalStore(subscribePresentation, getPresentation);
	const src = LIGHT_PRESENTATIONS.includes(presentation) ? '/legacy/photodiary-banner-short-dark-text.png' : '/legacy/photodiary-banner-short.png';
	return <img className={className} src={src} alt={t('common.appName')} />;
}
