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

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AuthImage } from './AuthImage';
import { AuthVideo } from './AuthVideo';
import { PostIt } from './PostIt';
import { ProgressiveImage } from './ProgressiveImage';

// Legacy default (UserConfiguration.slideShowInterval, default 5s) - used
// only until the real per-user setting (see MeController/api/me) has loaded,
// same default the backend itself falls back to for a brand new user.
const DEFAULT_SLIDESHOW_INTERVAL_MS = 5000;

// No legacy default to match here (see the V8 migration's own comment) -
// same default the backend itself falls back to for a brand new user
// (User.postItFadeDelay), used only until the real per-user setting has
// loaded.
const DEFAULT_POSTIT_FADE_DELAY_MS = 8000;

// Matches .controls-bar's own height in app.css - the post-it is positioned
// just below it (explicit ask), not at a fixed top:20px regardless of the
// bar (PostIt.jsx's own CSS default) - a gap on top of that so the two don't
// touch. Added to `controlsTop` (the bar's own dynamic offset for
// letterboxing, computed below) rather than a second static value, so the
// post-it tracks the real image edge exactly the same way the bar does.
const CONTROLS_BAR_HEIGHT_PX = 47;
// In full-screen, the control bar never goes closer than this to the top of
// the screen (06/10/2026, reported: a second click on Full-screen "randomly"
// didn't exit). A picture filling the screen's height put the bar flush
// against the top edge - exactly where macOS slides its menu bar (and Chrome
// its own exit controls) in when the pointer reaches the top, so a click
// there went to whichever was on top at that instant.
const FULLSCREEN_TOP_CLEARANCE_PX = 56;
const POSTIT_GAP_PX = 12;

/**
 * AuthVideo or AuthImage/PublicImage depending on media type - the one
 * thing both crossfade layers (current and outgoing) need to render
 * identically. `ImageComponent` (02/09/2026, share-link viewer) defaults to
 * `AuthImage`, same reasoning as Filmstrip's own identical prop - video is
 * never reachable through a share link at all (ShareController's own
 * scope-limit doc), so there's no equivalent swap needed for `AuthVideo`
 * here.
 *
 * `progressive` (10/09/2026, explicit ask - slow-network image loading):
 * only the *incoming* (`.crossfade-current`) layer is ever rendered with
 * this true - see ImageViewer's own doc comment on why the outgoing layer
 * and the next-image preload deliberately keep using plain `ImageComponent`
 * instead.
 */
// `hd` (06/10/2026): the full-resolution original instead of the 1600px
// web copy - only while the user has HD switched on (ImageViewer's HD
// button). A shared/public item has no originalUrl, so it keeps webUrl.
function MediaLayer({ item, onReady, ImageComponent, progressive, hd = false }) {
	if (item.mediaType === 'VIDEO') {
		return <AuthVideo imageId={item.id} className="visible-image" onLoadedMetadata={onReady} />;
	}
	const src = hd && item.originalUrl ? item.originalUrl : item.webUrl;
	if (progressive) {
		return <ProgressiveImage key={src} src={src} thumbnailSrc={item.thumbnailUrl} alt={item.name} className="visible-image" onLoad={onReady} />;
	}
	return <ImageComponent key={src} src={src} alt={item.name} className="visible-image" onLoad={onReady} />;
}

// "HD" (06/10/2026) - same badge style as the other control-bar icons;
// filled white with dark letters while HD is on, so its state shows.
function HdIcon({ active }) {
	return (
		<svg viewBox="0 0 44 44" aria-hidden="true">
			<circle cx="22" cy="22" r="20" fill={active ? 'white' : 'rgb(90,90,90)'} />
			<text x="22" y="27.5" textAnchor="middle" fontFamily="Arial, Helvetica, sans-serif" fontWeight="bold" fontSize="15" fill={active ? 'rgb(60,60,60)' : 'white'}>
				HD
			</text>
		</svg>
	);
}

/**
 * Search screen's top-left control (01/09/2026, explicit ask) - a folder
 * glyph on the same dark-circle badge as delete/play/full-screen's own
 * 44x44 PNGs (`app.css`'s `.controls-bar` styling), but hand-drawn rather
 * than a ported legacy asset: this button has no legacy equivalent at all
 * (Search never had a way back to Gallery), so there's nothing to copy
 * byte-for-byte the way decision #8 calls for elsewhere - same approach
 * MobileIcon.jsx already takes for icons without a legacy asset. Folder
 * glyph itself is Material Design's own "folder" path (viewBox 0 0 24 24),
 * scaled/centered into this 44x44 circle to match the delete/play/
 * full-screen icons' own visual weight and padding.
 */
function DirectoryLinkIcon() {
	return (
		<svg viewBox="0 0 44 44" aria-hidden="true">
			<circle cx="22" cy="22" r="20" fill="rgb(90,90,90)" />
			<g transform="translate(22,22) scale(1.15) translate(-12,-12)">
				<path d="M10 4H4c-1.11 0-2 .89-2 2v12c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2h-8l-2-2z" fill="white" />
			</g>
		</svg>
	);
}

/**
 * Bottom toolbar's left icon, Gallery only (02/09/2026, explicit ask) - a
 * chain-link glyph (Material Design's own "link" path), meaning "copy an
 * external, shareable link to this one image" - distinct on purpose from
 * `DirectoryLinkIcon` above (a folder shape, meaning "go to this sequence
 * inside the app") even though the two happen to occupy the same visual
 * corner on different screens: this button leaves the app's own auth
 * boundary entirely (ShareController's `/api/images/{id}/share-token`),
 * `DirectoryLinkIcon` never does. Same 44x44 dark-circle badge as every
 * other control-bar icon; no legacy asset exists for this (new feature).
 */
function ImageLinkIcon() {
	return (
		<svg viewBox="0 0 44 44" aria-hidden="true">
			<circle cx="22" cy="22" r="20" fill="rgb(90,90,90)" />
			<g transform="translate(22,22) scale(1.15) translate(-12,-12)">
				<path d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z" fill="white" />
			</g>
		</svg>
	);
}

/**
 * Bottom toolbar's own left icon (02/09/2026) - "copy an external,
 * shareable link to this whole sequence" (ShareController's
 * `/api/directories/{id}/share-token`). A "collections" glyph (two
 * overlapping picture frames, Material Design's own path) rather than
 * reusing `ImageLinkIcon`'s chain-link or `DirectoryLinkIcon`'s folder -
 * deliberately a third, distinct shape so a user who sees both this and
 * Search's `DirectoryLinkIcon` in the same top-left-ish corner across
 * screens never mistakes one action for the other.
 */
function SequenceLinkIcon() {
	return (
		<svg viewBox="0 0 44 44" aria-hidden="true">
			<circle cx="22" cy="22" r="20" fill="rgb(90,90,90)" />
			<g transform="translate(22,22) scale(1.15) translate(-12,-12)">
				<path d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H8V4h12v12z" fill="white" />
			</g>
		</svg>
	);
}

/**
 * Bottom toolbar's center icon (originally 02/09/2026 as a direct one-click
 * Rotate; repurposed 25/09/2026, explicit ask - opens the rudimentary image
 * editor popup instead, which now houses Crop and Rotate together -
 * ImageEditorPopup's own doc comment). A crop-corner-brackets glyph
 * (Material Design's own "crop" path) rather than the previous rotate-arrow
 * icon this slot used to show - clicking it no longer rotates immediately
 * by itself, so the icon needed to say "edit", not "rotate". Never shown
 * for a video (explicit ask, unchanged from before) - editing a video's own
 * frames would mean re-encoding the whole file, a transcoding operation
 * this project has deliberately never done (Design.md §8's "no
 * transcoding" video policy); GalleryScreen only ever passes `onOpenEditor`
 * down when `image.mediaType` isn't `'VIDEO'`, so this icon (and the click
 * target it sits in) simply never renders for one, rather than rendering
 * disabled.
 */
// "Comment" (27/09/2026) - Material Design's "comment" glyph (a speech
// bubble with text lines), same badge style as the icons around it.
function CommentIcon() {
	return (
		<svg viewBox="0 0 44 44" aria-hidden="true">
			<circle cx="22" cy="22" r="20" fill="rgb(90,90,90)" />
			<g transform="translate(22,22) scale(1.15) translate(-12,-12)">
				<path d="M21.99 4c0-1.1-.89-2-1.99-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14l4 4-.01-18zM18 14H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z" fill="white" />
			</g>
		</svg>
	);
}

function EditImageIcon() {
	return (
		<svg viewBox="0 0 44 44" aria-hidden="true">
			<circle cx="22" cy="22" r="20" fill="rgb(90,90,90)" />
			<g transform="translate(22,22) scale(1.15) translate(-12,-12)">
				<path d="M17 15h2V7c0-1.1-.9-2-2-2H9v2h8v8zM7 17V1H5v4H1v2h4v10c0 1.1.9 2 2 2h10v4h2v-4h4v-2H7z" fill="white" />
			</g>
		</svg>
	);
}

/**
 * The `.photo-frame` main viewer (idx.css) - large image, prev/next
 * (`#controls-left`/`#controls-right`), the top `.controls-bar`
 * (delete/play/full-screen - `#controls-topleft`/`#controls-top`/
 * `#controls-topright` in index.jsp, same icons copied byte-for-byte into
 * public/legacy/), and the post-it comment (sequence comment above the
 * picture's own, each opening its own popup - see PostIt.jsx). Shared by
 * the Gallery and Search screens (a direct ask - search results get the
 * same viewer and controls as browsing the gallery, not a separate
 * lightbox). Rating/description editing and the always-visible rating bar
 * moved into the picture-description popup (ImageEditForm) - idx.css's own
 * `#ratingBar` lives inside the imgEdit form too, not as a separate
 * always-visible widget, and freeing that space is a direct ask.
 *
 * `#controls-topleft` is one of three things, never more than one
 * (01/09/2026 + 02/09/2026, explicit asks, in priority order):
 * `onGoToDirectory` (SearchScreen - the folder-icon button that jumps to
 * the Gallery screen open on this same image's own sequence) >
 * `onCopyImageLink` (GalleryScreen, new - copies an external share link to
 * this one image, `ImageLinkIcon` above) > `onDelete` (legacy fallback, no
 * current caller actually reaches this branch - both real callers below
 * always pass one of the first two). Nothing renders in this slot at all
 * if none of the three are given (the external share viewer, ShareScreen -
 * a later addition - passes none of them, deliberately: no delete-looking
 * icon with a dead click for someone with no account to delete anything).
 *
 * Gallery's own toolbar (27/09/2026, explicit ask - replaces the separate
 * bottom toolbar of 02/09/2026): all seven actions in the one top bar, in
 * this order, left to right - sequence link, image link, Comment, **Play**,
 * Edit, Delete, Full-screen. `onCopyImageLink` is what selects this layout
 * (Gallery passes it together with `onCopySequenceLink`/`onOpenEditor`/
 * `onDelete`; Search and the external share viewer pass none of them and
 * keep the plain three-slot bar). Three groups on a `1fr auto 1fr` grid
 * (`.controls-bar-grouped`), so Play stays exactly centered even when the
 * right group is one icon short - Edit is not rendered for a video
 * (editing a video's frames would mean re-encoding it, never done here).
 * **Comment** reveals the post-it, same as hovering it (PostIt's own
 * `revealKey`), then lets it fade after the usual delay.
 *
 * The Search screen uses the same grouped bar since 04/10/2026 (sharing a
 * search result): Go to this sequence, search-result link (the sequence-
 * link icon, `copySequenceLinkTitle`), picture link, then Play and
 * Full-screen - no Comment (`showCommentButton={false}`), Edit or Delete,
 * which stay on the Gallery screen.
 *
 * Keyboard shortcuts mirror legacy's own global keydown/keyup handler
 * (idx.js): Left/Right move between images, Up leaves full-screen (if
 * active) and asks the caller to navigate to the parent directory via
 * `onNavigateUp` (Gallery only - Search has no directory tree to move
 * within, so it simply omits that prop), Down toggles play/pause, Esc
 * (bound on keyup, like legacy - see that effect's own comment) exits
 * full-screen. Suspended (`disableShortcuts`) while a popup is open, same
 * as legacy's own `popupFocus` guard - without it, e.g. typing a
 * description that starts with a left/right arrow keystroke would instead
 * flip images out from under the open popup.
 *
 * Displays `image.webUrl` (the medium-resolution tier, ImageStorageService's
 * 1600px derivative), not `originalUrl` - legacy's own mobile viewer
 * (midx.js's getWebUrl) always does the same; desktop's bandwidth-adaptive
 * full/web switch isn't ported (Design.md §15, legacy feature-parity
 * audit), a simpler and consistently lighter choice, full resolution
 * reserved for explicit download (ImageEditForm). `nextImage`, when given,
 * is preloaded via a hidden AuthImage - same spirit as legacy's own
 * preloadSel/preload-next-image, so advancing feels instant rather than
 * waiting on the next fetch to start only once the user actually clicks.
 *
 * `emptyImageSrc` (28/08/2026, explicit ask) - shown full-frame in place of
 * `image` when there isn't one, porting idx.js's own swap(): "no image,
 * display a default image" (images/default.png). Purely a static public
 * asset reference, not fetched/stored/indexed as a real Image in any way.
 *
 * Video (28/08/2026, "have a talk about a new topic not in the legacy") -
 * `image.mediaType === 'VIDEO'` swaps `AuthImage` for `AuthVideo` (a native
 * `<video controls>`, token-authenticated streaming rather than a blob
 * fetch - see that component's own comment for why). Both share the exact
 * same `.visible-image` class/sizing and `frameRef`-relative
 * controls-bar-alignment mechanics below, so a video and a photo look and
 * frame identically - the delete/rating/tags/description/RBAC/search
 * "remaining features strictly equivalent to images" part of that ask is
 * true by construction (Image, GalleryService, etc. never branch on
 * mediaType at all), not something this component has to reimplement.
 *
 * Cross-fade between pictures (28/08/2026, explicit ask) - no legacy
 * equivalent (idx.js's own swap() replaces the `<img src>` in place, an
 * instant cut). Two absolutely-stacked `.crossfade-layer`s, not a single
 * element whose `src` changes: `outgoingImage` (the previous `image`,
 * fading out via CSS animation, removed once that animation's own
 * `onAnimationEnd` fires) sits behind the current one (fading in). Both
 * layers render through the same `MediaLayer` helper, so a video crossfades
 * exactly like a photo - no special case needed there either, same
 * "strictly equivalent" spirit as the media-type branch above.
 * `.crossfade-current` (not `.visible-image` alone) is what
 * `updateControlsPosition` now queries, so the controls-bar always aligns to the
 * incoming image's own letterboxing, not whichever layer happens to appear
 * first in the DOM.
 *
 * Progressive loading on the incoming layer only (10/09/2026, explicit ask -
 * see `MediaLayer`'s own doc) - gated on `ImageComponent === AuthImage`
 * (the authenticated default), not applied unconditionally: `ProgressiveImage`
 * calls `useAuth()` internally, and the public share viewer (ShareScreen,
 * `ImageComponent={PublicImage}`) renders this whole component *outside*
 * `<AuthProvider>` entirely (`PublicImage`'s own doc) - calling `useAuth()`
 * there wouldn't degrade gracefully, it would throw. The outgoing crossfade
 * layer and the next-image preload are unaffected either way - both always
 * keep using plain `ImageComponent`, already-loaded/not-yet-needed images
 * don't need a progress bar.
 */
export function ImageViewer({
	image,
	hasPrevious,
	hasNext,
	onPrevious,
	onNext,
	onDelete,
	onGoToDirectory,
	onCopyImageLink,
	onCopySequenceLink,
	// Search screen (04/10/2026): the first share icon shares the whole
	// search result there, not a sequence - only its tooltip differs.
	copySequenceLinkTitle,
	showCommentButton = true,
	onOpenEditor,
	onNavigateUp,
	disableShortcuts = false,
	slideShowIntervalMs = DEFAULT_SLIDESHOW_INTERVAL_MS,
	postItFadeDelayMs = DEFAULT_POSTIT_FADE_DELAY_MS,
	sequenceDescription,
	onOpenSequencePopup,
	onOpenImagePopup,
	nextImage,
	emptyMessage,
	emptyImageSrc,
	// Share-link viewer (02/09/2026) - see MediaLayer's own doc comment.
	ImageComponent = AuthImage,
}) {
	const { t } = useTranslation();
	// See this component's own doc comment on progressive loading for why
	// this is gated on the default AuthImage specifically.
	const progressive = ImageComponent === AuthImage;
	const frameRef = useRef(null);
	const [playing, setPlaying] = useState(false);
	const [fullscreen, setFullscreen] = useState(false);
	// Vertical offset of the controls-bar from the frame's own top edge, so
	// its upper edge matches the *rendered* image's upper edge - not the
	// frame's, which is usually taller than the image once `object-fit:
	// contain` letterboxes it. Recomputed whenever the frame's box changes
	// size (window resize, entering/exiting full-screen) or a new image
	// finishes loading (a differently-shaped image changes the letterboxing).
	const [controlsTop, setControlsTop] = useState(0);
	// Bumped by the toolbar's Comment button - PostIt reveals itself on
	// every change (its own `revealKey` doc).
	const [postItRevealKey, setPostItRevealKey] = useState(0);
	// HD mode (06/10/2026, explicit ask): full-resolution originals while on.
	// Temporary on purpose - plain component state, never saved: it switches
	// off when the screen is left or the page reloaded.
	const [hd, setHd] = useState(false);

	// Cross-fade bookkeeping: whenever `image` actually switches to a
	// different id, the item it's replacing is kept around one extra render
	// as `outgoingImage`, fading out behind the new current one - see this
	// component's own doc comment above. `lastImageRef` (not `image` itself)
	// is what's compared, since a same-id edit (rating/description saved
	// through a popup) gives a new object reference without being a real
	// picture change - that case must NOT trigger a cross-fade.
	const [outgoingImage, setOutgoingImage] = useState(null);
	const lastImageRef = useRef(image);
	useEffect(() => {
		if (lastImageRef.current && lastImageRef.current.id !== image?.id) {
			setOutgoingImage(lastImageRef.current);
		}
		lastImageRef.current = image;
	}, [image]);

	// Auto-advances like legacy's imageSwapper.play() - re-armed each time
	// the current image actually changes (image?.id in the deps), so it's
	// always a fresh timer rather than a stale closure counting from the
	// wrong image. Stops on its own at the end of the sequence, exactly
	// like legacy's next() returning false.
	useEffect(() => {
		if (!playing) return;
		if (!hasNext) {
			setPlaying(false);
			return;
		}
		const timer = setTimeout(onNext, slideShowIntervalMs);
		return () => clearTimeout(timer);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [playing, hasNext, image?.id, slideShowIntervalMs]);

	// Stops the slideshow, and syncs `fullscreen` back to false, if the
	// browser's own fullscreen exits from under us (the user pressing Esc,
	// not our own button).
	useEffect(() => {
		function onFullscreenChange() {
			setFullscreen(!!document.fullscreenElement);
		}
		document.addEventListener('fullscreenchange', onFullscreenChange);
		return () => document.removeEventListener('fullscreenchange', onFullscreenChange);
	}, []);

	function updateControlsPosition() {
		const frame = frameRef.current;
		// .crossfade-current .visible-image, not just .visible-image
		// (28/08/2026, cross-fade) - during a transition there are briefly
		// two layers in the DOM (the outgoing one is still fading out); this
		// always resolves to the incoming/current one specifically, which is
		// also the reason .visible-image itself still matches both AuthImage's
		// <img> and AuthVideo's <video> (video support, same day) rather than
		// needing a second selector for each media type.
		const img = frame?.querySelector('.crossfade-current .visible-image');
		if (!frame || !img) return;
		const frameRect = frame.getBoundingClientRect();
		const imgRect = img.getBoundingClientRect();
		const top = imgRect.top - frameRect.top;
		const inFullscreen = document.fullscreenElement === frame || frame.classList.contains('photo-full-screen');
		setControlsTop(inFullscreen ? Math.max(top, FULLSCREEN_TOP_CLEARANCE_PX) : top);
	}

	// A ResizeObserver on the frame catches every reason its box can change
	// size (window resize, the full-screen class swap) in one place, rather
	// than listening for each individually - `onLoad` below still covers the
	// case where the frame's own size doesn't change but a new image with a
	// different aspect ratio (different letterboxing) does.
	useEffect(() => {
		const frame = frameRef.current;
		if (!frame || typeof ResizeObserver === 'undefined') return;
		const observer = new ResizeObserver(updateControlsPosition);
		observer.observe(frame);
		return () => observer.disconnect();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [fullscreen]);

	async function toggleFullscreen() {
		if (!fullscreen) {
			setFullscreen(true);
			try {
				await frameRef.current?.requestFullscreen?.();
			} catch {
				// Fullscreen API refused (e.g. no user-gesture context in
				// some embeddings) - the CSS class swap below still gives a
				// full-viewport view either way.
			}
		} else {
			setFullscreen(false);
			if (document.fullscreenElement) {
				try {
					await document.exitFullscreen();
				} catch {
					// already exiting/exited
				}
			}
		}
	}

	useEffect(() => {
		if (disableShortcuts) return;
		function onKeyDown(e) {
			const target = e.target;
			if (target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.tagName === 'SELECT' || target?.isContentEditable) {
				return;
			}
			if (e.key === 'ArrowLeft') {
				if (hasPrevious) {
					e.preventDefault();
					onPrevious();
				}
			} else if (e.key === 'ArrowRight') {
				if (hasNext) {
					e.preventDefault();
					onNext();
				}
			} else if (e.key === 'ArrowUp') {
				e.preventDefault();
				if (fullscreen) toggleFullscreen();
				onNavigateUp?.();
			} else if (e.key === 'ArrowDown') {
				e.preventDefault();
				setPlaying((p) => !p);
			}
		}
		window.addEventListener('keydown', onKeyDown);
		return () => window.removeEventListener('keydown', onKeyDown);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [disableShortcuts, hasPrevious, hasNext, onPrevious, onNext, fullscreen, onNavigateUp]);

	// Esc is bound on keyup, not keydown, same as legacy - keydown repeats
	// while the key is held, which would rapidly toggle fullscreen on and
	// off instead of exiting it once.
	//
	// Exit-only, not a blind toggle like legacy's own fullScreen() - legacy
	// gets away with toggling because it never syncs its fullScreenState
	// flag back from the browser (no fullscreenchange listener at all), so
	// the flag only ever changes via its own click/Esc handlers. Our
	// `fullscreen` state IS synced from the real Fullscreen API (the effect
	// above) - the browser's native Esc handling can (and reliably does, in
	// real - as opposed to CDP-driven headless - browsers) exit real
	// fullscreen and flip `fullscreen` to false *before* this keyup handler
	// runs, purely from a real key press having nonzero duration. Toggling
	// blindly there would immediately re-enter fullscreen right after the
	// user pressed Esc specifically to leave it - checking `fullscreen`
	// first makes this a no-op in that case instead.
	useEffect(() => {
		if (disableShortcuts) return;
		function onKeyUp(e) {
			const target = e.target;
			if (target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.tagName === 'SELECT' || target?.isContentEditable) {
				return;
			}
			if (e.key === 'Escape' && fullscreen) {
				toggleFullscreen();
			}
		}
		window.addEventListener('keyup', onKeyUp);
		return () => window.removeEventListener('keyup', onKeyUp);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [disableShortcuts, fullscreen]);

	if (!image) {
		// Legacy default (idx.js's swap()/midx.js's own equivalent): "no image,
		// display a default image" - the same #visible <img> element's src is
		// just swapped to images/default.png, no separate text message at all
		// for GalleryScreen's own case (displayCurrentImgData(null) only
		// clears the post-it description) - a plain static asset from
		// public/legacy (not fetched via AuthImage, which is for backend-
		// served pictures only), never uploaded/persisted/indexed as a real
		// Image (explicit ask) - it's not part of `images` and never touches
		// the gallery API.
		//
		// SearchScreen passes both this *and* emptyMessage (23/09/2026 -
		// first tried image-only like GalleryScreen, reverted the same day:
		// "put the noMatches text back above the default.png, it was useful
		// information" - unlike a genuinely empty gallery directory, a "no
		// matches" result set has real information worth keeping, the
		// default image alone isn't a full replacement for it here).
		if (emptyImageSrc) {
			return (
				<div className="photo-frame photo-frame-empty">
					{emptyMessage && <p>{emptyMessage}</p>}
					<img src={emptyImageSrc} alt="" className="visible-image" />
				</div>
			);
		}
		return (
			<div className="photo-frame photo-frame-empty">
				<p>{emptyMessage ?? t('gallery.noImagesYet')}</p>
			</div>
		);
	}

	return (
		<div ref={frameRef} className={fullscreen ? 'photo-full-screen' : 'photo-frame'}>
			{outgoingImage && (
				<div key={`out-${outgoingImage.id}`} className="crossfade-layer crossfade-out" onAnimationEnd={() => setOutgoingImage(null)}>
					<MediaLayer item={outgoingImage} ImageComponent={ImageComponent} hd={hd} />
				</div>
			)}
			<div key={`cur-${image.id}`} className="crossfade-layer crossfade-in crossfade-current">
				<MediaLayer item={image} onReady={updateControlsPosition} ImageComponent={ImageComponent} progressive={progressive} hd={hd} />
			</div>
			{/* Video is deliberately never preloaded here (28/08/2026) - unlike
			    a photo's own lightweight "web" tier, preloading the *next*
			    video would mean silently downloading a whole, potentially
			    large file nobody has asked to watch yet; AuthVideo's own
			    token-issue-then-stream flow already starts promptly once a
			    video actually becomes current. */}
			{/* No preloading in HD mode: an original is typically 5-20 MB, too
			    heavy to download for a picture that may never be looked at. */}
			{nextImage && nextImage.mediaType !== 'VIDEO' && !hd && (
				<ImageComponent key={nextImage.id} src={nextImage.webUrl} alt="" className="preload-image" />
			)}

			{onCopyImageLink ? (
				<div className="controls-bar controls-bar-grouped" style={{ top: controlsTop }}>
					<div className="controls-group controls-group-left">
						{onGoToDirectory && (
							<div className="controls-button" onClick={onGoToDirectory} title={t('imageViewer.goToDirectory')}>
								<DirectoryLinkIcon />
							</div>
						)}
						<div className="controls-button" onClick={onCopySequenceLink} title={copySequenceLinkTitle ?? t('imageViewer.copySequenceLink')}>
							<SequenceLinkIcon />
						</div>
						<div className="controls-button" onClick={onCopyImageLink} title={t('imageViewer.copyImageLink')}>
							<ImageLinkIcon />
						</div>
						{showCommentButton && (
							<div className="controls-button" onClick={() => setPostItRevealKey((k) => k + 1)} title={t('imageViewer.showComment')}>
								<CommentIcon />
							</div>
						)}
					</div>
					<div className="controls-button" onClick={() => setPlaying((p) => !p)} title={playing ? t('imageViewer.pause') : t('imageViewer.play')}>
						<img src={playing ? '/legacy/pause.png' : '/legacy/play.png'} alt={playing ? t('imageViewer.pause') : t('imageViewer.play')} />
					</div>
					<div className="controls-group controls-group-right">
						{image.mediaType !== 'VIDEO' && onOpenEditor && (
							<div className="controls-button" onClick={onOpenEditor} title={t('imageViewer.editImage')}>
								<EditImageIcon />
							</div>
						)}
						{onDelete && (
							<div className="controls-button" onClick={onDelete} title={t('imageViewer.delete')}>
								<img src="/legacy/delete.png" alt={t('imageViewer.delete')} />
							</div>
						)}
						{image.mediaType !== 'VIDEO' && (
							<div className="controls-button" onClick={() => setHd((on) => !on)} title={hd ? t('imageViewer.hdOff') : t('imageViewer.hdOn')}>
								<HdIcon active={hd} />
							</div>
						)}
						<div className="controls-button" onClick={toggleFullscreen} title={t('imageViewer.fullScreen')}>
							<img src="/legacy/full-screen.png" alt={t('imageViewer.fullScreen')} />
						</div>
					</div>
				</div>
			) : (
				<div className="controls-bar" style={{ top: controlsTop }}>
					{onGoToDirectory ? (
						<div id="controls-topleft" onClick={onGoToDirectory} title={t('imageViewer.goToDirectory')}>
							<DirectoryLinkIcon />
						</div>
					) : onDelete ? (
						<div id="controls-topleft" onClick={onDelete} title={t('imageViewer.delete')}>
							<img src="/legacy/delete.png" alt={t('imageViewer.delete')} />
						</div>
					) : (
						// Invisible same-size stand-in (02/09/2026) - without a real
						// topleft icon (the public share viewer is read-only),
						// .controls-bar's `justify-content: space-between` only had
						// two children left (Play, Fullscreen), which pins Play to
						// the bar's left edge instead of the frame's own center -
						// a real ask, "keep the Play button horizontally centered".
						<div className="controls-bar-spacer" aria-hidden="true" />
					)}
					<div id="controls-top" onClick={() => setPlaying((p) => !p)} title={playing ? t('imageViewer.pause') : t('imageViewer.play')}>
						<img src={playing ? '/legacy/pause.png' : '/legacy/play.png'} alt={playing ? t('imageViewer.pause') : t('imageViewer.play')} />
					</div>
					<div id="controls-topright" onClick={toggleFullscreen} title={t('imageViewer.fullScreen')}>
						<img src="/legacy/full-screen.png" alt={t('imageViewer.fullScreen')} />
					</div>
				</div>
			)}

			{hasPrevious && (
				<div id="controls-left" onClick={onPrevious}>
					<img src="/legacy/left.png" alt={t('imageViewer.previousPicture')} />
				</div>
			)}
			{hasNext && (
				<div id="controls-right" onClick={onNext}>
					<img src="/legacy/right.png" alt={t('imageViewer.nextPicture')} />
				</div>
			)}

			<PostIt
				sequenceDescription={sequenceDescription}
				imageDescription={image.description}
				onOpenSequence={onOpenSequencePopup}
				onOpenImage={onOpenImagePopup}
				style={{ top: controlsTop + CONTROLS_BAR_HEIGHT_PX + POSTIT_GAP_PX }}
				fadeDelayMs={postItFadeDelayMs}
				resetKey={image.id}
				revealKey={postItRevealKey}
			/>
		</div>
	);
}
