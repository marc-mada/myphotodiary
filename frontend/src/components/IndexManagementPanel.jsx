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
import { galleryApi } from '../api/gallery';
import { directoryTreeApi, importApi } from '../api/navigation';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * The "Index management" pane from legacy admin.jsp (`dirIndexPane`,
 * `idxAdmin.js`) - Design.md §15, legacy feature-parity audit, 27/08/2026: bulk index/
 * reset-index/delete over many sequences at once (a checkbox table, not one
 * popup per directory the way the Gallery screen's own sequence popup
 * works).
 *
 * A flat table rather than legacy's fancytree-in-table-mode - the checkbox
 * semantics and the three commands are what's being ported, not the tree
 * widget itself (Design.md decision #8 corollary: jQuery DOM-manipulating widgets are
 * rewritten as React components, not reused, and a lazily-expandable tree
 * isn't needed here the way the Navigation screen's own tree needs it - this
 * table is meant to show everything at once for bulk selection).
 *
 * One of four independent Admin sub-panels (28/08/2026, explicit ask) -
 * UserTable now owns which one is showing (a single active tab, not each
 * panel independently toggled open), so this component just renders its own
 * content unconditionally; it no longer has its own "Index management"/
 * "Close index management" button or `open` state - it's only ever mounted
 * while its tab is the active one, so the initial load runs on mount.
 *
 * Restructured into three stacked white boxes with a literal black spacer
 * between each (explicit ask, 28/08/2026): the directory table (unchanged),
 * then the "New Directory" form (moved here from the Gallery sidebar - see
 * its own comment below for why), then "Batch Publish".
 *
 * **"Batch Publish" rebuilt as legacy's real behavior (01/09/2026)** - it
 * used to call the recursive-reindex endpoint (`directoryTreeApi
 * .batchPublish`, still real, still used by `batch-publish-by-year.sh`),
 * which just re-scans the *existing* tree - never what legacy's own Batch
 * Publish actually did (import from an external staging path, sorted by
 * EXIF, into the tree). Genuinely redundant with the table above for that
 * old meaning (the table already shows/acts on every existing directory);
 * not redundant for the real meaning, which nothing else in this panel does
 * at all. Now calls `importApi.stagingImport` - see
 * `StagingImportService`'s own javadoc for the full feature.
 */
export function IndexManagementPanel() {
	const { t } = useTranslation();
	const [rows, setRows] = useState(null);
	const [selection, setSelection] = useState({}); // path -> { index, reset, delete }
	// Batch Publish (staging import) form - stagingDefaultYearMonth is the
	// optional year/month selector (condition 3, 01/09/2026): an
	// <input type="month"> value ("yyyy-MM" or "") - only used as the EXIF
	// fallback date for a file that has none of its own.
	const [stagingPath, setStagingPath] = useState('');
	const [stagingDefaultYearMonth, setStagingDefaultYearMonth] = useState('');
	// Scanned old photos sorted by hand into yyyy/mm/<sequence> (06/10/2026):
	// the folders give the date, so the month selector doesn't apply.
	const [useFolderDates, setUseFolderDates] = useState(false);
	// New Directory form (moved here from GalleryScreen's sidebar,
	// 28/08/2026 bug report: creating a directory that way only ever wrote a
	// DB row, never a real folder on disk - invisible to the filesystem-driven
	// navigation tree, Design.md decision #6/#7. Fixed at the source
	// (ImageStorageService.createDirectory, now called from
	// GalleryService.createDirectory) and relocated here per the same
	// request - this admin table is where directories are managed in bulk,
	// not a sidebar of the screen meant for browsing/viewing pictures.
	const [newPath, setNewPath] = useState('');
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [summary, setSummary] = useState(null);

	useEffect(() => {
		load();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	async function load() {
		setError(null);
		try {
			const all = await directoryTreeApi.listAllRecursive('');
			setRows(all);
			setSelection({});
		} catch (err) {
			reportError(err);
		}
	}

	function toggle(path, column) {
		setSelection((prev) => ({ ...prev, [path]: { ...prev[path], [column]: !prev[path]?.[column] } }));
	}

	function pathsFor(column) {
		return Object.entries(selection)
			.filter(([, cols]) => cols[column])
			.map(([path]) => path);
	}

	async function execute() {
		// Same order as legacy's dirIndexPane task chain (deleteTask ->
		// indexResetTask -> indexTask): delete first (a path both deleted and
		// re-indexed in the same batch should end up gone, not resurrected),
		// then reset, then index.
		const toDelete = pathsFor('delete');
		const toReset = pathsFor('reset');
		const toIndex = pathsFor('index');
		const totalCount = toDelete.length + toReset.length + toIndex.length;
		if (totalCount === 0) return;
		if (
			!window.confirm(
				t('indexManagement.confirmTasks', {
					count: totalCount,
					toDelete: toDelete.length,
					toReset: toReset.length,
					toIndex: toIndex.length,
				}),
			)
		) {
			return;
		}
		setBusy(true);
		setError(null);
		setSummary(null);
		try {
			const results = [];
			if (toDelete.length) results.push(await directoryTreeApi.batchIndex('delete', toDelete));
			if (toReset.length) results.push(await directoryTreeApi.batchIndex('reset-index', toReset));
			if (toIndex.length) results.push(await directoryTreeApi.batchIndex('index', toIndex));
			const succeeded = results.reduce((n, r) => n + r.succeeded.length, 0);
			const failed = results.flatMap((r) => r.failed);
			setSummary({ succeeded, failed });
			await load();
		} catch (err) {
			reportError(err);
		} finally {
			setBusy(false);
		}
	}

	async function createDirectory(e) {
		e.preventDefault();
		if (!newPath.trim()) return;
		setError(null);
		try {
			await galleryApi.createDirectory({ path: newPath.trim(), groupName: null });
			setNewPath('');
			await load();
		} catch (err) {
			reportError(err);
		}
	}

	async function importStaging(e) {
		e.preventDefault();
		if (!stagingPath.trim()) return;
		setBusy(true);
		setError(null);
		setSummary(null);
		try {
			// "yyyy-MM" from <input type="month">, or "" if left blank -
			// both halves given together or neither, matching the backend's
			// own validation (StagingImportService.importBatch).
			const [year, month] = stagingDefaultYearMonth && !useFolderDates ? stagingDefaultYearMonth.split('-').map(Number) : [undefined, undefined];
			const result = await importApi.stagingImport(stagingPath.trim(), year, month, undefined, useFolderDates);
			setSummary({ succeeded: result.succeeded.length, failed: result.failed });
			setStagingPath('');
			setStagingDefaultYearMonth('');
			await load();
		} catch (err) {
			reportError(err);
		} finally {
			setBusy(false);
		}
	}

	return (
		<div className="index-management-panel">
			{error && <p className="form-error">{error}</p>}
			{summary && (
				<p className="hint">
					{t('indexManagement.succeeded', { count: summary.succeeded })}
					{summary.failed.length > 0 &&
						t('indexManagement.failedSuffix', {
							count: summary.failed.length,
							details: summary.failed.map((f) => `${f.path} (${f.message})`).join('; '),
						})}
				</p>
			)}

			<div className="index-management-box">
				{rows === null ? (
					<p className="tree-loading">{t('common.loading')}</p>
				) : rows.length === 0 ? (
					<p className="tree-empty">{t('indexManagement.noDirectories')}</p>
				) : (
					<>
						<div className="index-management-table-wrap">
							<table className="index-management-table">
								<thead>
									<tr>
										<th>{t('indexManagement.sequenceCol')}</th>
										<th>{t('indexManagement.imagesCol')}</th>
										<th>{t('indexManagement.indexCol')}</th>
										<th>{t('indexManagement.resetIndexCol')}</th>
										<th>{t('indexManagement.deleteCol')}</th>
									</tr>
								</thead>
								<tbody>
									{rows.map((row) => (
										<tr key={row.path}>
											<td>{row.path}</td>
											<td>{row.imageCount}</td>
											<td>
												{row.imageCount > 0 && (
													<input type="checkbox" checked={!!selection[row.path]?.index} onChange={() => toggle(row.path, 'index')} />
												)}
											</td>
											<td>
												{row.indexed && (
													<input type="checkbox" checked={!!selection[row.path]?.reset} onChange={() => toggle(row.path, 'reset')} />
												)}
											</td>
											<td>
												<input type="checkbox" checked={!!selection[row.path]?.delete} onChange={() => toggle(row.path, 'delete')} />
											</td>
										</tr>
									))}
								</tbody>
							</table>
						</div>
						<button type="button" onClick={execute} disabled={busy}>
							{busy ? t('indexManagement.working') : t('indexManagement.checkAndExecute')}
						</button>
					</>
				)}
			</div>

			<div className="index-management-box">
				<form onSubmit={createDirectory} className="directory-create-form">
					<input placeholder={t('indexManagement.newDirectoryPlaceholder')} value={newPath} onChange={(e) => setNewPath(e.target.value)} />
					<button type="submit">{t('indexManagement.newDirectory')}</button>
				</form>
			</div>

			<div className="index-management-box">
				<form onSubmit={importStaging} className="batch-publish-form">
					<input
						placeholder={t('indexManagement.batchPublishPlaceholder')}
						value={stagingPath}
						onChange={(e) => setStagingPath(e.target.value)}
					/>
					<input
						type="month"
						title={t('indexManagement.batchPublishDefaultDate')}
						value={stagingDefaultYearMonth}
						disabled={useFolderDates}
						onChange={(e) => setStagingDefaultYearMonth(e.target.value)}
					/>
					<button type="submit" disabled={busy}>
						{t('indexManagement.batchPublish')}
					</button>
					<label className="batch-publish-folder-dates">
						<input type="checkbox" checked={useFolderDates} onChange={(e) => setUseFolderDates(e.target.checked)} />
						{t('indexManagement.batchPublishUseFolderDates')}
					</label>
				</form>
			</div>
		</div>
	);
}
