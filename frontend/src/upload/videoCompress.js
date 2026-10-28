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

import { log, uploadSettings } from './adaptiveShrink';

/**
 * Adaptive video compression before upload (28/10/2026, explicit ask - the
 * video counterpart of adaptiveShrink.js's photo shrinking, tried first on
 * real devices with the video-probe prototype). Fully automatic: a video
 * whose upload wouldn't fit the target time ("target average upload time
 * per photo or video", Admin -> Configure) is re-encoded in the browser to
 * H.264 + AAC in MP4 with WebCodecs (Mediabunny, loaded only when a video
 * needs it), then uploaded instead of the original. As with photos, the
 * compressed file *is* the original from then on (server, backup).
 *
 * Quality ladder - the first step whose estimated size fits the byte budget
 * (target time x measured link speed):
 *   1. 1080p (short side), 5 down to 3 Mbit/s
 *   2. 720p, 3 down to 1.5 Mbit/s
 *   floor: 720p at 1.5 Mbit/s even when the budget isn't met - or 1080p at
 *   3 Mbit/s when the app-wide "lowest resolution" setting says 1080p.
 * Never upscaled, never above 30 fps, and never a higher video bit rate
 * than the source's (1.5x it for HEVC/VP9/AV1, which need about half
 * H.264's bit rate for the same quality).
 *
 * The original is uploaded unchanged (and the server's size limit applies
 * to it, explicit ask) when:
 * - it already fits the target, or nothing is known about the link yet
 *   (adaptiveUpload.js may still switch to a compressed copy mid-upload);
 * - the estimated result isn't clearly smaller (above 70% of the original -
 *   typically an iPhone/iPad HEVC clip), or the actual result isn't smaller;
 * - it's HDR (washed-out colours once converted to 8-bit SDR) or longer
 *   than 15 minutes (the result is held in memory until uploaded);
 * - the browser can't do it: no WebCodecs, can't decode this video or its
 *   sound, can't encode H.264 (or any sound codec), or an error midway.
 *
 * Above the maximum video size (28/10/2026, explicit ask): an original
 * the server would refuse is compressed whatever the link speed, aiming
 * at the limit (or the speed budget if smaller), even when the 70% rule or
 * HDR would otherwise keep it - a compressed video beats a refused one.
 * The server applies the limit to whatever arrives, so a result still too
 * big at the floor resolution is refused there.
 *
 * Too long to ever fit (same day, explicit ask): the size at the floor is
 * known from the duration alone (about 12 MB per minute at 720p) and
 * matches the real result closely, so when it would exceed the limit by
 * more than 15% the video is refused at once - nothing compressed, nothing
 * uploaded - with `{ tooLarge: { estimate, limit } }`. Duration, not the
 * original's size: a short 4K clip at 20x the limit still fits once
 * compressed, a long compact one at 3x may not.
 *
 * Sound: AAC, or Opus where the browser can't encode AAC (Firefox) - the
 * server then makes its usual H.264+AAC playback copy in the background.
 *
 * Date and place: the compressed file's own metadata isn't reliable (its
 * movie header holds the time of compression, the location is lost), so
 * the output gets no tags and the original's recording date and place go
 * as separate request fields (`params`) - see backend
 * ImportService.ClientVideoMetadata. Read the same way ffprobe reports them
 * on the original: the movie header date (Android), the Apple creation
 * date, an ISO 6709 location.
 *
 * One compression at a time (desktop uploads two files in parallel): a
 * phone has one hardware encoder, and two compressions would each hold a
 * whole output in memory. The screen is kept awake meanwhile where the
 * browser allows it (a backgrounded tab may pause the work until it's back).
 */

const MAX_FPS = 30;
const AUDIO_BITRATE = 128_000;
const MAX_DURATION_SECONDS = 15 * 60;
const WORTHWHILE_RATIO = 0.7;
// Aim a bit under the maximum video size - the encoder's bit rate control
// is approximate.
const SIZE_LIMIT_MARGIN = 0.9;
// Refused up front when even the floor would give more than this times the
// limit; between the limit and this, it's tried (it may just fit).
const TOO_LARGE_TOLERANCE = 1.15;
const STEPS = [
	{ shortSide: 1080, maxBitrate: 5_000_000, minBitrate: 3_000_000 },
	{ shortSide: 720, maxBitrate: 3_000_000, minBitrate: 1_500_000 },
];
const EFFICIENT_CODECS = new Set(['hevc', 'vp9', 'av1']);

/** Whether this file goes through video compression rather than photo shrinking. */
export function isVideoFile(file) {
	return file.type.startsWith('video/');
}

let queue = Promise.resolve();
function oneAtATime(task) {
	const run = queue.then(task, task);
	queue = run.catch(() => {});
	return run;
}

/**
 * Resolves to `{ body, params }` - the compressed File (same lastModified,
 * name ending in .mp4) and the extra /api/import fields - to
 * `{ tooLarge }` when it can't fit the size limit even compressed, or to
 * null when the original should be uploaded. Never throws.
 */
export async function maybeCompressVideo(file, speedBudget, onProgress = () => {}) {
	if (typeof VideoEncoder === 'undefined' || typeof VideoDecoder === 'undefined') {
		log(file, speedBudget, 'sent as is (no WebCodecs in this browser)');
		return null;
	}
	const { maxVideoSizeBytes } = await uploadSettings();
	const overLimit = maxVideoSizeBytes != null && file.size > maxVideoSizeBytes;
	let budget = speedBudget;
	if (overLimit) {
		const limitBudget = maxVideoSizeBytes * SIZE_LIMIT_MARGIN;
		budget = budget == null ? limitBudget : Math.min(budget, limitBudget);
	} else if (budget == null) {
		log(file, budget, 'sent as is (link speed not known yet)');
		return null;
	} else if (file.size <= budget) {
		log(file, budget, 'sent as is');
		return null;
	}
	return oneAtATime(async () => {
		let wakeLock = null;
		try {
			wakeLock = await navigator.wakeLock?.request('screen');
		} catch {
			// Not allowed here (no user gesture, battery saver...) - not needed to work.
		}
		try {
			return await compress(file, budget, overLimit ? maxVideoSizeBytes : null, onProgress);
		} catch (err) {
			log(file, budget, `sent as is (compression failed: ${err?.message ?? err})`);
			return null;
		} finally {
			onProgress(null);
			wakeLock?.release().catch(() => {});
		}
	});
}

async function compress(file, budget, sizeLimit, onProgress) {
	const overLimit = sizeLimit != null;
	const mb = await import('mediabunny');
	const input = new mb.Input({ source: new mb.BlobSource(file), formats: mb.ALL_FORMATS });
	const video = await input.getPrimaryVideoTrack();
	if (!video) return skip(file, budget, 'no video track');
	const audio = await input.getPrimaryAudioTrack();
	const duration = await input.computeDuration();
	if (!(duration > 0)) return skip(file, budget, 'unknown duration');
	const audioBitrate = audio ? AUDIO_BITRATE : 0;
	const { videoCompressionFloor } = await uploadSettings();
	if (overLimit) {
		const floorPlan = choosePlan({ file, duration, budget: 0, audioBitrate, codec: video.codec, floor: videoCompressionFloor });
		const floorEstimate = ((floorPlan.bitrate + audioBitrate) * duration) / 8;
		if (floorEstimate > sizeLimit * TOO_LARGE_TOLERANCE) {
			log(file, budget, `refused: too long for the size limit (about ${mbText(floorEstimate)} even at ${floorPlan.shortSide}p)`);
			return { tooLarge: { estimate: floorEstimate, limit: sizeLimit } };
		}
	}
	if (duration > MAX_DURATION_SECONDS) return skip(file, budget, `longer than ${MAX_DURATION_SECONDS / 60} minutes`);
	if (!overLimit && (await video.hasHighDynamicRange())) return skip(file, budget, 'HDR video');
	if (!(await video.canDecode())) return skip(file, budget, `this browser can't decode ${video.codec}`);

	let audioCodec = null;
	if (audio) {
		if (!(await audio.canDecode())) return skip(file, budget, `this browser can't decode ${audio.codec} sound`);
		const channels = Math.min(2, audio.numberOfChannels);
		const audioConfig = { numberOfChannels: channels, sampleRate: audio.sampleRate, bitrate: AUDIO_BITRATE };
		if (await mb.canEncodeAudio('aac', audioConfig)) audioCodec = 'aac';
		else if (await mb.canEncodeAudio('opus', { ...audioConfig, sampleRate: 48_000 })) audioCodec = 'opus';
		else return skip(file, budget, "this browser can't encode sound");
	}
	const plan = choosePlan({ file, duration, budget, audioBitrate, codec: video.codec, floor: videoCompressionFloor });
	const width = video.displayWidth;
	const height = video.displayHeight;
	const size = targetSize(width, height, plan.shortSide);
	const estimated = ((plan.bitrate + audioBitrate) * duration) / 8;
	if (!overLimit && estimated > file.size * WORTHWHILE_RATIO) {
		return skip(file, budget, `compression not worth it (about ${mbText(estimated)} at ${size.width}x${size.height})`);
	}
	if (!(await mb.canEncodeVideo('avc', { width: size.width, height: size.height, bitrate: plan.bitrate }))) {
		return skip(file, budget, `this browser can't encode H.264 at ${size.width}x${size.height}`);
	}

	const source = await readOriginalMetadata(file, input);
	const stats = await video.computePacketStats(100);
	const output = new mb.Output({ format: new mb.Mp4OutputFormat({ fastStart: 'in-memory' }), target: new mb.BufferTarget() });
	const conversion = await mb.Conversion.init({
		input,
		output,
		tracks: 'primary',
		showWarnings: false,
		tags: {}, // see this file's doc comment - date and place go separately
		video: {
			codec: 'avc',
			width: size.width,
			height: size.height,
			fit: 'contain',
			frameRate: stats.averagePacketRate > MAX_FPS + 0.5 ? MAX_FPS : undefined,
			quality: new mb.Quality({ bitrate: plan.bitrate }),
			forceTranscode: true,
		},
		audio: audioCodec
			? { codec: audioCodec, numberOfChannels: Math.min(2, audio.numberOfChannels), quality: new mb.Quality({ bitrate: AUDIO_BITRATE }) }
			: { discard: true },
	});
	if (!conversion.isValid) {
		return skip(file, budget, `conversion not possible (${conversion.discardedTracks.map((d) => `${d.track.type}: ${d.reason}`).join(', ')})`);
	}
	onProgress(0);
	conversion.onProgress = (p) => onProgress(p);
	const started = performance.now();
	await conversion.execute();

	const buffer = output.target.buffer;
	if (buffer.byteLength >= file.size) return skip(file, budget, `result not smaller (${mbText(buffer.byteLength)})`);
	const name = file.name.replace(/\.[^.]*$/, '') + '.mp4';
	log(
		file,
		budget,
		`${overLimit ? 'over the size limit, ' : ''}compressed to ${size.width}x${size.height} at ${(plan.bitrate / 1e6).toFixed(1)} Mbit/s, ${mbText(buffer.byteLength)}, ` +
			`in ${((performance.now() - started) / 1000).toFixed(1)} s`,
	);
	const params = { clientCompressed: 'true' };
	if (source.creationTime) params.sourceCreationTime = source.creationTime;
	if (source.appleCreationDate) params.sourceAppleCreationDate = source.appleCreationDate;
	if (source.location) params.sourceLocation = source.location;
	return { body: new File([buffer], name, { type: 'video/mp4', lastModified: file.lastModified }), params };
}

function skip(file, budget, reason) {
	log(file, budget, `sent as is (${reason})`);
	return null;
}

const mbText = (bytes) => `${(bytes / 1e6).toFixed(1)} MB`;

/** The ladder in this file's doc comment: `{ shortSide, bitrate }`. */
export function choosePlan({ file, duration, budget, audioBitrate, codec, floor }) {
	const sourceVideoBitrate = Math.max(1, (file.size * 8) / duration - audioBitrate);
	const cap = EFFICIENT_CODECS.has(codec) ? sourceVideoBitrate * 1.5 : sourceVideoBitrate;
	const budgetBitrate = (budget * 8) / duration - audioBitrate;
	const steps = STEPS.filter((step) => step.shortSide >= floor);
	for (const step of steps) {
		const bitrate = Math.min(step.maxBitrate, cap, budgetBitrate);
		if (bitrate >= step.minBitrate) return { shortSide: step.shortSide, bitrate: Math.round(bitrate) };
	}
	const last = steps[steps.length - 1];
	return { shortSide: last.shortSide, bitrate: Math.round(Math.min(last.minBitrate, cap)) };
}

// Short side capped at `shortSide`, either orientation, never upscaled,
// even sizes (H.264 needs them).
function targetSize(width, height, shortSide) {
	const scale = Math.min(1, shortSide / Math.min(width, height));
	const even = (v) => Math.max(2, Math.round((v * scale) / 2) * 2);
	return { width: even(width), height: even(height) };
}

// --- The original's recording date and place, as ffprobe reports them ---

// Seconds between 1904-01-01 (QuickTime epoch) and 1970-01-01.
const QUICKTIME_EPOCH_OFFSET = 2_082_844_800;
const MAX_MOOV_BYTES = 64 * 1024 * 1024;

async function readOriginalMetadata(file, input) {
	let raw = {};
	try {
		raw = (await input.getMetadataTags()).raw ?? {};
	} catch {
		// No tag support for this container - the movie header may still help.
	}
	let creationTime = null;
	try {
		creationTime = await readMvhdCreationTime(file);
	} catch {
		creationTime = null;
	}
	let location = null;
	for (const [key, value] of Object.entries(raw)) {
		if (location) break;
		if (key === 'loci') location = decodeLoci(value);
		else if (/xyz|location|iso6709/i.test(key)) location = findIso6709(tagText(value));
	}
	const apple = tagText(raw['com.apple.quicktime.creationdate']);
	return { creationTime, appleCreationDate: apple ? apple.trim() : null, location };
}

function tagText(value) {
	if (typeof value === 'string') return value;
	if (value instanceof Uint8Array) return Array.from(value, (b) => (b >= 32 && b < 127 ? String.fromCharCode(b) : ' ')).join('');
	return null;
}

function findIso6709(text) {
	const match = text && /[+-]\d{1,3}(?:\.\d+)?[+-]\d{1,3}(?:\.\d+)?[^ ]*\//.exec(text);
	return match ? match[0] : null;
}

// 3GPP 'loci' box (binary): version/flags, language, name\0, role, then
// longitude, latitude, altitude as signed 16.16 fixed point. Returned as the
// ISO 6709 text ffprobe would give.
function decodeLoci(bytes) {
	if (!(bytes instanceof Uint8Array) || bytes.length < 20) return null;
	let pos = 6;
	while (pos < bytes.length && bytes[pos] !== 0) pos++;
	pos += 2;
	if (pos + 8 > bytes.length) return null;
	const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	const longitude = view.getInt32(pos) / 65536;
	const latitude = view.getInt32(pos + 4) / 65536;
	const signed = (v) => (v < 0 ? '-' : '+') + Math.abs(v).toFixed(4);
	return `${signed(latitude)}${signed(longitude)}/`;
}

async function readBytes(file, start, length) {
	return new DataView(await file.slice(start, start + length).arrayBuffer());
}

// Creation time of the movie header (moov/mvhd), as an ISO instant - the
// only date Android's camera writes; Mediabunny doesn't expose it. The
// server ignores an unset one (1904/1970).
async function readMvhdCreationTime(file) {
	let pos = 0;
	while (pos + 8 <= file.size) {
		const header = await readBytes(file, pos, 16);
		let size = header.getUint32(0);
		const type = String.fromCharCode(...[4, 5, 6, 7].map((i) => header.getUint8(i)));
		let headerSize = 8;
		if (size === 1) {
			size = Number(header.getBigUint64(8));
			headerSize = 16;
		} else if (size === 0) {
			size = file.size - pos;
		}
		if (size < headerSize) return null;
		if (type === 'moov') {
			const moov = await readBytes(file, pos + headerSize, Math.min(size - headerSize, MAX_MOOV_BYTES));
			let inner = 0;
			while (inner + 8 <= moov.byteLength) {
				const childSize = moov.getUint32(inner);
				const childType = String.fromCharCode(...[4, 5, 6, 7].map((i) => moov.getUint8(inner + i)));
				if (childType === 'mvhd') {
					const version = moov.getUint8(inner + 8);
					const seconds = version === 1 ? Number(moov.getBigUint64(inner + 12)) : moov.getUint32(inner + 12);
					return seconds > 0 ? new Date((seconds - QUICKTIME_EPOCH_OFFSET) * 1000).toISOString() : null;
				}
				if (childSize < 8) return null;
				inner += childSize;
			}
			return null;
		}
		pos += size;
	}
	return null;
}
