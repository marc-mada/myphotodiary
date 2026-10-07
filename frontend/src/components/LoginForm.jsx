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

import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { PowerIcon } from './PowerIcon';

export function LoginForm() {
	const { t } = useTranslation();
	const { login } = useAuth();
	const [username, setUsername] = useState('');
	const [password, setPassword] = useState('');
	const [error, setError] = useState(null);
	const [submitting, setSubmitting] = useState(false);

	async function handleSubmit(e) {
		e.preventDefault();
		setSubmitting(true);
		setError(null);
		try {
			// Session-cookie auth (18/09/2026) - login() itself now IS the
			// real credential check (POST /api/login, backend
			// AuthenticationManager) - no separate precheck call needed the
			// way the old Basic-auth version needed one (there was nothing
			// to "try" credentials against otherwise, since Basic auth had
			// no login endpoint of its own at all).
			await login(username, password);
		} catch {
			setError(t('common.invalidCredentials'));
		} finally {
			setSubmitting(false);
		}
	}

	return (
		<div className="login-screen">
			{/* Banner bar at the top of the *screen*, not the form - same
			    position/markup as the Gallery screen's own `.screen-nav`/
			    `.nav-logo` (App.jsx) and ShareScreen's reuse of it, not a
			    login-specific element (corrected 03/09/2026: an earlier pass
			    had put the banner inside `.login-form` itself instead). */}
			<nav className="screen-nav">
				<img className="nav-logo" src="/legacy/photodiary-banner-short.png" alt={t('common.appName')} />
			</nav>
			<div className="login-screen-body">
				<form className="login-form" onSubmit={handleSubmit}>
					<h2>{t('login.title')}</h2>
					<label>
						{t('login.username')}
						<input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus required />
					</label>
					<label>
						{t('login.password')}
						<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
					</label>
					{error && <p className="form-error">{error}</p>}
					<button type="submit" className="power-button" disabled={submitting}>
						<PowerIcon /> {submitting ? t('common.signingIn') : t('common.signIn')}
					</button>
				</form>
			</div>
		</div>
	);
}
