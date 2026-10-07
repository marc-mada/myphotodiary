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

const LANGUAGES = ['en', 'fr', 'de', 'es'];

/**
 * Replaces "Signed in as {username}" in the top nav (explicit ask,
 * 28/08/2026) - a manual switch for the languages this app supports
 * (i18n.js's own `supportedLngs`, ES added 04/09/2026), on top of the
 * existing browser-detected default rather than instead of it:
 * `i18n.changeLanguage` both updates the live UI and (via
 * i18next-browser-languagedetector's own default localStorage cache -
 * already observed populating `i18nextLng` during the German-locale
 * verification, no extra wiring needed here) persists the choice across
 * reloads, overriding the detector's own guess from then on.
 *
 * A `<select>`, not a row of buttons (explicit ask, 28/08/2026 - the first
 * version of this component). Its default/initial value is whatever
 * `i18n.language` already resolved to on load - i18n.js's own
 * `supportedLngs`/`fallbackLng: 'en'` already make that "the navigator's
 * language if it's one of these, English otherwise" without this component
 * needing its own matching logic: an unsupported navigator language (e.g.
 * Italian) never reaches this component as `i18n.language` at all - i18next
 * itself substitutes `fallbackLng` before anything here ever runs. Verified
 * live with a simulated es-ES browser locale when Spanish was still
 * unsupported (fell back to English, as expected then) and again once
 * es.json was wired in (resolved to Spanish correctly).
 *
 * `i18n.language` can come back region-qualified (e.g. "en-US") even though
 * `supportedLngs`/resources are registered under the bare code - sliced to
 * the first two characters so the <select> highlights the right option
 * rather than never matching any of LANGUAGES.
 */
export function LanguageSwitcher() {
	const { i18n } = useTranslation();
	const detected = (i18n.language ?? 'en').slice(0, 2);
	const current = LANGUAGES.includes(detected) ? detected : 'en';

	return (
		<select className="language-switcher" value={current} onChange={(e) => i18n.changeLanguage(e.target.value)}>
			{LANGUAGES.map((lng) => (
				<option key={lng} value={lng}>
					{lng.toUpperCase()}
				</option>
			))}
		</select>
	);
}
