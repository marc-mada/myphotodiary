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

import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, getCsrfHeader } from '../api/client';
import { MobileIcon } from './MobileIcon';
import { useForbiddenAwareError } from '../temporaryMessage';
import { adaptiveUpload } from '../upload/adaptiveUpload';
import { isValidSequenceName } from '../gallery/sequenceName';

/**
 * The `.import-form` core shared by legacy's upload-page and camera-page
 * (mindex.jsp l.138-157 / l.172-191) - identical markup in both, differing
 * only in the file input: `<input type="file" accept="image/*" multiple>`
 * for Publish vs. `<input type="file" accept="image/*" capture="camera">`
 * for Camera, faithfully ported at first - legacy never had video at all
 * (video is entirely new in this project, Design.md §15), so there was nothing to carry over for it.
 *
 * `accept` (now a prop, see below) widened to `image/*,video/mp4` on
 * Publish (01/09/2026, real gap found live: Publish/Camera on mobile only
 * ever offered photos) once this project's own video support existed - the
 * same MP4-only client-side courtesy filter desktop's own `Uploader.jsx`
 * already applies (`acceptedFileTypes`), the real codec-level check
 * staying server-side either way (`VideoFormatValidator`).
 *
 * **Real bug found live, 03/09/2026, on Camera specifically**: widening
 * `accept` to two different top-level media types (`image/*` *and*
 * `video/mp4`) together with `capture` broke `capture`'s own direct-camera
 * launch on the real device tested - the input fell back to the ordinary
 * file/gallery chooser instead of jumping into the camera. `accept` is now
 * a prop instead of hardcoded here, defaulting to the wide
 * `image/*,video/mp4` (Publish's own need, unaffected - it never sets
 * `capture`) - `MobileCameraPage` passes `image/*` alone explicitly, which
 * is what actually restores reliable direct-camera behavior. Video capture
 * *through the Camera page* is dropped as a result (a real video can still
 * be attached through Publish, from the library/files, or recorded by the
 * device's own native camera app and selected there afterward - just not
 * via this page's own camera shutter). `capture` is what actually changes
 * the picker a mobile browser opens - present with a single, unambiguous
 * `accept` category, it jumps straight into the camera; absent (Publish),
 * the browser offers its full library chooser (gallery/library *and*
 * camera, both media types, since there's no `capture` to make the
 * ambiguity matter there).
 *
 * MobileUploadPage originally reused the desktop `Uploader` (FilePond) -
 * wrong on a touch device for two concrete reasons found testing on a
 * handset: FilePond's own UI foregrounds a drag-and-drop target, which
 * doesn't apply to touch input at all, and browsers only ever offered the
 * camera through it, never the gallery/library (FilePond's own file input
 * doesn't get the "no capture attribute" treatment automatically). This
 * plain input - the same one legacy itself uses - sidesteps both, with the
 * "hide the ugly native button, proxy a nicer one" trick legacy's own
 * `importCtrl.getPictures()` already uses (`$(...).click()`).
 *
 * Legacy's own form is three explicit numbered steps, not one - "1. Select
 * files", "2. Select options", "3. Publish" (l.136-155/170-189) - selecting
 * files never submits by itself, only the separate step-3 submit button
 * does (`data-icon="action"`, the same icon used here). This form
 * originally collapsed steps 1 and 3 into one immediate upload-on-select,
 * which real testing on a handset flagged as unwanted: a distinct "Upload"
 * button, appearing once something is selected, is what's implemented here
 * - closer to legacy's real flow than the first version was.
 *
 * Multiple files (from Publish) upload one at a time to the same
 * `/api/import` endpoint the single-file Camera flow uses - there's no
 * batch endpoint, and legacy's own multipart form only ever posted whatever
 * the browser's multi-file `<input>` combined into one submission, which
 * `ImportSvr` still processes as independent per-file entries server-side.
 * `onImported` fires once, after the whole batch, with the last file's
 * result - enough for the caller (MobileApp) to navigate to wherever
 * things landed, without re-navigating mid-upload while more files remain.
 */

// See handleFilesSelected's own comment - only ever applied to a captured
// file (Camera/Movie), never a Publish selection. A plain `new File(...)`
// wrapper, not a mutation (File objects are immutable) - preserves bytes,
// MIME type, and lastModified exactly, only the name changes.
function withUniqueName(file) {
	const dot = file.name.lastIndexOf('.');
	const base = dot > 0 ? file.name.slice(0, dot) : file.name;
	const ext = dot > 0 ? file.name.slice(dot) : '';
	return new File([file], `${base}-${Date.now()}${ext}`, { type: file.type, lastModified: file.lastModified });
}

export function MobileImportForm({ capture, accept = 'image/*,video/mp4', multiple, buttonLabel, buttonIcon, currentDirectoryPath, onImported }) {
	const { t } = useTranslation();
	const fileInputRef = useRef(null);
	// Single checkbox (25/09/2026, explicit ask - same simplification as
	// desktop's Uploader, see its own comment) replacing what used to be two
	// separate autoSort/createSubDir checkboxes here too - both still go
	// true/false together under the hood.
	const [sortAndCreate, setSortAndCreate] = useState(true);
	const [subDirName, setSubDirName] = useState('');
	const [selectedFiles, setSelectedFiles] = useState([]);
	const [uploading, setUploading] = useState(false);
	const [progress, setProgress] = useState(null); // { done, total }
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);

	function handleFilesSelected(e) {
		const raw = Array.from(e.target.files ?? []);
		e.target.value = '';
		if (raw.length === 0) return;
		setError(null);
		// iOS camera/video capture hands back a fixed, generic filename every
		// time ("image.jpg"/a fixed .mov name, regardless of how many times
		// you shoot) - real bug found live (03/09/2026, iPad): a second
		// photo/movie taken in the same session was rejected outright by the
		// backend's own duplicate-name guard (ImageAlreadyExistsException,
		// GalleryService/ImportService) - a deliberate protection against
		// re-uploading the exact same file by mistake, not something to
		// weaken generally. Scoped to `capture` specifically (Camera/Movie
		// only, never Publish's own file/library picker, whose real
		// filenames are already meaningfully unique on their own) - stamp a
		// client-side timestamp into the name so back-to-back captures in
		// the same session never collide, without touching the duplicate-
		// name guard itself.
		const files = capture ? raw.map(withUniqueName) : raw;
		setSelectedFiles(files);
	}

	async function uploadOne(file) {
		const params = new URLSearchParams({ autoSort: String(sortAndCreate), createSubDir: String(sortAndCreate) });
		if (sortAndCreate && subDirName.trim()) params.set('subDirName', subDirName.trim());
		if (!sortAndCreate && currentDirectoryPath) params.set('dirPath', currentDirectoryPath);
		// Same signal, same reason as the desktop Uploader - see its own
		// comment and backend ExifDateReader's javadoc (fallback #5).
		if (Number.isFinite(file.lastModified)) params.set('clientLastModifiedEpochMillis', String(file.lastModified));
		// Adaptive pre-upload shrinking + live fallback (26/09/2026,
		// ../upload/adaptiveUpload.js) - XMLHttpRequest under the hood rather
		// than the fetch this used before, since fetch reports no upload
		// progress for the link meter to read.
		const { status, responseText } = await adaptiveUpload({
			file,
			url: `/api/import?${params.toString()}`,
			headers: getCsrfHeader(),
		}).promise;
		let body = null;
		try {
			body = responseText ? JSON.parse(responseText) : null;
		} catch {
			body = null;
		}
		// ApiError, not a plain Error (13/09/2026) - carries `.status` so a
		// 403 here (RBAC CREATE_SEQUENCE/EDIT_SEQUENCE denial) gets the same
		// translated, temporary "Forbidden access" treatment as every other
		// API call in the app (useForbiddenAwareError, ../temporaryMessage) -
		// this endpoint is called via a raw request (multipart body), not the
		// shared apiFetch wrapper that normally throws this, so it has to be
		// constructed by hand here instead.
		if (status < 200 || status >= 300) throw new ApiError(status, body?.error ?? `Upload failed: ${status}`);
		return body;
	}

	async function handleUpload() {
		if (selectedFiles.length === 0) return;
		// Same rule as the server (../gallery/sequenceName.js), checked before
		// anything is sent so the message is translated.
		if (sortAndCreate && subDirName.trim() && !isValidSequenceName(subDirName)) {
			setError(t('directoryDetail.invalidSequenceName'));
			return;
		}
		setUploading(true);
		setError(null);
		setProgress({ done: 0, total: selectedFiles.length });
		let lastResult = null;
		try {
			for (const file of selectedFiles) {
				lastResult = await uploadOne(file);
				setProgress((p) => ({ ...p, done: p.done + 1 }));
			}
			setSelectedFiles([]);
			if (lastResult) onImported(lastResult);
		} catch (err) {
			reportError(err);
		} finally {
			setUploading(false);
			setProgress(null);
		}
	}

	return (
		<>
			<div className="import-options">
				<label className="import-option">
					<input type="checkbox" checked={sortAndCreate} onChange={(e) => setSortAndCreate(e.target.checked)} />
					{sortAndCreate ? (
						t('upload.sortAndCreateSequence')
					) : (
						<>
							{t('upload.storeInCurrentPathPrefix')}
							{/* Same reasoning as desktop's Uploader.jsx - '' means the
							    root itself, not "nothing selected", so reuse
							    DirectoryTree's own root label rather than a "select a
							    directory" hint that was never correct here. */}
							<strong>{currentDirectoryPath || t('directoryTree.root')}</strong>
						</>
					)}
					{sortAndCreate && (
						<input
							className="sequence-name-input"
							placeholder={t('upload.sequenceNamePlaceholder')}
							value={subDirName}
							onChange={(e) => setSubDirName(e.target.value)}
							onClick={(e) => e.stopPropagation()}
						/>
					)}
				</label>
			</div>
			{error && <p className="form-error">{error}</p>}
			<input
				ref={fileInputRef}
				type="file"
				accept={accept}
				capture={capture}
				multiple={multiple}
				className="mobile-hidden-file-input"
				onChange={handleFilesSelected}
				disabled={uploading}
			/>
			<button type="button" onClick={() => fileInputRef.current?.click()} disabled={uploading}>
				{buttonLabel} <MobileIcon name={buttonIcon} />
			</button>
			{selectedFiles.length > 0 && (
				<p className="mobile-selected-files">{t('mobile.picturesSelected', { count: selectedFiles.length })}</p>
			)}
			<button type="button" onClick={handleUpload} disabled={uploading || selectedFiles.length === 0}>
				{uploading ? (progress ? t('mobile.uploadingProgress', { done: progress.done, total: progress.total }) : t('mobile.uploading')) : t('mobile.upload')}{' '}
				<MobileIcon name="action" />
			</button>
		</>
	);
}
