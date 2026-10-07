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

import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev-time proxy to the Spring Boot backend (backend runs on
// :8080, isolated dev DB - see backend/README references in root README.md).
// In production this app will be served separately and hit a real API
// origin; not decided yet, deliberately deferred along with the real auth
// strategy (see AuthContext.jsx).

// Dev-server HTTPS (10/09/2026, explicit ask) - "Download original
// pictures"/"Download current original" (SearchScreen.jsx) use
// showDirectoryPicker, which - like every other "powerful feature" API -
// only exists at all in a secure context (HTTPS, or specifically
// `localhost`). Plain `http://192.168.x.x:5183` (this project's own
// deliberate LAN-access setup, dev-server-lan-access memory - so a phone/
// tablet on the same network can reach the dev server) is *not* a secure
// context, so that API was silently undefined there even in a fully
// showDirectoryPicker-capable browser - confirmed live, not assumed
// (window.isSecureContext/typeof window.showDirectoryPicker checked
// directly against both origins in a real Chrome). Real production is
// already HTTPS and unaffected either
// way - this only ever mattered for the LAN dev case.
//
// Certificate generated with `mkcert` (a *locally*-trusted CA, not a
// public one - there's nothing to publicly validate for a private LAN
// address) into frontend/.certs/ (gitignored - a private key, same
// never-committed treatment as frontend/.env's own real secrets). Missing
// entirely on a fresh checkout or a machine that hasn't run mkcert yet -
// existsSync guards below fall back to plain HTTP rather than crashing
// `npm run dev`, so this stays optional infrastructure, not a hard
// requirement to develop on this project at all. One-time setup:
//   mkcert -install    # trusts mkcert's root CA on this machine
//   mkcert -cert-file .certs/dev-cert.pem -key-file .certs/dev-key.pem \
//          localhost 127.0.0.1 ::1 <this machine's LAN address>
// and, on any other device that must reach the dev server over the LAN,
// trust rootCA.pem from `mkcert -CAROOT` (never rootCA-key.pem).
const certPath = fileURLToPath(new URL('.certs/dev-cert.pem', import.meta.url));
const keyPath = fileURLToPath(new URL('.certs/dev-key.pem', import.meta.url));
const httpsConfig = existsSync(certPath) && existsSync(keyPath) ? { cert: readFileSync(certPath), key: readFileSync(keyPath) } : undefined;

/**
 * Build-only (10/09/2026, explicit ask - "on slow 3/4G networks it is
 * lengthy to load the frontend files"). Rewrites the real
 * `<script type="module" src="...">`/`<link rel="stylesheet" href="...">`
 * tags Vite's own default HTML handling just injected (hashed filenames,
 * already resolved - this plugin doesn't need to know or guess them) into
 * inert markers the bootstrap script at the end of index.html's own
 * `<body>` looks for instead: `data-progressive-src`/`data-progressive-href`
 * on a non-executing `<script type="application/x-mpd-deferred">`/inert
 * `<link rel="mpd-deferred-stylesheet">`. Neither tag shape is one the
 * browser recognizes as something to fetch on its own (an unrecognized
 * `type` on a `<script src>`, an unrecognized `rel` on a `<link>` - both
 * per-spec no-fetch, not just "no-execute"), so the browser never starts
 * its own, untracked download of either file - only the bootstrap script's
 * own `fetch()` does, which is what lets it report real progress.
 *
 * `enforce: 'post'` + the plain-function `transformIndexHtml` form
 * (stable across Vite's plugin API - not the newer `{order, handler}`
 * object form) guarantees this runs *after* Vite's own core HTML plugin
 * has already injected the real tags, not before (there'd be nothing to
 * rewrite yet).
 *
 * `apply: 'build'` - dev server HTML is never touched: `npm run dev`
 * serves unbundled native ESM (dozens of individual module requests, no
 * single hashed entry tag to rewrite in the first place), already fast on
 * localhost, and rewriting anything there would only risk breaking Vite's
 * own dev-server HMR injection for no benefit.
 */
function progressiveLoaderPlugin() {
	return {
		name: 'mpd-progressive-loader',
		apply: 'build',
		enforce: 'post',
		transformIndexHtml(html) {
			// Non-greedy blob capture for `attrs`, not an attribute-by-
			// attribute pattern - found necessary, not assumed, by checking
			// Vite's *actual* build output first: `crossorigin` is emitted
			// as a bare boolean attribute (no `="..."`), which a
			// `name="value"`-shaped pattern wouldn't match at all, silently
			// leaving both tags un-rewritten (and the bootstrap script
			// finding nothing to progress-track) rather than throwing.
			return html
				.replace(
					/<script type="module"([^>]*?)\ssrc="([^"]+)"><\/script>/,
					(_match, attrs, src) => `<script type="application/x-mpd-deferred"${attrs} data-progressive-src="${src}"></script>`,
				)
				.replace(
					/<link rel="stylesheet"([^>]*?)\shref="([^"]+)"\s*\/?>/,
					(_match, attrs, href) => `<link rel="mpd-deferred-stylesheet"${attrs} data-progressive-href="${href}" />`,
				);
		},
	};
}

export default defineConfig({
	plugins: [react(), progressiveLoaderPlugin()],
	server: {
		https: httpsConfig,
		proxy: {
			'/api': 'http://localhost:8080',
		},
	},
});
