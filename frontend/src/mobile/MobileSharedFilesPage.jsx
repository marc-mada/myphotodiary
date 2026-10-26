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

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { MobileImportForm } from './MobileImportForm';
import { MobilePage } from './MobilePage';
import { MobileSequencePicker, rememberImportTarget } from './MobileSequencePicker';
import { discardSharedFiles, loadSharedFiles } from './sharedFiles';

/**
 * "Publish shared files" (21/10/2026, explicit ask) - what opens when
 * pictures/videos are shared to myPhotoDiary from another app (WhatsApp,
 * the gallery...) through the phone's share sheet (public/
 * share-target-sw.js). The files are already chosen, so it's the mobile
 * Publish form without its file picker, always sorted automatically: each
 * file goes to the year/month of its own date (EXIF, else the WhatsApp
 * file-name date, else the file's date - the server's usual order), into
 * the sequence named here. Group: the default one (explicit choice).
 *
 * Home ("Cancel") discards the files; publishing discards them too.
 */
const MODE_KEY = 'mpd.sharedFilesTarget';

function readMode() {
	try {
		return window.localStorage.getItem(MODE_KEY) === 'existing' ? 'existing' : 'date';
	} catch {
		return 'date';
	}
}

export function MobileSharedFilesPage({ sharedId, currentDirectoryPath, onImported, onHome, onLogout }) {
	const { t } = useTranslation();
	const [files, setFiles] = useState(undefined); // undefined while loading, null when gone
	const [previews, setPreviews] = useState([]);
	// Sort by date into a new sequence, or add to an existing one (25/10/2026) -
	// the last choice is remembered on this phone.
	const [mode, setMode] = useState(readMode);
	const [targetPath, setTargetPath] = useState(null);

	function changeMode(next) {
		setMode(next);
		try {
			window.localStorage.setItem(MODE_KEY, next);
		} catch {
			// Not remembered - harmless.
		}
	}

	useEffect(() => {
		let cancelled = false;
		(sharedId === 'error' ? Promise.resolve(null) : loadSharedFiles(sharedId))
			.catch(() => null)
			.then((loaded) => {
				if (!cancelled) setFiles(loaded);
			});
		return () => {
			cancelled = true;
		};
	}, [sharedId]);

	useEffect(() => {
		const urls = (files ?? []).slice(0, 12).map((file) => ({ url: URL.createObjectURL(file), video: file.type.startsWith('video/') }));
		setPreviews(urls);
		return () => urls.forEach((p) => URL.revokeObjectURL(p.url));
	}, [files]);

	async function handleImported(result) {
		await discardSharedFiles(sharedId).catch(() => {});
		rememberImportTarget(result?.directory?.path);
		onImported(result);
	}

	async function handleCancel() {
		await discardSharedFiles(sharedId).catch(() => {});
		onHome();
	}

	return (
		<MobilePage title={t('mobile.sharedFilesTitle')} onHome={handleCancel} onLogout={onLogout}>
			{files === undefined && <p>{t('common.loading')}</p>}
			{files === null && <p className="form-error">{t('mobile.sharedFilesUnavailable')}</p>}
			{files && (
				<>
					<div className="mobile-shared-previews">
						{previews.map((p) => (p.video ? <video key={p.url} src={p.url} muted /> : <img key={p.url} src={p.url} alt="" />))}
					</div>
					<label className="mobile-target-option">
						<input type="radio" name="sharedTarget" checked={mode === 'date'} onChange={() => changeMode('date')} />
						{t('upload.sortAndCreateSequence')}
					</label>
					<label className="mobile-target-option">
						<input type="radio" name="sharedTarget" checked={mode === 'existing'} onChange={() => changeMode('existing')} />
						{t('mobile.addToExistingSequence')}
					</label>
					<p className="mobile-shared-hint">{t(mode === 'date' ? 'mobile.sharedFilesHint' : 'mobile.sharedFilesExistingHint')}</p>
					{mode === 'existing' && (
						<MobileSequencePicker currentDirectoryPath={currentDirectoryPath} value={targetPath} onChange={setTargetPath} />
					)}
					<MobileImportForm
						initialFiles={files}
						targetPath={mode === 'existing' ? targetPath : undefined}
						buttonIcon="action"
						onImported={handleImported}
					/>
				</>
			)}
		</MobilePage>
	);
}
