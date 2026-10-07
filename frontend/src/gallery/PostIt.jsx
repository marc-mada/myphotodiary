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

/**
 * The comment "post-it" - Design.md decision #8 names this specifically
 * alongside the filmstrip as visual identity to preserve. Same yellow
 * sticky-note styling as idx.css's div.postit (background #fefabc, cursive
 * font, rotated corners, drop shadow) - see app.css.
 *
 * Two stacked areas, sequence comment first then the current picture's own
 * comment - the same order and structure as index.jsp's `#dirDesc`/
 * `#imgDesc` inside `.postit`, each wrapped in its own link. Neither area
 * edits inline any more: clicking one opens the corresponding popup
 * (dirEditPopup/imageEditPopup equivalents) instead, so the always-visible
 * surface here is just the two comments, not a form - a direct ask to keep
 * as much of the frame free for the image itself.
 *
 * Desktop visibility state machine (`fadeDelayMs`/`resetKey`, both optional
 * - explicit ask, 28/08/2026, revised the same day from an earlier
 * always-visible-until-the-delay version to this hidden-by-default one):
 *
 * - Hidden by default - not shown at all until the cursor actually reaches
 *   it, unlike the previous version (which started visible on every new
 *   image and only faded after the delay).
 * - Hovering reveals it *only* for as long as the cursor stays over it -
 *   genuinely temporary, not delayed: moving away hides it again
 *   immediately (the CSS opacity transition still takes its own 0.6s to
 *   visually complete, but nothing waits before starting it).
 * - Clicking anywhere on it "pins" it visible, surviving the cursor moving
 *   away - `fadeDelayMs` only starts counting down once the cursor actually
 *   leaves *after* a pin (not from the click itself, so it isn't racing a
 *   still-hovering cursor) - "persistently visible", but not forever.
 *   Reaching zero un-pins it, back to hidden.
 * - `resetKey` (ImageViewer passes the current image's id) clears both
 *   hover and pin state on every navigation, so a freshly-shown picture's
 *   post-it always starts hidden, never carrying over a previous pin.
 * - `MobilePostIt` passes neither prop - `fadeDelayMs == null` keeps this
 *   whole state machine inert and the post-it permanently visible whenever
 *   mounted, exactly like before; mobile's own separate show/hide/auto-hide
 *   timing (a hard unmount on a fixed timer, not any of this) is unrelated.
 */
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

export function PostIt({ sequenceDescription, imageDescription, onOpenSequence, onOpenImage, style, fadeDelayMs, resetKey, revealKey }) {
	const { t } = useTranslation();
	const [hovering, setHovering] = useState(false);
	const [pinned, setPinned] = useState(false);
	const pinFadeTimerRef = useRef(null);

	const managed = fadeDelayMs != null;
	const visible = !managed || hovering || pinned;

	function clearPinFadeTimer() {
		clearTimeout(pinFadeTimerRef.current);
	}

	useEffect(() => {
		setHovering(false);
		setPinned(false);
		clearPinFadeTimer();
		return clearPinFadeTimer;
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [fadeDelayMs, resetKey]);

	// Revealed from outside (27/09/2026 - the Gallery toolbar's Comment
	// button, ImageViewer): each new `revealKey` value shows the post-it
	// like a hover would, then fades it after the usual `fadeDelayMs` -
	// unless the cursor moves onto it meanwhile, which keeps it visible
	// (and pins it on click) exactly as before. Skipped on mount (0).
	useEffect(() => {
		if (!managed || !revealKey) return;
		setPinned(true);
		clearPinFadeTimer();
		pinFadeTimerRef.current = setTimeout(() => setPinned(false), fadeDelayMs);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [revealKey]);

	function handleMouseEnter() {
		if (!managed) return;
		setHovering(true);
		// Actively hovering shouldn't fade out from under the cursor, even
		// if a pin's own countdown was already running.
		clearPinFadeTimer();
	}

	function handleMouseLeave() {
		if (!managed) return;
		setHovering(false);
		if (pinned) {
			clearPinFadeTimer();
			pinFadeTimerRef.current = setTimeout(() => setPinned(false), fadeDelayMs);
		}
	}

	function handleClick() {
		if (!managed) return;
		setPinned(true);
		// The countdown only starts once the cursor leaves (handleMouseLeave)
		// - clearing here just discards any leftover timer from a previous
		// pin, so a re-click always gets a fresh, full window once it's left.
		clearPinFadeTimer();
	}

	return (
		<div
			className={`postit${visible ? ' visible' : ''}`}
			style={style}
			onMouseEnter={handleMouseEnter}
			onMouseLeave={handleMouseLeave}
			onClick={handleClick}
		>
			<div className="postitarea" onClick={onOpenSequence} title={t('postit.describeSequence')}>
				<hr />
				{sequenceDescription ? sequenceDescription : <em>{t('postit.describeSequence')}</em>}
			</div>
			<div className="postitarea" onClick={onOpenImage} title={t('postit.describePicture')}>
				<hr />
				{imageDescription ? imageDescription : <em>{t('postit.describePicture')}</em>}
			</div>
		</div>
	);
}
