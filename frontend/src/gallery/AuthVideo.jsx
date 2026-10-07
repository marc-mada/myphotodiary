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
import { videoApi } from '../api/videos';

/**
 * The video counterpart of AuthImage (28/08/2026, video support) - but a
 * genuinely different mechanism, not the same blob-fetch trick reused:
 * `<video>` can't carry a custom Authorization header either, but unlike a
 * thumbnail/photo, fetching a whole video as a blob first would defeat the
 * entire point of Range-request streaming (VideoController) for a file that
 * can be hundreds of MB - the browser would have to download it completely
 * before playback could even start, and lose the ability to seek without
 * re-downloading. So instead: fetch a short-lived signed token
 * (VideoStreamTokenService) once, then hand `<video>` a plain URL with that
 * token as a query parameter - the browser's own native video engine does
 * the actual range-based fetching from there, same as it would for any
 * ordinary streaming URL.
 *
 * `className="visible-image"` (not a distinct video-only class) is
 * deliberate: ImageViewer's controls-bar positioning
 * (`updateControlsTop`) queries `img.visible-image` - actually a plain
 * CSS class shared with `<video>` keeps the exact same `max-width/
 * max-height: 100%; object-fit: contain` sizing without a second rule to
 * keep in sync, and `onLoadedMetadata` (video's rough equivalent of
 * `<img onLoad>`) is wired the same way so that recalculation still runs
 * when a video's own aspect ratio is what determines the letterboxing.
 */
export function AuthVideo({ imageId, className, onLoadedMetadata }) {
	const [streamUrl, setStreamUrl] = useState(null);

	useEffect(() => {
		let cancelled = false;
		setStreamUrl(null);
		videoApi
			.getToken(imageId)
			.then((res) => {
				if (!cancelled) setStreamUrl(res.streamUrl);
			})
			.catch(() => {
				// Swallowed on purpose, same spirit as AuthImage: a video that
				// fails to get a token just doesn't play, it doesn't crash the
				// viewer.
			});
		return () => {
			cancelled = true;
		};
	}, [imageId]);

	if (!streamUrl) {
		return <div className={className} />;
	}
	return <video src={streamUrl} controls className={className} onLoadedMetadata={onLoadedMetadata} />;
}
