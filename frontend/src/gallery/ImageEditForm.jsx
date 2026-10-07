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
import { StarRating } from './StarRating';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * The picture-description popup (idx.js's imageEditPopup) - description,
 * rating, and downloading the original (legacy's "Download this picture" /
 * `imageSwapper.exportImg`, ExportSvr - Design.md §15, legacy
 * feature-parity audit, 27/08/2026: legacy's left-menu link, not part of the controls-bar, so it
 * lives here rather than as a 4th icon next to delete/play/full-screen).
 * name/size/date are read-only display fields in legacy, not added here.
 *
 * The download itself can't be a plain `<a href>` - this backend's auth
 * header is attached per-request in JS (AuthImage's own comment explains
 * why), not a cookie a browser navigation would carry - so it fetches the
 * bytes as a blob and triggers the save via a synthetic, `download`-
 * attributed anchor, the standard pattern for an authenticated download.
 *
 * Per-image tag assignment (`allAttributes`, 28/08/2026) - closes a known
 * gap (roadmap point 7): this popup never had any tag UI at all before,
 * only directories did. Same `<select multiple>` widget as
 * DirectoryDetailPanel's own tags field, for the same reason (a checkbox
 * row doesn't scale to a large tag collection) - see that component's own
 * comment, and `.tag-multiselect` in app.css.
 *
 * Create-and-assign a brand-new tag inline (03/09/2026, explicit ask) -
 * restores legacy's `#newImgParam` (idx.js/index.jsp, imageEditPopup):
 * typing a name and submitting both creates the tag (if it doesn't already
 * exist) and assigns it to this image in one step, without a detour through
 * the ADMIN-only Tags admin screen. The backend does the find-or-create
 * (GalleryService.updateImage), deliberately not routed through the
 * ADMIN-only POST /api/attributes - see that method's own comment.
 *
 * Capture date/time (28/08/2026, explicit ask) - read-only, between the
 * name and the description, shown only `image.captureDate` is actually set
 * ("if known" - GalleryService/ImportService always fall back to the
 * upload instant when EXIF has none, but older/edge-case rows could still
 * lack one). Formatted with the viewer's own i18next language, not a fixed
 * locale, so it reads naturally in either UI language.
 */
export function ImageEditForm({ image, onUpdate, allAttributes }) {
	const { t, i18n } = useTranslation();
	const [description, setDescription] = useState(image.description ?? '');
	const [downloading, setDownloading] = useState(false);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [newTagName, setNewTagName] = useState('');

	useEffect(() => {
		setDescription(image.description ?? '');
		setNewTagName('');
	}, [image.id]);

	function saveDescription() {
		onUpdate({ description });
	}

	function handleAttributesChange(e) {
		const selected = Array.from(e.target.selectedOptions, (option) => option.value);
		onUpdate({ attributeNames: selected });
	}

	// Trim-only, not lowercased - matches AttributeService.create/
	// AttributeAdmin's own "new root tag" field, not legacy's exact
	// `.toLowerCase()` (internal consistency with this project's other tag-
	// creation entry point wins over byte-for-byte legacy fidelity here).
	// A duplicate name (case-sensitive, same as the <select>'s own values)
	// is just added to the selection rather than rejected - the backend's
	// find-or-create already treats it as "assign the existing tag", so
	// there is no separate error case to surface.
	function handleAddNewTag(e) {
		e.preventDefault();
		const trimmed = newTagName.trim();
		if (!trimmed) return;
		const current = image.attributeNames ?? [];
		if (!current.includes(trimmed)) {
			onUpdate({ attributeNames: [...current, trimmed] });
		}
		setNewTagName('');
	}

	// Alphabetical (03/09/2026, explicit ask, same as DirectoryDetailPanel's
	// own sequence-tags <select>) - allAttributes arrives in the backend's
	// own tree-traversal order, not sorted by name.
	const sortedAttributes = [...(allAttributes ?? [])].sort((a, b) => a.name.localeCompare(b.name));

	async function handleDownload() {
		setDownloading(true);
		setError(null);
		try {
			const response = await fetch(image.exportUrl, { credentials: 'same-origin' });
			if (!response.ok) throw new Error(`Download failed: ${response.status}`);
			const blob = await response.blob();
			const objectUrl = URL.createObjectURL(blob);
			const link = document.createElement('a');
			link.href = objectUrl;
			link.download = image.name;
			document.body.appendChild(link);
			link.click();
			link.remove();
			URL.revokeObjectURL(objectUrl);
		} catch (err) {
			reportError(err);
		} finally {
			setDownloading(false);
		}
	}

	const captureDate = formatCaptureDate(image.captureDate, i18n.language);

	return (
		<>
			<h3>{image.name}</h3>
			{captureDate && <p className="image-capture-date">{t('imageEdit.captureDate', { date: captureDate })}</p>}
			<label>
				{t('imageEdit.pictureComment')}
				<textarea autoFocus value={description} onBlur={saveDescription} onChange={(e) => setDescription(e.target.value)} rows={4} />
			</label>
			<label>
				{t('imageEdit.rating')}
				<StarRating rating={image.rating} onChange={(rating) => onUpdate({ rating })} />
			</label>
			<label className="directory-detail-attributes">
				{t('imageEdit.tags')}
				<select multiple size={8} className="tag-multiselect" value={image.attributeNames ?? []} onChange={handleAttributesChange}>
					{sortedAttributes.map((a) => (
						<option key={a.id} value={a.name}>
							{a.name}
						</option>
					))}
				</select>
				<form onSubmit={handleAddNewTag} className="new-tag-form">
					<input placeholder={t('imageEdit.newTagPlaceholder')} value={newTagName} onChange={(e) => setNewTagName(e.target.value)} />
					<button type="submit">{t('tags.add')}</button>
				</form>
			</label>
			{error && <p className="form-error">{error}</p>}
			<div className="gallery-popup-actions">
				<button type="button" onClick={handleDownload} disabled={downloading}>
					{downloading ? t('imageEdit.downloading') : t('imageEdit.download')}
				</button>
			</div>
		</>
	);
}

// image.captureDate is a naive LocalDateTime string ("2026-03-15T14:30:00",
// no offset) - parsed as local time, exactly as it was recorded (EXIF/
// upload instant), not converted across a timezone. Returns null (not
// rendered) for a missing/unparsable value, rather than something like
// "Invalid Date".
function formatCaptureDate(captureDate, locale) {
	if (!captureDate) return null;
	const date = new Date(captureDate);
	if (Number.isNaN(date.getTime())) return null;
	return date.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' });
}
