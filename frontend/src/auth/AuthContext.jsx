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

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { ApiError } from '../api/client';

/**
 * Session-cookie auth (18/09/2026, replaces HTTP Basic - explicit ask:
 * "Time to fix the basic authentication weakness and use session cookies" -
 * see backend SecurityConfig's own javadoc for the full design rationale).
 *
 * The real weakness this fixes: the previous version of this file kept the
 * raw username/password in `sessionStorage` and this context computed a
 * Basic header from them on every single API call - any future XSS would
 * leak the literal password, not just a session. There is no password
 * anywhere in this file any more, or in JS-reachable storage at all, once
 * login succeeds - `login()` posts credentials once to `/api/login`
 * (backend session-cookie login, `HttpOnly` - unreadable by JS even if an
 * XSS did occur) and never touches them again.
 *
 * This context only ever carries `isAuthenticated` - whether a session
 * exists at all, which is all any screen needs to decide whether to render
 * the login form. It deliberately does NOT carry `primaryRoleName`/role
 * data - that's `App.jsx`'s own separate concern (`meApi.get()`),
 * unchanged by this rewrite.
 *
 * Login no longer persists anything client-side at all (no `sessionStorage`
 * key, unlike before) - the *session cookie itself* is what persists login
 * across a page reload now (24h, `server.servlet.session.cookie.max-age` -
 * explicit ask, "long-lived for 24h"), set entirely by the backend's
 * `Set-Cookie` response header. On mount, this context has no way to know
 * whether a cookie already exists without asking the server - `GET
 * /api/me` (already the established "who am I" endpoint, not a new one
 * added just for this) doubles as that initial check: 200 means a valid
 * session cookie is already attached, 401 means there isn't one (or it
 * expired). `initializing` covers the brief window while that first check
 * is in flight, so the app doesn't flash the login form for an already-
 * signed-in user before the check resolves.
 */
const AuthContext = createContext(null);

export function AuthProvider({ children }) {
	const [isAuthenticated, setIsAuthenticated] = useState(false);
	const [initializing, setInitializing] = useState(true);

	useEffect(() => {
		let cancelled = false;
		// A bare fetch, not meApi.get() - this runs before this component
		// can know whether a session exists at all, and meApi.get() throwing
		// on the expected "not logged in yet" 401 would just be noise here;
		// this only ever cares about the status code.
		fetch('/api/me', { credentials: 'same-origin' })
			.then((response) => {
				if (!cancelled) setIsAuthenticated(response.ok);
			})
			.catch(() => {
				if (!cancelled) setIsAuthenticated(false);
			})
			.finally(() => {
				if (!cancelled) setInitializing(false);
			});
		return () => {
			cancelled = true;
		};
	}, []);

	const login = useCallback(async (username, password) => {
		const response = await fetch('/api/login', {
			method: 'POST',
			credentials: 'same-origin',
			headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
			body: new URLSearchParams({ username, password }),
		});
		if (!response.ok) {
			throw new ApiError(response.status, 'Invalid credentials');
		}
		setIsAuthenticated(true);
	}, []);

	const logout = useCallback(async () => {
		// Best-effort - even if this request itself fails (network hiccup,
		// an already-expired session), the UI still treats the user as
		// signed out locally; there's no meaningful recovery from a failed
		// logout call other than "you're signed out on this device anyway".
		try {
			const csrfMatch = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
			await fetch('/api/logout', {
				method: 'POST',
				credentials: 'same-origin',
				headers: csrfMatch ? { 'X-XSRF-TOKEN': decodeURIComponent(csrfMatch[1]) } : {},
			});
		} finally {
			setIsAuthenticated(false);
		}
	}, []);

	const value = useMemo(() => ({ isAuthenticated, initializing, login, logout }), [isAuthenticated, initializing, login, logout]);

	return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
	const ctx = useContext(AuthContext);
	if (!ctx) throw new Error('useAuth() must be used within an AuthProvider');
	return ctx;
}
