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
 * Fetches the image bytes by hand (`credentials: 'same-origin'`, same as
 * every other API call - client.js's own doc comment) and hands them to
 * <img> as a blob: URL, rather than a plain <img src="/api/images/…">.
 *
 * Session-cookie auth (18/09/2026) means the *authentication* half of that
 * reasoning no longer strictly requires this - the browser now attaches
 * the session cookie to a plain <img> request automatically, the same way
 * it does for a fetch. This component is kept as-is anyway for a
 * different, still-real reason: the explicit `AbortController` below gives
 * genuine cancel-on-unmount control over the underlying request (a
 * plain <img> whose `src` changes/unmounts doesn't offer that same
 * guarantee), which matters here specifically - this component unmounts
 * on every delete and every filmstrip re-render, a real source of
 * abandoned in-flight requests for images nobody's looking at anymore if
 * left uncancelled. Simplifying this back to a plain <img> tag is a
 * separate, deliberate future cleanup, not a side effect of the auth
 * change.
 *
 * Dev-mode note: React StrictMode deliberately mounts every component twice
 * (mount -> cleanup -> mount) to surface effect bugs. Because the cleanup
 * below now genuinely aborts the in-flight fetch (see the comment on
 * `controller`), that first, discarded mount shows up as a harmless
 * net::ERR_ABORTED in devtools/network logs on every load - expected
 * StrictMode noise, not a real failure, and not present in production
 * (StrictMode's double-invoke only happens in dev).
 */
export function AuthImage({ src, alt, className, onClick, onDoubleClick, onLoad }) {
	const [objectUrl, setObjectUrl] = useState(null);

	useEffect(() => {
		let currentUrl = null;
		// An AbortController - rather than a plain cancelled flag - actually
		// tears down the underlying network request on cleanup, instead of
		// just discarding the result once it eventually resolves. Matters
		// here specifically: this component unmounts on every delete (the
		// image row and its thumbnail both disappear) and on every filmstrip
		// re-render, so a plain flag would leave a real, growing number of
		// abandoned in-flight fetches for images that no longer exist.
		const controller = new AbortController();
		setObjectUrl(null);
		fetch(src, { credentials: 'same-origin', signal: controller.signal })
			.then((response) => (response.ok ? response.blob() : Promise.reject(new Error(`Failed to load image: ${response.status}`))))
			.then((blob) => {
				currentUrl = URL.createObjectURL(blob);
				setObjectUrl(currentUrl);
			})
			.catch(() => {
				// Swallowed on purpose (including AbortError from the cleanup
				// below): a broken thumbnail shouldn't crash the gallery, it
				// just stays blank - same graceful-degradation spirit as a
				// regular <img> failing to load.
			});
		return () => {
			controller.abort();
			if (currentUrl) URL.revokeObjectURL(currentUrl);
		};
	}, [src]);

	if (!objectUrl) {
		return <div className={className} />;
	}
	return <img src={objectUrl} alt={alt} className={className} onClick={onClick} onDoubleClick={onDoubleClick} onLoad={onLoad} />;
}
