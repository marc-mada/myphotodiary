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
import { usersApi } from '../api/users';
import { useForbiddenAwareError } from '../temporaryMessage';

const ROLES = ['ADMIN', 'WRITER', 'READER', 'LOWER'];

/** The per-user role sub-panel - the equivalent of jTable's "open child table" for roles. */
export function RoleAssignments({ userName, onClose }) {
	const { t } = useTranslation();
	const [roles, setRoles] = useState(null);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [groupName, setGroupName] = useState('');
	const [role, setRole] = useState('READER');

	async function reload() {
		try {
			setRoles(await usersApi.listRoles(userName));
		} catch (err) {
			reportError(err);
		}
	}

	useEffect(() => {
		reload();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [userName]);

	async function handleAdd(e) {
		e.preventDefault();
		setError(null);
		try {
			await usersApi.addRole(userName, { groupName, role });
			setGroupName('');
			await reload();
		} catch (err) {
			reportError(err);
		}
	}

	async function handleRemove(group) {
		setError(null);
		try {
			await usersApi.removeRole(userName, group);
			await reload();
		} catch (err) {
			reportError(err);
		}
	}

	return (
		<tr className="role-assignments-row">
			<td colSpan={6}>
				<div className="role-assignments">
					<div className="role-assignments-header">
						<strong>{t('roleAssignments.rolesFor', { userName })}</strong>
						<button type="button" onClick={onClose}>
							{t('roleAssignments.close')}
						</button>
					</div>
					{error && <p className="form-error">{error}</p>}
					{roles === null ? (
						<p>{t('common.loading')}</p>
					) : (
						<table className="inner-table">
							<thead>
								<tr>
									<th>{t('roleAssignments.groupCol')}</th>
									<th>{t('roleAssignments.roleCol')}</th>
									<th>{t('roleAssignments.primaryCol')}</th>
									<th></th>
								</tr>
							</thead>
							<tbody>
								{roles.map((ra) => (
									<tr key={ra.groupName}>
										<td>{ra.groupName}</td>
										<td>{ra.role}</td>
										<td>{ra.primary ? t('roleAssignments.yes') : ''}</td>
										<td>
											{!ra.primary && (
												<button type="button" onClick={() => handleRemove(ra.groupName)}>
													{t('roleAssignments.remove')}
												</button>
											)}
										</td>
									</tr>
								))}
							</tbody>
						</table>
					)}
					<form className="role-assignment-form" onSubmit={handleAdd}>
						<input
							placeholder={t('roleAssignments.groupNamePlaceholder')}
							value={groupName}
							onChange={(e) => setGroupName(e.target.value)}
							required
						/>
						<select value={role} onChange={(e) => setRole(e.target.value)}>
							{ROLES.map((r) => (
								<option key={r} value={r}>
									{r}
								</option>
							))}
						</select>
						<button type="submit">{t('roleAssignments.addRole')}</button>
					</form>
				</div>
			</td>
		</tr>
	);
}
