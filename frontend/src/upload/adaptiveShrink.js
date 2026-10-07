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

import { appSettingsApi } from '../api/appSettings';

/**
 * Adaptive photo shrinking before upload (26/09/2026, explicit ask - "shrinking
 * photos in the browser before upload, depending on the connection", fully
 * automatic, never below 2400px on the long side). Used by both upload
 * paths: desktop Uploader.jsx (FilePond) and mobile MobileImportForm.jsx.
 *
 * Accepted trade-off (explicit, same ask): a shrunk upload *is* the
 * original from then on - the server, the image editor and the NAS backup
 * only ever see the shrunk file.
 *
 * Trigger and ratio (same day, explicit ask): driven by the app-wide
 * "target average upload time per file" setting (Admin -> Configure,
 * AppSettings.targetUploadSeconds, default 10s). With the measured link
 * speed, a photo whose predicted transfer time (size / speed) fits the
 * target is sent untouched; otherwise it's scaled so its expected size fits
 * the target's byte budget (target x speed): JPEG size grows roughly with
 * pixel count, so the linear scale is sqrt(budget / size) - the quality-0.85
 * re-encode shrinks it a bit further still, so the result lands at or under
 * budget rather than over it. The long side never goes below 2400px
 * (explicit ask) - on a very slow link that floor wins and the upload simply
 * takes longer than the target.
 *
 * Connection estimate (reworked same day after real testing): one
 * continuous meter of the *whole* link - bytes sent by every in-flight
 * upload together (desktop FilePond runs 2 in parallel), over a sliding
 * 3s window, skipping the first second of activity (the OS socket buffer
 * swallows a burst instantly and would inflate it). So the target is an
 * *average* per file: 2 parallel shrunk files each take ~2x target, but
 * finish in pairs. A first measurement exists ~4s into a batch - used both
 * for the files still queued and to cancel/restart an in-flight full-size
 * upload (adaptiveUpload.js). Uploads too short for a window (a fast link)
 * record their whole-upload rate instead. Forgotten after 60s without any
 * upload (IDLE_EXPIRY_MS), so a new batch on a different network (4G ->
 * Wi-Fi) doesn't inherit a stale figure - found in testing: a slow
 * measurement kept for the whole page wrongly shrank the next fast batch.
 * Before any measurement: the Network Information API hint
 * (`navigator.connection`, Chrome/Edge/Android only), else full size.
 *
 * Only JPEG is touched (PNG/GIF/HEIC/video pass through unchanged - no
 * transcoding policy for video, and re-encoding a PNG as JPEG would change
 * its format). A photo already small enough, or whose re-encoded result
 * isn't actually smaller, is sent as-is.
 *
 * EXIF: a canvas re-encode drops all metadata, but the server derives the
 * capture date and GPS from it (ExifDateReader/ExifGpsReader). So the
 * original's raw Exif APP1 block is copied verbatim into the new JPEG, with
 * only its Orientation tag rewritten to 1 - the browser already applied the
 * orientation when drawing (drawImage on an <img> honors EXIF orientation in
 * every current browser), so keeping the old value would rotate it twice.
 */

const MIN_LONG_SIDE = 2400; // explicit ask: never below 2400px
const DEFAULT_TARGET_SECONDS = 10; // same as V13's own column default
const JPEG_QUALITY = 0.85;
// Below this, a file isn't worth re-encoding whatever the link.
const MIN_SIZE_TO_SHRINK = 1024 * 1024;
// Uploads smaller than this are dominated by latency, not bandwidth - they'd
// report a misleadingly low rate, so they don't feed the estimate.
const MIN_BYTES_TO_MEASURE = 256 * 1024;
const WINDOW_MS = 3000;
const BURST_SKIP_MS = 1000;
const IDLE_EXPIRY_MS = 60 * 1000;

// --- Link meter (see this file's doc comment) ---
let activeUploads = 0;
let activeSince = 0;
let totalBytes = 0;
let samples = []; // {t, bytes} - cumulative bytes over the current active period
let liveMbps = null; // from a window in the current active period
let storedMbps = null; // latest measurement, kept across periods until idle expiry
let lastActivityAt = 0;

const now = () => performance.now();

export function meterUploadStarted() {
	if (activeUploads === 0) {
		activeSince = now();
		samples = [];
		liveMbps = null;
	}
	activeUploads++;
	lastActivityAt = now();
}

export function meterBytesSent(delta) {
	if (delta <= 0) return;
	totalBytes += delta;
	const t = now();
	lastActivityAt = t;
	if (t < activeSince + BURST_SKIP_MS) return;
	samples.push({ t, bytes: totalBytes });
	while (samples.length > 1 && samples[1].t <= t - WINDOW_MS) samples.shift();
	const base = samples[0];
	if (base.t <= t - WINDOW_MS) {
		liveMbps = ((totalBytes - base.bytes) * 8) / ((t - base.t) / 1000) / 1e6;
		storedMbps = liveMbps;
	}
}

export function meterUploadEnded(bytes, elapsedMs) {
	// Too short to ever produce a window (typically a fast link): the whole
	// upload's own rate is the only figure available.
	if (elapsedMs > 0 && elapsedMs < WINDOW_MS + BURST_SKIP_MS && bytes >= MIN_BYTES_TO_MEASURE && liveMbps == null) {
		storedMbps = (bytes * 8) / (elapsedMs / 1000) / 1e6;
	}
	activeUploads = Math.max(0, activeUploads - 1);
	lastActivityAt = now();
	if (activeUploads === 0) {
		samples = [];
		liveMbps = null;
	}
}

/** True once the current batch has produced its own window measurement. */
export function hasLiveMeasurement() {
	return liveMbps != null;
}

function measuredMbps() {
	if (liveMbps != null) return liveMbps;
	if (storedMbps != null && activeUploads === 0 && now() - lastActivityAt > IDLE_EXPIRY_MS) storedMbps = null;
	return storedMbps;
}

// Fetched once per page load; falls back to the default rather than blocking
// an upload if the settings call fails.
let targetSecondsPromise = null;
function targetUploadSeconds() {
	if (!targetSecondsPromise) {
		targetSecondsPromise = appSettingsApi
			.get()
			.then((settings) => settings.targetUploadSeconds || DEFAULT_TARGET_SECONDS)
			.catch(() => DEFAULT_TARGET_SECONDS);
	}
	return targetSecondsPromise;
}

/** Forget the cached target - Admin -> Configure calls this after saving a new one. */
export function invalidateTargetUploadSeconds() {
	targetSecondsPromise = null;
}

function hintedMbps() {
	const connection = navigator.connection;
	if (!connection) return null;
	if (connection.saveData) return 0.5;
	switch (connection.effectiveType) {
		case 'slow-2g':
		case '2g':
			return 0.2;
		case '3g':
			return 1;
		default:
			return null; // '4g' says nothing useful about *upload* speed
	}
}

/**
 * The size budget (bytes) a file must fit to meet the target on the current
 * connection, or null when nothing is known about the connection yet.
 */
export async function byteBudget() {
	const mbps = measuredMbps() ?? hintedMbps();
	if (mbps == null || mbps <= 0) return null;
	return ((await targetUploadSeconds()) * mbps * 1e6) / 8;
}

/**
 * Returns either a smaller JPEG File (same name, same lastModified - the
 * server's date fallback reads it) or the original `file` untouched. Never
 * throws: any failure just means the original is uploaded.
 */
export async function maybeShrinkForUpload(file) {
	try {
		if (file.type !== 'image/jpeg') {
			if (file.type.startsWith('image/')) log(file, null, `sent as is (${file.type}: only JPEG is shrunk)`);
			return file;
		}
		if (file.size < MIN_SIZE_TO_SHRINK) return file;
		const budget = await byteBudget();
		if (budget == null || file.size <= budget) {
			log(file, budget, 'sent as is');
			return file;
		}

		const image = await loadImage(file);
		const width = image.naturalWidth;
		const height = image.naturalHeight;
		const longSide = Math.max(width, height);
		const maxSide = Math.max(MIN_LONG_SIDE, Math.round(longSide * Math.sqrt(budget / file.size)));
		if (longSide <= maxSide) {
			log(file, budget, `sent as is (already ${longSide}px, floor ${MIN_LONG_SIDE}px)`);
			return file;
		}

		const scale = maxSide / Math.max(width, height);
		const canvas = document.createElement('canvas');
		canvas.width = Math.round(width * scale);
		canvas.height = Math.round(height * scale);
		const context = canvas.getContext('2d');
		context.imageSmoothingQuality = 'high';
		context.drawImage(image, 0, 0, canvas.width, canvas.height);
		const encoded = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY));
		if (!encoded) return file;

		const originalBytes = new Uint8Array(await file.arrayBuffer());
		const exif = extractExifSegment(originalBytes);
		const encodedBytes = new Uint8Array(await encoded.arrayBuffer());
		const finalBytes = exif ? insertExifSegment(encodedBytes, resetOrientation(exif)) : encodedBytes;
		if (finalBytes.byteLength >= file.size) return file;

		log(file, budget, `shrunk to ${canvas.width}x${canvas.height}, ${mb(finalBytes.byteLength)}`);
		return new File([finalBytes], file.name, { type: 'image/jpeg', lastModified: file.lastModified });
	} catch (err) {
		console.warn('Pre-upload shrink skipped:', err);
		return file;
	}
}

const mb = (bytes) => `${(bytes / 1e6).toFixed(2)} MB`;

// One console line per decision (explicit ask after testing: "the 3rd file
// was not shrunk" could not be diagnosed after the fact) - visible in the
// browser's DevTools console.
function log(file, budget, outcome) {
	const mbps = measuredMbps() ?? hintedMbps();
	console.info(
		`[adaptive upload] ${file.name} (${mb(file.size)}): link ${mbps == null ? 'unknown' : `${mbps.toFixed(2)} Mbps`}, ` +
			`budget ${budget == null ? '-' : mb(budget)} -> ${outcome}`,
	);
}

function loadImage(file) {
	return new Promise((resolve, reject) => {
		const url = URL.createObjectURL(file);
		const image = new Image();
		image.onload = () => {
			URL.revokeObjectURL(url);
			resolve(image);
		};
		image.onerror = () => {
			URL.revokeObjectURL(url);
			reject(new Error('Could not decode image'));
		};
		image.src = url;
	});
}

// --- Minimal JPEG segment handling (no library needed for a verbatim copy) ---

/** The whole APP1 "Exif\0\0" segment (marker included), or null. */
function extractExifSegment(bytes) {
	if (bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
	let offset = 2;
	while (offset + 4 <= bytes.length && bytes[offset] === 0xff) {
		const marker = bytes[offset + 1];
		if (marker === 0xda || marker === 0xd9) break; // start of scan / end of image
		const length = (bytes[offset + 2] << 8) | bytes[offset + 3];
		if (
			marker === 0xe1 &&
			bytes[offset + 4] === 0x45 && // E
			bytes[offset + 5] === 0x78 && // x
			bytes[offset + 6] === 0x69 && // i
			bytes[offset + 7] === 0x66 // f
		) {
			return bytes.slice(offset, offset + 2 + length);
		}
		offset += 2 + length;
	}
	return null;
}

/** Copy of the APP1 segment with IFD0's Orientation (0x0112) set to 1, if present. */
function resetOrientation(segment) {
	const copy = segment.slice();
	const tiff = 10; // FF E1, 2 length bytes, "Exif\0\0"
	const little = copy[tiff] === 0x49; // "II" vs "MM"
	const view = new DataView(copy.buffer, copy.byteOffset, copy.byteLength);
	const ifd0 = tiff + view.getUint32(tiff + 4, little);
	const count = view.getUint16(ifd0, little);
	for (let i = 0; i < count; i++) {
		const entry = ifd0 + 2 + i * 12;
		if (view.getUint16(entry, little) === 0x0112) {
			view.setUint16(entry + 8, 1, little); // SHORT value, stored inline
			break;
		}
	}
	return copy;
}

/**
 * Canvas output starts SOI + a JFIF APP0; Exif is expected as the first
 * segment after SOI, so the APP0 is dropped and the Exif APP1 put there.
 */
function insertExifSegment(jpeg, exifSegment) {
	let rest = 2;
	if (jpeg[2] === 0xff && jpeg[3] === 0xe0) {
		rest = 4 + ((jpeg[4] << 8) | jpeg[5]);
	}
	const out = new Uint8Array(2 + exifSegment.length + (jpeg.length - rest));
	out.set(jpeg.subarray(0, 2), 0);
	out.set(exifSegment, 2);
	out.set(jpeg.subarray(rest), 2 + exifSegment.length);
	return out;
}
