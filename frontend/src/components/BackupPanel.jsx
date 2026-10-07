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
import { backupApi } from '../api/backup';
import { useForbiddenAwareError } from '../temporaryMessage';

function formatBytes(bytes) {
	if (bytes == null) return '';
	if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
	if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
	return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

/**
 * The fifth Admin sub-panel (18/09/2026 - see BackupController/
 * DatabaseBackupService's own javadoc for the full backend design).
 * Same "one of several independent sub-panels" pattern as ConfigPanel/
 * IndexManagementPanel/AttributeAdmin - UserTable mounts this only while
 * its own tab is active, ADMIN-only end to end (this tab is never shown to
 * a WRITER at all - UserTable's own comment on why).
 *
 * Deliberately read-mostly: the only action here is "back up the database
 * now" - there is no button anywhere in this UI to trigger the image-tree
 * backup (it runs on its own systemd timer, see backup-images.sh) and,
 * more importantly, no restore button of any kind - see
 * BackupController's own javadoc for why restore is never a one-click web
 * action in this app. This panel only ever shows the current state, plus
 * one narrow, low-risk trigger.
 */
export function BackupPanel() {
	const { t } = useTranslation();
	const [status, setStatus] = useState(null);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [lastTrigger, setLastTrigger] = useState(null);

	async function load() {
		try {
			setStatus(await backupApi.status());
		} catch (err) {
			reportError(err);
		}
	}

	useEffect(() => {
		load();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	async function handleTriggerDb() {
		setBusy(true);
		setError(null);
		setLastTrigger(null);
		try {
			const result = await backupApi.triggerDb();
			setLastTrigger(result);
			await load();
		} catch (err) {
			reportError(err);
		} finally {
			setBusy(false);
		}
	}

	if (status === null) {
		return (
			<div className="index-management-panel">
				<div className="index-management-box">
					<p>{t('backup.loading')}</p>
					{error && <p className="form-error">{error}</p>}
				</div>
			</div>
		);
	}

	return (
		<div className="index-management-panel">
			<div className="index-management-box">
				{error && <p className="form-error">{error}</p>}

				{status.backupRootMounted && !status.markerPresent ? (
					<p className="backup-status-warning">{t('backup.markerMissing', { file: status.markerFile })}</p>
				) : (
					<p className={status.backupRootMounted ? 'backup-status-ok' : 'backup-status-warning'}>
						{status.backupRootMounted ? t('backup.rootMounted') : t('backup.rootNotMounted')}
					</p>
				)}

				<h4>{t('backup.databaseHeading')}</h4>
				<button type="button" onClick={handleTriggerDb} disabled={busy}>
					{busy ? t('backup.backingUp') : t('backup.backUpNow')}
				</button>
				{lastTrigger && (
					<p className={lastTrigger.success ? 'backup-status-ok' : 'backup-status-warning'}>
						{lastTrigger.success ? t('backup.triggerSuccess', { size: formatBytes(lastTrigger.sizeBytes) }) : lastTrigger.message}
					</p>
				)}

				{status.dbBackups.length === 0 ? (
					<p>{t('backup.noSnapshotsYet')}</p>
				) : (
					<table className="index-management-table">
						<thead>
							<tr>
								<th>{t('backup.timestampCol')}</th>
								<th>{t('backup.sizeCol')}</th>
							</tr>
						</thead>
						<tbody>
							{status.dbBackups.map((snapshot) => (
								<tr key={snapshot.timestamp}>
									<td>{snapshot.timestamp}</td>
									<td>{formatBytes(snapshot.sizeBytes)}</td>
								</tr>
							))}
						</tbody>
					</table>
				)}
			</div>

			<div className="index-management-box">
				<h4>{t('backup.imagesHeading')}</h4>
				<p className="hint">{t('backup.imagesHint')}</p>
				{status.imageBackup.timestamp == null ? (
					<p>{t('backup.noSnapshotsYet')}</p>
				) : (
					<p className={status.imageBackup.success ? 'backup-status-ok' : 'backup-status-warning'}>
						{t('backup.imagesLastRun', { timestamp: status.imageBackup.timestamp })}
						{' - '}
						{status.imageBackup.success ? formatBytes(status.imageBackup.sizeBytes) : status.imageBackup.message}
					</p>
				)}
			</div>
		</div>
	);
}
