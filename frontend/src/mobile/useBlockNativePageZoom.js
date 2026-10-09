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

import { useEffect } from 'react';

/**
 * Stops the browser's own whole-page pinch-zoom while the mobile home page
 * (single-image or mosaic screen) is shown (08/10/2026, explicit ask: "when
 * the user zooms in an image, magnify only the image, not the control bars
 * which should not move"). The picture is now zoomed by MobileImageViewer
 * itself; a native page zoom would magnify and shift the Menu/Quit/Go/
 * Comment bars along with it.
 *
 * Chrome/Firefox already honour `touch-action: pan-x pan-y` on
 * `.mobile-home-page` (app.css). iOS Safari ignores the viewport meta's
 * `user-scalable` and doesn't reliably apply `touch-action` to pinch, so
 * its proprietary `gesturestart`/`gesturechange` events are cancelled here
 * as well - they only exist on WebKit, so this is a no-op elsewhere.
 * Scoped to the lifetime of the component using it: other mobile pages
 * (search, edit forms) keep native zoom, which helps reading small text.
 */
export function useBlockNativePageZoom() {
	useEffect(() => {
		const cancel = (e) => e.preventDefault();
		document.addEventListener('gesturestart', cancel, { passive: false });
		document.addEventListener('gesturechange', cancel, { passive: false });
		return () => {
			document.removeEventListener('gesturestart', cancel);
			document.removeEventListener('gesturechange', cancel);
		};
	}, []);
}
