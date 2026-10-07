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

import i18n from 'i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import fr from './locales/fr.json';
import de from './locales/de.json';
import es from './locales/es.json';

// Client-side equivalent of legacy's request.getLocale() (JSP/servlets picked
// the ResourceBundle from the request's Accept-Language via the container).
// LanguageDetector defaults to navigator.language, which is the closest
// browser-side analogue - same signal, resolved on the client instead of the
// server since there's no per-request server rendering any more. English is
// the fallback either way (both legacy's default Labels.properties and here).
//
// German (28/08/2026, explicit ask) has no legacy counterpart at all -
// legacy only ever had EN/FR/ES, and ES itself was skipped
// at the time (Labels_es.properties is mojibake-corrupted, not a
// trustworthy source to copy from) - so de.json is a fresh, from-scratch
// translation, not a port.
//
// Spanish (04/09/2026, explicit ask - "forget the legacy spanish file,
// translate the english to spanish") - the corrupted legacy file is
// deliberately never touched/read at all here either; es.json is
// translated fresh from en.json, same "from-scratch, not a port"
// treatment German already got, for the same reason.
//
// Both verified for exact key parity with en.json/fr.json (179 keys each,
// nothing missing or extra) before being wired in below.
i18n
	.use(LanguageDetector)
	.use(initReactI18next)
	.init({
		resources: {
			en: { translation: en },
			fr: { translation: fr },
			de: { translation: de },
			es: { translation: es },
		},
		fallbackLng: 'en',
		supportedLngs: ['en', 'fr', 'de', 'es'],
		// Detected browser locales are region-qualified (fr-FR, fr-CA, ...) but
		// the resource bundles above are registered under the bare language
		// code - 'languageOnly' strips the region before matching against
		// supportedLngs/resources, so any French-speaking locale resolves to
		// 'fr' instead of silently missing the exact 'fr-FR' key and falling
		// back to English.
		load: 'languageOnly',
		interpolation: {
			escapeValue: false, // React already escapes.
		},
	});

export default i18n;
