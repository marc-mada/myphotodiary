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
import { isValidSequenceName } from './sequenceName';
import { useForbiddenAwareError } from '../temporaryMessage';

// A sequence filed as year/month/name - the only kind whose date can be
// changed from the Rename form (05/10/2026). Groups: year, month.
const DATED_SEQUENCE = /^(\d{4})\/(\d{2})\/[^/]+$/;
// MM/YYYY as typed - a one-digit month is accepted too ("8/2026").
const MONTH_YEAR = /^(\d{1,2})\/(\d{4})$/;

/**
 * The sequence-description popup (idx.js's dirEditPopup) - description and
 * attributes here are distinct from the per-image post-it/tags already
 * built (Design.md §15: `Directory` carries its own description
 * and attributes, not just `Image`). Also folds in the rename form, a
 * deliberate solo-dev simplification of legacy's separate dirEditPopup/
 * admin-dirTable split - unchanged by this pass. Geolocation (lat/lng) is
 * its own popup now (SequenceGeolocationForm), matching legacy's separate
 * mapPopup rather than bundled in here.
 *
 * Reset index / delete index+files used to live here too (onResetIndex/
 * onDeleteIndexAndFiles) - removed (explicit ask, 28/08/2026): redundant
 * with the same commands already available in bulk from Admin -> Index
 * management (IndexManagementPanel), and a destructive action tucked into
 * an edit-description popup was easy to reach by mistake. The capability
 * itself isn't gone, only this second entry point to it.
 *
 * Create-and-assign a brand-new tag inline (03/09/2026, explicit ask) -
 * restores legacy's `#newDirParam` (idx.js/index.jsp, dirEditPopup); same
 * mechanism as ImageEditForm's own `#newImgParam` sibling - see that
 * component's own comment for the find-or-create/RBAC rationale.
 *
 * Sequence group picker (12/09/2026, explicit ask) - closes the audit gap
 * noted 27/08/2026 ("Changement de groupe d'une séquence... aucune UI pour
 * le modifier"). `assignableGroupNames` (resolved by the caller - see
 * GalleryScreen/SearchScreen's own doc) decides both *whether* the picker
 * shows at all and *which* groups it offers: empty for READER/LOWER (no
 * dropdown - they can't reach `EDIT_SEQUENCE` on the current group in the
 * first place, so nothing to offer), every existing group for ADMIN, or
 * only this user's own WRITER-role groups for WRITER
 * (`DirectoryIndexerService.updateDirectoryDetail`'s own doc has the
 * matching backend-side authorization). The directory's *current* group is
 * always included even if it isn't in `assignableGroupNames` - a WRITER
 * editing a sequence they can already edit (via a group-scoped ADMIN row,
 * say) should still see where it currently lives, not a blank/wrong
 * selection, even if moving it *elsewhere* is further restricted.
 *
 * Rendered inside a <Popup> by the caller - this component only owns the
 * form content, not the overlay/backdrop/centering.
 */
export function DirectoryDetailPanel({ directory, allAttributes, assignableGroupNames = [], onUpdate, onRename }) {
	const { t } = useTranslation();
	const [description, setDescription] = useState(directory.description ?? '');
	const [newName, setNewName] = useState('');
	const [renaming, setRenaming] = useState(false);
	const [renameError, setRenameError] = useState(null);
	const reportRenameError = useForbiddenAwareError(setRenameError);
	const [newDate, setNewDate] = useState('');
	const datedMatch = DATED_SEQUENCE.exec(directory.path);
	const currentName = directory.path.split('/').pop();

	// Both fields start from the sequence's current name and date (MM/YYYY,
	// read from where it's filed), so either or both can be changed.
	function startRenaming() {
		setNewName(currentName);
		setNewDate(datedMatch ? `${datedMatch[2]}/${datedMatch[1]}` : '');
		setRenameError(null);
		setRenaming(true);
	}
	const [newTagName, setNewTagName] = useState('');

	useEffect(() => {
		setDescription(directory.description ?? '');
		setNewName('');
		setRenaming(false);
		setNewTagName('');
	}, [directory.id]);

	function saveDescription() {
		onUpdate({ description });
	}

	function handleAttributesChange(e) {
		const selected = Array.from(e.target.selectedOptions, (option) => option.value);
		onUpdate({ attributeNames: selected });
	}

	function handleGroupChange(e) {
		onUpdate({ groupName: e.target.value });
	}

	// Always includes the current group even if it's outside what this user
	// is allowed to move *into* (see this component's own doc) - sorted the
	// same way the tag <select> above already is, for the same reason (a
	// flat list, nothing hierarchical to preserve here either).
	const groupOptions = [...new Set([directory.groupName, ...assignableGroupNames])].sort((a, b) => a.localeCompare(b));

	// See ImageEditForm's own handleAddNewTag comment - trim-only naming
	// convention and duplicate-just-selects-it behavior are identical here.
	function handleAddNewTag(e) {
		e.preventDefault();
		const trimmed = newTagName.trim();
		if (!trimmed) return;
		const current = directory.attributeNames ?? [];
		if (!current.includes(trimmed)) {
			onUpdate({ attributeNames: [...current, trimmed] });
		}
		setNewTagName('');
	}

	// Alphabetical (03/09/2026, explicit ask) - allAttributes arrives in
	// whatever order the backend's own tree traversal produced it in
	// (AttributeService, insertion/hierarchy order), not sorted by name; a
	// plain a-z compare is enough here since this <select> is a flat list
	// (no parent/child grouping to preserve, unlike the Admin tag tree).
	const sortedAttributes = [...allAttributes].sort((a, b) => a.name.localeCompare(b.name));

	async function submitRename(e) {
		e.preventDefault();
		const name = newName.trim();
		if (!name || !isValidSequenceName(name) || name.startsWith('/') || name.endsWith('/')) {
			setRenameError(t('directoryDetail.invalidSequenceName'));
			return;
		}
		// New date (05/10/2026, explicit ask - move a sequence to another
		// date): a bad MM/YYYY shows an error and nothing is moved. The server
		// checks the same rules (DirectoryIndexerService.renameDirectory).
		let date = null;
		if (datedMatch) {
			const m = MONTH_YEAR.exec(newDate.trim());
			const month = m ? Number(m[1]) : 0;
			const year = m ? Number(m[2]) : 0;
			if (!m || month < 1 || month > 12 || year < 1000) {
				setRenameError(t('directoryDetail.invalidSequenceDate'));
				return;
			}
			date = { year, month };
		}
		const unchanged = name === currentName && (!date || (date.year === Number(datedMatch[1]) && date.month === Number(datedMatch[2])));
		if (unchanged) {
			setRenaming(false);
			return;
		}
		setRenameError(null);
		try {
			await onRename(name, date);
			setRenaming(false);
		} catch (err) {
			// Shown right here, under the fields (e.g. "already exists"),
			// rather than behind the popup on the screen itself.
			reportRenameError(err);
		}
	}

	return (
		<>
			<h3>{directory.path}</h3>

			<label>
				{t('directoryDetail.sequenceComment')}
				<textarea value={description} onBlur={saveDescription} onChange={(e) => setDescription(e.target.value)} rows={3} />
			</label>

			{assignableGroupNames.length > 0 && (
				<label>
					{t('directoryDetail.group')}
					<select value={directory.groupName} onChange={handleGroupChange}>
						{groupOptions.map((groupName) => (
							<option key={groupName} value={groupName}>
								{groupName}
							</option>
						))}
					</select>
				</label>
			)}

			<label className="directory-detail-attributes">
				{t('directoryDetail.tags')}
				<select multiple size={8} className="tag-multiselect" value={directory.attributeNames ?? []} onChange={handleAttributesChange}>
					{sortedAttributes.map((a) => (
						<option key={a.id} value={a.name}>
							{a.name}
						</option>
					))}
				</select>
				<form onSubmit={handleAddNewTag} className="new-tag-form">
					<input placeholder={t('directoryDetail.newTagPlaceholder')} value={newTagName} onChange={(e) => setNewTagName(e.target.value)} />
					<button type="submit">{t('tags.add')}</button>
				</form>
			</label>

			<div className="gallery-popup-actions">
				{renaming ? (
					<form onSubmit={submitRename} className="rename-form">
						<label>
							{t('directoryDetail.sequenceName')}
							<input
								autoFocus
								placeholder={t('directoryDetail.renamePlaceholder')}
								value={newName}
								onChange={(e) => {
									setNewName(e.target.value);
									setRenameError(null);
								}}
							/>
						</label>
						{datedMatch && (
							<label>
								{t('directoryDetail.sequenceDate')}
								<input
									className="rename-date-input"
									placeholder="MM/YYYY"
									value={newDate}
									onChange={(e) => {
										setNewDate(e.target.value);
										setRenameError(null);
									}}
								/>
							</label>
						)}
						<button type="submit">{t('common.save')}</button>
						<button type="button" onClick={() => setRenaming(false)}>
							{t('common.cancel')}
						</button>
						{renameError && <p className="form-error">{renameError}</p>}
					</form>
				) : (
					<button type="button" onClick={startRenaming}>
						{t('directoryDetail.rename')}
					</button>
				)}
			</div>
		</>
	);
}
