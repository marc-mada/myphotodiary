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

/**
 * Replaces mauth.jsp - a dedicated full page, not an overlay like the
 * desktop `.login-form` (idx.css's `#loginPopup`, which is a popup over the
 * gallery). Legacy's mobile login is its own page precisely because there's
 * nothing behind it to pop up over yet (AuthenticationFilter's
 * `authMobileURI` forward happens before any content loads).
 *
 * Session-cookie auth (18/09/2026) - `login()` itself is the real
 * credential check now (`POST /api/login`), open to any authenticated role
 * exactly like `/api/me` always has been (no separate ADMIN-only check the
 * way the desktop `LoginForm` used to guard against before that was fixed -
 * see that file's own history) - mobile has no Admin screen at all
 * (decision #9) so this was never gated by role in the first place.
 */
export function MobileLoginPage() {
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
			await login(username, password);
		} catch {
			setError(t('common.invalidCredentials'));
		} finally {
			setSubmitting(false);
		}
	}

	return (
		<div className="mobile-auth-page">
			<header className="mobile-auth-header">
				<img className="mobile-auth-logo" src="/legacy/photodiary-banner-short.png" alt={t('mobileLogin.title')} />
			</header>
			<div className="mobile-auth-content">
				<form className="mobile-auth-form" onSubmit={handleSubmit} onKeyPress={(e) => e.key === 'Enter' && e.preventDefault()}>
					<label>
						{t('mobileLogin.user')}
						<input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus required autoCapitalize="off" autoCorrect="off" />
					</label>
					<label>
						{t('mobileLogin.password')}
						<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoComplete="off" />
					</label>
					{error && <p className="form-error">{error}</p>}
					<button type="submit" disabled={submitting}>
						{submitting ? t('common.signingIn') : t('mobileLogin.submit')}
					</button>
				</form>
			</div>
			<footer className="mobile-auth-footer">
				<h4>{t('mobileLogin.poweredBy')}</h4>
			</footer>
		</div>
	);
}
