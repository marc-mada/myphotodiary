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

/**
 * Share to myPhotoDiary (21/10/2026, explicit ask) - the page side of
 * public/share-target-sw.js: that worker keeps the files shared from
 * another app in IndexedDB and opens the app with `?shared=<id>`; these
 * functions read them back and discard them. Same database/store names as
 * the worker (it can't import this module - a classic service worker).
 *
 * Files are kept until published or cancelled, so signing in first (or an
 * expired session) loses nothing. Anything older than a week is dropped the
 * next time the app starts - a share that was never finished.
 */

const DB_NAME = 'mpd-share-target';
const STORE = 'pending';
const MAX_AGE_MS = 7 * 24 * 3600 * 1000;

/** Mobile only (main.jsx): registers the worker that receives shared files. */
export function registerShareTarget() {
	if (!('serviceWorker' in navigator)) return;
	navigator.serviceWorker.register('/share-target-sw.js').catch(() => {
		// No worker (e.g. plain-HTTP LAN dev server): sharing to the app just isn't offered.
	});
	discardStaleSharedFiles().catch(() => {});
}

/** The `?shared=` value of the current address: an id, 'error', or null. */
export function sharedIdFromLocation() {
	return new URLSearchParams(window.location.search).get('shared');
}

/** Removes `?shared=` from the address bar, so a reload doesn't reopen the page. */
export function clearSharedIdFromLocation() {
	const url = new URL(window.location.href);
	url.searchParams.delete('shared');
	window.history.replaceState(null, '', url.pathname + url.search + url.hash);
}

/** The files shared under `id`, or null when they're no longer there. */
export async function loadSharedFiles(id) {
	const key = Number(id);
	if (!Number.isInteger(key)) return null;
	const entry = await request((store) => store.get(key), 'readonly');
	return entry?.files?.length ? entry.files : null;
}

export async function discardSharedFiles(id) {
	const key = Number(id);
	if (Number.isInteger(key)) await request((store) => store.delete(key), 'readwrite');
}

async function discardStaleSharedFiles() {
	const db = await openDb();
	await new Promise((resolve, reject) => {
		const tx = db.transaction(STORE, 'readwrite');
		const cursorRequest = tx.objectStore(STORE).openCursor();
		cursorRequest.onsuccess = () => {
			const cursor = cursorRequest.result;
			if (!cursor) return;
			if (Date.now() - (cursor.value?.created ?? 0) > MAX_AGE_MS) cursor.delete();
			cursor.continue();
		};
		tx.oncomplete = resolve;
		tx.onerror = () => reject(tx.error);
	});
}

function openDb() {
	return new Promise((resolve, reject) => {
		const open = indexedDB.open(DB_NAME, 1);
		open.onupgradeneeded = () => open.result.createObjectStore(STORE, { autoIncrement: true });
		open.onsuccess = () => resolve(open.result);
		open.onerror = () => reject(open.error);
	});
}

async function request(operation, mode) {
	const db = await openDb();
	return new Promise((resolve, reject) => {
		const tx = db.transaction(STORE, mode);
		const req = operation(tx.objectStore(STORE));
		tx.oncomplete = () => resolve(req.result);
		tx.onerror = () => reject(tx.error);
	});
}
