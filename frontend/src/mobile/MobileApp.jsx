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

import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { galleryApi } from '../api/gallery';
import { attributeApi, directoryTreeApi, searchApi } from '../api/navigation';
import { shareApi } from '../api/share';
import { copyTextToClipboard } from '../clipboard';
import { MobileIcon } from './MobileIcon';
import { MobileImageViewer } from './MobileImageViewer';
import { MobileMosaicView } from './MobileMosaicView';
import { MobilePostIt } from './MobilePostIt';
import { MobileSharePopup } from './MobileSharePopup';
import { MobileSlidePanel } from './MobileSlidePanel';
import { MobileNavPanel } from './MobileNavPanel';
import { MobileMenuPanel } from './MobileMenuPanel';
import { MobileImageEditPage } from './MobileImageEditPage';
import { MobileSequenceEditPage } from './MobileSequenceEditPage';
import { MobileSearchPage } from './MobileSearchPage';
import { MobileUploadPage } from './MobileUploadPage';
import { MobileCameraPage } from './MobileCameraPage';
import { MobileMoviePage } from './MobileMoviePage';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * Replaces mindex.jsp/midx.js's `#home-page` and its five satellite pages -
 * the mobile counterpart to GalleryScreen/SearchScreen, sharing the exact
 * same `api/*` layer (galleryApi/directoryTreeApi/attributeApi/searchApi)
 * as the desktop tree, per decision #9's "one hooks/API layer written once".
 * Reduced in the same specific ways the §7 audit found in the legacy
 * source - not a smaller-viewport Gallery/SearchScreen:
 *
 * - Single-level browse-in/back-out navigation (`explorer`), not the
 *   expandable `DirectoryTree`.
 * - No slideshow/full-screen/delete, no keyboard shortcuts - swipe only
 *   (MobileImageViewer).
 * - Description-only edit forms for both the picture and the sequence -
 *   no rating, date, geolocation, group, rename, or attribute editing.
 * - A dedicated camera-capture entry alongside the FilePond publish flow.
 * - No Admin/Tags screens at all (decision #9 - `admin.jsp` never had a
 *   mobile counterpart).
 *
 * `viewMode` (09/09/2026, explicit ask - a genuinely new screen, no legacy
 * counterpart to reduce from) - 'single' (MobileImageViewer, one image at a
 * time - the session-start default) or 'mosaic' (MobileMosaicView, a grid of
 * the current sequence's thumbnails), switched by pinching out/in rather
 * than a button - see MobileImageViewer's/MobileMosaicView's own doc
 * comments and usePinchGesture for the gesture handling itself. Both share
 * the exact same `images`/`currentId` this component already tracks for the
 * single-image screen - there's still only one "current image" for the
 * whole home page, just two different ways of looking at/changing it.
 *
 * `activePage` mirrors legacy's `pagecontainer("change", ...)` navigation:
 * null means the home page is showing; any other value is one of the five
 * full-screen pages. `activePanel` is the two left-side slide-out panels
 * (`#nav-panel`/`#menu-panel`), which can coexist with the home page (they
 * overlay it) but not with a full page.
 */
export function MobileApp() {
	const { t } = useTranslation();
	const { logout } = useAuth();
	const [currentPath, setCurrentPath] = useState('');
	const [subdirectories, setSubdirectories] = useState([]);
	const [browsedDirectory, setBrowsedDirectory] = useState(null);
	const [images, setImages] = useState([]);
	const [currentId, setCurrentId] = useState(null);
	const [allAttributes, setAllAttributes] = useState([]);
	const [error, setError] = useState(null);
	// 403s (RBAC BROWSE denials) get a translated, temporary "Forbidden
	// access" message instead of raw backend text - see GalleryScreen's own
	// identical comment (the desktop equivalent of this same fix).
	const reportError = useForbiddenAwareError(setError);

	// Search results replace the browsable image list in place, exactly like
	// legacy's `explorer.query()` swapping the shared cache's data source -
	// there's no separate results screen. `searchResultDirectory` is resolved
	// on demand per current image (ImageResponse.directoryId), same as
	// desktop's SearchScreen, since search results can span many sequences;
	// `browsedDirectory` (from ordinary navigation) already knows its own
	// directory directly and doesn't need that resolution.
	const [searchActive, setSearchActive] = useState(false);
	// The criteria of the search currently being browsed - what "Share
	// search result" re-submits for the server to freeze (04/10/2026).
	const [lastSearchFilters, setLastSearchFilters] = useState(null);
	const [searchResultDirectory, setSearchResultDirectory] = useState(null);

	const [postitVisible, setPostitVisible] = useState(false);
	const [activePanel, setActivePanel] = useState(null); // null | 'nav' | 'menu'
	const [activePage, setActivePage] = useState(null); // null | 'search' | 'upload' | 'camera' | 'movie' | 'imgEdit' | 'dirEdit'
	// 'single' (one image at a time, MobileImageViewer) | 'mosaic' (grid of
	// the current sequence's thumbnails, MobileMosaicView) - explicit ask,
	// 09/09/2026. 'single' is the session-start default (explicit ask); a
	// pinch gesture switches between the two (MobileImageViewer's
	// onPinchOutAtRatioOne / MobileMosaicView's onPinchIn below) - nothing
	// else changes it, in particular navigating to a different sequence
	// deliberately does *not* reset it back to 'single' (only session start
	// does, per the ask), so mosaic-browsing several sequences in a row
	// doesn't mean re-pinching back into it after every navigation.
	const [viewMode, setViewMode] = useState('single');
	// The header/footer corner buttons (Menu/Quit/Go/Edit) - visible by
	// default at session start (explicit ask, 13/09/2026 - MobileApp
	// remounts fresh on every sign-in, App.jsx's own `isAuthenticated ?
	// <MobileApp /> : <MobileLoginPage />`, so this initial value is
	// exactly "at session start"), double-tapping the picture still
	// toggles them from there same as before (MobileImageViewer's own
	// `onTap`, single tap deliberately retired there, 09/09/2026 - freeing
	// up plain single tap for the mosaic screen to use unambiguously as
	// "select this thumbnail"). Originally ported from mindex.jsp's home
	// page header/footer exactly as marked up (l.78/121):
	// `data-fullscreen="true" data-tap-toggle="true"` - jQuery Mobile's own
	// "hidden chrome, tap the content to reveal it" mode (a single tap
	// there, legacy has no mosaic screen to disambiguate from), used
	// nowhere else in the legacy source (every other page's header is
	// `data-tap-toggle="false"`, see MobilePage) - legacy itself always
	// started hidden; this default is a deliberate deviation from that,
	// not a port.
	const [controlsVisible, setControlsVisible] = useState(true);

	// Share popup (25/09/2026, new footer "Share" button - no legacy
	// equivalent). Own error/status state, own reportShareError instance -
	// same "each independent error message gets its own useForbiddenAwareError
	// call" pattern desktop's ConfigPanel already established, not a second
	// consumer of the top-level `error`/`reportError` above (that one's error
	// line can be hidden behind an open side panel exactly the way
	// MobileNavPanel's own `error` prop already had to work around - this
	// popup renders its own error inline instead, for the same reason).
	const [sharePopupOpen, setSharePopupOpen] = useState(false);
	const [shareBusy, setShareBusy] = useState(false);
	const [shareError, setShareError] = useState(null);
	const [shareStatus, setShareStatus] = useState(null);
	const reportShareError = useForbiddenAwareError(setShareError);

	useEffect(() => {
		attributeApi.listAll().then(setAllAttributes).catch(reportError);
	}, []);

	// One function for both "browse into a subdirectory" (explorer.changeDir)
	// and "browse to the parent" (explorer.cdParent): the resolution rule is
	// the same one GalleryScreen's handleSelectNode/handleNavigateUp already
	// established - indexed already -> fetch it; not indexed but has files on
	// disk -> index it now (first-browse-indexes, Design.md §16.3); neither ->
	// no directory row at all, an empty/never-indexed level like a bare
	// year/month. `node` carries indexed/imageCount when navigating from a
	// listed subdirectory; omitted (parent/root navigation, including the
	// root itself) falls back to a plain lookup with a 404-means-unindexed
	// fallback.
	//
	// The root (path "") used to be hardcoded to "never has a directory,
	// never has pictures" without even trying the lookup below - wrong: a
	// picture published with auto-sort off while nothing was selected lands
	// in a real Directory row with path "" (ImportService.computeTargetPath),
	// so root can legitimately have pictures of its own, same as any other
	// path. Reported live: a picture uploaded that way from the root never
	// appeared after logging back in, because the app never asked the
	// backend whether root had anything - it assumed the answer without
	// checking, for every single visit.
	// `preferredImageId` (new, 12/09/2026 - the Search->Go handoff below) lets
	// a caller that already knows exactly which image it wants selected in
	// the destination directory override the "just land on the first image"
	// default - the search result being jumped to might not be the first
	// image in its sequence. Falls back to the same imgs[0] behavior when
	// omitted or when the requested id isn't actually in the loaded list
	// (defensive only - every real caller passes an id it already knows is
	// there).
	const navigateTo = useCallback(
		async (path, node, preferredImageId) => {
			setError(null);
			try {
				let resolved = null;
				if (node) {
					resolved = node.indexed
						? await directoryTreeApi.byPath(path)
						: node.imageCount > 0
							? await directoryTreeApi.index(path)
							: null;
				} else {
					try {
						resolved = await directoryTreeApi.byPath(path);
					} catch (err) {
						if (err.status !== 404) throw err;
						// Root specifically (not just any unindexed intermediate
						// ancestor - an earlier root-path fix already covers
						// "root can have pictures of its own", this extends it to
						// "root can have pictures never yet indexed at all") gets
						// the same first-browse-indexes fallback `node.imageCount >
						// 0` gives every other node above - there's no node object
						// for root to read an imageCount from (it isn't listed as
						// anyone's child), so this attempts indexing directly and
						// treats "no images to index" (400 -
						// DirectoryIndexerService.indexDirectory's own guard) as the
						// genuinely empty case. Found live (31/08/2026): a photo
						// placed directly at the storage root was invisible forever,
						// on every load, same root cause as desktop's
						// handleSelectRoot. Intermediate ancestors (cdParent to a
						// bare year/month) keep the plain 404-means-null behavior -
						// not what was reported, and attempting to index every
						// unindexed ancestor on every "back" tap is a bigger
						// behavior change than this fix calls for.
						if (path !== '') {
							resolved = null;
						} else {
							try {
								resolved = await directoryTreeApi.index('');
							} catch (indexErr) {
								if (indexErr.status === 400) {
									resolved = null;
								} else {
									throw indexErr;
								}
							}
						}
					}
				}
				setSearchActive(false);
				setCurrentPath(path);
				setBrowsedDirectory(resolved);
				const kids = await directoryTreeApi.listChildren(path);
				setSubdirectories(kids);
				const imgs = resolved ? await galleryApi.listImages(resolved.id) : [];
				setImages(imgs);
				setCurrentId(preferredImageId != null && imgs.some((img) => img.id === preferredImageId) ? preferredImageId : (imgs[0]?.id ?? null));
				// The Navigation panel is no longer closed here just because the
				// destination has pictures (explicit ask, 28/08/2026) - it stays
				// open through a selection, so drilling down and comparing several
				// sequences in a row doesn't mean reopening "Go" by hand each time.
				// It closes instead on the first double-tap or swipe on the
				// picture itself (MobileImageViewer's onTap/onPrevious/onNext,
				// wired below - onTap itself now only fires on a double tap,
				// 09/09/2026, see its own doc) - the moment the user actually
				// wants to look at or browse the image rather than the tree.
			} catch (err) {
				reportError(err);
			}
		},
		[],
	);

	useEffect(() => {
		navigateTo('', null);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	function cdParent() {
		const slash = currentPath.lastIndexOf('/');
		const parentPath = slash > 0 ? currentPath.slice(0, slash) : '';
		navigateTo(parentPath, null);
	}

	const currentIndex = images.findIndex((img) => img.id === currentId);
	const currentImage = currentIndex >= 0 ? images[currentIndex] : null;
	const activeDirectory = searchActive ? searchResultDirectory : browsedDirectory;

	useEffect(() => {
		if (!searchActive || currentImage == null) return;
		if (searchResultDirectory?.id === currentImage.directoryId) return;
		directoryTreeApi
			.byId(currentImage.directoryId)
			.then(setSearchResultDirectory)
			.catch(reportError);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [searchActive, currentImage?.directoryId]);

	async function handleSearch(filters) {
		// Real bug found live (03/09/2026): a single, unpaged request left
		// `pageSize` unset, which the backend then defaults to the signed-in
		// user's own `maxQueryLength` setting (10 by default) - any match
		// past that silently never made it into `images` at all, not a
		// pagination gap the user could scroll past. MobileSearchPage's own
		// doc comment about a deliberate "one-shot query, no infinite
		// scroll" describes not re-fetching more *while browsing* (unlike
		// desktop's SearchScreen) - it was never meant to mean "only ever
		// show the first page's worth of matches". Fetches every page up
		// front instead, in large batches (100 at a time, an explicit
		// pageSize override - same mechanism desktop's own SearchScreen
		// uses for its own paging) to keep the round-trip count low
		// regardless of how small the user's real maxQueryLength happens to
		// be, then hands the *complete* result to `images` as before - the
		// one-shot "whole list handed over at once" model is unchanged,
		// only what counts as "complete" is fixed.
		let page = 0;
		let allImages = [];
		while (true) {
			const result = await searchApi.search({ ...filters, page, pageSize: 100 });
			allImages = allImages.concat(result.images);
			if (!result.hasMore) break;
			page += 1;
		}
		setSearchActive(true);
		setLastSearchFilters(filters);
		setSearchResultDirectory(null);
		setImages(allImages);
		setCurrentId(allImages[0]?.id ?? null);
		setActivePage(null);
	}

	async function handleSaveImageEdit(patch) {
		if (currentId == null) return;
		setError(null);
		try {
			const updated = await galleryApi.updateImage(currentId, patch);
			setImages((prev) => prev.map((img) => (img.id === currentId ? updated : img)));
			setActivePage(null);
			setPostitVisible(true);
		} catch (err) {
			reportError(err);
		}
	}

	async function handleSaveSequenceEdit(patch) {
		if (!activeDirectory) return;
		setError(null);
		try {
			const updated = await directoryTreeApi.updateDetail(activeDirectory.id, patch);
			if (searchActive) {
				setSearchResultDirectory(updated);
			} else {
				setBrowsedDirectory(updated);
			}
			setActivePage(null);
			setPostitVisible(true);
		} catch (err) {
			reportError(err);
		}
	}

	// A fresh import can land anywhere (EXIF auto-sort) - re-navigate to wherever it actually went, same as
	// desktop's handleImported walking the tree to the new path.
	function handleImported(importResponse) {
		setActivePage(null);
		navigateTo(importResponse.directory.path, { path: importResponse.directory.path, indexed: true, imageCount: 1 });
	}

	if (activePage === 'search') {
		return <MobileSearchPage allAttributes={allAttributes} onSearch={handleSearch} onHome={() => setActivePage(null)} onLogout={logout} />;
	}
	if (activePage === 'upload') {
		return (
			<MobileUploadPage
				currentDirectoryPath={currentPath}
				onImported={handleImported}
				onHome={() => setActivePage(null)}
				onLogout={logout}
			/>
		);
	}
	if (activePage === 'camera') {
		return (
			<MobileCameraPage
				currentDirectoryPath={currentPath}
				onImported={handleImported}
				onHome={() => setActivePage(null)}
				onLogout={logout}
			/>
		);
	}
	if (activePage === 'movie') {
		return (
			<MobileMoviePage
				currentDirectoryPath={currentPath}
				onImported={handleImported}
				onHome={() => setActivePage(null)}
				onLogout={logout}
			/>
		);
	}
	if (activePage === 'imgEdit') {
		return <MobileImageEditPage image={currentImage} onSave={handleSaveImageEdit} onHome={() => setActivePage(null)} onLogout={logout} />;
	}
	if (activePage === 'dirEdit') {
		return (
			<MobileSequenceEditPage directory={activeDirectory} onSave={handleSaveSequenceEdit} onHome={() => setActivePage(null)} onLogout={logout} />
		);
	}

	// Both header/footer left-side buttons are toggles (tap again to close),
	// not just openers - there's no backdrop to tap-away-from any more (see
	// MobileSlidePanel), so the button itself is the only way to close its
	// own panel other than a drill-down/menu-item action doing it as a
	// side effect.
	//
	// While browsing a Search result (single-image or mosaic - both track
	// "the current image" through the same currentId), the "Go" button used
	// to just reopen the nav panel on whatever ordinary-browsing location it
	// was left at before the search started - the search result itself
	// (which sequence the currently displayed image actually lives in) was
	// never consulted (explicit ask, 12/09/2026). Fixed by making "Go" jump
	// the navigation gallery to that image's own directory first - the same
	// resolution the little effect above keeps `searchResultDirectory`
	// current with, reused directly when it's already up to date rather
	// than re-fetched - landing on that image (preferredImageId, see
	// navigateTo's own doc), *then* opening the nav panel on that new
	// location. navigateTo's own setSearchActive(false) is what actually
	// discards the search result, exactly as asked ("the search result is
	// lost") - not a separate step here.
	async function toggleNav() {
		if (searchActive) {
			if (currentImage == null) {
				setActivePanel((p) => (p === 'nav' ? null : 'nav'));
				return;
			}
			try {
				const directory =
					searchResultDirectory?.id === currentImage.directoryId
						? searchResultDirectory
						: await directoryTreeApi.byId(currentImage.directoryId);
				await navigateTo(directory.path, { path: directory.path, indexed: true, imageCount: 1 }, currentImage.id);
			} catch (err) {
				reportError(err);
				return;
			}
			setActivePanel('nav');
			return;
		}
		setActivePanel((p) => (p === 'nav' ? null : 'nav'));
	}
	function toggleMenu() {
		setActivePanel((p) => (p === 'menu' ? null : 'menu'));
	}

	function toggleShare() {
		setShareError(null);
		setShareStatus(null);
		setSharePopupOpen((open) => !open);
	}

	// Preferred option (explicit ask, 25/09/2026): hand off to the OS's own
	// native share sheet (Web Share API) rather than just copying a link -
	// `navigator.share` only exists in a secure context (HTTPS/localhost,
	// same restriction already documented on copyTextToClipboard) and isn't
	// universally supported, so it's feature-detected here with a graceful
	// fallback to the same clipboard copy desktop's own share buttons use,
	// not a hard requirement. Returns which of the two actually happened -
	// the caller only needs to show its own "copied" confirmation for the
	// fallback path, native share already shows its own OS-level UI.
	//
	// `AbortError` is `navigator.share` rejecting because the user picked
	// "Cancel" on the native sheet - entirely normal, not a real failure, so
	// it's swallowed here rather than surfaced as an error the caller would
	// otherwise report.
	async function shareLink(path, title) {
		const url = `${window.location.origin}${path}`;
		if (navigator.share) {
			try {
				await navigator.share({ title, url });
				return 'shared';
			} catch (err) {
				if (err.name === 'AbortError') return 'cancelled';
				throw err;
			}
		}
		await copyTextToClipboard(url);
		return 'copied';
	}

	async function handleShareImage() {
		if (currentId == null) return;
		setShareBusy(true);
		setShareError(null);
		setShareStatus(null);
		try {
			const { token } = await shareApi.getImageShareToken(currentId);
			const result = await shareLink(`/share/image/${currentId}?token=${encodeURIComponent(token)}`, t('common.appName'));
			if (result === 'copied') {
				setShareStatus(t('gallery.linkCopied'));
			} else if (result === 'shared') {
				setSharePopupOpen(false);
			}
		} catch (err) {
			reportShareError(err);
		} finally {
			setShareBusy(false);
		}
	}

	// Shares the whole search result (04/10/2026, explicit ask) - same
	// server-side freeze as desktop (SharedSearchService). When the result
	// was cut at the app-wide maximum, the popup stays open with the warning
	// even after the phone's share sheet, so the sharer actually sees it.
	async function handleShareSearchResult() {
		if (!lastSearchFilters) return;
		setShareBusy(true);
		setShareError(null);
		setShareStatus(null);
		try {
			const shared = await shareApi.shareSearch(lastSearchFilters);
			const warning = shared.truncated ? t('search.shareTruncated', { count: shared.sharedCount, total: shared.matchCount }) : null;
			const result = await shareLink(`/share/search/${shared.id}?token=${encodeURIComponent(shared.token)}`, t('common.appName'));
			if (warning) {
				setShareStatus(warning);
			} else if (result === 'copied') {
				setShareStatus(t('gallery.linkCopied'));
			} else if (result === 'shared') {
				setSharePopupOpen(false);
			}
		} catch (err) {
			reportShareError(err);
		} finally {
			setShareBusy(false);
		}
	}

	async function handleShareSequence() {
		if (!activeDirectory) return;
		setShareBusy(true);
		setShareError(null);
		setShareStatus(null);
		try {
			const { token } = await shareApi.getSequenceShareToken(activeDirectory.id);
			const result = await shareLink(`/share/sequence/${activeDirectory.id}?token=${encodeURIComponent(token)}`, t('common.appName'));
			if (result === 'copied') {
				setShareStatus(t('gallery.linkCopied'));
			} else if (result === 'shared') {
				setSharePopupOpen(false);
			}
		} catch (err) {
			reportShareError(err);
		} finally {
			setShareBusy(false);
		}
	}

	// Either side panel (Navigation, pushed open by "Go" - or Menu, opened by
	// "Menu") closes on the first double-tap or swipe on the picture itself
	// (double-tap rather than a single tap since 09/09/2026 - MobileImageViewer's
	// onTap doc), rather than as a side effect of selecting a directory
	// (explicit ask, 28/08/2026 - see navigateTo's own comment). That first
	// gesture only closes the panel; it doesn't also toggle the corner
	// controls or advance to the next/previous image - the user's asking to
	// see the picture properly, not to do both at once.
	//
	// Originally checked `activePanel === 'nav'` specifically, so a tap
	// with the Menu panel open fell through to the "advance/toggle" branch
	// instead of closing it - reported live (31/08/2026): tapping the image
	// did nothing visible while Menu was open, only while Go was. Fixed by
	// checking `activePanel` itself (truthy for either panel) rather than
	// naming one of the two.
	//
	// Passed to MobileMosaicView's own `onDoubleTap` too (09/09/2026) -
	// nothing about this function is specific to the single-image screen
	// (it only ever looks at `activePanel`/`controlsVisible`), so the same
	// double-tap on the mosaic screen reveals/closes the exact same chrome,
	// or closes an open panel first, identically.
	function handleImageTap() {
		if (activePanel) {
			setActivePanel(null);
			return;
		}
		setControlsVisible((v) => !v);
	}
	function handleImagePrevious() {
		if (activePanel) {
			setActivePanel(null);
			return;
		}
		setCurrentId(images[currentIndex - 1].id);
	}
	function handleImageNext() {
		if (activePanel) {
			setActivePanel(null);
			return;
		}
		setCurrentId(images[currentIndex + 1].id);
	}
	// Pinch out at ratio 1 (single-image screen) -> mosaic; pinch in
	// (mosaic screen) -> single-image screen; tapping a different thumbnail
	// in the mosaic -> that becomes the current image, without leaving the
	// mosaic (explicit ask - "the white frame moves to it", nothing about
	// switching screens). All three follow the same "an open side panel
	// eats the first gesture instead" convention as the tap/swipe handlers
	// just above, rather than acting and leaving the panel open underneath.
	function handlePinchOutToMosaic() {
		if (activePanel) {
			setActivePanel(null);
			return;
		}
		setViewMode('mosaic');
	}
	function handlePinchInToSingle() {
		if (activePanel) {
			setActivePanel(null);
			return;
		}
		setViewMode('single');
	}
	function handleMosaicSelect(id) {
		if (activePanel) {
			setActivePanel(null);
			return;
		}
		setCurrentId(id);
	}

	return (
		<div className="mobile-home-page">
			<MobileSlidePanel open={activePanel === 'nav'}>
				<MobileNavPanel
					currentPath={currentPath}
					subdirectories={subdirectories}
					onBack={cdParent}
					onSelect={(node) => navigateTo(node.path, node)}
					error={activePanel === 'nav' ? error : null}
				/>
			</MobileSlidePanel>

			<MobileSlidePanel open={activePanel === 'menu'}>
				<MobileMenuPanel
					onClose={() => setActivePanel(null)}
					onHome={() => setActivePage(null)}
					onGo={toggleNav}
					onSearch={() => setActivePage('search')}
					onCamera={() => setActivePage('camera')}
					onFilm={() => setActivePage('movie')}
					onPublish={() => setActivePage('upload')}
				/>
			</MobileSlidePanel>

			<MobileSharePopup
				open={sharePopupOpen}
				onClose={() => setSharePopupOpen(false)}
				onShareImage={handleShareImage}
				onShareSequence={searchActive ? handleShareSearchResult : handleShareSequence}
				sequenceLabel={searchActive ? t('mobile.shareSearchResult') : undefined}
				canShareImage={currentImage != null && currentImage.mediaType !== 'VIDEO'}
				canShareSequence={searchActive ? images.length > 0 && lastSearchFilters != null : activeDirectory != null}
				busy={shareBusy}
				error={shareError}
				status={shareStatus}
			/>

			<div className="mobile-home-main">
				{/* The mosaic screen now reveals/hides this chrome the same way
				    the single-image screen does - a double-tap
				    (MobileMosaicView's own onDoubleTap, 09/09/2026, sharing
				    MobileImageViewer's exact same handleImageTap - reported
				    broken here initially since this screen's chrome was, at
				    the time, unconditionally always visible instead). Single
				    tap stays unambiguous either way - selecting a thumbnail
				    on this screen, nothing on the single-image screen. */}
				<header className={`mobile-home-header${controlsVisible ? ' visible' : ''}${activePanel ? ' shifted' : ''}`}>
					<button type="button" onClick={toggleMenu}>
						<MobileIcon name="bars" /> {t('mobile.menu')}
					</button>
					<img className="mobile-home-logo" src="/legacy/photodiary-banner-short.png" alt={t('common.appName')} />
					<button type="button" onClick={logout}>
						{t('mobile.quit')} <MobileIcon name="power" />
					</button>
				</header>

				{/* Not rendered here while the nav panel is open (13/09/2026) - that
				    panel is a fully opaque overlay sitting right on top of this
				    spot (see MobileSlidePanel's own doc comment), so the message
				    would otherwise render invisibly behind it; MobileNavPanel shows
				    the same text itself in that case (see its own `error` prop). */}
				{error && activePanel !== 'nav' && <p className="form-error">{error}</p>}

				<div className={`mobile-home-content${activePanel ? ' shifted' : ''}`}>
					{viewMode === 'mosaic' ? (
						<MobileMosaicView
							images={images}
							currentId={currentId}
							onSelect={handleMosaicSelect}
							onPinchIn={handlePinchInToSingle}
							onDoubleTap={handleImageTap}
						/>
					) : (
						<MobileImageViewer
							image={currentImage}
							hasPrevious={currentIndex > 0}
							hasNext={currentIndex >= 0 && currentIndex < images.length - 1}
							onPrevious={handleImagePrevious}
							onNext={handleImageNext}
							onTap={handleImageTap}
							onPinchOutAtRatioOne={handlePinchOutToMosaic}
							nextImage={currentIndex >= 0 && currentIndex < images.length - 1 ? images[currentIndex + 1] : null}
						/>
					)}
					{/* Hidden entirely on the mosaic screen, not just visually -
					    there's no single "current photo" reading position while
					    browsing a grid of thumbnails. */}
					{viewMode === 'single' && (
						<MobilePostIt
							visible={postitVisible}
							onHide={() => setPostitVisible(false)}
							sequenceDescription={activeDirectory?.description}
							imageDescription={currentImage?.description}
							onOpenSequence={() => activeDirectory && setActivePage('dirEdit')}
							onOpenImage={() => currentImage && setActivePage('imgEdit')}
						/>
					)}
				</div>

				<footer className={`mobile-home-footer${controlsVisible ? ' visible' : ''}${activePanel ? ' shifted' : ''}`}>
					<button type="button" onClick={toggleNav}>
						<MobileIcon name="bullets" /> {t('mobile.go')}
					</button>
					<button type="button" onClick={toggleShare}>
						<MobileIcon name="share" /> {t('mobile.share')}
					</button>
					<button type="button" onClick={() => setPostitVisible((v) => !v)}>
						<MobileIcon name="comment" /> {t('mobile.edit')}
					</button>
				</footer>
			</div>
		</div>
	);
}
