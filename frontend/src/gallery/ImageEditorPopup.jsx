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
import { useForbiddenAwareError } from '../temporaryMessage';

// A crop rectangle (and a quad corner, below) can't shrink/collapse below
// this fraction of the image's own shorter dimension - purely a drag-
// usability guard (a handle dragged to a literal zero-size selection is
// fiddly, not a real limit the backend itself imposes - CropImageRequest/
// TransformImageRequest reject a non-positive size on their own terms).
const MIN_SELECTION_FRACTION = 0.05;

const FULL_SELECTION = { left: 0, top: 0, right: 1, bottom: 1 };

// The quad's own default - the full image's own corners, same shape as
// FULL_SELECTION above but as 4 independent draggable points rather than an
// axis-aligned rectangle. Corner order fixed project-wide (top-left,
// top-right, bottom-right, bottom-left - PerspectiveTransform's own doc on
// the backend), matching TransformImageRequest's own field names exactly.
const IDENTITY_QUAD = {
	tl: { x: 0, y: 0 },
	tr: { x: 1, y: 0 },
	br: { x: 1, y: 1 },
	bl: { x: 0, y: 1 },
};

function clamp(value, min, max) {
	return Math.min(Math.max(value, min), max);
}

/**
 * Rudimentary desktop image editor (25/09/2026, explicit ask - "is it
 * possible to create a rudimentary image editor to transform and crop an
 * image"). Rotate moves here from the bottom toolbar's own center icon
 * (ImageViewer's own doc comment on that slot), per the same ask.
 *
 * Loads {@code image.originalUrl}, not {@code webUrl} - unlike the rest of
 * this viewer (ImageViewer's own doc on why it displays the web tier), a
 * crop rectangle/quad has to be expressed in the true original's own pixel
 * coordinates (CropImageRequest/TransformImageRequest's own doc), and this
 * project has never persisted an Image's own width/height (a known gap,
 * Design.md §15) - there's no way to scale a selection made against
 * the smaller web tier up to the original's real size without first
 * knowing that real size, so the original is what gets decoded here.
 * Heavier over the network than the web tier, a deliberate simplicity-
 * over-performance trade-off for a "rudimentary" editor - not the same
 * choice as the rest of the app deliberately avoids elsewhere.
 *
 * The crop rectangle's and the quad's own on-screen geometry are both kept
 * as *fractions* (0..1) of the canvas box, not pixels - the canvas itself
 * is sized (in JS, see the `wrapperSize`/`naturalSize`/`effectiveSize`
 * logic below) so its own rendered box exactly matches the currently-shown
 * image's real aspect ratio, so a fraction here maps directly onto a
 * fraction of the true original's own pixel dimensions with no separate
 * scale factor to track.
 *
 * Canvas sizing ("fit entirely in the popup area without sliders", explicit
 * ask) - the classic `object-fit: contain` calculation, but applied by
 * hand rather than left to CSS: a plain block-level box with both `width`
 * and `height` left `auto` doesn't actually shrink-to-fit both axes
 * together just because `aspect-ratio` is set (only a *replaced* element
 * like a real `<img>`, or a box in a shrink-to-fit layout context, gets
 * that "as large as possible within both max-width and max-height"
 * treatment - checked against this exact CSS behavior before writing this,
 * not assumed). A `ResizeObserver` on the wrapper (`wrapperSize`) plus
 * `effectiveSize` (the currently-displayed image's own real dimensions -
 * the loaded original while editing, or the preview's own known
 * destWidth/destHeight while one is showing) gives both bounds explicitly;
 * `scale = min(availW/w, availH/h)` is applied to *both* dimensions at
 * once, so the result is guaranteed to fit the wrapper on both axes with
 * the ratio intact - no letterboxing to break the fraction math above, and
 * no scrollbar needed either. Deliberately not capped at 1 - a small image
 * is magnified up to fill the available area rather than staying
 * pixel-for-pixel tiny in a mostly-empty popup, matching "apply a
 * magnification ratio" literally.
 *
 * Crop, Rotate, and Transform all share one preview/Save/Cancel state
 * machine (25/09/2026, explicit ask: "make the Cancel/Save buttons state
 * machine work the same with Crop and Rotate" - Crop and Rotate used to
 * commit immediately per click, matching Rotate's own pre-existing single-
 * action behavior; Transform alone previewed first, since a flat wireframe
 * outline over an un-warped image doesn't tell you what the actual warped
 * result will look like the way Crop's own live selection rectangle
 * already did). Clicking any of the three action buttons now only ever
 * *previews* (`onRotatePreview`/`onCropPreview`/`onTransformPreview` -
 * each backed by its own read-only server endpoint, nothing written to
 * disk - GalleryService's own doc on each), tracked here as a single
 * `pending` object (`{ kind, url, size, save }`) rather than three
 * separate per-kind states - `save` is a closure over whatever params that
 * particular kind's commit needs (`onRotateSave` takes none,
 * `onCropSave`/`onTransformSave` take the exact request that produced the
 * preview), so "Save"/"Cancel" below don't need to know which kind is
 * pending to act on it. While a preview is showing: dragging and Crop/
 * Rotate/Transform are all disabled (nothing else may act until the
 * pending preview is resolved one way or the other) - "Cancel" discards it
 * (pure client-side, nothing was ever persisted) and returns to the
 * editable flat original with the selection/quad handles exactly where
 * they were left, so the user can adjust and retry; with no preview
 * showing, Cancel keeps its original meaning and closes the popup. The
 * popup itself stays open after a "Save" so edits can be chained in one
 * sitting.
 *
 * Exception for Rotate (26/09/2026, explicit ask): unlike Crop/Transform,
 * a 90-degree turn loses no information, so Rotate stays clickable while
 * its *own* preview is pending - each further click adds one quarter turn
 * (`pending.turns`, 1..3) and re-previews the cumulative result from the
 * untouched original; a 4th click wraps back to the original and simply
 * discards the preview (nothing left to save). Cancel still discards the
 * whole series at once, and Save commits it as a single rotation
 * (`onRotateSave(turns)` - one re-encode server-side, not one per click).
 * Crop/Transform remain disabled while any preview is pending, and Rotate
 * is disabled while a Crop/Transform preview is.
 */
export function ImageEditorPopup({
	image,
	onRotatePreview,
	onRotateSave,
	onCropPreview,
	onCropSave,
	onTransformPreview,
	onTransformSave,
	onCancel,
}) {
	const { t } = useTranslation();
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [busy, setBusy] = useState(false);
	const [naturalSize, setNaturalSize] = useState(null);
	const [selection, setSelection] = useState(FULL_SELECTION);
	const [quad, setQuad] = useState(IDENTITY_QUAD);
	const [dragging, setDragging] = useState(null); // 'top' | 'right' | 'bottom' | 'left' | null - an edge handle
	const [draggingCorner, setDraggingCorner] = useState(null); // 'tl' | 'tr' | 'br' | 'bl' | null
	const canvasRef = useRef(null);
	const wrapperRef = useRef(null);
	const [wrapperSize, setWrapperSize] = useState({ width: 0, height: 0 });
	// The current pending preview, if any (any of Rotate/Crop/Transform -
	// this component's own doc comment on the shared state machine) - a
	// local blob: URL (never sent anywhere, nothing was ever persisted to
	// produce it) plus its own known pixel `size` (for canvas sizing, this
	// component's own doc comment further down) and a `save` closure
	// already bound to whatever params that kind's commit needs, so
	// handleSave below can stay generic across all three kinds.
	const [pending, setPending] = useState(null);

	function discardPending() {
		setPending((prev) => {
			if (prev) URL.revokeObjectURL(prev.url);
			return null;
		});
	}

	// A committed edit (crop, rotate, or a transform Save) changes the true
	// original's real pixel dimensions/content in place at the exact same
	// `originalUrl` - GalleryScreen bumps that URL's own cache-busting
	// nonce afterward (same mechanism already built for Rotate's
	// thumbnail/web URLs, extended to originalUrl too), so this effect
	// re-fires and resets the selection/quad back to "whole image" against
	// the freshly (re)loaded bytes, rather than keeping a now-stale
	// rectangle/quad from before the edit. Also clears any lingering
	// preview - shouldn't normally still be one at this point (Crop/Rotate/
	// Transform are all disabled while a preview is pending, and a
	// successful Save already discards its own preview immediately), but
	// cheap insurance against ever displaying a preview for bytes that no
	// longer exist.
	useEffect(() => {
		setNaturalSize(null);
		setSelection(FULL_SELECTION);
		setQuad(IDENTITY_QUAD);
		discardPending();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [image.originalUrl]);

	// Revoke the preview's own blob: URL on unmount too (the popup closing
	// entirely, not just a discard/Save while it stays open) - otherwise a
	// closed-without-resolving preview would leak until the tab reloads.
	useEffect(() => {
		return () => {
			if (pending) URL.revokeObjectURL(pending.url);
		};
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	// Tracks the available space for the canvas (the wrapper below the
	// actions row) - reacts to the popup itself resizing (a window resize;
	// `.gallery-popup`'s own width/height are percentages) so the canvas
	// keeps fitting exactly, not just on first mount. No feedback loop:
	// the canvas is given an explicit pixel size, it never grows the
	// wrapper it sits in (the wrapper's own size comes from flexbox
	// distribution against *its* parent, not its child's content).
	useEffect(() => {
		const el = wrapperRef.current;
		if (!el) return undefined;
		const observer = new ResizeObserver((entries) => {
			const { width, height } = entries[0].contentRect;
			setWrapperSize({ width, height });
		});
		observer.observe(el);
		return () => observer.disconnect();
	}, []);

	useEffect(() => {
		if (!dragging) return undefined;
		function handleMove(event) {
			const box = canvasRef.current?.getBoundingClientRect();
			if (!box || box.width === 0 || box.height === 0) return;
			const fx = clamp((event.clientX - box.left) / box.width, 0, 1);
			const fy = clamp((event.clientY - box.top) / box.height, 0, 1);
			setSelection((prev) => {
				const next = { ...prev };
				if (dragging === 'left') next.left = Math.min(fx, prev.right - MIN_SELECTION_FRACTION);
				if (dragging === 'right') next.right = Math.max(fx, prev.left + MIN_SELECTION_FRACTION);
				if (dragging === 'top') next.top = Math.min(fy, prev.bottom - MIN_SELECTION_FRACTION);
				if (dragging === 'bottom') next.bottom = Math.max(fy, prev.top + MIN_SELECTION_FRACTION);
				return next;
			});
		}
		function handleUp() {
			setDragging(null);
		}
		window.addEventListener('pointermove', handleMove);
		window.addEventListener('pointerup', handleUp);
		return () => {
			window.removeEventListener('pointermove', handleMove);
			window.removeEventListener('pointerup', handleUp);
		};
	}, [dragging]);

	// Same shape as the edge-drag effect above, for the quad's own 4
	// corners - a corner is free to move anywhere within the image (no
	// minimum-distance-from-its-neighbors guard client-side, unlike the
	// edge handles' MIN_SELECTION_FRACTION): a genuinely degenerate quad is
	// instead caught server-side (PerspectiveTransform's own singularity
	// check) and surfaced through this popup's existing error banner,
	// exactly like an out-of-bounds Crop rectangle already is.
	useEffect(() => {
		if (!draggingCorner) return undefined;
		function handleMove(event) {
			const box = canvasRef.current?.getBoundingClientRect();
			if (!box || box.width === 0 || box.height === 0) return;
			const fx = clamp((event.clientX - box.left) / box.width, 0, 1);
			const fy = clamp((event.clientY - box.top) / box.height, 0, 1);
			setQuad((prev) => ({ ...prev, [draggingCorner]: { x: fx, y: fy } }));
		}
		function handleUp() {
			setDraggingCorner(null);
		}
		window.addEventListener('pointermove', handleMove);
		window.addEventListener('pointerup', handleUp);
		return () => {
			window.removeEventListener('pointermove', handleMove);
			window.removeEventListener('pointerup', handleUp);
		};
	}, [draggingCorner]);

	// Shared by all three "preview" buttons below - runs `preview()` (each
	// kind's own request-building + preview-fetch), then stores the result
	// as the single generic `pending` state (this component's own doc
	// comment on why one shape covers all three kinds).
	async function runPreview(kind, preview) {
		// A pending Rotate preview may be replaced by a further Rotate click
		// (this component's own doc comment) - nothing else may replace it.
		if (busy || (pending && !(kind === 'rotate' && pending.kind === 'rotate'))) return;
		setError(null);
		setBusy(true);
		try {
			const { blob, size, save, turns } = await preview();
			const url = URL.createObjectURL(blob);
			setPending((prev) => {
				if (prev) URL.revokeObjectURL(prev.url);
				return { kind, url, size, save, turns };
			});
		} catch (err) {
			reportError(err);
		} finally {
			setBusy(false);
		}
	}

	// "Rotate right" - preview only (25/09/2026, this component's own doc
	// comment on the shared state machine). The rotated result's own size
	// is always exactly the original's own width/height swapped - no need
	// to ask the server for it, a 90-degree turn can't be anything else.
	function handleRotate() {
		if (!naturalSize || busy) return;
		const turns = ((pending?.kind === 'rotate' ? pending.turns : 0) + 1) % 4;
		if (turns === 0) {
			// Four quarter turns = back to the original: nothing to save.
			discardPending();
			return;
		}
		const swapped = turns % 2 === 1;
		runPreview('rotate', async () => ({
			blob: await onRotatePreview(turns),
			size: swapped ? { width: naturalSize.height, height: naturalSize.width } : naturalSize,
			save: () => onRotateSave(turns),
			turns,
		}));
	}

	// "Crop" - preview only. Reuses `selection` (the same rectangle
	// Transform's own destination size below also reads from) as the crop
	// rectangle - one shared rectangle, not a second independent size
	// concept.
	function handleCrop() {
		if (!naturalSize) return;
		const x = Math.round(selection.left * naturalSize.width);
		const y = Math.round(selection.top * naturalSize.height);
		const width = Math.round((selection.right - selection.left) * naturalSize.width);
		const height = Math.round((selection.bottom - selection.top) * naturalSize.height);
		if (width <= 0 || height <= 0) return;
		const request = { x, y, width, height };
		runPreview('crop', async () => ({
			blob: await onCropPreview(request),
			size: { width, height },
			save: () => onCropSave(request),
		}));
	}

	// "Transform" - preview only, see this component's own doc comment on
	// why it never commits by itself. Reuses `selection` (the same
	// rectangle Crop's own edge handles already define) as the destination
	// size - one shared rectangle, not a second independent size concept
	// (Design discussion, 25/09/2026).
	function handleTransform() {
		if (!naturalSize) return;
		const destWidth = Math.round((selection.right - selection.left) * naturalSize.width);
		const destHeight = Math.round((selection.bottom - selection.top) * naturalSize.height);
		if (destWidth <= 0 || destHeight <= 0) return;
		const request = {
			destWidth,
			destHeight,
			topLeftX: Math.round(quad.tl.x * naturalSize.width),
			topLeftY: Math.round(quad.tl.y * naturalSize.height),
			topRightX: Math.round(quad.tr.x * naturalSize.width),
			topRightY: Math.round(quad.tr.y * naturalSize.height),
			bottomRightX: Math.round(quad.br.x * naturalSize.width),
			bottomRightY: Math.round(quad.br.y * naturalSize.height),
			bottomLeftX: Math.round(quad.bl.x * naturalSize.width),
			bottomLeftY: Math.round(quad.bl.y * naturalSize.height),
		};
		runPreview('transform', async () => ({
			blob: await onTransformPreview(request),
			size: { width: destWidth, height: destHeight },
			save: () => onTransformSave(request),
		}));
	}

	async function handleSave() {
		if (!pending || busy) return;
		setError(null);
		setBusy(true);
		try {
			await pending.save();
			discardPending();
		} catch (err) {
			reportError(err);
		} finally {
			setBusy(false);
		}
	}

	function handleCancelClick() {
		if (pending) {
			discardPending();
			return;
		}
		onCancel();
	}

	const midX = (selection.left + selection.right) / 2;
	const midY = (selection.top + selection.bottom) / 2;

	// See this component's own doc comment on canvas sizing - the preview
	// (once one exists) has its own known, exact `pending.size` (the very
	// numbers just used to produce it, or - Rotate's case - simply derived
	// from the original's own naturalSize), not the original's own
	// naturalSize itself.
	const effectiveSize = pending ? pending.size : naturalSize;

	// `undefined` (not yet computable, before both the currently-displayed
	// image and the wrapper have reported their real size) falls back to
	// `.image-editor-canvas`'s own plain 100%/100% CSS default, just while
	// the original is still loading.
	let canvasStyle;
	if (effectiveSize && wrapperSize.width > 0 && wrapperSize.height > 0) {
		const scale = Math.min(wrapperSize.width / effectiveSize.width, wrapperSize.height / effectiveSize.height);
		canvasStyle = { width: effectiveSize.width * scale, height: effectiveSize.height * scale };
	}

	return (
		<div className="image-editor">
			{error && <p className="form-error">{error}</p>}
			{/* Two groups, pushed apart (explicit ask, 25/09/2026: "on the left
			    corner: Rotate right, Crop, Transform, then a gap and then on
			    the right corner: Cancel, Save" - app.css's own comment on
			    .image-editor-actions has the layout mechanics). Save is now
			    unconditional (not wrapped in the video check below) - Crop's
			    own preview needs it to commit too, and Crop itself is shown
			    even for a video (same pre-existing shape this had before the
			    Crop/Rotate preview workflow existed - the backend's own
			    video rejection is still the real backstop, GalleryService.cropImage's
			    own doc). */}
			<div className="image-editor-actions">
				<div className="image-editor-actions-left">
					{image.mediaType !== 'VIDEO' && (
						<button
							type="button"
							onClick={handleRotate}
							disabled={busy || !naturalSize || (!!pending && pending.kind !== 'rotate')}
						>
							{t('imageViewer.rotate')}
						</button>
					)}
					<button type="button" onClick={handleCrop} disabled={busy || !naturalSize || !!pending}>
						{t('imageEditor.crop')}
					</button>
					{image.mediaType !== 'VIDEO' && (
						<button type="button" onClick={handleTransform} disabled={busy || !naturalSize || !!pending}>
							{t('imageEditor.transform')}
						</button>
					)}
				</div>
				<div className="image-editor-actions-right">
					{/* Explicit ask alongside the buttons above - the popup's own
					    close icon (Popup.jsx) already closes it too, this is a
					    second, more discoverable way to reach the same "no preview
					    pending" outcome. While a preview *is* showing, this
					    button's meaning changes (this component's own doc comment):
					    discards the preview and returns to editing rather than
					    closing the popup. */}
					<button type="button" onClick={handleCancelClick}>
						{t('common.cancel')}
					</button>
					<button type="button" onClick={handleSave} disabled={busy || !pending}>
						{t('common.save')}
					</button>
				</div>
			</div>
			<div ref={wrapperRef} className="image-editor-canvas-wrapper">
				<div ref={canvasRef} className="image-editor-canvas" style={canvasStyle}>
					{pending ? (
						<img src={pending.url} alt={image.name} className="image-editor-media" />
					) : (
						<AuthImage
							key={image.originalUrl}
							src={image.originalUrl}
							alt={image.name}
							className="image-editor-media"
							onLoad={(event) => setNaturalSize({ width: event.target.naturalWidth, height: event.target.naturalHeight })}
						/>
					)}
					{naturalSize && !pending && (
						<>
							<div
								className="image-editor-selection"
								style={{
									left: `${selection.left * 100}%`,
									top: `${selection.top * 100}%`,
									right: `${(1 - selection.right) * 100}%`,
									bottom: `${(1 - selection.bottom) * 100}%`,
								}}
							/>
							<div
								className="image-editor-handle image-editor-handle-top"
								style={{ left: `${midX * 100}%`, top: `${selection.top * 100}%` }}
								onPointerDown={() => setDragging('top')}
							/>
							<div
								className="image-editor-handle image-editor-handle-bottom"
								style={{ left: `${midX * 100}%`, top: `${selection.bottom * 100}%` }}
								onPointerDown={() => setDragging('bottom')}
							/>
							<div
								className="image-editor-handle image-editor-handle-left"
								style={{ left: `${selection.left * 100}%`, top: `${midY * 100}%` }}
								onPointerDown={() => setDragging('left')}
							/>
							<div
								className="image-editor-handle image-editor-handle-right"
								style={{ left: `${selection.right * 100}%`, top: `${midY * 100}%` }}
								onPointerDown={() => setDragging('right')}
							/>
							{/* The quad's own live wireframe (this component's own
							    doc comment on why Transform previews geometrically
							    but never warps live) - `viewBox="0 0 100 100"` +
							    `preserveAspectRatio="none"` so a plain 0..100
							    coordinate (the quad's own fraction * 100) maps
							    directly onto the canvas's real box, however tall
							    or wide it currently is. */}
							<svg className="image-editor-quad-outline" viewBox="0 0 100 100" preserveAspectRatio="none">
								<polygon
									points={`${quad.tl.x * 100},${quad.tl.y * 100} ${quad.tr.x * 100},${quad.tr.y * 100} ${quad.br.x * 100},${quad.br.y * 100} ${quad.bl.x * 100},${quad.bl.y * 100}`}
								/>
							</svg>
							<div
								className="image-editor-handle image-editor-handle-corner"
								style={{ left: `${quad.tl.x * 100}%`, top: `${quad.tl.y * 100}%` }}
								onPointerDown={() => setDraggingCorner('tl')}
							/>
							<div
								className="image-editor-handle image-editor-handle-corner"
								style={{ left: `${quad.tr.x * 100}%`, top: `${quad.tr.y * 100}%` }}
								onPointerDown={() => setDraggingCorner('tr')}
							/>
							<div
								className="image-editor-handle image-editor-handle-corner"
								style={{ left: `${quad.br.x * 100}%`, top: `${quad.br.y * 100}%` }}
								onPointerDown={() => setDraggingCorner('br')}
							/>
							<div
								className="image-editor-handle image-editor-handle-corner"
								style={{ left: `${quad.bl.x * 100}%`, top: `${quad.bl.y * 100}%` }}
								onPointerDown={() => setDraggingCorner('bl')}
							/>
						</>
					)}
				</div>
			</div>
		</div>
	);
}
