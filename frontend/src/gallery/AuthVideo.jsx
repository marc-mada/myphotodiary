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
import { useTranslation } from 'react-i18next';
import { videoApi } from '../api/videos';

// How often the viewer asks whether the playback copy is ready, while it
// shows "Preparing video..." (27/10/2026).
const STATUS_POLL_MS = 5000;

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
 *
 * `playback` (27/10/2026, option C - any video accepted, an H.264 copy made
 * in the background when needed): the server streams the copy once made,
 * else the original. While the copy is PENDING the original is tried; if
 * this browser can't play it (an error, or sound with no picture - Chrome
 * without an HEVC decoder), "Preparing video..." is shown and the status is
 * checked every 5 s, the player reloading as soon as the copy is ready.
 */
export function AuthVideo({ imageId, playback, className, onLoadedMetadata }) {
	const { t } = useTranslation();
	const [streamUrl, setStreamUrl] = useState(null);
	const [status, setStatus] = useState(playback ?? 'ORIGINAL');
	const [unplayable, setUnplayable] = useState(false);
	const [reloadKey, setReloadKey] = useState(0);

	useEffect(() => {
		setStatus(playback ?? 'ORIGINAL');
		setUnplayable(false);
	}, [imageId, playback]);

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
	}, [imageId, reloadKey]);

	useEffect(() => {
		if (!unplayable || status !== 'PENDING') return undefined;
		const timer = window.setInterval(() => {
			videoApi
				.getStatus(imageId)
				.then((res) => {
					if (res.videoPlayback === 'PENDING') return;
					setStatus(res.videoPlayback);
					if (res.videoPlayback === 'COPY') {
						setUnplayable(false);
						setReloadKey((key) => key + 1); // new token: the stream now serves the copy
					}
				})
				.catch(() => {
					// try again at the next tick
				});
		}, STATUS_POLL_MS);
		return () => window.clearInterval(timer);
	}, [unplayable, status, imageId]);

	if (unplayable) {
		return <VideoStatus className={className} message={status === 'PENDING' ? t('video.preparing') : t('video.unavailable')} />;
	}
	if (!streamUrl) {
		return <div className={className} />;
	}
	return (
		<video
			key={streamUrl}
			src={streamUrl}
			controls
			className={className}
			onLoadedMetadata={(e) => handleMetadata(e, onLoadedMetadata, () => setUnplayable(true))}
			onError={() => setUnplayable(true)}
		/>
	);
}

/**
 * A shared video (share link, 14/10/2026): the public stream URL is already
 * known. No polling here - a visitor whose browser can't play the original
 * yet is told it is still being prepared (27/10/2026).
 */
export function PublicVideo({ src, playback, className, onLoadedMetadata }) {
	const { t } = useTranslation();
	const [unplayable, setUnplayable] = useState(false);

	useEffect(() => setUnplayable(false), [src]);

	if (unplayable) {
		return <VideoStatus className={className} message={playback === 'PENDING' ? t('video.preparingShared') : t('video.unavailable')} />;
	}
	return (
		<video
			src={src}
			controls
			className={className}
			onLoadedMetadata={(e) => handleMetadata(e, onLoadedMetadata, () => setUnplayable(true))}
			onError={() => setUnplayable(true)}
		/>
	);
}

/** A video whose picture can't be decoded may still load (sound only, 0x0) - treated as unplayable. */
function handleMetadata(event, onLoadedMetadata, markUnplayable) {
	if (event.currentTarget.videoWidth === 0) {
		markUnplayable();
		return;
	}
	onLoadedMetadata?.(event);
}

function VideoStatus({ className, message }) {
	return (
		<div className={`${className ?? ''} video-status`}>
			<p>{message}</p>
		</div>
	);
}
