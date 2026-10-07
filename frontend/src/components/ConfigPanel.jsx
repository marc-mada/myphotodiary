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
import { appSettingsApi } from '../api/appSettings';
import { invalidateTargetUploadSeconds } from '../upload/adaptiveShrink';
import { meApi } from '../api/navigation';
import { usersApi } from '../api/users';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * The "Config" pane from legacy admin.jsp (configPane/configCache in
 * idxAdmin.js, backed by SessionConfigurationSvr/`json/config`) - edits a
 * *chosen* user's own settings, via UserController's `.../settings` pair
 * (ADMIN-only, targets any userName), not `/api/me` (self-only). Fields
 * ported one at a time as each one's own screen needed it (slideshow delay,
 * then default map center for the Map screen's geolocation picker - see
 * MeController's javadoc); maxQueryLength doesn't have a settings UI yet
 * either (search still just uses the stored default), still not in scope.
 *
 * User picker (29/08/2026, explicit ask) - this form used to implicitly
 * edit whoever is signed in via `/api/me`, which quietly meant "only an
 * ADMIN can ever set their own slideshow delay/map center/post-it fade
 * from this UI" (this whole panel is only reachable from the ADMIN-only
 * Admin screen - a non-admin has no path here at all) and gave no way for
 * an admin to help configure anyone else's preferences either. Since the
 * subject can now be any user, the choice is an explicit part of the form,
 * not inferred from who's signed in - a `<select>` populated from
 * `usersApi.list` (already available to this ADMIN-only screen), defaulting
 * to the signed-in admin's own name as a sane starting point, not a
 * restriction. Changing the selection re-fetches that user's settings from
 * scratch, same as switching users in `UserTable`'s own role panel.
 *
 * One of four independent Admin sub-panels (28/08/2026, explicit ask) -
 * UserTable now owns which one is showing (a single active tab, not each
 * panel independently toggled open), so this component just renders its own
 * content unconditionally; it no longer has its own "Configure"/"Close
 * config" button or `open` state - it's only ever mounted while its tab is
 * the active one, so the settings fetch runs on mount instead of on open.
 *
 * `postItFadeDelay` (28/08/2026, explicit ask) has no legacy field behind
 * it at all - PostIt.jsx used to just stay visible forever - see the V8
 * migration's own comment for why this one field is a new preference,
 * not a port, unlike every other field on this panel.
 *
 * Real bug found (not caused by the postItFadeDelay work, but found while
 * testing it, since it silently blocked the new field's own save too) and
 * fixed the same day: the latitude/longitude inputs' `step="5"` (ported
 * faithfully from admin.jsp's own `step="5"`) combined with the *real*
 * default value (48.8567/2.3508, Paris - UserConfiguration.java's own
 * default, correctly ported too) made the fields natively invalid on
 * load, since neither number is a multiple of 5 from their own `min`.
 * Legacy never hit this itself: its static HTML placeholder was `0`, which
 * *does* satisfy `step="5"`, and it never receives the real fetched value
 * until JS runs. Clicking Save with the fields still untouched then
 * silently did nothing at all (native HTML5 constraint validation blocks
 * `<form onSubmit>` from firing, with no error surfaced anywhere) - proven
 * with `input.checkValidity()`/`validationMessage`, not assumed. `step`
 * dropped to `any` on both fields - arbitrary-precision coordinates are
 * the actual requirement, and nothing here needed quick +/-5 spinner
 * increments in the first place.
 *
 * Max video size (28/08/2026, video support) - a deliberately separate
 * form/section below, not folded into the personal-settings one above:
 * it's the one field on this whole panel that isn't a per-user preference
 * (see AppSettings.java/appSettingsApi's own comments) - it protects the
 * server's own disk, applies to every uploader regardless of who's signed
 * in, and is backed by a different endpoint (`/api/app-settings`, ADMIN-
 * only to write) than every other field here (`/api/me`, self-service).
 * Shown/edited in MB, not raw bytes, for a human-sized number to type.
 *
 * Both forms got their own heading (29/08/2026, explicit ask) - the first
 * one also names *which* user's settings are shown, sourced from the
 * picker's own selection (defaulted once on mount to the signed-in user's
 * own name, read from `/api/me` - not from `AuthContext`, which only ever
 * tracks whether a session exists, not who it belongs to - 18/09/2026,
 * session-cookie auth).
 *
 * `selfOnly` (04/09/2026, explicit ask) - the WRITER-facing mode UserTable
 * mounts this in: only the first form, and even that reduced to exactly
 * the signed-in user's own settings - no picker (the field itself is
 * dropped, not just disabled/pre-filled - there's nothing to pick, it can
 * only ever be "me"), and no app-wide section at all (that one edits
 * server-wide state via an ADMIN-only endpoint - a deliberate decision, never
 * a WRITER's to touch). Swaps the *_ADMIN-only_* `usersApi.getSettings`/
 * `.updateSettings(someOtherUserName, ...)` for `meApi.get`/
 * `.update` - self-service, any authenticated role, already exists on the
 * backend for exactly this (MeController's own javadoc) - not a new
 * endpoint, just a UI finally routing a WRITER to the one that was already
 * theirs to use. `usersApi.list`/`appSettingsApi.get` are never even
 * called in this mode - both need to (or, for the video-size field,
 * needn't but simply have no reason to) exist, avoiding an ADMIN-only 403
 * on mount for the one call that would actually draw one.
 */
export function ConfigPanel({ selfOnly = false }) {
	const { t } = useTranslation();
	const [users, setUsers] = useState([]);
	const [selectedUserName, setSelectedUserName] = useState(null);
	const [slideShowInterval, setSlideShowInterval] = useState(5);
	const [defaultLatitude, setDefaultLatitude] = useState(48.8567);
	const [defaultLongitude, setDefaultLongitude] = useState(2.3508);
	const [postItFadeDelay, setPostItFadeDelay] = useState(8);
	const [saving, setSaving] = useState(false);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);

	const [maxVideoSizeMb, setMaxVideoSizeMb] = useState(20);
	const [targetUploadSeconds, setTargetUploadSeconds] = useState(10);
	const [maxShareSize, setMaxShareSize] = useState(50);
	const [savingVideoSize, setSavingVideoSize] = useState(false);
	const [videoSizeError, setVideoSizeError] = useState(null);
	const reportVideoSizeError = useForbiddenAwareError(setVideoSizeError);
	const [serverVersion, setServerVersion] = useState(null);

	useEffect(() => {
		if (selfOnly) return;
		usersApi
			.list()
			.then((list) => setUsers(list))
			.catch(reportError);
		appSettingsApi
			.get()
			.then((settings) => {
				setMaxVideoSizeMb(Math.round(settings.maxVideoSizeBytes / (1024 * 1024)));
				setTargetUploadSeconds(settings.targetUploadSeconds);
				setMaxShareSize(settings.maxShareSize);
			})
			.catch(reportVideoSizeError);
		// Non-critical label - a failure just leaves it hidden rather than
		// adding a third error banner to this panel.
		appSettingsApi
			.version()
			.then(({ version }) => setServerVersion(version))
			.catch(() => setServerVersion(null));
		// Default the picker to the signed-in user themselves (unchanged
		// behavior from before session-cookie auth - AuthContext no longer
		// carries `username` on its own, so this is now sourced from /api/me
		// directly, the same self-service endpoint the selfOnly branch below
		// already uses for its own purposes).
		meApi.get().then((me) => setSelectedUserName(me.userName)).catch(reportError);
	}, [selfOnly]);

	useEffect(() => {
		if (selfOnly) {
			meApi
				.get()
				.then((me) => {
					setSlideShowInterval(me.slideShowInterval);
					setDefaultLatitude(me.defaultLatitude);
					setDefaultLongitude(me.defaultLongitude);
					setPostItFadeDelay(me.postItFadeDelay);
				})
				.catch(reportError);
			return;
		}
		if (!selectedUserName) return;
		usersApi
			.getSettings(selectedUserName)
			.then((settings) => {
				setSlideShowInterval(settings.slideShowInterval);
				setDefaultLatitude(settings.defaultLatitude);
				setDefaultLongitude(settings.defaultLongitude);
				setPostItFadeDelay(settings.postItFadeDelay);
			})
			.catch(reportError);
	}, [selfOnly, selectedUserName]);

	async function save(e) {
		e.preventDefault();
		setSaving(true);
		setError(null);
		try {
			const patch = {
				slideShowInterval: Number(slideShowInterval),
				defaultLatitude: Number(defaultLatitude),
				defaultLongitude: Number(defaultLongitude),
				postItFadeDelay: Number(postItFadeDelay),
			};
			if (selfOnly) {
				await meApi.update(patch);
			} else {
				await usersApi.updateSettings(selectedUserName, patch);
			}
		} catch (err) {
			reportError(err);
		} finally {
			setSaving(false);
		}
	}

	async function saveVideoSize(e) {
		e.preventDefault();
		setSavingVideoSize(true);
		setVideoSizeError(null);
		try {
			const settings = await appSettingsApi.update({
				maxVideoSizeBytes: Math.round(Number(maxVideoSizeMb) * 1024 * 1024),
				targetUploadSeconds: Math.round(Number(targetUploadSeconds)),
				maxShareSize: Math.round(Number(maxShareSize)),
			});
			setMaxVideoSizeMb(Math.round(settings.maxVideoSizeBytes / (1024 * 1024)));
			setTargetUploadSeconds(settings.targetUploadSeconds);
			setMaxShareSize(settings.maxShareSize);
			// So this tab's next upload uses the new target, not the cached one.
			invalidateTargetUploadSeconds();
		} catch (err) {
			reportVideoSizeError(err);
		} finally {
			setSavingVideoSize(false);
		}
	}

	return (
		<>
			<form className="config-panel-form" onSubmit={save}>
				<h4>{t('config.userSettingsHeading', { username: selectedUserName })}</h4>
				{!selfOnly && (
					<label>
						{t('config.userSettingsUser')}
						<select value={selectedUserName ?? ''} onChange={(e) => setSelectedUserName(e.target.value)}>
							{users.map((u) => (
								<option key={u.userName} value={u.userName}>
									{u.longName ? `${u.userName} (${u.longName})` : u.userName}
								</option>
							))}
						</select>
					</label>
				)}
				<label>
					{t('config.slideShowDelay')}
					<input
						type="number"
						min={2}
						max={60}
						step={1}
						value={slideShowInterval}
						onChange={(e) => setSlideShowInterval(e.target.value)}
					/>
				</label>
				<label>
					{t('config.defaultLatitude')}
					<input type="number" min={-90} max={90} step="any" value={defaultLatitude} onChange={(e) => setDefaultLatitude(e.target.value)} />
				</label>
				<label>
					{t('config.defaultLongitude')}
					<input
						type="number"
						min={-180}
						max={180}
						step="any"
						value={defaultLongitude}
						onChange={(e) => setDefaultLongitude(e.target.value)}
					/>
				</label>
				<label>
					{t('config.postItFadeDelay')}
					<input
						type="number"
						min={2}
						max={60}
						step={1}
						value={postItFadeDelay}
						onChange={(e) => setPostItFadeDelay(e.target.value)}
					/>
				</label>
				{error && <p className="form-error">{error}</p>}
				<button type="submit" disabled={saving}>
					{saving ? t('common.saving') : t('common.save')}
				</button>
			</form>

			{/* Separate form, own submit/save state (see this component's own
			    comment above) - app-wide, not part of the caller's own
			    preferences, and never shown in `selfOnly` mode (a WRITER
			    edits their own settings here, never server-wide state). */}
			{!selfOnly && (
				<form className="config-panel-form app-settings-form" onSubmit={saveVideoSize}>
					<h4>{t('config.appWideSettingsHeading')}</h4>
					<label>
						{t('config.maxVideoSizeMb')}
						<input type="number" min={1} step={1} value={maxVideoSizeMb} onChange={(e) => setMaxVideoSizeMb(e.target.value)} />
					</label>
					{/* Drives the browser's automatic photo shrinking before upload
					    (26/09/2026, explicit ask - ../upload/adaptiveShrink.js). Same
					    1..600 bounds as AppSettingsService's own validation. */}
					<label>
						{t('config.targetUploadSeconds')}
						<input
							type="number"
							min={1}
							max={600}
							step={1}
							value={targetUploadSeconds}
							onChange={(e) => setTargetUploadSeconds(e.target.value)}
						/>
					</label>
					{/* Most pictures a shared search result may hold (04/10/2026) -
					    above it, the first ones are shared and the sharer warned.
					    Same 1..1000 bounds as AppSettingsService. */}
					<label>
						{t('config.maxShareSize')}
						<input type="number" min={1} max={1000} step={1} value={maxShareSize} onChange={(e) => setMaxShareSize(e.target.value)} />
					</label>
					{videoSizeError && <p className="form-error">{videoSizeError}</p>}
					<button type="submit" disabled={savingVideoSize}>
						{savingVideoSize ? t('common.saving') : t('common.save')}
					</button>
				</form>
			)}
			{/* Server-side version (26/09/2026, explicit ask) - right below the
			    App-wide settings form, so ADMIN-only like that form. */}
			{!selfOnly && serverVersion && <p className="app-version">{t('config.appVersion', { version: serverVersion })}</p>}
		</>
	);
}
