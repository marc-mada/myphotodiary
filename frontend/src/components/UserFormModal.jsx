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
import { useForbiddenAwareError } from '../temporaryMessage';

const ROLES = ['ADMIN', 'WRITER', 'READER', 'LOWER'];

/**
 * One form for both create and edit, mirroring how the backend itself
 * treats them (CreateUserRequest vs UpdateUserRequest - see UserService):
 * on edit, userName is fixed and a blank password means "leave it
 * unchanged" - the same rule from the legacy BCrypt fix (Design.md §16.8.1),
 * carried all the way through to this form's placeholder text.
 */
export function UserFormModal({ user, onSubmit, onCancel }) {
	const { t } = useTranslation();
	const isEdit = !!user;
	const [userName, setUserName] = useState(user?.userName ?? '');
	const [longName, setLongName] = useState(user?.longName ?? '');
	const [password, setPassword] = useState('');
	const [primaryGroupName, setPrimaryGroupName] = useState(user?.primaryGroupName ?? '');
	const [primaryRoleName, setPrimaryRoleName] = useState(user?.primaryRoleName ?? 'LOWER');
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [submitting, setSubmitting] = useState(false);

	async function handleSubmit(e) {
		e.preventDefault();
		setSubmitting(true);
		setError(null);
		try {
			if (isEdit) {
				await onSubmit({ longName, password: password || null, primaryRoleName });
			} else {
				await onSubmit({ userName, longName, password, primaryGroupName, primaryRoleName });
			}
		} catch (err) {
			reportError(err);
			setSubmitting(false);
		}
	}

	return (
		<div className="modal-backdrop" onClick={onCancel}>
			<form className="modal" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
				<h2>{isEdit ? t('userForm.editTitle', { userName: user.userName }) : t('userForm.newTitle')}</h2>

				{!isEdit && (
					<label>
						{t('userForm.username')}
						<input value={userName} onChange={(e) => setUserName(e.target.value)} required autoFocus />
					</label>
				)}
				<label>
					{t('userForm.longName')}
					<input value={longName} onChange={(e) => setLongName(e.target.value)} />
				</label>
				<label>
					{t('userForm.password')} {isEdit && <span className="hint">{t('userForm.passwordHint')}</span>}
					<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
				</label>
				{!isEdit && (
					<label>
						{t('userForm.primaryGroup')}
						<input
							value={primaryGroupName}
							onChange={(e) => setPrimaryGroupName(e.target.value)}
							placeholder={t('userForm.primaryGroupPlaceholder')}
						/>
					</label>
				)}
				<label>
					{t('userForm.primaryRole')}
					<select value={primaryRoleName} onChange={(e) => setPrimaryRoleName(e.target.value)}>
						{ROLES.map((r) => (
							<option key={r} value={r}>
								{r}
							</option>
						))}
					</select>
				</label>

				{error && <p className="form-error">{error}</p>}

				<div className="modal-actions">
					<button type="button" onClick={onCancel} disabled={submitting}>
						{t('common.cancel')}
					</button>
					<button type="submit" disabled={submitting}>
						{submitting ? t('common.saving') : t('common.save')}
					</button>
				</div>
			</form>
		</div>
	);
}
