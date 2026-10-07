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
import { AuthImage } from './AuthImage';

/**
 * A drop-in replacement for {@link AuthImage}, used only for the *current*
 * picture in the main viewer (`ImageViewer`/`MobileImageViewer` - never the
 * filmstrip/mosaic thumbnails, the crossfade's own outgoing layer, or the
 * next-image preload, all of which keep using plain `AuthImage` as before).
 *
 * Explicit ask (10/09/2026, "on slow 3/4G networks it is lengthy to load the
 * images"): while the real "web"-tier fetch is still in flight, show the
 * already-small, already-fast 128px thumbnail (`thumbnailSrc`), plus a real
 * download-progress bar. Once the full image finishes downloading, it
 * replaces the thumbnail and the bar disappears - the same swap-and-vanish
 * `AuthImage` already did, just with visible feedback in between instead of
 * a blank placeholder.
 *
 * The thumbnail is mapped to the *same area* the "web"-tier picture will
 * occupy (16/09/2026, corrected from an initial version that instead
 * zoomed/cropped it to fill the whole frame edge-to-edge) - `app.css`'s
 * `.progressive-image-thumbnail` uses `object-fit: contain`, exactly like
 * the final `.visible-image` itself, not `cover`. This only lines up because
 * the thumbnail and the "web" tier are both aspect-ratio-preserving
 * derivatives of the same original (Thumbnailator bounds an image to fit a
 * box, it never crops one to fill it) - so both are letterboxed identically
 * within the frame, and the placeholder-to-real swap doesn't visibly
 * "zoom out" the moment it happens.
 *
 * The percentage is real, not simulated: `GalleryController` now sets a real
 * `Content-Length` on every image response (10/09/2026 - previously absent
 * entirely, since an arbitrary `InputStream` has no length Spring can infer
 * on its own, so every one of these responses went out chunked). Reading the
 * body via `ReadableStream` chunk-by-chunk (rather than the single
 * `response.blob()` call `AuthImage` uses) is what makes the running total
 * available to report as it arrives, not just the size once fully done. If
 * `Content-Length` is ever missing (an intermediary stripping it, or an
 * endpoint that doesn't set it), `total` stays 0 and the bar falls back to
 * an indeterminate sweep instead of a stalled 0%.
 */
export function ProgressiveImage({ src, thumbnailSrc, alt, className, onClick, onDoubleClick, onLoad }) {
	const [finalUrl, setFinalUrl] = useState(null);
	// null = indeterminate (no usable Content-Length yet/at all), otherwise 0-100.
	const [progress, setProgress] = useState(null);

	useEffect(() => {
		let currentUrl = null;
		// Same real-cancellation reasoning as AuthImage's own controller -
		// this component unmounts on every navigation away from the image
		// currently mid-download, and a plain flag would leave the fetch
		// (and, on a slow connection, several more seconds of wasted
		// bandwidth) running for a picture nobody's looking at anymore.
		const controller = new AbortController();
		setFinalUrl(null);
		setProgress(null);

		async function loadWithProgress() {
			const response = await fetch(src, {
				credentials: 'same-origin',
				signal: controller.signal,
			});
			if (!response.ok || !response.body) {
				throw new Error(`Failed to load image: ${response.status}`);
			}
			const totalHeader = response.headers.get('content-length');
			const total = totalHeader ? Number.parseInt(totalHeader, 10) : 0;
			const reader = response.body.getReader();
			const chunks = [];
			let received = 0;
			for (;;) {
				const { done, value } = await reader.read();
				if (done) break;
				chunks.push(value);
				received += value.length;
				// Capped at 99 while still receiving - 100 is reserved for
				// "the blob is actually ready to show" below, so the bar
				// never briefly claims completion a render or two before
				// the swap itself happens.
				setProgress(total > 0 ? Math.min(99, Math.round((received / total) * 100)) : null);
			}
			currentUrl = URL.createObjectURL(new Blob(chunks));
			setProgress(100);
			setFinalUrl(currentUrl);
		}

		async function loadWithoutProgress() {
			// Very old browser (no streaming `fetch` body) - same plain
			// blob fetch AuthImage always uses, just with a still-visible
			// (indeterminate) bar rather than none at all.
			const response = await fetch(src, { credentials: 'same-origin', signal: controller.signal });
			if (!response.ok) throw new Error(`Failed to load image: ${response.status}`);
			const blob = await response.blob();
			currentUrl = URL.createObjectURL(blob);
			setFinalUrl(currentUrl);
		}

		const canStream = typeof ReadableStream !== 'undefined' && !!window.fetch;
		(canStream ? loadWithProgress() : loadWithoutProgress()).catch(() => {
			// Swallowed on purpose, same as AuthImage (including AbortError
			// from the cleanup below) - a failed load just leaves the
			// zoomed thumbnail + bar showing instead of crashing the viewer.
		});

		return () => {
			controller.abort();
			if (currentUrl) URL.revokeObjectURL(currentUrl);
		};
	}, [src]);

	if (finalUrl) {
		return <img src={finalUrl} alt={alt} className={className} onClick={onClick} onDoubleClick={onDoubleClick} onLoad={onLoad} />;
	}

	return (
		<div className={`progressive-image ${className ?? ''}`} onClick={onClick} onDoubleClick={onDoubleClick}>
			{thumbnailSrc && <AuthImage src={thumbnailSrc} alt="" className="progressive-image-thumbnail" />}
			<div className="progressive-image-progress" aria-hidden="true">
				<div
					className={progress == null ? 'progressive-image-progress-bar progressive-image-progress-indeterminate' : 'progressive-image-progress-bar'}
					style={progress == null ? undefined : { width: `${progress}%` }}
				/>
			</div>
		</div>
	);
}
