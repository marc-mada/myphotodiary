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
import { AttributeAdmin } from '../gallery/AttributeAdmin';
import { BackupPanel } from './BackupPanel';
import { ConfigPanel } from './ConfigPanel';
import { IndexManagementPanel } from './IndexManagementPanel';
import { TabBar } from './TabBar';
import { UserFormModal } from './UserFormModal';
import { RoleAssignments } from './RoleAssignments';
import { useForbiddenAwareError } from '../temporaryMessage';

const TAB_IDS = ['users', 'configure', 'index', 'tags', 'backup'];
const TAB_LABEL_KEYS = {
	users: 'admin.users',
	configure: 'admin.configure',
	index: 'admin.indexManagement',
	tags: 'admin.tags',
	backup: 'admin.backup',
};

/**
 * The Admin screen - restructured 28/08/2026 (explicit ask) into a secondary
 * toolbar (Users/Configure/Index management/Tags) that stays the same for
 * as long as the top-level Admin tab is selected, switching between four
 * genuinely independent sub-panels - one mounted at a time, not four
 * simultaneously-open toggles the way this screen used to work. Each
 * sub-panel is exactly one thing (the user table, the settings form, the
 * index-management table, or the tag tree) with nothing else mixed in, and
 * switching tabs unmounts whichever panel was showing rather than layering
 * them - the same swap-the-whole-component pattern App.jsx already uses for
 * the top-level Gallery/Search/Admin screens, applied one level down.
 *
 * "User administration" title removed (redundant with the Admin nav tab
 * already saying so) and the old "New user" button's blue is gone - it's
 * a plain button now, inside the Users sub-panel rather than doubling as
 * the tab switcher itself (previously the same button opened the create
 * form *and* stood in for "show the Users table", conflating the two).
 *
 * Hand-rolled table (Design.md decision #8) rather than a table library or
 * a reused jTable - jQuery widgets aren't reused as React components
 * (Design.md decision #8), and this screen's CRUD needs (a few dozen users, no
 * sorting/filtering/pagination requirement) don't justify a table library
 * either.
 *
 * `isAdmin` (04/09/2026, explicit ask) - App.jsx now also mounts this
 * component for a WRITER, not just ADMIN, but a WRITER only ever gets a
 * cut-down version: just the Configure sub-tab's content, and even that
 * restricted to their own settings (no user picker, no app-wide section -
 * see ConfigPanel's own `selfOnly` prop). No secondary tab bar at all in
 * that case - with only one destination reachable, a tab selector holding
 * a single tab would be a UI element with nothing to actually switch
 * between. Users/Index management/Tags all stay ADMIN-only, unconditionally
 * - their own backend endpoints already enforce that regardless of what
 * this component renders, but there's no reason to build UI for a screen
 * every request from it would just 403 against anyway.
 */
export function UserTable({ isAdmin }) {
	const { t } = useTranslation();
	const [activeTab, setActiveTab] = useState('users');
	const [users, setUsers] = useState(null);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [editingUser, setEditingUser] = useState(undefined); // undefined = modal closed, null = create, object = edit
	const [expandedUser, setExpandedUser] = useState(null);

	async function reload() {
		try {
			setUsers(await usersApi.list());
		} catch (err) {
			reportError(err);
		}
	}

	useEffect(() => {
		// A WRITER never sees the Users tab at all (below) - fetching the
		// ADMIN-only user list for them would just 403 on mount.
		if (isAdmin) reload();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [isAdmin]);

	async function handleDelete(userName) {
		if (!window.confirm(t('admin.deleteUserConfirm', { userName }))) return;
		setError(null);
		try {
			await usersApi.remove(userName);
			await reload();
		} catch (err) {
			reportError(err);
		}
	}

	async function handleFormSubmit(request) {
		if (editingUser) {
			await usersApi.update(editingUser.userName, request);
		} else {
			await usersApi.create(request);
		}
		setEditingUser(undefined);
		await reload();
	}

	// WRITER: no tab bar, no Users/Index management/Tags - just the one
	// destination they're actually allowed, rendered directly (see this
	// component's own comment on why a single-tab bar isn't worth building).
	if (!isAdmin) {
		return (
			<div className="user-admin">
				<ConfigPanel selfOnly />
			</div>
		);
	}

	return (
		<div className="user-admin">
			{error && <p className="form-error">{error}</p>}

			<div className="user-admin-toolbar">
				<TabBar tabs={TAB_IDS.map((id) => ({ id, label: t(TAB_LABEL_KEYS[id]) }))} activeId={activeTab} onSelect={setActiveTab} />
			</div>

			{activeTab === 'users' && (
				<div className="users-panel">
					<button type="button" onClick={() => setEditingUser(null)}>
						{t('admin.newUser')}
					</button>

					{users === null ? (
						<p>{t('admin.loadingUsers')}</p>
					) : (
						<table className="user-table">
							<thead>
								<tr>
									<th>{t('admin.userCol')}</th>
									<th>{t('admin.longNameCol')}</th>
									<th>{t('admin.createdCol')}</th>
									<th>{t('admin.groupCol')}</th>
									<th>{t('admin.roleCol')}</th>
									<th></th>
								</tr>
							</thead>
							<tbody>
								{users.map((u) => (
									<UserRow
										key={u.userName}
										user={u}
										isExpanded={expandedUser === u.userName}
										onEdit={() => setEditingUser(u)}
										onDelete={() => handleDelete(u.userName)}
										onToggleRoles={() => setExpandedUser(expandedUser === u.userName ? null : u.userName)}
									/>
								))}
							</tbody>
						</table>
					)}

					{editingUser !== undefined && (
						<UserFormModal user={editingUser} onSubmit={handleFormSubmit} onCancel={() => setEditingUser(undefined)} />
					)}
				</div>
			)}

			{activeTab === 'configure' && <ConfigPanel />}
			{activeTab === 'index' && <IndexManagementPanel />}
			{activeTab === 'tags' && <AttributeAdmin />}
			{activeTab === 'backup' && <BackupPanel />}
		</div>
	);
}

function UserRow({ user, isExpanded, onEdit, onDelete, onToggleRoles }) {
	const { t } = useTranslation();
	return (
		<>
			<tr>
				<td>{user.userName}</td>
				<td>{user.longName}</td>
				<td>{user.creationDate}</td>
				<td>{user.primaryGroupName}</td>
				<td>{user.primaryRoleName}</td>
				{/* Buttons in an inner div, not `display: flex` on the <td> itself
				    (04/10/2026): a flex <td> stops being a real table cell, so the
				    header cell above it collapsed and, once the header became
				    sticky, these buttons showed through on its right. */}
				<td>
					<div className="row-actions">
						<button type="button" onClick={onToggleRoles}>
							{t('admin.roles')}
						</button>
						<button type="button" onClick={onEdit}>
							{t('admin.edit')}
						</button>
						<button type="button" onClick={onDelete}>
							{t('admin.delete')}
						</button>
					</div>
				</td>
			</tr>
			{isExpanded && <RoleAssignments userName={user.userName} onClose={onToggleRoles} />}
		</>
	);
}
