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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AuthImage } from '../gallery/AuthImage';
import { usePinchGesture } from './usePinchGesture';
import { useDoubleTap } from './useDoubleTap';

// 4px margins, explicit ask - both between images in a row and between rows.
const GAP_PX = 4;
// Rows aren't a fixed count - they're derived from how much vertical space is
// actually available (explicit ask: "the number of lines... adjusted to make
// the thumbnails visible... if the screen is vertical it can contain more
// rows than if it is horizontal"). Driving the row count off the *measured*
// available height, rather than a hardcoded portrait/landscape pair of
// numbers, gets that for free: rotating the device changes the available
// height, which changes how many rows of ~this target height fit, with no
// separate orientation check needed - and it scales sensibly across phone
// and tablet screens alike, not just the two orientations of one device.
const TARGET_ROW_HEIGHT_PX = 110;
const MIN_ROWS_PER_PAGE = 2;
const MAX_ROWS_PER_PAGE = 8;
// Assumed width/height ratio for an image whose thumbnail hasn't finished
// loading yet (so its *real* aspect ratio isn't known - ImageResponse has no
// width/height field at all, a known gap, Design.md §15). A fairly
// ordinary landscape-photo ratio, close enough that most thumbnails don't
// visibly reflow once the real ratio replaces it; portrait photos correct
// themselves the moment they load, same as every other thumbnail.
const DEFAULT_ASPECT = 1.5;
// Same feel as MobileImageViewer's own one-finger swipe threshold.
const SWIPE_THRESHOLD_PX = 30;

// Greedy row packing: images are added to the current row, at a shared
// `rowHeight` and each one's own real (or, until loaded, assumed) aspect
// ratio, until the next one wouldn't fit within `availableWidth` - then a
// new row starts. Deliberately not a "justified" layout that stretches each
// row's images to fill the width exactly (which would need reflowing every
// row every time a single thumbnail's real aspect ratio arrives) - a plain
// left-to-right flow already satisfies "same height, 4px margins" without
// that extra churn, and leaves a small, harmless gap at the end of most rows
// instead. Rows are then grouped into fixed-size pages of `rowsPerPage` rows
// each - what MobileMosaicView pages left/right between.
function packIntoPages(images, aspectRatios, availableWidth, rowHeight, rowsPerPage) {
	if (availableWidth <= 0 || rowHeight <= 0 || images.length === 0) return [];
	const rows = [[]];
	let currentRowWidth = 0;
	for (const image of images) {
		const aspect = aspectRatios.get(image.id) ?? DEFAULT_ASPECT;
		// Clamped so one unusually wide panorama can't itself exceed the page
		// width and break the "one page = one screen" assumption below.
		const width = Math.min(rowHeight * aspect, availableWidth);
		const row = rows[rows.length - 1];
		const widthWithGap = row.length === 0 ? width : currentRowWidth + GAP_PX + width;
		if (row.length > 0 && widthWithGap > availableWidth) {
			rows.push([{ image, width }]);
			currentRowWidth = width;
		} else {
			row.push({ image, width });
			currentRowWidth = widthWithGap;
		}
	}
	const pages = [];
	for (let i = 0; i < rows.length; i += rowsPerPage) {
		pages.push(rows.slice(i, i + rowsPerPage));
	}
	return pages;
}

/**
 * The mosaic screen (09/09/2026, explicit ask) - a second way to browse the
 * current sequence, alongside MobileImageViewer's existing one-image-at-a-
 * time screen. No legacy counterpart to port (mindex.jsp never had a grid
 * view at all) - a genuinely new screen, built to the same conventions as
 * the rest of this project's mobile UI (plain touch-delta gesture detection,
 * no library; AuthImage for authenticated thumbnail bytes) rather than
 * anything copied from elsewhere.
 *
 * Reached from MobileImageViewer by pinching out at magnification ratio 1
 * (MobileApp wires that up), left again by pinching in here (`onPinchIn`) -
 * see usePinchGesture's own doc for why the gesture detection lives in a
 * shared hook. Still "the current sequence's images", same `images`/
 * `currentId` MobileApp already tracks for the single-image screen - a tap
 * on another thumbnail here just moves which one is current (`onSelect`),
 * exactly like MobileImageViewer's swipe does for the single-image screen;
 * it does *not* also switch back to the single-image screen (explicit ask:
 * "the current image... the white frame moves to it", nothing about
 * leaving the mosaic).
 *
 * No image-dimension metadata exists anywhere in this app (ImageResponse
 * has no width/height field - Design.md §15 already flags this as a
 * known gap), so each thumbnail's real aspect ratio is only known once its
 * own `<img>` has actually loaded and reports `naturalWidth`/`naturalHeight`
 * (`handleThumbLoad` below) - until then `DEFAULT_ASPECT` stands in, and the
 * page layout gets recomputed (a small, expected reflow) as real ratios
 * arrive. Server-side dimensions would remove this whole "provisional then
 * corrected" step, but adding that field is a separate change, out of scope
 * here (and not something a mobile-UI-only ask should reach into).
 *
 * Two things compound that reflow into real instability on a large sequence
 * (>100 images, reported live 10/09/2026 - "thumbnails and rectangular
 * frame are blinking and moving around till all the thumbnails have been
 * loaded... the more pages, the more instability"), both fixed the same
 * day:
 *
 * (1) Every page used to mount unconditionally - `pages.map` rendered an
 * `AuthImage` (a real authenticated fetch on mount) for *every* thumbnail
 * on *every* page at once, not just the one currently showing. For a
 * 100+-image sequence that's 100+ concurrent fetches racing the instant the
 * mosaic opens, each one's `onLoad` landing at an unpredictable time -
 * exactly the same class of bug already found and fixed once before in this
 * project, for the desktop Filmstrip (31/08/2026 - 10-20 s of latency, each
 * thumbnail triggering its own fetch), just not yet
 * ported to this newer screen. Fixed the same way: `visibleImageIds` (a
 * `Set`, only ever grows - once a thumbnail has loaded, swiping away and
 * back must not re-fetch it) gates which thumbnails actually mount an
 * `AuthImage` versus a plain placeholder `<div>` - populated from whichever
 * pages are within one page of `pageIndex` (current ± 1, "load a little
 * ahead" for a smooth swipe, the same spirit as Filmstrip's own
 * `rootMargin`), not the *individual*-thumbnail `IntersectionObserver`
 * Filmstrip itself uses - pages here are already discrete, fixed-size units
 * switched by a deliberate swipe, so "which page(s) are relevant" is a
 * cheap arithmetic range check, no observer needed.
 *
 * (2) Even with (1) capping how many thumbnails load at once, each one's
 * `onLoad` firing `setAspectRatios` synchronously still meant one full
 * repack (`packIntoPages` over the *entire* `images` array, not just the
 * loading thumbnail's own page) per image - a dozen-ish thumbnails on a
 * freshly-shown page finishing within milliseconds of each other still
 * repacked a dozen-ish times in a row, each one a visible nudge. Fixed by
 * batching: `handleThumbLoad` no longer calls `setAspectRatios` directly,
 * it only records into `pendingAspectUpdates` (a plain `Map`, not state -
 * mutating it triggers no render by itself) and arms a single
 * `requestAnimationFrame` flush; further `onLoad` calls before that frame
 * fires just add to the same pending batch instead of arming a second one.
 * Everything that finished loading within one frame lands in exactly one
 * `setAspectRatios` call - one repack, not N.
 *
 * "Swipe left/right to scan all thumbnails" (explicit ask) is real paging
 * between fixed-size, fixed-row-count pages - not a taller-than-the-screen
 * grid that happens to also scroll sideways. Same one-finger touch-delta
 * technique as MobileImageViewer's own swipe, translating a `flex` track by
 * whole page widths (same CSS technique already used for the side panels,
 * MobileSlidePanel).
 *
 * A page whose row count falls short of `rowsPerPage` (a short sequence, or
 * simply the last page of one that doesn't divide evenly) has its block of
 * rows vertically centered within the screen rather than left pinned to the
 * top edge with empty space below (explicit ask, 09/09/2026) - handled
 * entirely in CSS (`.mobile-mosaic-page`'s own `justify-content: center`),
 * nothing computed here: a page with the full `rowsPerPage` rows already
 * adds up to exactly this element's own height (the same math `rowHeight`
 * itself comes from), so centering it has nothing left to distribute and
 * behaves identically to being pinned to the top - one CSS rule covers both
 * cases the ask describes without a separate "is this page full?" branch.
 *

 * `onDoubleTap` (09/09/2026, explicit ask - "double tap works good in the
 * single-image screen but does not work in the mosaic screen"): a single
 * tap here is already fully spoken for, unambiguously (selecting a
 * thumbnail, via each thumbnail's own `onClick` below) - but the
 * header/footer chrome still needs a way to be revealed on this screen too,
 * the same double-tap gesture MobileImageViewer already uses for exactly
 * that (shared timing logic, `useDoubleTap`). A tap that lands on a
 * thumbnail still fires that thumbnail's own `onClick` regardless of
 * whether it turns out to be part of a double-tap - both taps of a
 * double-tap on (or near) the same thumbnail just select it twice in a row
 * (harmless, idempotent), so double-tapping a thumbnail both selects it
 * *and* toggles the chrome, which reads as one sensible outcome rather than
 * a conflict; nothing here tries to suppress the click to prevent that.
 */
export function MobileMosaicView({ images, currentId, onSelect, onPinchIn, onDoubleTap }) {
	const { t } = useTranslation();
	const containerRef = useRef(null);
	const [containerSize, setContainerSize] = useState({ width: 0, height: 0 });
	const [aspectRatios, setAspectRatios] = useState(() => new Map());
	const [pageIndex, setPageIndex] = useState(0);
	const [visibleImageIds, setVisibleImageIds] = useState(() => new Set());
	const touchStartX = useRef(null);
	const hasJumpedToCurrentPageRef = useRef(false);
	const registerTap = useDoubleTap(onDoubleTap);
	const pendingAspectUpdates = useRef(new Map());
	const flushScheduled = useRef(false);

	useEffect(() => {
		const el = containerRef.current;
		if (!el || typeof ResizeObserver === 'undefined') return undefined;
		// `contentRect` reports the *content* box (padding excluded) - the
		// area actually free for thumbnails.
		const observer = new ResizeObserver((entries) => {
			const { width, height } = entries[0].contentRect;
			setContainerSize({ width, height });
		});
		observer.observe(el);
		return () => observer.disconnect();
	}, []);

	// Batches same-frame onLoad events into a single repack (see the class
	// doc above) - `pendingAspectUpdates` is a plain ref, not state, so
	// recording into it never itself triggers a render; only the rAF flush
	// below calls setAspectRatios, at most once per animation frame no
	// matter how many thumbnails finished loading in that time.
	const handleThumbLoad = useCallback((imageId, e) => {
		const { naturalWidth, naturalHeight } = e.target;
		if (!naturalWidth || !naturalHeight) return;
		pendingAspectUpdates.current.set(imageId, naturalWidth / naturalHeight);
		if (flushScheduled.current) return;
		flushScheduled.current = true;
		requestAnimationFrame(() => {
			flushScheduled.current = false;
			const pending = pendingAspectUpdates.current;
			pendingAspectUpdates.current = new Map();
			setAspectRatios((prev) => {
				let changed = false;
				const next = new Map(prev);
				pending.forEach((aspect, id) => {
					if (Math.abs((next.get(id) ?? DEFAULT_ASPECT) - aspect) >= 0.01) {
						next.set(id, aspect);
						changed = true;
					}
				});
				return changed ? next : prev;
			});
		});
	}, []);

	const rowsPerPage = useMemo(() => {
		if (containerSize.height <= 0) return MIN_ROWS_PER_PAGE;
		const raw = Math.round(containerSize.height / TARGET_ROW_HEIGHT_PX);
		return Math.min(MAX_ROWS_PER_PAGE, Math.max(MIN_ROWS_PER_PAGE, raw));
	}, [containerSize.height]);

	const rowHeight = containerSize.height > 0 ? (containerSize.height - (rowsPerPage - 1) * GAP_PX) / rowsPerPage : 0;

	const pages = useMemo(
		() => packIntoPages(images, aspectRatios, containerSize.width, rowHeight, rowsPerPage),
		[images, aspectRatios, containerSize.width, rowHeight, rowsPerPage],
	);

	// Lands on whichever page already holds the current image, once (not on
	// every render/repack - a manual page swipe shouldn't keep getting
	// overridden just because a thumbnail elsewhere finished loading and
	// nudged the layout). Naturally re-arms every time this component
	// mounts, i.e. every time the user re-enters the mosaic screen - exactly
	// "current image's page" each time, not just the very first time ever.
	useEffect(() => {
		if (hasJumpedToCurrentPageRef.current || pages.length === 0) return;
		const flatIndex = pages.findIndex((page) => page.some((row) => row.some((cell) => cell.image.id === currentId)));
		setPageIndex(flatIndex >= 0 ? flatIndex : 0);
		hasJumpedToCurrentPageRef.current = true;
	}, [pages, currentId]);

	// Only the current page and its immediate neighbors actually mount an
	// AuthImage (see the class doc's point (1)) - re-derived whenever the
	// page itself changes (a swipe) or `pages` is recomputed (a repack can
	// shift which images fall on which page, including the current one) so
	// a thumbnail that scrolled into range always gets picked up. Grows
	// only (a Set, unioned in) - never drops an id once loaded, so paging
	// back to an already-visited page never re-fetches it.
	useEffect(() => {
		if (pages.length === 0) return;
		const rangeStart = Math.max(0, pageIndex - 1);
		const rangeEnd = Math.min(pages.length - 1, pageIndex + 1);
		setVisibleImageIds((prev) => {
			let changed = false;
			const next = new Set(prev);
			for (let p = rangeStart; p <= rangeEnd; p += 1) {
				pages[p].forEach((row) =>
					row.forEach(({ image }) => {
						if (!next.has(image.id)) {
							next.add(image.id);
							changed = true;
						}
					}),
				);
			}
			return changed ? next : prev;
		});
	}, [pages, pageIndex]);

	// Keeps the page index in range if a repack ever produces fewer pages
	// than the one currently showing (harmless no-op otherwise).
	useEffect(() => {
		setPageIndex((p) => Math.min(p, Math.max(pages.length - 1, 0)));
	}, [pages.length]);

	const pinch = usePinchGesture({ onPinchIn });

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
		if (deltaX <= -SWIPE_THRESHOLD_PX) {
			setPageIndex((p) => Math.min(p + 1, Math.max(pages.length - 1, 0)));
		} else if (deltaX >= SWIPE_THRESHOLD_PX) {
			setPageIndex((p) => Math.max(p - 1, 0));
		} else {
			registerTap();
		}
	}

	if (images.length === 0) {
		return (
			<div
				className="mobile-mosaic mobile-mosaic-empty"
				ref={containerRef}
				onTouchStart={handleTouchStart}
				onTouchMove={handleTouchMove}
				onTouchEnd={handleTouchEnd}
			>
				<p>{t('mobile.noPicturesYet')}</p>
			</div>
		);
	}

	return (
		<div className="mobile-mosaic" ref={containerRef} onTouchStart={handleTouchStart} onTouchMove={handleTouchMove} onTouchEnd={handleTouchEnd}>
			<div className="mobile-mosaic-track" style={{ transform: `translateX(-${pageIndex * 100}%)` }}>
				{pages.map((page, pageIdx) => (
					<div className="mobile-mosaic-page" key={pageIdx}>
						{page.map((row, rowIdx) => (
							<div className="mobile-mosaic-row" key={rowIdx}>
								{row.map(({ image, width }) => (
									<div
										key={image.id}
										className={`mobile-mosaic-thumb${image.id === currentId ? ' mobile-mosaic-thumb-current' : ''}`}
										style={{ width, height: rowHeight }}
										onClick={() => onSelect(image.id)}
									>
										{visibleImageIds.has(image.id) ? (
											<AuthImage
												src={image.thumbnailUrl}
												alt={image.name}
												className="mobile-mosaic-thumb-img"
												onLoad={(e) => handleThumbLoad(image.id, e)}
											/>
										) : (
											<div className="mobile-mosaic-thumb-img" />
										)}
										{image.mediaType === 'VIDEO' && <span className="filmstrip-video-badge" aria-hidden="true" />}
									</div>
								))}
							</div>
						))}
					</div>
				))}
			</div>
		</div>
	);
}
