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

import { useState } from 'react';
import { FilePond } from 'react-filepond';
import 'filepond/dist/filepond.min.css';
import { useTranslation } from 'react-i18next';
import { getCsrfHeader } from '../api/client';
import { adaptiveUpload } from '../upload/adaptiveUpload';
import { isValidSequenceName } from './sequenceName';

/**
 * FilePond (Design.md §3 - the committed replacement for the
 * legacy dropzone.js, per decision #8's corollary: jQuery DOM-manipulating
 * widgets are rewritten as React components, not wrapped), wired to
 * `/api/import` - the faithfully-ported legacy ImportSvr behavior (see
 * ImportService's javadoc on the backend), not a fixed-directory upload.
 *
 * A single checkbox (25/09/2026, explicit ask - simplified from the two
 * separate `autoSort`/`createSubDir` checkboxes index.jsp's import popup
 * had, both still true/false together under the hood): checked (default)
 * sorts each file by its own EXIF date *and* creates a new sequence under
 * it (`autoSort`+`createSubDir` both `true`) - files in the same drop can
 * legitimately land in different directories, and the sequence name field
 * appends one more path segment under whichever date-derived directory
 * each file landed in. Unchecked, both go `false` together: every file
 * goes as-is into whatever directory is currently selected in the tree
 * (`currentDirectoryPath`) - no date sorting, no new sequence. The other
 * two combinations (sort without a new sequence, or a new sequence without
 * date-sorting) are still valid to the backend, just no longer reachable
 * from this simplified UI.
 *
 * Note for anyone testing this headlessly (e.g. Playwright): FilePond queues
 * the actual upload dispatch through a requestAnimationFrame-driven loop
 * internally and only flushes it once that loop goes idle - polling the
 * upload's result immediately after triggering it can observe the file
 * stuck in a "queued" state for longer than expected. That's real elapsed
 * time FilePond needs, not a hang - give it a few seconds before concluding
 * an upload failed.
 */
export function Uploader({ currentDirectoryPath, onImported }) {
	const { t } = useTranslation();
	const [sortAndCreate, setSortAndCreate] = useState(true);
	const [subDirName, setSubDirName] = useState('');

	const server = {
		process: (fieldName, file, metadata, load, error, progress, abort) => {
			// Same rule as the server (./sequenceName.js) - refused here first
			// so the message is translated; nothing is sent.
			if (sortAndCreate && subDirName.trim() && !isValidSequenceName(subDirName)) {
				error(t('directoryDetail.invalidSequenceName'));
				return { abort };
			}
			const params = new URLSearchParams({ autoSort: String(sortAndCreate), createSubDir: String(sortAndCreate) });
			if (sortAndCreate && subDirName.trim()) params.set('subDirName', subDirName.trim());
			if (!sortAndCreate && currentDirectoryPath) params.set('dirPath', currentDirectoryPath);
			// The device's own last-modified timestamp for this file (01/09/2026,
			// see backend ExifDateReader's own javadoc, fallback #5) - a real
			// signal previously discarded entirely: without EXIF and without a
			// WhatsApp-style filename, the server had nothing better than "the
			// moment this temp file was written", i.e. right now. Sent whenever
			// the browser reports one (the File API guarantees a value, so this
			// is effectively always). Always the *original* file's - a shrunk
			// copy keeps it anyway (../upload/adaptiveShrink.js).
			if (Number.isFinite(file.lastModified)) params.set('clientLastModifiedEpochMillis', String(file.lastModified));
			// Adaptive pre-upload shrinking + live fallback (26/09/2026,
			// ../upload/adaptiveUpload.js). Same-origin XHR under the hood,
			// so the session cookie goes automatically - only the CSRF header
			// needs attaching by hand (client.js's own doc comment).
			const upload = adaptiveUpload({
				file,
				url: `/api/import?${params.toString()}`,
				headers: getCsrfHeader(),
				onProgress: (loaded, total) => progress(true, loaded, total),
			});
			upload.promise.then(
				({ status, responseText }) => {
					const body = safeParse(responseText);
					if (status >= 200 && status < 300) {
						load(responseText);
						if (body) onImported(body);
					} else {
						error(body?.error ?? `Upload failed: ${status}`);
					}
				},
				(err) => {
					if (err?.name !== 'AbortError') error('Upload failed: network error');
				},
			);
			return {
				abort: () => {
					upload.abort();
					abort();
				},
			};
		},
	};

	return (
		<div className="uploader">
			<div className="import-options">
				<label className="import-option">
					<input type="checkbox" checked={sortAndCreate} onChange={(e) => setSortAndCreate(e.target.checked)} />
					{sortAndCreate ? (
						t('upload.sortAndCreateSequence')
					) : (
						<>
							{t('upload.storeInCurrentPathPrefix')}
							{/* currentDirectoryPath is '' at the root itself, not "nothing
							    selected" (GalleryScreen's own selectedPath starts at '',
							    and the root is a real Directory row) - reuses the same
							    label as DirectoryTree's own root row instead of a "select
							    a directory" hint that was never actually correct here,
							    25/09/2026 explicit ask. */}
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
			<FilePond
				allowMultiple
				// image/*, not a fixed jpg/png list - photos already accept
				// several formats server-side (ImageStorageService's own
				// IMAGE_EXTENSIONS); video/mp4 specifically, not video/* -
				// this is a client-side courtesy filter only (the real,
				// codec-level check is VideoFormatValidator, server-side,
				// unavoidably - see its own javadoc for why the extension/
				// declared type alone can't be trusted), but no reason to
				// invite an obviously-wrong pick (.mov, .avi) through the file
				// browser just to reject it a moment later.
				acceptedFileTypes={['image/*', 'video/mp4']}
				server={server}
				labelIdle={t('upload.dropHere', { browseAction: `<span class="filepond--label-action">${t('upload.browse')}</span>` })}
			/>
		</div>
	);
}

function safeParse(text) {
	try {
		return JSON.parse(text);
	} catch {
		return null;
	}
}
