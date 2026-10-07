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

import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { AuthImage } from '../gallery/AuthImage';
import { AuthVideo } from '../gallery/AuthVideo';
import { ProgressiveImage } from '../gallery/ProgressiveImage';
import { usePinchGesture } from './usePinchGesture';
import { useDoubleTap } from './useDoubleTap';

// Legacy (idx.js/midx.js) doesn't expose a configurable swipe threshold -
// jQuery Mobile's own swipeleft/swiperight events use a 30px default, kept
// here for the same feel. A touch that moves less than this is a tap, not
// a swipe attempt.
const SWIPE_THRESHOLD_PX = 30;

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
 * One real platform wrinkle, not verifiable in this project's own headless
 * environment (no real device/browser double-tap heuristic to observe):
 * some mobile browsers treat a double-tap on a page that permits zooming
 * (index.html's own viewport meta, `user-scalable=yes`) as its own native
 * "zoom to this point" gesture, independently of this component's touch
 * handling below (which never calls `preventDefault`, on purpose - see
 * `usePinchGesture`'s own doc). On such a browser a double-tap here could
 * plausibly *also* trigger a brief native zoom alongside the chrome toggle
 * this component asked for - not reproduced or ruled out here, flagged
 * rather than silently assumed away.
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
 * pinching out (two fingers moving together) switches to the new mosaic
 * screen (MobileMosaicView, MobileApp wires the callback), but *only* when
 * the page isn't already zoomed in - pinching out while zoomed in (ratio >
 * 1) must keep behaving exactly like v2.3: nothing to do here at all, the
 * native browser pinch-zoom (index.html's viewport meta, minimum-scale=1/
 * maximum-scale=2, unchanged) simply zooms the picture back out on its own.
 * `window.visualViewport.scale` is the live magnification ratio the browser
 * is actually rendering at - read fresh at the moment a pinch gesture
 * completes rather than mirrored into React state anywhere, since it can
 * also change from zoom activity this component never sees a touch event
 * for (e.g. a gesture that started before this screen mounted). Falls back
 * to "assume ratio 1" if `visualViewport` isn't available at all (very old
 * browsers) - the more useful of the two possible guesses, given every
 * device this feature actually targets already relies on it implicitly via
 * the viewport meta tag's own min/max-scale. Pinching *in* is left
 * completely alone here (no `onPinchIn` passed to the hook below) - that's
 * the native zoom-in gesture, also unchanged from v2.3.
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
	const touchStartX = useRef(null);
	const registerTap = useDoubleTap(onTap);

	const pinch = usePinchGesture({
		onPinchOut: () => {
			const ratio = window.visualViewport?.scale ?? 1;
			if (ratio <= 1.01) onPinchOutAtRatioOne?.();
		},
	});

	function handleTouchStart(e) {
		if (pinch.onTouchStart(e)) {
			touchStartX.current = null;
			return;
		}
		touchStartX.current = e.touches[0].clientX;
	}

	function handleTouchMove(e) {
		pinch.onTouchMove(e);
	}

	function handleTouchEnd(e) {
		if (pinch.onTouchEnd(e)) {
			touchStartX.current = null;
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
		<div className="mobile-photoframe" onTouchStart={handleTouchStart} onTouchMove={handleTouchMove} onTouchEnd={handleTouchEnd}>
			{image.mediaType === 'VIDEO' ? (
				<AuthVideo imageId={image.id} className="mobile-visible-image" />
			) : progressive ? (
				<ProgressiveImage src={image.webUrl} thumbnailSrc={image.thumbnailUrl} alt={image.name} className="mobile-visible-image" />
			) : (
				<ImageComponent src={image.webUrl} alt={image.name} className="mobile-visible-image" />
			)}
			{nextImage && nextImage.mediaType !== 'VIDEO' && <ImageComponent key={nextImage.id} src={nextImage.webUrl} alt="" className="preload-image" />}
		</div>
	);
}
