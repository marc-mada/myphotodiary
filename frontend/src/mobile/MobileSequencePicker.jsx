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
import { directoryTreeApi } from '../api/navigation';
import { useForbiddenAwareError } from '../temporaryMessage';
import { MobileIcon } from './MobileIcon';

const RECENT_KEY = 'mpd.recentImportTargets';
const RECENT_MAX = 3;

function readRecent() {
	try {
		const list = JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]');
		return Array.isArray(list) ? list.filter((p) => typeof p === 'string' && p) : [];
	} catch {
		return [];
	}
}

/** Remembers a folder this phone just published into (newest first). */
export function rememberImportTarget(path) {
	if (!path) return;
	try {
		const list = [path, ...readRecent().filter((p) => p !== path)].slice(0, RECENT_MAX);
		window.localStorage.setItem(RECENT_KEY, JSON.stringify(list));
	} catch {
		// Storage unavailable (private window...) - shortcuts are a convenience only.
	}
}

function parentOf(path) {
	const i = path.lastIndexOf('/');
	return i < 0 ? '' : path.slice(0, i);
}

/**
 * Picks an existing folder as the target of a mobile import (25/10/2026,
 * Share to myPhotoDiary - see Design.md). Two ways in:
 * - Recent: the folder open in the Go panel, then the last folders this
 *   phone published into. Each one is checked against the tree on disk
 *   before being offered - a renamed or deleted sequence must not come back
 *   as a fresh empty folder (/api/import creates a missing dirPath).
 * - Browse: the same year -> month -> sequence drill-down as the Go panel.
 *   Tapping a row opens it; "Choose this folder" picks the open folder,
 *   since a sequence can have sub-sequences of its own. Any folder but the
 *   root can be chosen.
 * A folder with pictures on disk that was never indexed is indexed when
 * chosen, so its existing pictures show next to the new ones.
 */
export function MobileSequencePicker({ currentDirectoryPath, value, onChange }) {
	const { t } = useTranslation();
	const [recent, setRecent] = useState([]);
	const [stack, setStack] = useState([{ path: '', name: '' }]); // opened folders, root first
	const [listing, setListing] = useState(null); // { path, kids } of the open folder
	const [choosing, setChoosing] = useState(false);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const open = stack[stack.length - 1];
	// Never show the previous folder's rows while the opened one loads.
	const children = listing?.path === open.path ? listing.kids : null;

	useEffect(() => {
		let cancelled = false;
		const candidates = [...new Set([currentDirectoryPath, ...readRecent()].filter(Boolean))];
		Promise.all(
			candidates.map((path) =>
				directoryTreeApi
					.listChildren(parentOf(path))
					.then((kids) => kids.find((k) => k.path === path) ?? null)
					.catch(() => null),
			),
		).then((nodes) => {
			if (!cancelled) setRecent(nodes.filter(Boolean).slice(0, RECENT_MAX + 1));
		});
		return () => {
			cancelled = true;
		};
	}, [currentDirectoryPath]);

	useEffect(() => {
		let cancelled = false;
		directoryTreeApi
			.listChildren(open.path)
			.then((kids) => {
				if (!cancelled) setListing({ path: open.path, kids });
			})
			.catch((err) => {
				if (!cancelled) {
					setListing({ path: open.path, kids: [] });
					reportError(err);
				}
			});
		return () => {
			cancelled = true;
		};
	}, [open.path, reportError]);

	async function choose(node) {
		setError(null);
		setChoosing(true);
		try {
			if (!node.indexed && node.imageCount > 0) await directoryTreeApi.index(node.path);
			onChange(node.path);
		} catch (err) {
			reportError(err);
		} finally {
			setChoosing(false);
		}
	}

	if (value) {
		return (
			<div className="mobile-target-chosen">
				<span>{t('mobile.importTarget', { path: value })}</span>
				<button type="button" onClick={() => onChange(null)} aria-label={t('mobile.changeImportTarget')} title={t('mobile.changeImportTarget')}>
					✕
				</button>
			</div>
		);
	}

	return (
		<div className="mobile-sequence-picker">
			{error && <p className="form-error">{error}</p>}
			{recent.length > 0 && (
				<>
					<h3>{t('mobile.recentSequences')}</h3>
					<ul className="mobile-panel-list">
						{recent.map((node) => (
							<li key={node.path}>
								<button type="button" onClick={() => choose(node)} disabled={choosing}>
									{node.path} <span className="mobile-picker-count">({node.imageCount})</span>
								</button>
							</li>
						))}
					</ul>
				</>
			)}
			<h3>{t('mobile.browseSequences')}</h3>
			<div className="mobile-picker-bar">
				<button type="button" onClick={() => setStack((s) => s.slice(0, -1))} disabled={stack.length === 1}>
					<MobileIcon name="caretLeft" /> {t('mobile.back')}
				</button>
				<button type="button" onClick={() => choose(open)} disabled={!open.path || choosing}>
					<MobileIcon name="check" /> {t('mobile.chooseThisFolder')}
				</button>
			</div>
			<ul className="mobile-panel-list">
				<li className="mobile-panel-current-path">{open.path || t('directoryTree.root')}</li>
				{children === null && <li>{t('common.loading')}</li>}
				{children?.length === 0 && <li className="mobile-picker-empty">{t('mobile.noSubfolders')}</li>}
				{children?.map((node) => (
					<li key={node.path}>
						<button type="button" onClick={() => setStack((s) => [...s, node])}>
							{node.name} {node.imageCount > 0 && <span className="mobile-picker-count">({node.imageCount})</span>} ›
						</button>
					</li>
				))}
			</ul>
		</div>
	);
}
