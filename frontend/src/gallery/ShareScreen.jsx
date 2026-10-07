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
import { shareApi } from '../api/share';
import { Filmstrip } from './Filmstrip';
import { ImageViewer } from './ImageViewer';
import { PublicImage } from './PublicImage';

/**
 * The desktop external/public viewer (02/09/2026, explicit ask) - what a
 * `/share/image/:id?token=...` or `/share/sequence/:id?token=...` link (see
 * ImageViewer's own copyImageLink/copySequenceLink handlers in
 * GalleryScreen) actually renders for the person who receives it. Reached
 * before `<AuthProvider>` even mounts (App.jsx's own top-level route check)
 * - a share link's whole point is working for someone with no account, same
 * as LoginForm/MobileLoginPage never call `useAuth()` for reads either.
 *
 * "Similar to the gallery without the tabs, nor the left side bar of
 * controls and directories. User can only browse and play the images...
 * visually consistent with the other screens, use the same banner picture"
 * (explicit ask, verbatim) - reuses `.app-shell`/`.app-screen` (own height
 * chain, unrelated to the authenticated nav bar that normally lives inside
 * them) and `.screen-nav` (just for its black bar + centered banner image;
 * no TabBar/LanguageSwitcher/Sign-out rendered into it - none apply to an
 * anonymous visitor) plus `.gallery-main`/`.gallery-body` (no
 * `.gallery-sidebar` at all - that's the "directories" half of the ask).
 * `ImageViewer`/`Filmstrip` are the exact same components Gallery/Search
 * use, just with `ImageComponent={PublicImage}` (no Authorization header -
 * the token already travels in each image URL, see PublicImageResponse) and
 * none of the write-oriented props (`onDelete`/`onRotate`/`onCopyImageLink`/
 * `onCopySequenceLink`/`onOpenSequencePopup`/`onOpenImagePopup`) - all
 * optional on ImageViewer, so simply omitting them is what makes this
 * viewer read-only ("can only browse and play"), not a second, forked copy
 * of ImageViewer with those features cut out.
 *
 * `type` is `'image'` or `'sequence'` (from the URL path App.jsx parsed) -
 * an image-link has nothing to browse (one photo, the whole point of that
 * link), so no Filmstrip is rendered for it; a sequence-link shows the full
 * filmstrip and Previous/Next, exactly like Gallery itself.
 */
export function ShareScreen({ type, id, token }) {
	const { t } = useTranslation();
	const [images, setImages] = useState(null);
	const [currentId, setCurrentId] = useState(null);
	const [sequenceDescription, setSequenceDescription] = useState(null);
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
				setSequenceDescription(data.description ?? null);
				setImages(data.images);
				setCurrentId(data.images.length > 0 ? data.images[0].id : null);
			})
			.catch((err) => setError(err.message));
	}, [type, id, token]);

	const currentIndex = images ? images.findIndex((img) => img.id === currentId) : -1;
	const currentImage = currentIndex >= 0 ? images[currentIndex] : null;

	return (
		<div className="app-shell">
			<nav className="screen-nav">
				<img className="nav-logo" src="/legacy/photodiary-banner-short.png" alt={t('common.appName')} />
			</nav>
			<div className="app-screen">
				<div className="gallery-screen">
					{error && <p className="form-error">{t('share.invalidLink')}</p>}
					{!error && images === null && <p>{t('share.loading')}</p>}
					{/* Every picture of a shared search result deleted since it was shared. */}
					{!error && images !== null && images.length === 0 && type === 'search' && <p>{t('share.searchEmpty')}</p>}
					{!error && images !== null && (images.length > 0 || type !== 'search') && (
						<div className="gallery-layout">
							<div className="gallery-main">
								<div className="gallery-body">
									<ImageViewer
										image={currentImage}
										hasPrevious={currentIndex > 0}
										hasNext={currentIndex >= 0 && currentIndex < images.length - 1}
										onPrevious={() => setCurrentId(images[currentIndex - 1].id)}
										onNext={() => setCurrentId(images[currentIndex + 1].id)}
										sequenceDescription={
											type === 'sequence' ? sequenceDescription : type === 'search' ? (currentImage?.sequenceDescription ?? null) : undefined
										}
										nextImage={currentIndex >= 0 && currentIndex < images.length - 1 ? images[currentIndex + 1] : null}
										ImageComponent={PublicImage}
									/>
									{(type === 'sequence' || type === 'search') && <Filmstrip images={images} currentId={currentId} onSelect={setCurrentId} ImageComponent={PublicImage} />}
								</div>
							</div>
						</div>
					)}
				</div>
			</div>
		</div>
	);
}
