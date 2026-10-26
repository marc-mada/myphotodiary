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
import { DirectoryTree } from './DirectoryTree';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * Rename / Move one picture or video (19/10/2026, explicit ask) - inside the
 * picture popup (ImageEditForm), desktop only. The name is edited without
 * its extension (the photo/video type is read from it, so it can't change);
 * the target sequence is picked in the same directory tree as the Gallery's
 * sidebar. A name already used in the target is refused, never suffixed
 * (explicit choice) - see DirectoryIndexerService.moveImage.
 *
 * `onMove(request)` performs the move and resolves once the screen has
 * caught up (it closes the popup); a rejected promise is shown here.
 * `onCancel` hides the form (ImageEditForm owns whether it's shown).
 */
export function ImageMoveForm({ image, onMove, onCancel }) {
	const { t } = useTranslation();
	const { base, extension } = splitName(image.name);
	const [newBase, setNewBase] = useState(base);
	const [targetPath, setTargetPath] = useState(image.directoryPath ?? '');
	const [pickingTarget, setPickingTarget] = useState(false);
	const [saving, setSaving] = useState(false);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);

	useEffect(() => {
		setNewBase(base);
		setTargetPath(image.directoryPath ?? '');
		setPickingTarget(false);
		setError(null);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [image.id]);

	const trimmed = newBase.trim();
	const nameChanged = trimmed !== base;
	const targetChanged = targetPath !== (image.directoryPath ?? '');

	async function handleSubmit(e) {
		e.preventDefault();
		setError(null);
		if (!trimmed || trimmed.includes('/') || trimmed.includes('\\') || trimmed.startsWith('.')) {
			setError(t('imageMove.invalidName'));
			return;
		}
		const newName = trimmed + extension;
		setSaving(true);
		try {
			await onMove({ newName: nameChanged ? newName : null, targetPath: targetChanged ? targetPath : null });
		} catch (err) {
			if (err.status === 409) setError(t('imageMove.nameTaken', { name: newName }));
			else if (err.status === 404) setError(t('imageMove.targetMissing'));
			else reportError(err);
		} finally {
			setSaving(false);
		}
	}

	return (
		<form className="image-move-form" onSubmit={handleSubmit}>
			<h4>{t('imageMove.title')}</h4>
			<label>
				{t('imageMove.name')}
				<span className="image-move-name">
					<input autoFocus value={newBase} onChange={(e) => setNewBase(e.target.value)} />
					<span className="image-move-extension">{extension}</span>
				</span>
			</label>
			<div className="image-move-target">
				<span>
					{t('imageMove.sequence')} <strong>{targetPath === '' ? t('directoryTree.root') : targetPath}</strong>
				</span>
				<button type="button" onClick={() => setPickingTarget(!pickingTarget)}>
					{pickingTarget ? t('imageMove.closeTree') : t('imageMove.change')}
				</button>
			</div>
			{pickingTarget && (
				<div className="image-move-tree">
					<DirectoryTree selectedPath={targetPath} onSelect={(node) => setTargetPath(node.path)} onSelectRoot={() => setTargetPath('')} />
				</div>
			)}
			{targetChanged && <p className="image-move-warning">{t('imageMove.inheritedWarning')}</p>}
			{error && <p className="form-error">{error}</p>}
			<div className="gallery-popup-actions">
				<button type="submit" disabled={saving || (!nameChanged && !targetChanged)}>
					{saving ? t('imageMove.saving') : t('imageMove.save')}
				</button>
				<button type="button" onClick={onCancel} disabled={saving}>
					{t('imageMove.cancel')}
				</button>
			</div>
		</form>
	);
}

// "IMG_1.jpg" -> { base: "IMG_1", extension: ".jpg" }; a name without a dot
// keeps no extension (same rule as the server's own check).
function splitName(name) {
	const dot = name.lastIndexOf('.');
	return dot > 0 ? { base: name.slice(0, dot), extension: name.slice(dot) } : { base: name, extension: '' };
}
