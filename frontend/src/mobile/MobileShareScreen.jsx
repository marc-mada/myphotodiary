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
import { PublicImage } from '../gallery/PublicImage';
import { shareApi } from '../api/share';
import { MobileImageViewer } from './MobileImageViewer';

/**
 * The mobile counterpart of {@link ShareScreen} (02/09/2026, explicit ask:
 * "Mobile UI: same characteristics, Visually consistent with the other
 * screens of mobile UI"). Reached before `<AuthProvider>` mounts, same as
 * `MobileLoginPage` - App.jsx's own top-level route check branches here
 * directly for a mobile device, never through `MobileApp`.
 *
 * "Same characteristics" as the desktop share screen means: browse and play
 * only, nothing else - no Menu/Go/Edit (there's no side panel to navigate,
 * no comment to edit, this is someone else's link), no upload, no camera.
 * Reuses `.mobile-auth-page`/`.mobile-auth-header` (the sign-in screen's own
 * always-visible banner header, not `.mobile-home-header`'s tap-to-reveal
 * chrome - there's no Menu/Quit button to hide behind a tap here) for the
 * "visually consistent" banner, and the same `MobileImageViewer` swipe
 * gesture handling `MobileApp` uses, with `ImageComponent={PublicImage}` -
 * no Authorization header, the token already travels in each image URL
 * (see PublicImageResponse). No `MobilePostIt` - it's edit-oriented
 * (tapping either half opens an edit popup this anonymous viewer has no
 * access to), and showing the sequence/image description read-only wasn't
 * part of what was asked.
 *
 * `type`/`id`/`token` - see ShareScreen's own doc for where these come from.
 */
export function MobileShareScreen({ type, id, token }) {
	const { t } = useTranslation();
	const [images, setImages] = useState(null);
	const [currentId, setCurrentId] = useState(null);
	const [error, setError] = useState(null);

	useEffect(() => {
		setImages(null);
		setError(null);
		// 'search' (04/10/2026): a frozen search result - its pictures still
		// available, each carrying its own sequenceDescription.
		const request =
			type === 'sequence'
				? shareApi.getPublicSequence(id, token)
				: type === 'search'
					? shareApi.getPublicSearchShare(id, token)
					: shareApi.getPublicImage(id, token).then((image) => ({ images: [image] }));
		request
			.then((data) => {
				setImages(data.images);
				setCurrentId(data.images.length > 0 ? data.images[0].id : null);
			})
			.catch((err) => setError(err.message));
	}, [type, id, token]);

	const currentIndex = images ? images.findIndex((img) => img.id === currentId) : -1;
	const currentImage = currentIndex >= 0 ? images[currentIndex] : null;

	return (
		<div className="mobile-auth-page">
			<header className="mobile-auth-header">
				<img className="mobile-auth-logo" src="/legacy/photodiary-banner-short.png" alt={t('common.appName')} />
			</header>
			{error && <p className="form-error">{t('share.invalidLink')}</p>}
			{!error && images === null && <p>{t('share.loading')}</p>}
			{!error && images !== null && images.length === 0 && type === 'search' && <p>{t('share.searchEmpty')}</p>}
			{!error && images !== null && (images.length > 0 || type !== 'search') && (
				<div className="mobile-share-content">
					<MobileImageViewer
						image={currentImage}
						hasPrevious={currentIndex > 0}
						hasNext={currentIndex >= 0 && currentIndex < images.length - 1}
						onPrevious={() => setCurrentId(images[currentIndex - 1].id)}
						onNext={() => setCurrentId(images[currentIndex + 1].id)}
						nextImage={currentIndex >= 0 && currentIndex < images.length - 1 ? images[currentIndex + 1] : null}
						ImageComponent={PublicImage}
					/>
				</div>
			)}
		</div>
	);
}
