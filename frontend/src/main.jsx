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

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import ErrorBoundary from './components/ErrorBoundary.jsx';
import './app.css';
import './i18n';
import { isLargeScreen } from './mobile/deviceDetection';

// Tablet-sized screen: larger mobile corner buttons/menu/banner (app.css).
// Decided once from the physical screen, so rotating never changes it.
document.documentElement.classList.toggle('mpd-large-screen', isLargeScreen());

createRoot(document.getElementById('root')).render(
	<StrictMode>
		<ErrorBoundary>
			<App />
		</ErrorBoundary>
	</StrictMode>,
);

// Hides index.html's own boot-loading progress bar (10/09/2026) - the one
// true "loading is over" signal (the app has actually mounted), not tied to
// any single resource's own load event. `render` above is synchronous for
// the initial mount (React 18's createRoot still commits the first render
// synchronously within this call), so by the time this line runs the real
// UI is already in the DOM underneath where the loader sits. Optional
// chaining: this global only exists in a production build where
// index.html's own bootstrap script actually created it (see
// vite.config.js's progressiveLoaderPlugin) - absent entirely on the dev
// server, where there was never a loader to hide in the first place.
window.__hideAppBootLoader?.();
