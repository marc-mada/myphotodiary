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

/*
 * Share to myPhotoDiary (21/10/2026, explicit ask) - the receiving end of
 * the Web Share Target declared in manifest.json. When the installed app is
 * picked in the phone's share sheet, the browser POSTs the shared files to
 * /share-receive; this worker catches that request (it never reaches the
 * server - an OS-initiated POST carries no CSRF token), keeps the files in
 * IndexedDB and opens the app with ?shared=<id>, where MobileApp shows the
 * "Publish shared files" page (src/mobile/sharedFiles.js reads them back).
 *
 * Deliberately does nothing else: no caching, no offline mode - every other
 * request goes straight to the network as if there were no worker.
 * Registered on mobile only (main.jsx).
 */

const DB_NAME = 'mpd-share-target';
const STORE = 'pending';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
	const url = new URL(event.request.url);
	if (event.request.method !== 'POST' || url.pathname !== '/share-receive') return;
	event.respondWith(receive(event.request));
});

async function receive(request) {
	try {
		const form = await request.formData();
		const files = form.getAll('media').filter((value) => value instanceof File);
		const id = await save({ files, created: Date.now() });
		return Response.redirect(`/?shared=${id}`, 303);
	} catch {
		return Response.redirect('/?shared=error', 303);
	}
}

function openDb() {
	return new Promise((resolve, reject) => {
		const open = indexedDB.open(DB_NAME, 1);
		open.onupgradeneeded = () => open.result.createObjectStore(STORE, { autoIncrement: true });
		open.onsuccess = () => resolve(open.result);
		open.onerror = () => reject(open.error);
	});
}

async function save(entry) {
	const db = await openDb();
	return new Promise((resolve, reject) => {
		const tx = db.transaction(STORE, 'readwrite');
		const add = tx.objectStore(STORE).add(entry);
		tx.oncomplete = () => resolve(add.result);
		tx.onerror = () => reject(tx.error);
	});
}
