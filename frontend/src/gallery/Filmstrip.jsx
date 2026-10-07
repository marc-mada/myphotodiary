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
import { AuthImage } from './AuthImage';

// Empty-slot placeholders (explicit ask): same 128px width as a real
// thumbnail, but their own height/width = 2/3 (a plain rectangle, not
// pretending to be any particular photo's own aspect ratio) - kept in sync
// with .filmstrip-placeholder's CSS by deriving both from this one width.
// app.css sets a global box-sizing: border-box, so this declared height
// already *includes* the 5px border (same as .noFocusThumb/.focusThumb's
// own width:128px) - only the 5px+5px vertical margin sits outside it.
const PLACEHOLDER_WIDTH = 128;
const PLACEHOLDER_HEIGHT = (PLACEHOLDER_WIDTH * 2) / 3;
// Total height one placeholder slot occupies in the rail - used to size how many fit.
const PLACEHOLDER_SLOT_HEIGHT = PLACEHOLDER_HEIGHT + 2 * 5;

/**
 * The `#rsidebar`/`#rslider` thumbnail rail (idx.css) - Design.md decision #8
 * calls this out by name as a concrete case to preserve. Same film.png
 * texture (copied byte-for-byte into public/legacy/), same 128px
 * focus/no-focus thumbnail border. The legacy version scrolls via a custom
 * mouse-wheel handler (vScroller in idx.js); a plain scrollable div gets the
 * same practical result (long lists scroll) without reimplementing that
 * hand-rolled scroller - except for keeping the selected thumbnail in view,
 * which needs its own explicit handling below.
 *
 * Shared by both the Gallery and Search screens (a direct ask - Search's
 * results should look and behave like the Gallery's own filmstrip, not a
 * separate grid) - `onEndReached`/`hasMore`/`loadingMore` are optional and
 * only meaningful for Search's paginated results; the Gallery just omits them.
 *
 * `ImageComponent` (02/09/2026, share-link viewer - explicit ask: "visually
 * consistent with the other screens") - defaults to `AuthImage`, unchanged
 * for every existing caller; ShareScreen/MobileShareScreen pass
 * `PublicImage` instead, which fetches the same way but with no
 * Authorization header (the share token already travels as part of
 * `image.thumbnailUrl` itself - `PublicImageResponse`'s own doc). Swapping
 * only the fetch mechanism, not this component's own lazy-loading/
 * scroll-to-selected/placeholder-filling logic, is what actually delivers
 * "visually consistent" rather than a second, hand-rolled filmstrip that
 * could drift from this one over time.
 */
export function Filmstrip({ images, currentId, onSelect, onEndReached, hasMore, loadingMore, ImageComponent = AuthImage }) {
	const selectedRef = useRef(null);
	const sentinelRef = useRef(null);
	const sidebarRef = useRef(null);
	const sliderRef = useRef(null);
	const realThumbsRef = useRef(null);
	const [placeholderCount, setPlaceholderCount] = useState(0);

	// Lazy-load thumbnail bytes (31/08/2026, real production latency found:
	// entering a directory with many pictures took 10-20s). Not thumbnail
	// regeneration - GalleryController's own /thumbnail endpoint just reads
	// an existing file, checked before assuming otherwise - it was every
	// single thumbnail in the directory firing its own authenticated
	// fetch() the instant the filmstrip mounted (AuthImage's own effect,
	// one per image, all at once, no windowing at all), each one a full
	// round trip through the Apache reverse proxy: dozens/hundreds of
	// queued requests for a directory with many pictures. Same
	// IntersectionObserver pattern already used just below for pagination's
	// own sentinel - only thumbnails that have actually scrolled into (or
	// near) view get an AuthImage mounted at all; the rest stay a plain
	// placeholder div, identical in size/appearance to AuthImage's own
	// not-yet-loaded state, until they do. `visibleIds` only ever grows (a
	// Set, unioned in) - once a thumbnail has loaded, scrolling back past it
	// must not re-trigger its fetch.
	const [visibleIds, setVisibleIds] = useState(() => new Set(currentId != null ? [currentId] : []));
	const thumbRefs = useRef(new Map());
	const observeThumb = useCallback((id, el) => {
		if (el) thumbRefs.current.set(id, el);
		else thumbRefs.current.delete(id);
	}, []);

	// The initially/newly selected thumbnail shouldn't have to wait for an
	// intersection callback to appear - it's the one image guaranteed to be
	// relevant the instant a directory/search result is opened.
	useEffect(() => {
		if (currentId == null) return;
		setVisibleIds((prev) => (prev.has(currentId) ? prev : new Set(prev).add(currentId)));
	}, [currentId]);

	useEffect(() => {
		const sidebar = sidebarRef.current;
		if (!sidebar || typeof IntersectionObserver === 'undefined') return;
		const observer = new IntersectionObserver(
			(entries) => {
				const newlyVisible = entries.filter((entry) => entry.isIntersecting).map((entry) => Number(entry.target.dataset.imageId));
				if (newlyVisible.length === 0) return;
				setVisibleIds((prev) => {
					const next = new Set(prev);
					newlyVisible.forEach((id) => next.add(id));
					return next;
				});
			},
			// Generous margin along the scroll axis - thumbnails load a bit
			// ahead of actually being scrolled to, rather than popping in at
			// the exact moment they cross the edge.
			{ root: sidebar, rootMargin: '600px 0px' },
		);
		thumbRefs.current.forEach((el) => observer.observe(el));
		return () => observer.disconnect();
	}, [images]);

	// Keeps the selected thumbnail visible: if it's scrolled too far up or
	// down to be seen, the rail scrolls itself so the selection re-centers -
	// a direct ask, not just "scrolls into view" (block: 'nearest' would
	// only just barely reveal it at the edge instead).
	useEffect(() => {
		selectedRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
	}, [currentId]);

	useEffect(() => {
		if (!onEndReached) return;
		const sentinel = sentinelRef.current;
		if (!sentinel) return;
		const observer = new IntersectionObserver(
			(entries) => {
				if (entries[0].isIntersecting && hasMore && !loadingMore) onEndReached();
			},
			{ root: sentinel.closest('#rsidebar'), rootMargin: '200px' },
		);
		observer.observe(sentinel);
		return () => observer.disconnect();
	}, [onEndReached, hasMore, loadingMore]);

	// Fills whatever empty rail space is left below the real thumbnails with
	// grey placeholder slots (explicit ask) - measured, not a fixed guess,
	// since #rsidebar's own height varies with the viewport and the real
	// thumbnails' combined height varies with how many images there are and
	// each one's own aspect ratio. A ResizeObserver on both boxes catches
	// every reason that gap can change (window resize, thumbnails finishing
	// their async load via AuthImage - see its own comment on why they start
	// at zero height - a photo count that changes), same pattern already
	// used for the image viewer's own controls-bar alignment.
	useEffect(() => {
		const sidebar = sidebarRef.current;
		const slider = sliderRef.current;
		const realThumbs = realThumbsRef.current;
		if (!sidebar || !slider || !realThumbs || typeof ResizeObserver === 'undefined') return;

		function recompute() {
			// #rslider's own vertical padding (16px top + 16px bottom, app.css)
			// was missing from this calculation entirely - it eats into the same
			// #rsidebar height that both the real thumbnails and the
			// placeholders below have to share, so skipping it overestimates
			// how much room is actually left. Read live via getComputedStyle
			// rather than a hardcoded 32 (kept in sync with app.css by
			// construction, not by a comment asking the next edit to remember
			// updating a second place) - real bug found live (01/09/2026):
			// resizing the window *vertically* only (thumbnails are a single
			// column here, so their own combined height never depends on
			// width) occasionally landed #rsidebar's height just inside that
			// missing 32px, adding one placeholder too many and producing a
			// scrollbar that shouldn't have been there.
			const sliderStyle = getComputedStyle(slider);
			const sliderVerticalPadding = parseFloat(sliderStyle.paddingTop) + parseFloat(sliderStyle.paddingBottom);
			const emptySpace = sidebar.clientHeight - sliderVerticalPadding - realThumbs.getBoundingClientRect().height;
			setPlaceholderCount(emptySpace > 0 ? Math.floor(emptySpace / PLACEHOLDER_SLOT_HEIGHT) : 0);
		}

		recompute();
		const observer = new ResizeObserver(recompute);
		observer.observe(sidebar);
		observer.observe(realThumbs);
		return () => observer.disconnect();
	}, [images]);

	// Renders even with zero/few thumbnails (28/08/2026, explicit ask -
	// reverts an earlier explicit ask that returned null here entirely for
	// an empty list) - the film-textured column (#rsidebar/#rslider, now
	// full-height regardless of content via app.css) is a constant part of
	// the frame, same as it was originally, not something that disappears
	// depending on how many photos happen to be in the current directory.
	return (
		<div id="rsidebar" ref={sidebarRef}>
			<div id="rslider" ref={sliderRef}>
				<div ref={realThumbsRef} className="filmstrip-real-thumbs">
					{images.map((image) => (
						<div
							key={image.id}
							className="filmstrip-thumb-wrap"
							data-image-id={image.id}
							ref={(el) => {
								observeThumb(image.id, el);
								if (image.id === currentId) selectedRef.current = el;
							}}
						>
							{visibleIds.has(image.id) ? (
								<ImageComponent
									src={image.thumbnailUrl}
									alt={image.name}
									className={image.id === currentId ? 'focusThumb' : 'noFocusThumb'}
									onClick={() => onSelect(image.id)}
								/>
							) : (
								<div className={image.id === currentId ? 'focusThumb' : 'noFocusThumb'} />
							)}
							{/* Video badge (28/08/2026) - the thumbnail itself is
							    always a plain JPEG frame either way (ImageStorageService's
							    own comment), nothing in its own bytes distinguishes a
							    video's thumbnail from a photo's - this overlay is the
							    only visual cue in the filmstrip that an item is a video. */}
							{image.mediaType === 'VIDEO' && <span className="filmstrip-video-badge" aria-hidden="true" />}
						</div>
					))}
					{onEndReached && hasMore && (
						<div ref={sentinelRef} className="filmstrip-sentinel">
							{loadingMore ? '…' : ''}
						</div>
					)}
				</div>
				{/* Grey placeholders (explicit ask) for whatever empty rail space
				    is left below the real thumbnails - not selectable (no onClick,
				    no cursor:pointer, pointer-events:none in app.css): they aren't
				    photos, just filling the rail's own texture visually the way an
				    empty photo album's remaining slots would. */}
				{Array.from({ length: placeholderCount }, (_, i) => (
					<div key={`placeholder-${i}`} className="filmstrip-placeholder" aria-hidden="true" />
				))}
			</div>
		</div>
	);
}
