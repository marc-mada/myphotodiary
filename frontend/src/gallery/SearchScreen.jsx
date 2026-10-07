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
import { galleryApi } from '../api/gallery';
import { attributeApi, directoryTreeApi, groupApi, meApi, searchApi } from '../api/navigation';
import { shareApi } from '../api/share';
import { copyTextToClipboard } from '../clipboard';
import { DirectoryDetailPanel } from './DirectoryDetailPanel';
import { Filmstrip } from './Filmstrip';
import { ImageEditForm } from './ImageEditForm';
import { ImageViewer } from './ImageViewer';
import { Popup } from './Popup';
import { hasSearchCriteria, MIN_TEXT_SEARCH_LENGTH } from './searchCriteria';
import { SequenceGeolocationForm } from './SequenceGeolocationForm';
import { StarRating } from './StarRating';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * Replaces the legacy QuerySvr-backed search (Design.md §16.5) - a direct
 * ask that this screen present results exactly like the Gallery screen
 * does (same filmstrip on the right, same viewer with the same delete/
 * play/full-screen controls and the same post-it/popup behavior), not a
 * separate thumbnail grid with its own modal preview. `ImageViewer`/
 * `Filmstrip` are the literal same components GalleryScreen uses. The
 * layout mirrors Gallery's own `.gallery-layout` shell too (11/09/2026,
 * explicit ask): every filter criterion lives in the left `.gallery-sidebar`
 * now, stacked above the download buttons (see their own doc comment
 * below) - there's no horizontal filter bar along the top edge any more,
 * unlike this screen's own earlier version.
 *
 * The post-it's sequence comment needs the current result's *owning*
 * directory, which search results don't carry inline (they can come from
 * many different directories) - resolved on demand via
 * `ImageResponse.directoryId` -> `GET /api/directories/{id}`, refetched
 * every time the current selection's directory actually changes.
 *
 * Default page size is the viewer's own "Nombre maximum de photos dans les
 * recherches" setting (User.maxQueryLength, ported from legacy
 * UserConfiguration - see backend/.../GalleryController.search) - the page
 * size shown is whatever the first response comes back with, and every
 * subsequent page asks for the same (omitting `pageSize` lets the backend
 * keep resolving it from the caller's own setting). Loading more is
 * triggered by scrolling the filmstrip itself to its end (Filmstrip's
 * onEndReached), not a separate button or the page's own scroll.
 *
 * `onGoToDirectory` (01/09/2026, explicit ask, given by App.jsx) - replaces
 * this screen's own delete button with a "go to this picture's sequence in
 * Gallery" one instead (ImageViewer's `onGoToDirectory` prop - see its own
 * doc comment for why the two are mutually exclusive). Search loses delete
 * entirely as a result, not just this one shortcut to it (it never had
 * another) - a deliberate scope reduction: deleting now happens from
 * Gallery, which this button is the bridge to.
 *
 * Free-text filter (03/09/2026, explicit ask, "without changing the DB
 * schema"; revised same day) - matches the tag/sequence-name/sequence-
 * description/image-description disjunction - see GalleryService.search's
 * own doc for the exact logic asked for (`minDate AND maxDate AND
 * minRating AND (tag OR name OR seqDesc OR imgDesc)` - tag selection is
 * now one alternative *inside* that parenthesized group, not a separate
 * hard filter). Debounced client-side (`debouncedText`, 300ms) rather than
 * searching on every keystroke - the only filter here that's actually
 * typed character by character, unlike the rest (`<select>`s/date
 * pickers); also skipped below `MIN_TEXT_SEARCH_LENGTH` (`./searchCriteria`
 * - an echo of the backend's own real enforcement, purely to avoid firing a
 * request for a fragment the backend would ignore anyway).
 *
 * No search runs at all while every filter is empty (explicit ask,
 * 10/09/2026) - `hasCriteria` (`./searchCriteria`, shared with
 * MobileSearchPage so the two screens can't quietly judge "any criteria?"
 * differently) gates the effect below that would otherwise fire on mount
 * with nothing set, showing every image in the gallery rather than
 * prompting the user to actually filter something.
 */
export function SearchScreen({ onGoToDirectory }) {
	const { t } = useTranslation();
	const [allAttributes, setAllAttributes] = useState([]);
	const [selectedAttributes, setSelectedAttributes] = useState([]);
	const [minRating, setMinRating] = useState('');
	// Date-range filter (legacy queryEditPopup's #fromDate/#toDate, "since"/
	// "till") - Design.md §15, legacy feature-parity audit: search had no
	// equivalent at all before this.
	const [fromDate, setFromDate] = useState('');
	const [toDate, setToDate] = useState('');
	// Free-text filter (03/09/2026, explicit ask, revised same day) -
	// matched against the tag/sequence-name/sequence-description/image-
	// description disjunction - see GalleryService.search's own doc for the
	// exact logic (the backend, not this constant, is the authoritative
	// enforcement of the 4-character minimum below - this is only a
	// request-count optimization). `debouncedText` (not `text` itself) is
	// what actually feeds the search below - unlike every other filter here
	// (a `<select>`/date picker, changed rarely), this is free text typed
	// character by character; searching on every keystroke would fire a
	// request per letter for no benefit the user would ever see mid-word.
	const [text, setText] = useState('');
	const [debouncedText, setDebouncedText] = useState('');
	const [images, setImages] = useState([]);
	const [currentId, setCurrentId] = useState(null);
	const [directory, setDirectory] = useState(null);
	const [page, setPage] = useState(0);
	const [hasMore, setHasMore] = useState(false);
	const [totalCount, setTotalCount] = useState(null);
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState(null);
	// Share links (04/10/2026): "Link copied!", or a warning when the shared
	// search result was cut at the app-wide maximum. Auto-clears.
	const [shareFeedback, setShareFeedback] = useState(null);
	const shareFeedbackTimerRef = useRef(null);
	// 403s (RBAC BROWSE denials) get a translated, temporary "Forbidden
	// access" message instead of raw backend text - see GalleryScreen's own
	// identical comment.
	const reportError = useForbiddenAwareError(setError);
	const [slideShowIntervalMs, setSlideShowIntervalMs] = useState(null);
	const [postItFadeDelayMs, setPostItFadeDelayMs] = useState(null);
	// Same "which groups can this user move a sequence into" resolution as
	// GalleryScreen (12/09/2026, sequence group picker) - DirectoryDetailPanel
	// is the exact same shared component here, showing the search result's
	// own sequence-edit popup.
	const [assignableGroupNames, setAssignableGroupNames] = useState([]);
	// null | 'sequence' | 'geolocation' | 'image' - see GalleryScreen's
	// comment on the same state; no 'upload' here, Search doesn't publish.
	const [openPopup, setOpenPopup] = useState(null);
	// Bulk/single original-picture download to a user-chosen folder
	// (10/09/2026, explicit ask) - see the sidebar buttons' own handlers
	// below for why these are two separate flags rather than one shared
	// "downloading" boolean (each disables only its own button's label,
	// not the other one's, while still disabling *both* buttons so a
	// second directory picker can't be opened mid-download).
	const [downloadingAll, setDownloadingAll] = useState(false);
	const [downloadingCurrent, setDownloadingCurrent] = useState(false);
	const [downloadProgress, setDownloadProgress] = useState(null);

	useEffect(() => {
		const timer = setTimeout(() => {
			const trimmed = text.trim();
			// Below the minimum, commit an empty string rather than the raw
			// fragment (functionally identical either way once it reaches
			// the backend - both are "no text constraint" there - but skips
			// re-running the search while the user is still typing through
			// a too-short fragment the backend would just ignore anyway).
			setDebouncedText(trimmed.length >= MIN_TEXT_SEARCH_LENGTH ? trimmed : '');
		}, 300);
		return () => clearTimeout(timer);
	}, [text]);

	useEffect(() => {
		attributeApi.listAll().then(setAllAttributes).catch(reportError);
		meApi
			.get()
			.then((me) => {
				setSlideShowIntervalMs(me.slideShowInterval * 1000);
				setPostItFadeDelayMs(me.postItFadeDelay * 1000);
				if (me.primaryRoleName === 'ADMIN') {
					groupApi
						.list()
						.then((groups) => setAssignableGroupNames(groups.map((g) => g.groupName)))
						.catch(reportError);
				} else if (me.primaryRoleName === 'WRITER') {
					meApi
						.groups()
						.then((groups) => setAssignableGroupNames(groups.filter((g) => g.role === 'WRITER').map((g) => g.groupName)))
						.catch(reportError);
				}
			})
			.catch(reportError);
	}, []);

	// Guards against fetching the same page twice concurrently - a real bug
	// found live, not a hypothetical one: the filmstrip's IntersectionObserver
	// (Filmstrip.jsx) reports its current intersection state immediately
	// every time it's (re)created, and it *was* being torn down and
	// recreated on every single render (see handleEndReached below) - once
	// auto-scroll during playback brought the sentinel within range, every
	// subsequent image advance re-fired "load more" again before `page`
	// state had caught up from the previous fetch, appending the last
	// page's images twice. `findIndex` then always resolves a repeated id
	// to its *first* occurrence, so paging forward past that point kept
	// bouncing between the same two duplicated images instead of ever
	// reaching the true end - "loops on the last two images forever".
	// A ref (synchronous, unlike state) makes the guard effective the
	// instant a fetch starts, regardless of what triggers a duplicate call.
	const fetchingPageRef = useRef(null);

	const runSearch = useCallback(
		async (targetPage) => {
			if (fetchingPageRef.current === targetPage) return;
			fetchingPageRef.current = targetPage;
			setLoading(true);
			setError(null);
			try {
				const result = await searchApi.search({
					attributeNames: selectedAttributes,
					minRating: minRating === '' ? null : Number(minRating),
					fromDate: fromDate || null,
					toDate: toDate || null,
					text: debouncedText || null,
					page: targetPage,
				});
				setImages((prev) => (targetPage === 0 ? result.images : [...prev, ...result.images]));
				setPage(result.page);
				setHasMore(result.hasMore);
				setTotalCount(result.totalCount);
				if (targetPage === 0) {
					setCurrentId(result.images[0]?.id ?? null);
				}
			} catch (err) {
				reportError(err);
			} finally {
				setLoading(false);
				fetchingPageRef.current = null;
			}
		},
		[selectedAttributes, minRating, fromDate, toDate, debouncedText],
	);

	const hasCriteria = hasSearchCriteria({
		attributeNames: selectedAttributes,
		minRating: minRating === '' ? null : Number(minRating),
		fromDate: fromDate || null,
		toDate: toDate || null,
		text: debouncedText || null,
	});

	// New filters -> start over from page 0. Skipped entirely while nothing
	// is actually set (explicit ask, 10/09/2026) - this effect used to fire
	// unconditionally, including on mount with every filter still at its
	// default, which searched (and showed) the *entire* gallery rather than
	// prompting the user to filter something first. Clears any results left
	// over from a previous, now-abandoned set of filters rather than leaving
	// them on screen looking like they still match nothing selected.
	useEffect(() => {
		if (!hasCriteria) {
			setImages([]);
			setCurrentId(null);
			setPage(0);
			setHasMore(false);
			setTotalCount(null);
			setError(null);
			return;
		}
		runSearch(0);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [selectedAttributes, minRating, fromDate, toDate, debouncedText]);

	// Memoized so the filmstrip's IntersectionObserver effect (which depends
	// on this identity) only actually resubscribes when `page` itself
	// changes - not on every unrelated render (e.g. each image advance
	// during playback), which is what caused the churn described above.
	const handleEndReached = useCallback(() => {
		runSearch(page + 1);
	}, [runSearch, page]);

	const currentIndex = images.findIndex((img) => img.id === currentId);
	const currentImage = currentIndex >= 0 ? images[currentIndex] : null;

	// The post-it's sequence comment follows whichever image is currently
	// displayed - refetched only when that image's *directory* actually
	// changes (not on every image, since a sequence usually holds several).
	useEffect(() => {
		if (currentImage == null) {
			setDirectory(null);
			return;
		}
		if (directory?.id === currentImage.directoryId) return;
		directoryTreeApi
			.byId(currentImage.directoryId)
			.then(setDirectory)
			.catch(reportError);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [currentImage?.directoryId]);

	// Plain filter selection, no ancestor auto-inclusion (explicit ask,
	// 28/08/2026) - unlike DirectoryDetailPanel/ImageEditForm's own
	// <select multiple>, which assigns an entity's actual tag set and
	// therefore needs the same parent-inheritance rule the backend applies
	// (addWithAncestors). Here the selection is only ever a filter passed
	// straight to GalleryService.search, which already matches on exactly
	// the attribute names given - adding parents here would just narrow
	// results for no reason.
	function handleAttributesChange(e) {
		setSelectedAttributes(Array.from(e.target.selectedOptions, (option) => option.value));
	}

	// Same widget as ImageEditForm's own rating (03/09/2026, explicit ask) -
	// StarRating itself has no notion of "no rating"/"Any" (it always calls
	// onChange(level) on click, since assigning an image's own rating never
	// needs to go back to unrated by clicking a star - the old <select> this
	// replaces did have an explicit "Any" option though, and that has to
	// stay reachable here (min-rating is an optional filter, not a required
	// value). Clicking the star that's already the current minimum toggles
	// it back off instead - the minimum-rating equivalent of "Any", without
	// changing StarRating itself (still "the same widget", not a fork).
	function handleMinRatingClick(level) {
		setMinRating((prev) => (prev === String(level) ? '' : String(level)));
	}

	// Alphabetical (03/09/2026, explicit ask) - allAttributes arrives in the
	// backend's own tree-traversal order, not sorted by name.
	const sortedAttributes = [...allAttributes].sort((a, b) => a.name.localeCompare(b.name));

	async function handleUpdateImage(patch) {
		if (currentId == null) return;
		setError(null);
		try {
			const updated = await galleryApi.updateImage(currentId, patch);
			setImages((prev) => prev.map((img) => (img.id === currentId ? updated : img)));
			// A patch touching tags may have minted a brand-new one on the fly
			// (GalleryService's find-or-create, 03/09/2026 - restores legacy's
			// #newImgParam) - refresh the shared tag list so it shows up as a
			// selectable option right away.
			if (patch.attributeNames) {
				attributeApi.listAll().then(setAllAttributes).catch(reportError);
			}
		} catch (err) {
			reportError(err);
		}
	}

	// Search never had its own way to delete beyond the button ImageViewer's
	// onGoToDirectory now replaces (no button on ImageEditForm either) -
	// deleting happens from Gallery now, which handleGoToGalleryDirectory
	// below is the bridge to.
	function showShareFeedback(message, warning) {
		clearTimeout(shareFeedbackTimerRef.current);
		setShareFeedback({ message, warning });
		shareFeedbackTimerRef.current = setTimeout(() => setShareFeedback(null), warning ? 8000 : 2500);
	}

	useEffect(() => () => clearTimeout(shareFeedbackTimerRef.current), []);

	// Shares the whole search result (04/10/2026, explicit ask) - the server
	// re-runs these same criteria and freezes at most the app-wide maximum of
	// pictures, the first ones in this same order (SharedSearchService); when
	// it had to cut, the sharer is told how many of how many were shared.
	async function handleShareSearchResult() {
		setError(null);
		try {
			const shared = await shareApi.shareSearch({
				attributeNames: selectedAttributes,
				minRating: minRating === '' ? null : Number(minRating),
				fromDate: fromDate || null,
				toDate: toDate || null,
				text: debouncedText || null,
			});
			await copyTextToClipboard(`${window.location.origin}/share/search/${shared.id}?token=${encodeURIComponent(shared.token)}`);
			if (shared.truncated) {
				showShareFeedback(t('search.shareTruncated', { count: shared.sharedCount, total: shared.matchCount }), true);
			} else {
				showShareFeedback(t('gallery.linkCopied'), false);
			}
		} catch (err) {
			reportError(err);
		}
	}

	async function handleCopyImageLink() {
		if (!currentImage) return;
		setError(null);
		try {
			const { token } = await shareApi.getImageShareToken(currentImage.id);
			await copyTextToClipboard(`${window.location.origin}/share/image/${currentImage.id}?token=${encodeURIComponent(token)}`);
			showShareFeedback(t('gallery.linkCopied'), false);
		} catch (err) {
			reportError(err);
		}
	}

	function handleGoToGalleryDirectory() {
		if (!currentImage || !directory) return;
		onGoToDirectory?.({ directoryPath: directory.path, imageId: currentImage.id });
	}

	async function handleUpdateDirectory(patch) {
		setError(null);
		try {
			setDirectory(await directoryTreeApi.updateDetail(directory.id, patch));
			// See handleUpdateImage's own comment - same find-or-create tag
			// refresh, on the sequence-form side.
			if (patch.attributeNames) {
				attributeApi.listAll().then(setAllAttributes).catch(reportError);
			}
		} catch (err) {
			reportError(err);
		}
	}

	// Errors reach DirectoryDetailPanel, shown under its fields (05/10/2026).
	async function handleRenameDirectory(newName, date) {
		setError(null);
		setDirectory(await directoryTreeApi.rename(directory.id, newName, date));
		setOpenPopup(null);
	}

	// Two picture names collide inside the same download batch (two
	// different sequences can each have their own, e.g., camera-numbered
	// "IMG_0001.jpg") - kept as-is on the first occurrence (the common
	// case), only disambiguated with a short numeric suffix once a real
	// collision actually happens, rather than renaming every file up front
	// "just in case".
	function uniqueFileName(name, usedNames) {
		if (!usedNames.has(name)) {
			usedNames.add(name);
			return name;
		}
		const dot = name.lastIndexOf('.');
		const base = dot > 0 ? name.slice(0, dot) : name;
		const ext = dot > 0 ? name.slice(dot) : '';
		let n = 2;
		let candidate = `${base}-${n}${ext}`;
		while (usedNames.has(candidate)) {
			n += 1;
			candidate = `${base}-${n}${ext}`;
		}
		usedNames.add(candidate);
		return candidate;
	}

	/**
	 * Downloads each image's real original bytes (`exportUrl` - same
	 * endpoint/fetch-as-blob technique ImageEditForm's own single-picture
	 * "Download original" already uses, `Content-Disposition: attachment`)
	 * directly into a folder the user picks, via the File System Access
	 * API (`showDirectoryPicker`) - a *directory* picker, not the browser's
	 * own per-file save dialog, per the explicit ask. Chromium-only
	 * (Chrome/Edge) as of this writing - Firefox/Safari have no
	 * `showDirectoryPicker` at all; `handleDownloadAllOriginals`/
	 * `handleDownloadCurrentOriginal` below surface a clear, translated
	 * error rather than silently doing nothing on those browsers, a real,
	 * disclosed platform limitation rather than assumed away.
	 *
	 * One failed picture doesn't abort the rest (same "don't let one bad
	 * item stop the whole batch" spirit as the backend's own batch
	 * index/reset/delete) - failures are collected and reported as a
	 * count once the batch finishes, not thrown on the first one.
	 */
	async function saveImagesToDirectory(imageList, onProgress) {
		if (typeof window.showDirectoryPicker !== 'function') {
			throw new Error(t('search.downloadNotSupported'));
		}
		const dirHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
		const usedNames = new Set();
		const failedNames = [];
		let done = 0;
		for (const image of imageList) {
			try {
				const response = await fetch(image.exportUrl, { credentials: 'same-origin' });
				if (!response.ok) throw new Error(`Download failed: ${response.status}`);
				const blob = await response.blob();
				const fileHandle = await dirHandle.getFileHandle(uniqueFileName(image.name, usedNames), { create: true });
				const writable = await fileHandle.createWritable();
				await writable.write(blob);
				await writable.close();
			} catch {
				failedNames.push(image.name);
			}
			done += 1;
			onProgress?.(done, imageList.length);
		}
		return failedNames;
	}

	// Not just `images` (this screen's own on-screen list, fed incrementally
	// by the filmstrip's infinite scroll, Filmstrip's onEndReached) - "all
	// the original pictures returned by the Search" means the complete
	// match set, fetched here independently of however much of it has
	// actually been scrolled into view so far. Always re-fetches fresh
	// rather than reusing `images` even when nothing's left to scroll
	// (`!hasMore`) - simpler than tracking whether that's still true, and
	// this app's real search results are small enough (a personal photo
	// library, not a commercial archive) that the extra round trip costs
	// nothing worth optimizing away.
	async function fetchAllMatchingImages() {
		let currentPage = 0;
		let all = [];
		for (;;) {
			const result = await searchApi.search({
				attributeNames: selectedAttributes,
				minRating: minRating === '' ? null : Number(minRating),
				fromDate: fromDate || null,
				toDate: toDate || null,
				text: debouncedText || null,
				page: currentPage,
				pageSize: 100,
			});
			all = all.concat(result.images);
			if (!result.hasMore) break;
			currentPage += 1;
		}
		return all;
	}

	// showDirectoryPicker() rejects with a DOMException named "AbortError"
	// when the user simply closes/cancels the native folder picker - not a
	// real failure, so it's swallowed silently here rather than surfaced
	// through the same `error` paragraph a genuine download problem uses.
	async function handleDownloadAllOriginals() {
		setError(null);
		setDownloadingAll(true);
		setDownloadProgress(null);
		try {
			const allImages = await fetchAllMatchingImages();
			if (allImages.length === 0) return;
			const failedNames = await saveImagesToDirectory(allImages, (done, total) => setDownloadProgress({ done, total }));
			if (failedNames.length > 0) setError(t('search.downloadFailed', { count: failedNames.length }));
		} catch (err) {
			if (err?.name !== 'AbortError') reportError(err);
		} finally {
			setDownloadingAll(false);
			setDownloadProgress(null);
		}
	}

	async function handleDownloadCurrentOriginal() {
		if (!currentImage) return;
		setError(null);
		setDownloadingCurrent(true);
		try {
			const failedNames = await saveImagesToDirectory([currentImage]);
			if (failedNames.length > 0) setError(t('search.downloadFailed', { count: failedNames.length }));
		} catch (err) {
			if (err?.name !== 'AbortError') reportError(err);
		} finally {
			setDownloadingCurrent(false);
		}
	}

	return (
		<div className="search-screen">
			{error && <p className="form-error">{error}</p>}
			{shareFeedback && <p className={shareFeedback.warning ? 'share-warning' : 'copy-feedback'}>{shareFeedback.message}</p>}

			<div className="gallery-layout">
				<aside className="gallery-sidebar">
					{/* Moved here from a horizontal bar along the top edge
					    (explicit ask, 11/09/2026) - every criterion now stacks in
					    this sidebar instead, ahead of the two download buttons
					    below (pushed down as a result, not moved on purpose - they
					    keep their own doc comment where they're defined). */}
					<label className="search-text">
						{t('search.text')}
						<input type="text" value={text} onChange={(e) => setText(e.target.value)} placeholder={t('search.textPlaceholder')} />
					</label>
					<label className="search-attributes">
						{t('search.tags')}
						<select multiple size={8} className="tag-multiselect" value={selectedAttributes} onChange={handleAttributesChange}>
							{sortedAttributes.map((a) => (
								<option key={a.id} value={a.name}>
									{a.name}
								</option>
							))}
						</select>
					</label>
					<label className="search-min-rating">
						{t('search.minimumRating')}
						<StarRating rating={minRating === '' ? -1 : Number(minRating)} onChange={handleMinRatingClick} />
					</label>
					<label className="search-date-bound">
						{t('search.since')}
						<input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} max={toDate || undefined} />
					</label>
					<label className="search-date-bound">
						{t('search.till')}
						<input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} min={fromDate || undefined} />
					</label>

					{/* Thin grey line separating the criteria above from the two
					    download buttons below (explicit ask, 11/09/2026) - a real
					    <hr>, not a styled <div>, since it's genuinely a divider
					    between two unrelated groups of controls, not decoration.
					    No margin of its own - .gallery-sidebar's own `gap` already
					    spaces every child evenly, this one included. */}
					<hr className="search-criteria-separator" />

					{/* Same original-picture bytes/endpoint as ImageEditForm's own
					    single-picture "Download original" (exportUrl) - these two
					    just write straight into a folder the user picks instead of
					    the browser's own per-file save dialog, and the first one
					    covers the *whole* search result, not only the current
					    picture. Same sidebar/button styling as Gallery's own
					    Publish/Sequence-geolocation pair (explicit ask - "similar
					    to the publish-button"), via dedicated classes rather than
					    reusing .publish-button/.geolocation-button themselves -
					    those names describe *their* actions, not downloading, even
					    though the visual rule (app.css) is identical. */}
					<button
						type="button"
						className="download-all-button"
						onClick={handleDownloadAllOriginals}
						disabled={downloadingAll || downloadingCurrent || images.length === 0}
					>
						{downloadingAll
							? downloadProgress
								? t('search.downloadingProgress', downloadProgress)
								: t('search.downloading')
							: t('search.downloadAllOriginals')}
					</button>
					<button
						type="button"
						className="download-current-button"
						onClick={handleDownloadCurrentOriginal}
						disabled={downloadingAll || downloadingCurrent || !currentImage}
					>
						{downloadingCurrent ? t('search.downloading') : t('search.downloadCurrentOriginal')}
					</button>
				</aside>

				<div className="gallery-main">
					{totalCount != null && (
						<p className="search-result-count">{t('search.matchCount', { count: totalCount })}</p>
					)}
					<div className="gallery-body">
						<ImageViewer
							image={currentImage}
							hasPrevious={currentIndex > 0}
							hasNext={currentIndex >= 0 && currentIndex < images.length - 1}
							onPrevious={() => setCurrentId(images[currentIndex - 1].id)}
							onNext={() => setCurrentId(images[currentIndex + 1].id)}
							onGoToDirectory={handleGoToGalleryDirectory}
							onCopySequenceLink={handleShareSearchResult}
							copySequenceLinkTitle={t('search.copySearchLink')}
							onCopyImageLink={handleCopyImageLink}
							showCommentButton={false}
							disableShortcuts={openPopup !== null}
							slideShowIntervalMs={slideShowIntervalMs ?? undefined}
							postItFadeDelayMs={postItFadeDelayMs ?? undefined}
							sequenceDescription={directory?.description}
							onOpenSequencePopup={() => directory && setOpenPopup('sequence')}
							onOpenImagePopup={() => currentImage && setOpenPopup('image')}
							nextImage={currentIndex >= 0 && currentIndex < images.length - 1 ? images[currentIndex + 1] : null}
							emptyImageSrc="/legacy/default.png"
							emptyMessage={t('imageViewer.noMatches')}
						/>
						<Filmstrip
							images={images}
							currentId={currentId}
							onSelect={setCurrentId}
							onEndReached={handleEndReached}
							hasMore={hasMore}
							loadingMore={loading}
						/>
					</div>
				</div>
			</div>

			{directory != null && (
				<Popup open={openPopup === 'sequence'} onClose={() => setOpenPopup(null)}>
					<DirectoryDetailPanel
						directory={directory}
						allAttributes={allAttributes}
						assignableGroupNames={assignableGroupNames}
						onUpdate={handleUpdateDirectory}
						onRename={handleRenameDirectory}
					/>
				</Popup>
			)}

			{directory != null && (
				<Popup open={openPopup === 'geolocation'} onClose={() => setOpenPopup(null)} title={t('gallery.sequenceGeolocation')}>
					<SequenceGeolocationForm directory={directory} onUpdate={handleUpdateDirectory} />
				</Popup>
			)}

			{currentImage != null && (
				<Popup open={openPopup === 'image'} onClose={() => setOpenPopup(null)}>
					<ImageEditForm image={currentImage} onUpdate={handleUpdateImage} allAttributes={allAttributes} />
				</Popup>
			)}
		</div>
	);
}
