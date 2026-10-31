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
import { groupApi, meApi } from '../api/navigation';
import { hasSearchCriteria, MIN_TEXT_SEARCH_LENGTH } from '../gallery/searchCriteria';
import { StarRating } from '../gallery/StarRating';
import { MobileIcon } from './MobileIcon';
import { MobilePage } from './MobilePage';
import { TEMPORARY_MESSAGE_TIMEOUT_MS, useForbiddenAwareError } from '../temporaryMessage';

/**
 * `#query-page` - date range, attributes (alphabetical, 03/09/2026, explicit
 * ask - `allAttributes` itself arrives in tree order, not name order),
 * minimum rating (moved above the attribute list, same ask; now the same
 * 4-star `StarRating` widget `MobileImageEditPage` already uses, replacing
 * a `<select>` - 03/09/2026, explicit ask, "add a four stars rating
 * input"), a free-text field (03/09/2026, explicit ask - matches the
 * sequence name/sequence
 * description/image description, an OR of the three, ANDed with every
 * other criterion here - see GalleryService.search's own doc for the exact
 * logic), exactly the same filter fields as desktop's Search screen
 * (Design.md §15, legacy feature-parity audit:
 * "search is just as complete as on desktop - no reduction there" - the one legacy mobile screen that isn't a cut-down version of its
 * PC counterpart). What IS simplified, deliberately: no infinite-scroll
 * pagination *while browsing* - legacy's own `queryPageCtrl.query()` runs a
 * single query and never asks for more once results are on screen
 * (`explorer.query` has no notion of a next page at all), so this page's
 * own `onSearch` prop (MobileApp's `handleSearch`) fetches the *entire*
 * match set up front - not just its own first page, a real bug fixed the
 * same day this file's other two changes landed - and hands it over as one
 * browsable list, same as legacy swapping the whole result set into
 * `imageSwapper` in one go.
 *
 * Tapping Search with nothing actually filled in doesn't call `onSearch` at
 * all (explicit ask, 10/09/2026, shared with desktop's identical rule -
 * `hasSearchCriteria`/`MIN_TEXT_SEARCH_LENGTH`, `../gallery/searchCriteria`)
 * - instead shows a temporary "Fill in search criteria" message that
 * clears itself after `TEMPORARY_MESSAGE_TIMEOUT_MS` (`../temporaryMessage`
 * - the same shared duration the new "Forbidden access" message uses,
 * 13/09/2026, not a constant of its own any more). Reuses the same
 * `.form-error` paragraph
 * `error` already renders above the button (moved there 09/09/2026) rather
 * than a second element - the two states never overlap (this one is
 * resolved locally, before `onSearch` is ever called; a real search failure
 * can only happen after it), so one piece of state covers both.
 */
export function MobileSearchPage({ allAttributes, onSearch, onHome, onLogout }) {
	const { t } = useTranslation();
	const [text, setText] = useState('');
	const [selectedAttributes, setSelectedAttributes] = useState([]);
	const [minRating, setMinRating] = useState('');
	const [fromDate, setFromDate] = useState('');
	const [toDate, setToDate] = useState('');
	// Photos or videos only (15/10/2026) - '' for both, else 'IMAGE'/'VIDEO'.
	const [mediaType, setMediaType] = useState('');
	// One group's pictures only (31/10/2026, explicit ask - same filter as
	// desktop's SearchScreen): '' for all the groups the user can browse.
	const [group, setGroup] = useState('');
	const [readableGroupNames, setReadableGroupNames] = useState([]);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [searching, setSearching] = useState(false);
	const criteriaMessageTimeoutRef = useRef(null);

	useEffect(() => {
		return () => {
			if (criteriaMessageTimeoutRef.current != null) window.clearTimeout(criteriaMessageTimeoutRef.current);
		};
	}, []);

	// Every group for an admin, otherwise the groups the user holds any role
	// in (any role can browse) - same resolution as desktop's SearchScreen.
	useEffect(() => {
		meApi
			.get()
			.then((me) =>
				me.primaryRoleName === 'ADMIN'
					? groupApi.list().then((groups) => groups.map((g) => g.groupName))
					: meApi.groups().then((groups) => [...new Set(groups.map((g) => g.groupName))]),
			)
			.then(setReadableGroupNames)
			.catch(reportError);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);
	const otherGroupNames = readableGroupNames.filter((name) => name !== 'public').sort((a, b) => a.localeCompare(b));

	// Since/Till (31/10/2026, real bug reported on a phone): the two fields
	// used to limit each other with min/max. With Till set and Since empty,
	// Android's date picker still opened on today and let a later date be
	// picked, which the browser then turned into a bogus year-0001 value.
	// No min/max any more: picking a Since after Till (or a Till before
	// Since) moves the other date along instead. A year before 1800 can't
	// be a photo date - such a value is ignored rather than searched for.
	function isPlausibleDate(value) {
		return value === '' || Number(value.slice(0, 4)) >= 1800;
	}

	function handleFromDateChange(value) {
		if (!isPlausibleDate(value)) return;
		setFromDate(value);
		if (value && toDate && value > toDate) setToDate(value);
	}

	function handleToDateChange(value) {
		if (!isPlausibleDate(value)) return;
		setToDate(value);
		if (value && fromDate && value < fromDate) setFromDate(value);
	}

	function toggleAttribute(name) {
		setSelectedAttributes((prev) => (prev.includes(name) ? prev.filter((n) => n !== name) : [...prev, name]));
	}

	// Alphabetical (03/09/2026, explicit ask) - allAttributes arrives in
	// whatever order the backend's own tree traversal produced it in
	// (AttributeService, insertion/hierarchy order), not sorted by name;
	// a plain a-z compare is enough here since this list is flat (no
	// parent/child grouping to preserve, unlike the Admin tag tree).
	const sortedAttributes = [...allAttributes].sort((a, b) => a.name.localeCompare(b.name));

	// Same widget as MobileImageEditPage's own rating (03/09/2026, explicit
	// ask - "add a four stars rating input") - see SearchScreen's own
	// (desktop) identical handler for why a plain StarRating click needs
	// this wrapper here specifically: StarRating always calls
	// onChange(level), with no built-in way back to "no rating" the way the
	// <select> it replaces had an explicit "Any" option for - min-rating is
	// an optional filter, not a required value, so clicking the star that's
	// already the current minimum toggles it back off instead.
	function handleMinRatingClick(level) {
		setMinRating((prev) => (prev === String(level) ? '' : String(level)));
	}

	async function handleSearch() {
		const trimmedText = text.trim();
		const criteria = {
			attributeNames: selectedAttributes,
			minRating: minRating === '' ? null : Number(minRating),
			fromDate: fromDate || null,
			toDate: toDate || null,
			mediaType: mediaType || null,
			group: group || null,
			// Below the minimum, not a criterion at all (explicit ask,
			// 10/09/2026) - same threshold desktop's own debounce applies,
			// now enforced here too rather than left for the backend to
			// silently ignore a too-short fragment it was sent anyway.
			text: trimmedText.length >= MIN_TEXT_SEARCH_LENGTH ? trimmedText : null,
		};
		if (!hasSearchCriteria(criteria)) {
			if (criteriaMessageTimeoutRef.current != null) window.clearTimeout(criteriaMessageTimeoutRef.current);
			setError(t('mobile.fillInSearchCriteria'));
			criteriaMessageTimeoutRef.current = window.setTimeout(() => setError(null), TEMPORARY_MESSAGE_TIMEOUT_MS);
			return;
		}
		setSearching(true);
		setError(null);
		try {
			await onSearch(criteria);
		} catch (err) {
			reportError(err);
		} finally {
			setSearching(false);
		}
	}

	return (
		<MobilePage title={t('mobile.search')} onHome={onHome} onLogout={onLogout}>
			{/* Moved above every criterion (explicit ask, 09/09/2026) - no
			    longer requires scrolling past the whole filter list (text,
			    dates, rating, attributes) to reach it. The error message
			    (a failed search, not a validation problem with any one
			    field) moved up with it - it's feedback on this button's own
			    action, not tied to any single criterion below. */}
			{error && <p className="form-error">{error}</p>}
			<button type="button" onClick={handleSearch} disabled={searching}>
				{searching ? t('mobile.searching') : t('mobile.search')} <MobileIcon name="search" />
			</button>
			<label>
				{t('mobile.searchText')}
				<input type="text" value={text} onChange={(e) => setText(e.target.value)} placeholder={t('mobile.searchTextPlaceholder')} />
			</label>
			{/* Two fields per row (31/10/2026, explicit ask): Since/Till, then
			    rating/type, then the group on its own row below. */}
			<div className="mobile-search-row">
				<label>
					{t('mobile.since')}
					<input type="date" value={fromDate} onChange={(e) => handleFromDateChange(e.target.value)} />
				</label>
				<label>
					{t('mobile.till')}
					<input type="date" value={toDate} onChange={(e) => handleToDateChange(e.target.value)} />
				</label>
			</div>
			<div className="mobile-search-row">
				<label>
					{t('mobile.minimumRating')}
					<StarRating rating={minRating === '' ? -1 : Number(minRating)} onChange={handleMinRatingClick} />
				</label>
				<label>
					{t('search.mediaType')}
					<select value={mediaType} onChange={(e) => setMediaType(e.target.value)}>
						<option value="">{t('search.mediaTypeAll')}</option>
						<option value="IMAGE">{t('search.mediaTypeImage')}</option>
						<option value="VIDEO">{t('search.mediaTypeVideo')}</option>
					</select>
				</label>
			</div>
			<label>
				{t('search.group')}
				<select value={group} onChange={(e) => setGroup(e.target.value)}>
					<option value="">{t('search.groupAll')}</option>
					<option value="public">public</option>
					{otherGroupNames.map((name) => (
						<option key={name} value={name}>
							{name}
						</option>
					))}
				</select>
			</label>
			<fieldset className="mobile-attribute-checklist">
				<legend>{t('mobile.attributesLegend')}</legend>
				{sortedAttributes.map((a) => (
					<label key={a.id} className="attribute-checkbox">
						<input type="checkbox" checked={selectedAttributes.includes(a.name)} onChange={() => toggleAttribute(a.name)} />
						{a.name}
					</label>
				))}
			</fieldset>
		</MobilePage>
	);
}
