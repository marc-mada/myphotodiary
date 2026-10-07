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

import { byteBudget, hasLiveMeasurement, maybeShrinkForUpload, meterBytesSent, meterUploadEnded, meterUploadStarted } from './adaptiveShrink';

/**
 * One photo/video upload to `url` (a multipart POST, field "file"), with the
 * adaptive shrinking of ./adaptiveShrink.js applied (26/09/2026) - shared by
 * desktop Uploader.jsx (FilePond) and mobile MobileImportForm.jsx.
 * XMLHttpRequest rather than fetch on both: fetch reports no upload
 * progress, which both the link meter and the live check below need.
 *
 * 1. Before sending: shrunk if the current estimate says it wouldn't fit the
 *    target upload time.
 * 2. While sending a full-size file (typically the first ones of a batch,
 *    when nothing was known yet - explicit ask after testing: "decide
 *    quicker to fall back"): as soon as the batch has a live measurement
 *    (~4s in), if what's *left* of this upload wouldn't fit the target's
 *    byte budget, a shrunk copy is prepared while the original keeps going,
 *    and the original is cancelled and replaced only if the shrunk copy is
 *    smaller than what remains to send. Checked once per upload.
 *
 * Returns `{ promise, abort }`; `promise` resolves to `{ status,
 * responseText }` for any HTTP response (callers decide what's an error),
 * rejects on a network failure or a caller abort (`AbortError`).
 */
export function adaptiveUpload({ file, url, headers = {}, onProgress = () => {} }) {
	let cancelled = false;
	let current = null;

	const promise = (async () => {
		let body = await maybeShrinkForUpload(file);
		let allowSwitch = body === file;
		for (;;) {
			if (cancelled) throw abortError();
			current = sendOnce(body, allowSwitch);
			const result = await current.promise;
			if (!result.switchTo) return result;
			body = result.switchTo;
			allowSwitch = false;
		}
	})();

	function sendOnce(body, allowSwitch) {
		const xhr = new XMLHttpRequest();
		let switchTo = null;
		let checked = false;
		let sent = 0;
		let startedAt = 0;
		let metering = false;

		const promise = new Promise((resolve, reject) => {
			xhr.open('POST', url);
			Object.entries(headers).forEach(([name, value]) => xhr.setRequestHeader(name, value));

			const stopMetering = () => {
				if (!metering) return;
				metering = false;
				meterUploadEnded(body.size, performance.now() - startedAt);
			};
			xhr.upload.onloadstart = () => {
				metering = true;
				startedAt = performance.now();
				meterUploadStarted();
			};
			xhr.upload.onprogress = (e) => {
				meterBytesSent(e.loaded - sent);
				sent = e.loaded;
				onProgress(e.loaded, e.total);
				if (allowSwitch && !checked && hasLiveMeasurement()) {
					checked = true;
					considerSwitch(e.total);
				}
			};
			xhr.upload.onload = stopMetering;
			xhr.upload.onabort = stopMetering;
			xhr.upload.onerror = stopMetering;

			xhr.onload = () => resolve({ status: xhr.status, responseText: xhr.responseText });
			xhr.onerror = () => {
				stopMetering();
				reject(new Error('network error'));
			};
			xhr.onabort = () => {
				stopMetering();
				if (switchTo) resolve({ switchTo });
				else reject(abortError());
			};

			const form = new FormData();
			form.append('file', body, file.name);
			xhr.send(form);
		});

		async function considerSwitch(total) {
			const budget = await byteBudget();
			if (budget == null || total - sent <= budget) return; // finishing still fits the target
			const shrunk = await maybeShrinkForUpload(file);
			if (shrunk === file || xhr.readyState === XMLHttpRequest.DONE || cancelled) return;
			const remaining = total - sent;
			if (shrunk.size >= remaining) return;
			console.info(
				`[adaptive upload] ${file.name}: ${(remaining / 1e6).toFixed(2)} MB still to send at full size - ` +
					`restarting with the ${(shrunk.size / 1e6).toFixed(2)} MB shrunk copy`,
			);
			switchTo = shrunk;
			onProgress(0, shrunk.size);
			xhr.abort();
		}

		return { promise, abort: () => xhr.abort() };
	}

	return {
		promise,
		abort: () => {
			cancelled = true;
			if (current) current.abort();
		},
	};
}

function abortError() {
	return new DOMException('Upload aborted', 'AbortError');
}
