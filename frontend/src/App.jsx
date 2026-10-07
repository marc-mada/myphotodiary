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
import { meApi } from './api/navigation';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { LanguageSwitcher } from './components/LanguageSwitcher';
import { LoginForm } from './components/LoginForm';
import { PowerIcon } from './components/PowerIcon';
import { TabBar } from './components/TabBar';
import { UserTable } from './components/UserTable';
import { GalleryScreen } from './gallery/GalleryScreen';
import { SearchScreen } from './gallery/SearchScreen';
import { ShareScreen } from './gallery/ShareScreen';
import { isMobileDevice } from './mobile/deviceDetection';
import { MobileApp } from './mobile/MobileApp';
import { MobileLoginPage } from './mobile/MobileLoginPage';
import { MobileShareScreen } from './mobile/MobileShareScreen';
import { useLandscapeFullscreen } from './mobile/useLandscapeFullscreen';

// Decided once per page load (deviceDetection.js) - not read reactively
// inside AppContent, so a device's presentation doesn't flip mid-session.
const IS_MOBILE = isMobileDevice();

// Share-link routing (02/09/2026, explicit ask) - this app otherwise has no
// client-side routing at all (a known, previously-documented gap: everything
// else is a plain useState screen switch, see AppContent's own doc comment
// on galleryTarget). A share link is the first thing that actually needs
// one, and only for this one narrow case - not a reason to pull in a router
// for the rest of the app. Parsed once, outside AuthProvider entirely
// (below), since a share link has to work for someone with no account at
// all - same reasoning as IS_MOBILE, decided per page load, not reactively:
// this app never navigates *to* a share link from within itself, only ever
// arrives at one via a freshly loaded URL.
const SHARE_PATH_PATTERN = /^\/share\/(image|sequence|search)\/(\d+)\/?$/;

function parseShareRoute() {
	const match = SHARE_PATH_PATTERN.exec(window.location.pathname);
	if (!match) return null;
	const token = new URLSearchParams(window.location.search).get('token');
	if (!token) return null;
	return { type: match[1], id: Number(match[2]), token };
}

const SHARE_ROUTE = parseShareRoute();

/**
 * Both screens share one login (AuthContext) - there's no separate
 * per-screen auth in the legacy app either. This top nav is new, not a port
 * of anything legacy: the old app has no equivalent because admin.jsp and
 * index.jsp were always reached by direct URL, not an in-app switcher.
 *
 * The language switcher/Sign out control lives here, once, rather than
 * duplicated in each screen's own header (GalleryScreen/SearchScreen/
 * UserTable each used to render their own copy) - a direct ask for visual
 * coherence: one control, always in the exact same top-right spot
 * regardless of which screen is showing, not a per-screen header that
 * could drift depending on that screen's own title length/layout.
 *
 * "Signed in as {username}" replaced by LanguageSwitcher (28/08/2026,
 * explicit ask) - Sign out itself is untouched, only the text next to it.
 *
 * "Tags" is no longer a top-level entry - moved into the Admin screen
 * itself (UserTable), alongside Configure/Index management.
 *
 * The Admin tab is shown for ADMIN and WRITER (`canSeeAdminTab`, resolved
 * from /api/me's own primaryRoleName - added to MeResponse for exactly
 * this) - originally ADMIN-only (explicit ask, 28/08/2026), widened
 * 04/09/2026 (explicit ask) so a WRITER can reach their own preferences,
 * which have always been self-service on the backend (`/api/me`,
 * MeController's own javadoc) but previously had no UI route to them at
 * all outside the ADMIN-only Admin screen. WRITER gets a cut-down version
 * of the screen, not the full one - `isAdmin` is passed down to `UserTable`
 * separately, which decides *what* renders; this only decides whether the
 * tab shows at all. READER/LOWER still see nothing here. The backend's own
 * UserController (Users tab's data) stays ADMIN-only end to end regardless
 * (real enforcement, unaffected by any of this) - only `/api/me` itself is,
 * and always was, self-service for any authenticated role; this is the UI
 * finally offering a route to it for someone other than an admin.
 *
 * Mobile branches off entirely before any of this: `MobileApp` has its own
 * hamburger-menu navigation (mirroring mindex.jsp) and no Admin/Tags
 * screens at all (decision #9 - legacy's admin.jsp never had a mobile
 * counterpart), so it isn't just another entry in the screen switch below.
 *
 * `galleryTarget`/`handleGoToDirectory` (01/09/2026, explicit ask) - the
 * Search screen's new "go to directory" button (ImageViewer's
 * onGoToDirectory) needs to both switch to the Gallery tab *and* tell that
 * fresh GalleryScreen instance where to land, which a plain `setScreen`
 * alone can't express - this project has no URL routing at all (Design.md
 * §12/§15 list that gap), so passing state down through here is
 * the only channel between the two screens. `{ directoryPath, imageId }` or
 * null; GalleryScreen clears it back via `onInitialTargetConsumed` right
 * after reading it (not waited on), so a later, unrelated visit to Gallery
 * (e.g. via the tab itself) doesn't keep re-applying a stale target.
 * Screens are no longer rendered through a generic id->component map for
 * this reason - Gallery and Search each need their own distinct props now,
 * unlike Admin.
 */
function AppContent() {
	const { t } = useTranslation();
	const { isAuthenticated, initializing, logout } = useAuth();
	const [screen, setScreen] = useState('gallery');
	const [primaryRoleName, setPrimaryRoleName] = useState(null);
	const [galleryTarget, setGalleryTarget] = useState(null);

	const isAdmin = primaryRoleName === 'ADMIN';
	// WRITER also gets the Admin tab, but a cut-down one - only the
	// Configure sub-tab, self-service only (explicit ask, 04/09/2026): a
	// WRITER can already edit their own preferences today, just not from
	// anywhere reachable in this UI (MeController's own `/api/me` has
	// always been self-service for any authenticated role - see its own
	// javadoc - the ADMIN-only Admin screen was simply the only place any
	// UI for it existed). UserTable itself decides what "cut-down" renders
	// (see its own comment) - this only decides *whether* the tab shows.
	const canSeeAdminTab = isAdmin || primaryRoleName === 'WRITER';

	function handleGoToDirectory(target) {
		setGalleryTarget(target);
		setScreen('gallery');
	}

	useEffect(() => {
		if (IS_MOBILE || !isAuthenticated) return;
		meApi
			.get()
			.then((me) => setPrimaryRoleName(me.primaryRoleName))
			.catch(() => setPrimaryRoleName(null));
	}, [isAuthenticated]);

	// Always land on Gallery for a fresh sign-in (explicit ask, 04/09/2026) -
	// `AppContent` itself never unmounts across a sign-out/sign-in cycle in
	// the same tab (only its conditional render output below switches
	// between <LoginForm> and the real shell), so `screen`'s own `useState`
	// initial value only ever applies on the very first page load - without
	// this, signing out while on Search/Admin and back in (same tab, same
	// or even a different account) landed right back on that same tab
	// rather than defaulting to Gallery every time. Fires once per
	// `isAuthenticated` transitioning to true, not on every render while
	// already signed in - doesn't fight normal in-session tab switching.
	useEffect(() => {
		if (isAuthenticated) setScreen('gallery');
	}, [isAuthenticated]);

	// Never leave `screen` pointed at Admin for a user who turns out not to
	// be eligible for it at all (the check above resolves async, after the
	// initial render).
	useEffect(() => {
		if (screen === 'admin' && !canSeeAdminTab) setScreen('gallery');
	}, [screen, canSeeAdminTab]);

	// Session-cookie auth (18/09/2026) - AuthProvider's own initial check
	// (does a cookie from an earlier visit still work?) is async, unlike the
	// old sessionStorage-backed version, which had the answer synchronously
	// on the very first render. Without this guard, an already-signed-in
	// visitor reloading the page would see the login form flash for a
	// moment before flipping to the real app once the check resolves - a
	// brief neutral loading state instead, rather than that flash.
	if (initializing) {
		return <div className="app-loading">{t('common.loading')}</div>;
	}

	if (IS_MOBILE) {
		return isAuthenticated ? <MobileApp /> : <MobileLoginPage />;
	}

	if (!isAuthenticated) {
		return <LoginForm />;
	}

	return (
		<div className="app-shell app-shell-with-footer">
			<nav className="screen-nav">
				<TabBar
					tabs={[
						{ id: 'gallery', label: t('nav.gallery') },
						{ id: 'search', label: t('nav.search') },
						...(canSeeAdminTab ? [{ id: 'admin', label: t('nav.admin') }] : []),
					]}
					activeId={screen}
					onSelect={setScreen}
				/>
				<div className="nav-account">
					<LanguageSwitcher />
					<button className="power-button sign-out-button" onClick={logout}>
						<PowerIcon /> {t('common.signOut')}
					</button>
				</div>
			</nav>
			<div className="app-screen">
				{screen === 'gallery' && <GalleryScreen initialTarget={galleryTarget} onInitialTargetConsumed={() => setGalleryTarget(null)} />}
				{screen === 'search' && <SearchScreen onGoToDirectory={handleGoToDirectory} />}
				{screen === 'admin' && canSeeAdminTab && <UserTable isAdmin={isAdmin} />}
			</div>
			{/* Single 40px bottom band carrying "Powered by" + the banner
			    (26/09/2026, explicit ask - moved here from the top bar); see
			    .screen-footer in app.css. */}
			<footer className="screen-footer">
				<span className="footer-label">{t('common.poweredBy')}</span>
				<img className="footer-logo" src="/legacy/photodiary-banner-short.png" alt={t('common.appName')} />
			</footer>
		</div>
	);
}

export default function App() {
	// Mounted above every branch below (mobile/desktop, share-link route vs.
	// the ordinary app) so it covers the whole mobile experience, not just
	// MobileApp's own screens - a no-op on desktop (isMobileDevice() guards
	// it internally), see the hook's own doc comment for why this can't be
	// truly automatic on rotation alone.
	useLandscapeFullscreen();

	if (SHARE_ROUTE) {
		return IS_MOBILE ? <MobileShareScreen {...SHARE_ROUTE} /> : <ShareScreen {...SHARE_ROUTE} />;
	}
	return (
		<AuthProvider>
			<AppContent />
		</AuthProvider>
	);
}
