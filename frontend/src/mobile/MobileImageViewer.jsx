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

import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AuthImage } from '../gallery/AuthImage';
import { AuthVideo } from '../gallery/AuthVideo';
import { ProgressiveImage } from '../gallery/ProgressiveImage';
import { useDoubleTap } from './useDoubleTap';
import { useBlockNativePageZoom } from './useBlockNativePageZoom';

// Legacy (idx.js/midx.js) doesn't expose a configurable swipe threshold -
// jQuery Mobile's own swipeleft/swiperight events use a 30px default, kept
// here for the same feel. A touch that moves less than this is a tap, not
// a swipe attempt.
const SWIPE_THRESHOLD_PX = 30;

// Same cap as the native pinch-zoom this replaces (index.html's viewport
// meta, maximum-scale=2.0, itself copied from legacy mindex.jsp).
const MAX_ZOOM = 2;
// Below this, a pinch that ends barely zoomed snaps back to exactly 1x.
const MIN_ZOOM_SNAP = 1.02;

function midpoint(touches) {
	return { x: (touches[0].clientX + touches[1].clientX) / 2, y: (touches[0].clientY + touches[1].clientY) / 2 };
}

function distance(touches) {
	return Math.hypot(touches[1].clientX - touches[0].clientX, touches[1].clientY - touches[0].clientY);
}

/**
 * The mobile `.photoframe` (mindex.jsp/midx.js's `mobImageSwapper`) - swipe
 * left/right between images, nothing else. Deliberately missing every
 * control the desktop `ImageViewer` has: no slideshow, no full-screen, no
 * delete, no keyboard shortcuts. That isn't an oversight - `mobImageSwapper`
 * is never wired to those selectors in the legacy source at all (it only
 * binds swipeleft/swiperight), confirmed while auditing midx.js rather than
 * assumed from the UI screenshots (Design.md §15, legacy feature-parity
 * audit: "not a legacy gap to fill - a deliberate choice already made by
 * the legacy author").
 *
 * No hand-rolled orientation/letterboxing math either (legacy's own
 * `sizeImage`, ported faithfully to desktop's controls-bar positioning) -
 * `object-fit: contain` in CSS does the same visual job for a frame with no
 * overlaid controls to align against.
 *
 * Swipe detection is a plain touch-delta check, not a gesture library -
 * consistent with this project's "rewrite jQuery widgets as plain
 * components" rule (decision #8's corollary) extended to jQuery Mobile's
 * own swipe binding, and simple enough not to warrant a dependency. The
 * same touch-end handler also detects a plain tap (a touch that never moved
 * past the swipe threshold) - this is the same physical gesture jQuery
 * Mobile's own `data-tap-toggle="true"` fullscreen header/footer respond to
 * on the legacy home page (mindex.jsp l.78/121): hidden by default, tapping
 * the content reveals them, freeing the small screen for the picture rather
 * than permanent chrome (real feedback from testing on a handset).
 *
 * `onTap` itself now only fires on a *double* tap, not every plain tap
 * (09/09/2026, explicit ask): single tap on this screen used to double as
 * both "toggle the header/footer chrome" and (via MobileApp's own
 * `onTap`/`onPrevious`/`onNext` wrappers) "close an open side panel" - the
 * exact same physical gesture the mosaic screen (MobileMosaicView) uses,
 * unambiguously, to select a thumbnail. Requiring two taps here removes
 * that overlap rather than trying to disambiguate by context. `onTap`
 * itself is untouched otherwise - still whatever MobileApp's `onTap` prop
 * does today (chrome toggle, or closing a side panel first) - only the
 * gesture that triggers it changed, so closing a panel by tapping the
 * photo now also takes two taps; swiping left/right still closes it in one
 * (MobileApp's own `onPrevious`/`onNext` wrappers are untouched by this).
 * Detected via the shared `useDoubleTap` hook (also used by
 * MobileMosaicView, for the same chrome-toggle gesture there - see its own
 * doc for why double-tap, unlike single-tap, means the same thing on both
 * screens).
 *
 * Native double-tap-to-zoom no longer competes with this double-tap: page
 * zoom is blocked on this screen since 08/10/2026 (see "Zoom" below).
 *
 * `nextImage` (28/08/2026, explicit ask - "have you ported the legacy
 * next-image pre-fetch?" - answer at the time was desktop only) ports
 * `midx.js`'s own `mobImageSwapper.swap()`'s "preload next image": a
 * hidden `AuthImage` for `nextImage`, so swiping forward feels instant.
 * Same `.preload-image` (display:none) treatment as desktop's ImageViewer,
 * reusing that CSS rather than duplicating it. Never preloaded for a video
 * (mediaType check below), same reasoning as desktop's own ImageViewer -
 * silently downloading a whole unwatched video is wasteful in a way
 * preloading a lightweight photo isn't.
 *
 * Video (28/08/2026, "have a talk about a new topic not in the legacy") -
 * `image.mediaType === 'VIDEO'` swaps `AuthImage` for `AuthVideo` (native
 * `<video controls>`, same component desktop's ImageViewer uses). Known,
 * not-yet-resolved tension left as-is rather than guessed at: this frame's
 * own swipe-left/right gesture handling lives on the *wrapping* div, so a
 * touch that starts on the video's own native scrubber controls can
 * plausibly still be interpreted as a swipe attempt - no real device
 * testing has confirmed either way yet, unlike everything else in this
 * component.
 *
 * `ImageComponent` (02/09/2026, MobileShareScreen - same reasoning as
 * desktop's Filmstrip/ImageViewer) - defaults to `AuthImage`, unchanged for
 * every existing caller. No equivalent swap for `AuthVideo`: video is
 * excluded from public share links entirely (ShareController's own
 * PublicSequenceResponse never lists one), so this component never renders
 * one in a share context to begin with.
 *
 * Progressive loading (10/09/2026, explicit ask - slow-network image
 * loading, same mechanism/reasoning as desktop's `ImageViewer` - see its own
 * doc comment) - gated the same way, on `ImageComponent === AuthImage`, so
 * `MobileShareScreen`'s `ImageComponent={PublicImage}` (rendered outside
 * `<AuthProvider>` entirely, same as the desktop share viewer) never reaches
 * `ProgressiveImage`'s own `useAuth()` call. This screen has no crossfade to
 * coordinate with (no equivalent of desktop's outgoing layer/next-image
 * preload distinction) - the single visible image either is or isn't the
 * progressive one.
 *
 * `onPinchOutAtRatioOne` (09/09/2026, mosaic screen - explicit ask):
 * pinching out (two fingers moving together) switches to the mosaic screen
 * (MobileMosaicView, MobileApp wires the callback), but *only* when the
 * picture isn't zoomed in - pinching out while zoomed in just zooms back out.
 *
 * Zoom (08/10/2026, explicit ask - "allow to zoom in the image even when the
 * smartphone is in landscape/full-screen mode" and "magnify only the image,
 * not the control bars which should not move"): this component now zooms the
 * picture itself, instead of relying on the browser's whole-page pinch-zoom
 * (index.html's viewport meta). The native zoom magnified and shifted the
 * overlaid Menu/Quit/Go/Comment bars together with the picture, and Chrome
 * doesn't offer page pinch-zoom at all in element full-screen (which
 * `useLandscapeFullscreen` enters in landscape) - hence the report. Page
 * zoom is now blocked on this screen (`useBlockNativePageZoom`, and
 * `touch-action` in app.css) and the picture is scaled with a CSS transform
 * on `.mobile-zoom-layer`:
 * - two fingers: zoom (1x..MAX_ZOOM, same 2x cap as the old viewport meta)
 *   around the point between the fingers, which follows the fingers;
 * - one finger while zoomed: pan, clamped so the picture's edges never come
 *   inside the frame (no panning into black). Once the picture can't move
 *   any further left (resp. right), the *next* swipe left (resp. right)
 *   shows the next (resp. previous) picture at 1x (10/10/2026 trial);
 * - a tap still counts toward the double-tap that toggles the bars;
 * - zoom resets to 1x on every picture change and on resize/rotation.
 * Videos aren't zoomable (their native controls need the touches).
 */
export function MobileImageViewer({
	image,
	hasPrevious,
	hasNext,
	onPrevious,
	onNext,
	onTap,
	onPinchOutAtRatioOne,
	emptyMessage,
	nextImage,
	ImageComponent = AuthImage,
}) {
	const { t } = useTranslation();
	// See this component's own doc comment on progressive loading for why
	// this is gated on the default AuthImage specifically.
	const progressive = ImageComponent === AuthImage;
	const zoomable = !!image && image.mediaType !== 'VIDEO';
	const frameRef = useRef(null);
	const layerRef = useRef(null);
	// Current transform, kept in a ref and written straight to the layer's
	// style on every touchmove - no React re-render per finger movement.
	const zoom = useRef({ scale: 1, x: 0, y: 0 });
	// The in-progress zoom/pan gesture, if any (null while swiping at 1x).
	const gesture = useRef(null);
	// Only drives `touch-action` (app.css): at 1x the frame keeps `pan-y`
	// (pull-to-refresh still works), zoomed it becomes `none` so a one-finger
	// pan is never taken over by the browser.
	const [zoomed, setZoomed] = useState(false);
	const touchStartX = useRef(null);
	const registerTap = useDoubleTap(onTap);
	useBlockNativePageZoom();

	const applyZoom = useCallback(() => {
		const { scale, x, y } = zoom.current;
		if (layerRef.current) {
			layerRef.current.style.transform = scale === 1 ? '' : `translate(${x}px, ${y}px) scale(${scale})`;
		}
	}, []);

	const resetZoom = useCallback(() => {
		zoom.current = { scale: 1, x: 0, y: 0 };
		gesture.current = null;
		applyZoom();
		setZoomed(false);
	}, [applyZoom]);

	useEffect(() => {
		resetZoom();
	}, [image?.id, resetZoom]);

	useEffect(() => {
		window.addEventListener('resize', resetZoom);
		return () => window.removeEventListener('resize', resetZoom);
	}, [resetZoom]);

	// Keeps the picture covering the frame wherever it's larger than it:
	// the translation is limited to the overflow on each axis, measured from
	// the picture's own untransformed size (offsetWidth/Height ignore the
	// CSS transform), so the black letterbox bars are never panned into view.
	function translationLimits() {
		const frame = frameRef.current;
		if (!frame) return { maxX: 0, maxY: 0 };
		const media = layerRef.current?.querySelector('.mobile-visible-image');
		const contentWidth = media?.offsetWidth || frame.clientWidth;
		const contentHeight = media?.offsetHeight || frame.clientHeight;
		const { scale } = zoom.current;
		return {
			maxX: Math.max(0, (contentWidth * scale - frame.clientWidth) / 2),
			maxY: Math.max(0, (contentHeight * scale - frame.clientHeight) / 2),
		};
	}

	function clampTranslation() {
		if (!frameRef.current) return;
		const { maxX, maxY } = translationLimits();
		zoom.current.x = Math.min(maxX, Math.max(-maxX, zoom.current.x));
		zoom.current.y = Math.min(maxY, Math.max(-maxY, zoom.current.y));
	}

	// A point on screen, relative to the frame's centre - the layer's
	// transform-origin, so `screen = translate + scale * content`.
	function fromFrameCentre(point) {
		const rect = frameRef.current.getBoundingClientRect();
		return { x: point.x - (rect.left + rect.width / 2), y: point.y - (rect.top + rect.height / 2) };
	}

	function startPinch(touches) {
		const { scale, x, y } = zoom.current;
		const m = fromFrameCentre(midpoint(touches));
		gesture.current = {
			mode: 'pinch',
			startDistance: distance(touches),
			startScale: scale,
			// The content point under the fingers, which stays under them.
			contentX: (m.x - x) / scale,
			contentY: (m.y - y) / scale,
			startedAtOne: scale === 1,
			lastDistance: distance(touches),
		};
	}

	function startPan(touch) {
		// Whether the picture's right/left edge was already against the
		// frame when this drag began: a swipe that can't move the picture
		// any further that way goes to the next/previous picture instead.
		const { maxX } = translationLimits();
		const { x } = zoom.current;
		gesture.current = {
			mode: 'pan',
			lastX: touch.clientX,
			lastY: touch.clientY,
			startX: touch.clientX,
			startY: touch.clientY,
			moved: false,
			rightEdgeShown: x <= -maxX + 0.5,
			leftEdgeShown: x >= maxX - 0.5,
		};
	}

	function handleTouchStart(e) {
		if (e.touches.length >= 2) {
			touchStartX.current = null;
			if (zoomable) startPinch(e.touches);
			else gesture.current = { mode: 'pinch', startDistance: distance(e.touches), lastDistance: distance(e.touches), startedAtOne: true };
			return;
		}
		if (zoomable && zoom.current.scale > 1) {
			touchStartX.current = null;
			startPan(e.touches[0]);
			return;
		}
		touchStartX.current = e.touches[0].clientX;
	}

	function handleTouchMove(e) {
		const g = gesture.current;
		if (!g) return;
		if (g.mode === 'pinch' && e.touches.length >= 2) {
			g.lastDistance = distance(e.touches);
			if (!zoomable) return;
			const scale = Math.min(MAX_ZOOM, Math.max(1, (g.startScale * g.lastDistance) / g.startDistance));
			const m = fromFrameCentre(midpoint(e.touches));
			zoom.current = { scale, x: m.x - scale * g.contentX, y: m.y - scale * g.contentY };
			clampTranslation();
			applyZoom();
		} else if (g.mode === 'pan' && e.touches.length === 1) {
			const touch = e.touches[0];
			zoom.current.x += touch.clientX - g.lastX;
			zoom.current.y += touch.clientY - g.lastY;
			g.lastX = touch.clientX;
			g.lastY = touch.clientY;
			if (Math.hypot(touch.clientX - g.startX, touch.clientY - g.startY) >= SWIPE_THRESHOLD_PX) g.moved = true;
			clampTranslation();
			applyZoom();
		}
	}

	function endPinch(g) {
		const delta = g.lastDistance - g.startDistance;
		if (zoom.current.scale < MIN_ZOOM_SNAP) {
			resetZoom();
		} else {
			setZoomed(true);
		}
		// Same 40px threshold as usePinchGesture (used by MobileMosaicView) - fingers
		// coming together, starting from 1x, is the "go to mosaic" gesture.
		if (g.startedAtOne && delta <= -40) onPinchOutAtRatioOne?.();
	}

	function handleTouchEnd(e) {
		const g = gesture.current;
		if (g?.mode === 'pinch') {
			if (e.touches.length >= 2) return; // a third finger lifted
			gesture.current = null;
			endPinch(g);
			// One finger still down after a pinch: carry on as a pan, rather
			// than letting that finger start a swipe to another picture.
			if (e.touches.length === 1 && zoomable && zoom.current.scale > 1) startPan(e.touches[0]);
			return;
		}
		if (g?.mode === 'pan') {
			if (e.touches.length > 0) return;
			gesture.current = null;
			if (!g.moved) {
				registerTap();
				return;
			}
			const dx = g.lastX - g.startX;
			const horizontal = Math.abs(dx) >= SWIPE_THRESHOLD_PX && Math.abs(dx) > Math.abs(g.lastY - g.startY);
			// Picture changes reset the zoom to 1x (effect on image.id).
			if (horizontal && dx < 0 && g.rightEdgeShown && hasNext) onNext();
			else if (horizontal && dx > 0 && g.leftEdgeShown && hasPrevious) onPrevious();
			return;
		}
		if (touchStartX.current == null) return;
		const deltaX = e.changedTouches[0].clientX - touchStartX.current;
		touchStartX.current = null;
		if (deltaX <= -SWIPE_THRESHOLD_PX && hasNext) {
			onNext();
		} else if (deltaX >= SWIPE_THRESHOLD_PX && hasPrevious) {
			onPrevious();
		} else {
			registerTap();
		}
	}

	if (!image) {
		return (
			<div
				ref={frameRef}
				className="mobile-photoframe mobile-photoframe-empty"
				onTouchStart={handleTouchStart}
				onTouchMove={handleTouchMove}
				onTouchEnd={handleTouchEnd}
			>
				<p>{emptyMessage ?? t('mobile.noPicturesYet')}</p>
			</div>
		);
	}

	return (
		<div
			ref={frameRef}
			className={`mobile-photoframe${zoomed ? ' zoomed' : ''}`}
			onTouchStart={handleTouchStart}
			onTouchMove={handleTouchMove}
			onTouchEnd={handleTouchEnd}
		>
			<div ref={layerRef} className="mobile-zoom-layer">
				{image.mediaType === 'VIDEO' ? (
					<AuthVideo imageId={image.id} className="mobile-visible-image" />
				) : progressive ? (
					<ProgressiveImage src={image.webUrl} thumbnailSrc={image.thumbnailUrl} alt={image.name} className="mobile-visible-image" />
				) : (
					<ImageComponent src={image.webUrl} alt={image.name} className="mobile-visible-image" />
				)}
			</div>
			{nextImage && nextImage.mediaType !== 'VIDEO' && <ImageComponent key={nextImage.id} src={nextImage.webUrl} alt="" className="preload-image" />}
		</div>
	);
}
