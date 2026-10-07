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

import { useEffect, useState } from 'react';

/**
 * The external-share counterpart of {@link AuthImage} (02/09/2026,
 * ShareScreen/MobileShareScreen) - fetches image bytes to a `blob:` URL the
 * same way, but with no `Authorization` header at all: a share link's whole
 * point is working for someone with no account, and `src` here already
 * carries the signed share token as a `?token=` query parameter
 * (`PublicImageResponse.from`'s own doc) - the backend validates that, not
 * a header. Deliberately its own small component rather than adding an
 * `authHeader`-optional branch to `AuthImage` itself: this one is never
 * rendered inside `<AuthProvider>` (ShareScreen bypasses it entirely, same
 * as the login screens do before authenticating), so it can't call
 * `useAuth()` at all, not just choose not to.
 */
export function PublicImage({ src, alt, className, onClick, onLoad }) {
	const [objectUrl, setObjectUrl] = useState(null);

	useEffect(() => {
		let currentUrl = null;
		const controller = new AbortController();
		setObjectUrl(null);
		fetch(src, { signal: controller.signal })
			.then((response) => (response.ok ? response.blob() : Promise.reject(new Error(`Failed to load image: ${response.status}`))))
			.then((blob) => {
				currentUrl = URL.createObjectURL(blob);
				setObjectUrl(currentUrl);
			})
			.catch(() => {
				// Swallowed on purpose (including AbortError from the cleanup
				// below) - same graceful-degradation spirit as AuthImage.
			});
		return () => {
			controller.abort();
			if (currentUrl) URL.revokeObjectURL(currentUrl);
		};
	}, [src]);

	if (!objectUrl) {
		return <div className={className} />;
	}
	return <img src={objectUrl} alt={alt} className={className} onClick={onClick} onLoad={onLoad} />;
}
