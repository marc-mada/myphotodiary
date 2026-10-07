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
import { attributeApi, directoryTreeApi, groupApi, meApi } from '../api/navigation';
import { shareApi } from '../api/share';
import { copyTextToClipboard } from '../clipboard';
import { DirectoryDetailPanel } from './DirectoryDetailPanel';
import { DirectoryTree } from './DirectoryTree';
import { Filmstrip } from './Filmstrip';
import { ImageEditForm } from './ImageEditForm';
import { ImageEditorPopup } from './ImageEditorPopup';
import { ImageViewer } from './ImageViewer';
import { Popup } from './Popup';
import { SequenceGeolocationForm } from './SequenceGeolocationForm';
import { Uploader } from './Uploader';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * Replaces the legacy gallery half of index.jsp/idx.js (Design.md §16.9
 * points 2 and 3 - upload/gallery plus navigation/indexing). No mobile tree
 * yet (Design.md decision #9) - this is the desktop presentation; mindex.jsp's
 * equivalent is its own pass once this one is settled, sharing this file's
 * data-fetching logic the way idxdata.js is shared today.
 *
 * `initialTarget` (01/09/2026, explicit ask) - `{ directoryPath, imageId }`,
 * given by App.jsx after the Search screen's new "go to directory" button
 * (ImageViewer's `onGoToDirectory`) sends the user here. Consumed once, in
 * the mount effect below, via the exact same `expandToPath` mechanism
 * already built for "jump to wherever an import landed" (`handleImported`) -
 * this is the second, independent caller of that same tree-walking
 * machinery, not a parallel implementation. `imageId` alone needs its own
 * one-shot handling in `reloadImages` (a ref, not state - see there) since
 * `expandToPath` only knows how to resolve/select a *directory*, not which
 * of its images should end up current. `onInitialTargetConsumed` clears the
 * target back in App.jsx right away (not waiting for the async directory
 * resolution to finish, since everything needed from it is already captured
 * into local state/refs by then) - otherwise a later, unrelated visit to
 * this screen (remounted fresh each time, same as always) would keep
 * re-applying the same stale search-originated target forever.
 */
export function GalleryScreen({ initialTarget, onInitialTargetConsumed }) {
	const { t } = useTranslation();
	// '' (root) is the default selection, not null/"nothing selected" - a
	// direct ask: the pictures published directly at the root (above every
	// year) should already be showing right after sign-in, not require
	// clicking into the tree first.
	const [selectedPath, setSelectedPath] = useState('');
	const [directory, setDirectory] = useState(null);
	const [allAttributes, setAllAttributes] = useState([]);
	const [images, setImages] = useState([]);
	const [currentId, setCurrentId] = useState(null);
	const [error, setError] = useState(null);
	// 403s (RBAC BROWSE/write-action denials) get a translated, temporary
	// "Forbidden access" message instead of raw backend text - every other
	// error keeps the plain setError(err.message) behavior below.
	const reportError = useForbiddenAwareError(setError);
	// Slideshow delay (legacy UserConfiguration.slideShowInterval) - loaded
	// once from the caller's own settings (MeController), editable from the
	// Admin screen's Config panel exactly like legacy's admin.jsp did.
	const [slideShowIntervalMs, setSlideShowIntervalMs] = useState(null);
	// Post-it auto-fade delay (28/08/2026, explicit ask) - same loading
	// pattern as slideShowIntervalMs, no legacy field behind it (see the
	// V8 migration's own comment).
	const [postItFadeDelayMs, setPostItFadeDelayMs] = useState(null);
	// The group names this user is allowed to move a sequence into
	// (12/09/2026, sequence group picker, explicit ask) - empty for
	// READER/LOWER (no dropdown at all, see DirectoryDetailPanel's own
	// doc), every existing group for ADMIN (groupApi.list, admin-only), or
	// just this user's own WRITER-role groups for WRITER (meApi.groups,
	// filtered client-side - "one of its own groups as a WRITER").
	const [assignableGroupNames, setAssignableGroupNames] = useState([]);
	// Bumped after every import to force DirectoryTree to remount and
	// re-fetch from scratch - auto-sort (Uploader) can create brand-new
	// directories server-side that an already-expanded tree branch has no
	// other way to learn about (each TreeNode caches its own children once
	// fetched).
	const [treeRefreshKey, setTreeRefreshKey] = useState(0);
	// Set after an import so DirectoryTree can auto-expand down to (and
	// select) wherever the file actually landed - auto-sort can put it
	// somewhere the user was never looking, and hunting for it by hand
	// through a tree that only expands one tiny triangle at a time is
	// exactly the friction this exists to remove.
	const [expandToPath, setExpandToPath] = useState(null);
	// Which popup (if any) is open - null, 'upload', 'sequence',
	// 'geolocation', 'image' or 'editor' (25/09/2026, the rudimentary crop/
	// rotate editor - ImageEditorPopup). Every one of these used to be an
	// always-visible or toggled-inline panel; a direct ask moved them all
	// behind their own trigger (a post-it area or a toolbar button) so the
	// image itself keeps as much of the frame as possible, same spirit as
	// legacy's own popups (importPopup/dirEditPopup/mapPopup/imageEditPopup)
	// which the old inline panels here had drifted away from.
	const [openPopup, setOpenPopup] = useState(null);
	// Brief "Link copied!" confirmation (02/09/2026, image-link/sequence-link
	// buttons) - copyTextToClipboard itself gives no visible feedback on
	// success (neither of its two underlying mechanisms do), unlike a
	// native OS copy affordance would; a
	// transient message is the minimal signal that the click actually did
	// something. Auto-clears itself (setTimeout) rather than needing a
	// second click to dismiss.
	const [copyFeedback, setCopyFeedback] = useState(null);
	// Cache-busting for rotate (02/09/2026), extended to crop (25/09/2026) -
	// an edited image's bytes change in place at the exact same URL
	// (ImageStorageService.rotateImage/cropImage), but GalleryController's
	// own image endpoints are deliberately cached (Cache-Control: private,
	// max-age=3600 - "image bytes for a given id never change in place" was
	// true until Rotate). Rather than touch that caching policy (still
	// correct for every *other* endpoint), each edited image gets its own
	// nonce here, appended as `?v=` to just that image's thumbnail/web/
	// original URLs when rendering (decoratedImages below) - the browser
	// sees a genuinely different URL and re-fetches, every other still-
	// cached image is untouched. `originalUrl` joined the set with Crop
	// (rotate didn't need it - nothing in this screen ever loads the true
	// original) - ImageEditorPopup fetches it directly for its editing
	// canvas, and needs to see the just-cropped bytes on its own next open,
	// not the pre-crop ones still sitting in the browser's HTTP cache.
	const [imageNonces, setImageNonces] = useState({});
	// One-shot: the specific image `initialTarget` asked for, consumed by
	// `reloadImages` the first time it actually loads a directory's images
	// after mount - a ref rather than state, since clearing it must not
	// itself trigger another fetch (see `reloadImages`'s own comment).
	const pendingImageIdRef = useRef(initialTarget?.imageId ?? null);

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

	// Resolve the root directory on mount - a picture published at the root
	// (auto-sort off, nothing selected) lands in a real Directory row with
	// path "" (ImportService/GalleryService), same as any other path, so it
	// needs the same byPath lookup any other selection gets, not an assumed
	// "root never has anything" the way this used to just start empty.
	//
	// `initialTarget` (Search's "go to directory" button) overrides this
	// default: root ("") is resolved the same way as any ordinary visit
	// (handleSelectRoot), since expandToPath's own tree-walking mechanism
	// doesn't cover the root row (it isn't a TreeNode - see DirectoryTree's
	// own comment); anything else goes through expandToPath exactly like a
	// post-import jump does.
	useEffect(() => {
		if (initialTarget && initialTarget.directoryPath !== '') {
			setExpandToPath(initialTarget.directoryPath);
		} else {
			handleSelectRoot();
		}
		onInitialTargetConsumed?.();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	// A never-indexed root, unlike any other tree node, has no
	// DirectoryTreeNode/imageCount handed to this call (root isn't itself
	// listed as anyone's child) - so unlike handleSelectNode below, there's
	// no `node.imageCount > 0` to check before deciding whether to index.
	// Found live (31/08/2026): a photo placed directly at the storage root
	// was invisible forever, on every reload, because this path used to just
	// give up on a 404 rather than attempt the same first-browse-indexes
	// fallback every other node already gets. Fixed by attempting to index
	// root directly on a 404 and treating "no images to index" (400 -
	// DirectoryIndexerService.indexDirectory's own guard) as the genuinely
	// empty case; any other failure is a real error, not silently swallowed.
	async function handleSelectRoot() {
		setError(null);
		try {
			const resolved = await directoryTreeApi.byPath('');
			setSelectedPath('');
			setDirectory(resolved);
		} catch (err) {
			if (err.status !== 404) {
				reportError(err);
				return;
			}
			try {
				const indexed = await directoryTreeApi.index('');
				setSelectedPath('');
				setDirectory(indexed);
			} catch (indexErr) {
				if (indexErr.status === 400) {
					setSelectedPath('');
					setDirectory(null);
				} else {
					setError(indexErr.message);
				}
			}
		}
	}

	const reloadImages = useCallback(async () => {
		if (directory == null) {
			setImages([]);
			return;
		}
		const list = await galleryApi.listImages(directory.id);
		setImages(list);
		// One-shot consumption of initialTarget's imageId (see this
		// component's own doc comment) - checked and cleared via a ref, not
		// state, specifically so clearing it doesn't change this callback's
		// own identity and re-trigger the effect that calls it below.
		// Consumed regardless of whether the id is actually found in this
		// directory's list (e.g. the image was deleted in the meantime) -
		// it should never be retried against a later, unrelated directory.
		const pending = pendingImageIdRef.current;
		if (pending != null) {
			pendingImageIdRef.current = null;
			if (list.some((img) => img.id === pending)) {
				setCurrentId(pending);
				return;
			}
		}
		setCurrentId((prev) => (list.some((img) => img.id === prev) ? prev : (list[0]?.id ?? null)));
	}, [directory]);

	useEffect(() => {
		reloadImages().catch(reportError);
	}, [reloadImages]);

	// Selecting a tree node resolves it to a DB directory - indexing it on
	// the fly if it isn't yet (a folder freshly dropped on disk, Design.md
	// decision #6/#7, has files but no DB row until something indexes it -
	// browsing it is as good a trigger as any, same spirit as the legacy
	// "initial indexing allowed on first browse access", Design.md §16.3).
	async function handleSelectNode(node) {
		setError(null);
		try {
			const resolved = node.indexed
				? await directoryTreeApi.byPath(node.path)
				: node.imageCount > 0
					? await directoryTreeApi.index(node.path)
					: null;
			setSelectedPath(node.path);
			setDirectory(resolved);
		} catch (err) {
			reportError(err);
		}
	}

	// Up-arrow shortcut (ImageViewer's onNavigateUp) - legacy's own
	// parentDirPath (idxdata.js): drop the last path segment, or clear the
	// selection entirely if already at a top-level directory (there's no
	// tree "root" node to select - legacy's own equivalent there is "" too).
	// The parent's own row is already visible in the tree (you had to
	// expand through it to reach the current selection), so unlike rename/
	// import this doesn't need a tree remount - just updating `selectedPath`
	// re-highlights the already-rendered parent row.
	async function handleNavigateUp() {
		if (!selectedPath) return;
		const slash = selectedPath.lastIndexOf('/');
		const parentPath = slash > 0 ? selectedPath.slice(0, slash) : '';
		if (!parentPath) {
			// The parent of a top-level directory is the root itself - it can
			// have pictures of its own too (see handleSelectRoot), not just an
			// always-empty landing spot.
			await handleSelectRoot();
			return;
		}
		setError(null);
		try {
			const resolved = await directoryTreeApi.byPath(parentPath);
			setSelectedPath(parentPath);
			setDirectory(resolved);
		} catch (err) {
			if (err.status === 404) {
				// Not every intermediate directory (e.g. a bare year/month) has
				// its own DB row - same as an unindexed, empty node in the tree.
				setSelectedPath(parentPath);
				setDirectory(null);
			} else {
				reportError(err);
			}
		}
	}

	async function handleUpdateDirectory(patch) {
		setError(null);
		try {
			setDirectory(await directoryTreeApi.updateDetail(directory.id, patch));
			// A patch touching tags may have minted a brand-new one on the fly
			// (GalleryService/DirectoryIndexerService's find-or-create,
			// 03/09/2026 - restores legacy's #newDirParam/#newImgParam) - refresh
			// the shared tag list so it shows up as a selectable option right
			// away, not just after the next full remount of this screen.
			if (patch.attributeNames) {
				attributeApi.listAll().then(setAllAttributes).catch(reportError);
			}
		} catch (err) {
			reportError(err);
		}
	}

	// Errors are not caught here (05/10/2026): DirectoryDetailPanel shows
	// them under its own fields, rather than behind the popup.
	async function handleRenameDirectory(newName, date) {
		setError(null);
		const renamed = await directoryTreeApi.rename(directory.id, newName, date);
		setSelectedPath(renamed.path);
		setDirectory(renamed);
		setOpenPopup(null);
		// Same reason as handleImported: each TreeNode caches its own
		// children once fetched, so the parent's cached list still shows
		// the old name/path after a rename until the tree is forced to
		// re-fetch from scratch. Remount it and walk back down to the
		// new path, rather than leaving the stale name in view until the
		// next full page load (login/logout, as reported).
		setTreeRefreshKey((k) => k + 1);
		setExpandToPath(renamed.path);
	}

	async function handleUpdateImage(patch) {
		if (currentId == null) return;
		setError(null);
		try {
			await galleryApi.updateImage(currentId, patch);
			await reloadImages();
			// See handleUpdateDirectory's own comment - same find-or-create tag
			// refresh, on the image-form side.
			if (patch.attributeNames) {
				attributeApi.listAll().then(setAllAttributes).catch(reportError);
			}
		} catch (err) {
			reportError(err);
		}
	}

	async function handleDeleteImage() {
		if (currentId == null) return;
		if (!window.confirm(t('gallery.deleteImageConfirm'))) return;
		setError(null);
		try {
			await galleryApi.removeImage(currentId);
			await reloadImages();
		} catch (err) {
			reportError(err);
		}
	}

	// image-link/sequence-link (02/09/2026, explicit ask) - the absolute URL
	// is built here, client-side, from window.location.origin - the backend
	// only ever hands back the bare signed token (ShareLinkResponse's own
	// doc: it has no reliable way to know its own public-facing scheme/host
	// from behind a reverse proxy), so this is the one place that actually
	// knows what to put in front of it.
	async function copyShareLink(path) {
		setError(null);
		try {
			const url = `${window.location.origin}${path}`;
			await copyTextToClipboard(url);
			setCopyFeedback(t('gallery.linkCopied'));
			setTimeout(() => setCopyFeedback(null), 2500);
		} catch (err) {
			reportError(err);
		}
	}

	async function handleCopyImageLink() {
		if (currentId == null) return;
		const { token } = await shareApi.getImageShareToken(currentId);
		await copyShareLink(`/share/image/${currentId}?token=${encodeURIComponent(token)}`);
	}

	async function handleCopySequenceLink() {
		if (directory == null) return;
		const { token } = await shareApi.getSequenceShareToken(directory.id);
		await copyShareLink(`/share/sequence/${directory.id}?token=${encodeURIComponent(token)}`);
	}

	// No internal try/catch (25/09/2026, since the image editor popup
	// moved in) - unlike this screen's other action handlers, this one is
	// only ever called from inside ImageEditorPopup now (ImageViewer's own
	// bottom-toolbar center icon just opens that popup, doesn't rotate
	// directly anymore), which has its own local error banner and already
	// awaits/catches this itself - letting the error propagate there avoids
	// showing the exact same failure in two separate banners at once (this
	// screen's top-level one AND the popup's).
	async function handleRotateSave(quarterTurns) {
		if (currentId == null) return;
		await galleryApi.rotateImage(currentId, quarterTurns);
		bumpImageNonce(currentId);
	}

	// "Rotate right" (preview only, 25/09/2026 - Rotate brought onto the
	// same preview/Save/Cancel workflow Transform already had) -
	// deliberately does NOT call bumpImageNonce below, same reasoning as
	// handleTransformPreview below: nothing on disk changed yet.
	async function handleRotatePreview(quarterTurns) {
		if (currentId == null) return null;
		return galleryApi.previewRotate(currentId, quarterTurns);
	}

	// Same "let the popup's own error banner handle it" reasoning as
	// handleRotateSave just above - ImageEditorPopup's own doc comment.
	async function handleCropSave(cropRequest) {
		if (currentId == null) return;
		await galleryApi.cropImage(currentId, cropRequest);
		bumpImageNonce(currentId);
	}

	// "Crop" (preview only, 25/09/2026 - same reasoning as handleRotatePreview
	// just above).
	async function handleCropPreview(request) {
		if (currentId == null) return null;
		return galleryApi.previewCrop(currentId, request);
	}

	// "Transform" (preview only) - deliberately does NOT call
	// bumpImageNonce below: nothing on disk changed (GalleryService
	// .previewTransform's own doc), so there's nothing stale to bust the
	// cache for. Returns the blob directly to the popup, which turns it
	// into its own local object URL - this screen never needs to look at
	// the bytes itself.
	async function handleTransformPreview(request) {
		if (currentId == null) return null;
		return galleryApi.previewTransform(currentId, request);
	}

	// "Save" - commits the exact warp handleTransformPreview already
	// rendered. Same shape/reasoning as handleRotateSave/handleCropSave above.
	async function handleTransformSave(request) {
		if (currentId == null) return;
		await galleryApi.transformImage(currentId, request);
		bumpImageNonce(currentId);
	}

	// See imageNonces' own comment above - forces a fresh fetch of just
	// this image's thumbnail/web/original bytes instead of the still-
	// cached, now-stale pre-edit ones. Shared by handleRotateSave/handleCropSave/
	// handleTransformSave - all three mutate the same three files in place
	// at the same URLs, so all three need the exact same cache-busting
	// (the three *preview* handlers above deliberately do not call this -
	// see their own comments).
	function bumpImageNonce(imageId) {
		setImageNonces((prev) => ({ ...prev, [imageId]: Date.now() }));
	}

	// A single import can land anywhere - remount the tree (so a brand-new
	// top-level year/month shows up at all) and walk it down to wherever
	// this file landed, rather than leaving the user to go find it.
	function handleImported(importResponse) {
		setTreeRefreshKey((k) => k + 1);
		setExpandToPath(importResponse.directory.path);
	}

	// Only ever differs from `images` for whichever image(s) were actually
	// edited this session (imageNonces' own comment above) - everything
	// else passes through unchanged, so this stays cheap even for a large
	// directory.
	const decoratedImages =
		Object.keys(imageNonces).length === 0
			? images
			: images.map((img) => {
					const nonce = imageNonces[img.id];
					if (!nonce) return img;
					return {
						...img,
						thumbnailUrl: `${img.thumbnailUrl}?v=${nonce}`,
						webUrl: `${img.webUrl}?v=${nonce}`,
						originalUrl: `${img.originalUrl}?v=${nonce}`,
					};
				});

	const currentIndex = decoratedImages.findIndex((img) => img.id === currentId);
	const currentImage = currentIndex >= 0 ? decoratedImages[currentIndex] : null;

	return (
		<div className="gallery-screen">
			{error && <p className="form-error">{error}</p>}
			{copyFeedback && <p className="copy-feedback">{copyFeedback}</p>}

			<div className="gallery-layout">
				<aside className="gallery-sidebar">
					<button type="button" className="publish-button" onClick={() => setOpenPopup('upload')}>
						{t('gallery.publishPictures')}
					</button>
					{directory != null && (
						<button type="button" className="geolocation-button" onClick={() => setOpenPopup('geolocation')}>
							{t('gallery.sequenceGeolocation')}
						</button>
					)}
					<DirectoryTree
						key={treeRefreshKey}
						selectedPath={selectedPath}
						onSelect={handleSelectNode}
						onSelectRoot={handleSelectRoot}
						expandToPath={expandToPath}
					/>
				</aside>

				<div className="gallery-main">
					<div className="gallery-body">
						<ImageViewer
							image={currentImage}
							hasPrevious={currentIndex > 0}
							hasNext={currentIndex >= 0 && currentIndex < decoratedImages.length - 1}
							onPrevious={() => setCurrentId(decoratedImages[currentIndex - 1].id)}
							onNext={() => setCurrentId(decoratedImages[currentIndex + 1].id)}
							onDelete={handleDeleteImage}
							onNavigateUp={handleNavigateUp}
							onCopyImageLink={handleCopyImageLink}
							onCopySequenceLink={handleCopySequenceLink}
							onOpenEditor={currentImage && currentImage.mediaType !== 'VIDEO' ? () => setOpenPopup('editor') : undefined}
							disableShortcuts={openPopup !== null}
							slideShowIntervalMs={slideShowIntervalMs ?? undefined}
						postItFadeDelayMs={postItFadeDelayMs ?? undefined}
							sequenceDescription={directory?.description}
							onOpenSequencePopup={() => directory && setOpenPopup('sequence')}
							onOpenImagePopup={() => currentImage && setOpenPopup('image')}
							nextImage={currentIndex >= 0 && currentIndex < decoratedImages.length - 1 ? decoratedImages[currentIndex + 1] : null}
							emptyImageSrc="/legacy/default.png"
						/>
						<Filmstrip images={decoratedImages} currentId={currentId} onSelect={setCurrentId} />
					</div>
				</div>
			</div>

			<Popup open={openPopup === 'upload'} onClose={() => setOpenPopup(null)} title={t('gallery.publishPictures')}>
				<Uploader currentDirectoryPath={selectedPath} onImported={handleImported} />
			</Popup>

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

			{currentImage != null && (
				<Popup
					open={openPopup === 'editor'}
					onClose={() => setOpenPopup(null)}
					title={t('imageEditor.title')}
					className="image-editor-popup"
				>
					<ImageEditorPopup
						image={currentImage}
						onRotatePreview={handleRotatePreview}
						onRotateSave={handleRotateSave}
						onCropPreview={handleCropPreview}
						onCropSave={handleCropSave}
						onTransformPreview={handleTransformPreview}
						onTransformSave={handleTransformSave}
						onCancel={() => setOpenPopup(null)}
					/>
				</Popup>
			)}
		</div>
	);
}
