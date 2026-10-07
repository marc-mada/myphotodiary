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

import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';
import { useTranslation } from 'react-i18next';
import { meApi } from '../api/navigation';
import { galleryApi } from '../api/gallery';

// Leaflet's default marker icon is three PNGs referenced by relative URL
// inside its own CSS, which breaks under a bundler (Vite included - the
// well-known "broken marker icon" issue with every Webpack/Vite/Leaflet
// combination, not specific to this project). A real, verified bug in the
// first attempt at this fix, not just theory: `L.Icon.Default.mergeOptions
// ({iconUrl: <a real, already-fully-resolved Vite URL>, ...})` alone still
// rendered a broken image - `IconDefault._getIconUrl` (leaflet-src.js) does
// NOT just return `options.iconUrl` as given, it unconditionally *prepends*
// an auto-detected `imagePath` in front of it (`_detectIconPath()`, meant
// for the classic non-bundled setup where `iconUrl` is a bare relative
// filename like `"marker-icon.png"`, not a full URL) - so the actual `src`
// ended up as that detected path concatenated with an already-complete URL,
// garbage either way. A plain `L.icon({...})` instance sidesteps
// `IconDefault` entirely - `Icon.prototype._getIconUrl` (the un-overridden
// version) returns `options.iconUrl` verbatim, no prepending - and is
// passed explicitly per marker instead of mutating Leaflet's own shared
// prototype at module scope.
const pinIcon = L.icon({
	iconUrl: markerIcon,
	iconRetinaUrl: markerIcon2x,
	shadowUrl: markerShadow,
	// Same geometry as IconDefault's own options (leaflet-src.js) - not
	// reproduced automatically by plain L.icon(), which has no size/anchor
	// defaults of its own.
	iconSize: [25, 41],
	iconAnchor: [12, 41],
	popupAnchor: [1, -34],
	tooltipAnchor: [16, -28],
	shadowSize: [41, 41],
});

/**
 * The sequence geolocation popup (idx.js's mapPopup, backed by gmap.jsp/
 * gmap.js) - an interactive map with a draggable pin, matching legacy's own
 * picker instead of typing raw coordinates by hand.
 *
 * Leaflet + OpenStreetMap tiles (16/09/2026, explicit ask - "y a-t-il des
 * alternatives à Google Maps ?"), replacing the Google Maps JS API this
 * screen used from 27/08/2026 to 16/09/2026. Not a port of gmap.js's own
 * provider, a deliberate swap: Google Maps needs a Cloud Console API key
 * *and* a billing account attached to the project (true since ~2018, not
 * when legacy was written in 2014 - `frontend/.env.example`'s own now-
 * removed comment), real friction for a self-hosted single-user/family app
 * where nobody particularly wants to set up Google Cloud billing just to
 * place one marker. Leaflet + the public OpenStreetMap tile server need
 * neither - no API key, no account, nothing to configure at all - and the
 * only real behavior this screen needs (a draggable single marker, dragend
 * updates the position) maps directly onto Leaflet's own `L.marker(...,
 * {draggable:true})`/`dragend` event, the same shape as the Google Maps
 * version this replaces. Trade-off accepted: OSM's raster tiles render a
 * little less smoothly than Google's vector tiles, invisible in practice
 * for a single-marker picker like this one.
 *
 * `VITE_GOOGLE_MAPS_API_KEY`/`frontend/.env.example`/the "map unavailable,
 * no key configured" message are all gone with this swap - there is no
 * "not configured" state left for this screen to be in.
 *
 * Per-image pins + average-based initialization (16/09/2026, explicit ask) -
 * this sequence's own images (`galleryApi.listImages`, fetched fresh here
 * rather than accepting them as a prop - `GalleryScreen`'s own `images`
 * state genuinely is this sequence's full image list, but `SearchScreen`'s
 * own `images` is its *search results*, which can span multiple sequences
 * and, even filtered down to this one, aren't guaranteed to be all of its
 * images; fetching directly is the one approach that's correct for both
 * callers rather than one being subtly wrong) are filtered to those
 * carrying real EXIF GPS (`Image.latitude`/`longitude`, backend 16/09/2026
 * addition), each rendered as a small, non-draggable, read-only dot -
 * explicit ask: "do not display any pin for [an image with no GPS] and do
 * not use it to compute the sequence latitude/longitude", and separately,
 * "let the option to the user ... to modify the sequence GPS position pin,
 * but not the images position" - only the one big teardrop marker (the
 * sequence's own position) is ever draggable; the dots have no
 * `draggable`/`dragend` at all, not a disabled one. When the sequence
 * itself has no saved position yet (`directory.latitude/longitude` both
 * null), the draggable marker starts at the *average* of those same
 * GPS-tagged images instead of jumping straight to the account's own
 * default map center - that fallback still applies, unchanged, only when
 * there are no GPS-tagged images to average either. This average is never
 * written back to the server on its own - same discipline the rest of this
 * component already has (nothing is saved until a real `dragend`), it only
 * decides where the draggable marker (and the coordinate text below the
 * map) starts out.
 */
export function SequenceGeolocationForm({ directory, onUpdate }) {
	const { t } = useTranslation();
	const mapDivRef = useRef(null);
	const mapRef = useRef(null);
	const [position, setPosition] = useState({ lat: directory.latitude, lng: directory.longitude });
	const [mapError, setMapError] = useState(null);

	useEffect(() => {
		setPosition({ lat: directory.latitude, lng: directory.longitude });
		setMapError(null);

		if (!mapDivRef.current) return;

		let cancelled = false;

		async function init() {
			try {
				// This sequence's own images, fetched fresh (see this component's
				// own doc comment on why a prop from either caller wouldn't be
				// reliably correct) - never includes an image with no GPS tag
				// (explicit ask), and never mutated after this point, so the pins
				// drawn below and the average used for the fallback center are
				// guaranteed to agree on the exact same set.
				const allImages = await galleryApi.listImages(directory.id);
				if (cancelled || !mapDivRef.current) return;
				const imagesWithGps = allImages.filter((img) => img.latitude != null && img.longitude != null);

				// A sequence with no location yet is centered on the average of
				// its own GPS-tagged photos when there are any, or - same as
				// before this feature - the caller's own default map center
				// (legacy: UserConfiguration.defaultLat/Lng) otherwise, never
				// "null island" (0,0).
				let center;
				if (directory.latitude != null && directory.longitude != null) {
					center = { lat: directory.latitude, lng: directory.longitude };
				} else if (imagesWithGps.length > 0) {
					center = {
						lat: imagesWithGps.reduce((sum, img) => sum + img.latitude, 0) / imagesWithGps.length,
						lng: imagesWithGps.reduce((sum, img) => sum + img.longitude, 0) / imagesWithGps.length,
					};
				} else {
					center = await meApi.get().then((me) => ({ lat: me.defaultLatitude, lng: me.defaultLongitude }));
				}
				if (cancelled || !mapDivRef.current) return;
				// Reflected in the coordinate text below the map too, not just
				// the marker's own visual position - both were only ever synced
				// from `directory.latitude/longitude` before, so a sequence with
				// no saved position of its own showed a marker sitting somewhere
				// real while the text underneath still said "drag the pin".
				setPosition(center);

				const map = L.map(mapDivRef.current).setView([center.lat, center.lng], 6);
				mapRef.current = map;
				L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
					maxZoom: 19,
					attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
				}).addTo(map);

				// Small, read-only, non-interactive dots - deliberately not
				// L.marker/pinIcon (the sequence's own big draggable teardrop),
				// so the two are never visually confused about which one can
				// actually be moved.
				for (const img of imagesWithGps) {
					L.circleMarker([img.latitude, img.longitude], {
						radius: 6,
						color: '#1d4ed8',
						weight: 2,
						fillColor: '#7ec8ff',
						fillOpacity: 0.9,
						interactive: false,
					}).addTo(map);
				}

				const marker = L.marker([center.lat, center.lng], { icon: pinIcon, draggable: true }).addTo(map);
				marker.on('dragend', () => {
					const { lat, lng } = marker.getLatLng();
					setPosition({ lat, lng });
					onUpdate({ latitude: lat, longitude: lng });
				});
			} catch {
				if (!cancelled) {
					setMapError(t('geolocation.loadError'));
				}
			}
		}
		init();
		return () => {
			cancelled = true;
			// Leaflet throws "Map container is already initialized" if a new
			// map is created on the same DOM node without this - matters here
			// specifically because this effect re-runs on every `directory.id`
			// change (a different sequence opened from the same popup instance,
			// not a fresh mount each time).
			mapRef.current?.remove();
			mapRef.current = null;
		};
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [directory.id]);

	return (
		<>
			<h3>{directory.path}</h3>
			{mapError ? <p className="form-error">{mapError}</p> : <div ref={mapDivRef} className="sequence-map" />}
			<p className="hint">
				{position.lat != null && position.lng != null
					? `${position.lat.toFixed(6)}, ${position.lng.toFixed(6)}`
					: t('geolocation.dragPin')}
			</p>
		</>
	);
}
