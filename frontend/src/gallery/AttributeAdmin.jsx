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
import { attributeApi } from '../api/navigation';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * Attribute (tag) hierarchy admin - replaces the legacy AttributeTreeSvr-
 * backed fancytree pane in admin.jsp (`attrAdminPane`/`#attrTree`,
 * Design.md §16.5/§16.6). Same lazy-loaded-per-level tree pattern as
 * DirectoryTree, styled the same way. Reparenting via drag-and-drop
 * (idxAdmin.js's `dnd` extension) ported (Design.md §15, legacy
 * feature-parity audit) with native HTML5 drag-and-drop rather than a library -
 * dropping a row onto another moves it under that attribute; dropping onto
 * the tree's own background (not a row) detaches it back to root. The
 * backend already validated "can't move under itself or a descendant"
 * (AttributeService.update) since the API was first built - this only adds
 * the UI to actually trigger it.
 *
 * Moved into the Admin screen itself (28/08/2026, explicit ask). Further
 * restructured the same day: one of four independent Admin sub-panels -
 * UserTable now owns which one is showing (a single active tab, not each
 * panel independently toggled open), so this component just renders its
 * own content unconditionally; it no longer has its own "Tags"/"Close
 * tags" button or `open` state - it's only ever mounted while its tab is
 * the active one, so the tree fetch runs on mount.
 */
export function AttributeAdmin() {
	const { t } = useTranslation();
	const [rootNodes, setRootNodes] = useState(null);
	const [newName, setNewName] = useState('');
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);
	const [refreshKey, setRefreshKey] = useState(0);
	const [draggingId, setDraggingId] = useState(null);
	const [dropTarget, setDropTarget] = useState(null); // attribute id, or 'root'

	useEffect(() => {
		attributeApi
			.listChildren(null)
			.then(setRootNodes)
			.catch(reportError);
	}, [refreshKey]);

	function refresh() {
		setRefreshKey((k) => k + 1);
	}

	async function createRoot(e) {
		e.preventDefault();
		if (!newName.trim()) return;
		setError(null);
		try {
			await attributeApi.create({ name: newName.trim(), parentName: null });
			setNewName('');
			refresh();
		} catch (err) {
			reportError(err);
		}
	}

	async function reparent(draggedId, newParentName) {
		setError(null);
		try {
			await attributeApi.update(draggedId, { parentName: newParentName });
			refresh();
		} catch (err) {
			reportError(err);
		}
	}

	function handleRootDrop(e) {
		e.preventDefault();
		setDropTarget(null);
		const id = Number(e.dataTransfer.getData('text/plain'));
		// Not `if (id)` - attribute id 0 is falsy but a perfectly valid id.
		if (!Number.isNaN(id)) reparent(id, '');
	}

	return (
		<div className="attribute-admin-body">
			{error && <p className="form-error">{error}</p>}

			<form onSubmit={createRoot} className="directory-create-form">
				<input placeholder={t('tags.newTagPlaceholder')} value={newName} onChange={(e) => setNewName(e.target.value)} />
				<button type="submit">{t('tags.newTag')}</button>
			</form>

			<div
				key={refreshKey}
				className={`directory-tree attribute-tree${dropTarget === 'root' ? ' tree-drop-target' : ''}`}
				onDragOver={(e) => {
					// Always preventDefault, unconditionally - a browser only
					// fires the final `drop` event if the *last* dragover
					// before it called preventDefault(); gating that call on
					// React state (draggingId) risks missing it if a render
					// hasn't flushed yet, silently turning the drop into a
					// no-op with no error. Cheap and harmless when nothing is
					// being dragged.
					e.preventDefault();
					setDropTarget('root');
				}}
				onDragLeave={() => setDropTarget((t) => (t === 'root' ? null : t))}
				onDrop={handleRootDrop}
				title={draggingId != null ? t('tags.dropToTopLevel') : undefined}
			>
				{rootNodes === null ? (
					<p className="tree-loading">{t('common.loading')}</p>
				) : rootNodes.length === 0 ? (
					<p className="tree-empty">{t('tags.noTags')}</p>
				) : (
					rootNodes.map((a) => (
						<AttributeNode
							key={a.id}
							attribute={a}
							depth={0}
							onChange={refresh}
							onError={reportError}
							draggingId={draggingId}
							setDraggingId={setDraggingId}
							dropTarget={dropTarget}
							setDropTarget={setDropTarget}
							onReparent={reparent}
						/>
					))
				)}
			</div>
		</div>
	);
}

function AttributeNode({ attribute, depth, onChange, onError, draggingId, setDraggingId, dropTarget, setDropTarget, onReparent }) {
	const { t } = useTranslation();
	const [expanded, setExpanded] = useState(false);
	const [children, setChildren] = useState(null);
	const [editing, setEditing] = useState(false);
	const [draftName, setDraftName] = useState(attribute.name);
	const [addingChild, setAddingChild] = useState(false);
	const [childName, setChildName] = useState('');

	async function toggleExpand() {
		if (!expanded && children === null) {
			setChildren(await attributeApi.listChildren(attribute.name));
		}
		setExpanded(!expanded);
	}

	async function saveRename() {
		if (!draftName.trim() || draftName === attribute.name) {
			setEditing(false);
			return;
		}
		try {
			await attributeApi.update(attribute.id, { name: draftName.trim() });
			setEditing(false);
			onChange();
		} catch (err) {
			onError(err);
		}
	}

	async function handleDelete() {
		if (!window.confirm(t('tags.deleteTagConfirm', { name: attribute.name }))) return;
		try {
			await attributeApi.remove(attribute.id);
			onChange();
		} catch (err) {
			onError(err);
		}
	}

	async function submitAddChild(e) {
		e.preventDefault();
		if (!childName.trim()) return;
		try {
			await attributeApi.create({ name: childName.trim(), parentName: attribute.name });
			setChildName('');
			setAddingChild(false);
			setChildren(await attributeApi.listChildren(attribute.name));
			setExpanded(true);
		} catch (err) {
			onError(err);
		}
	}

	const isDropTarget = dropTarget === attribute.id;
	const isDragSource = draggingId === attribute.id;

	return (
		<div className="tree-node">
			<div
				className={`tree-row${isDropTarget ? ' tree-drop-target' : ''}${isDragSource ? ' tree-drag-source' : ''}`}
				style={{ paddingLeft: depth * 16 + 4 }}
				draggable
				onDragStart={(e) => {
					e.dataTransfer.setData('text/plain', String(attribute.id));
					e.dataTransfer.effectAllowed = 'move';
					setDraggingId(attribute.id);
				}}
				onDragEnd={() => {
					setDraggingId(null);
					setDropTarget(null);
				}}
				onDragOver={(e) => {
					// Always preventDefault + stopPropagation (see the root
					// container's own onDragOver comment on why) - only the
					// *visual* highlight is conditional on this being a valid
					// target (not dragging a node onto itself).
					e.preventDefault();
					e.stopPropagation();
					if (draggingId != null && draggingId !== attribute.id) setDropTarget(attribute.id);
				}}
				onDragLeave={(e) => {
					e.stopPropagation();
					setDropTarget((t) => (t === attribute.id ? null : t));
				}}
				onDrop={(e) => {
					e.preventDefault();
					e.stopPropagation();
					setDropTarget(null);
					const id = Number(e.dataTransfer.getData('text/plain'));
					if (!Number.isNaN(id) && id !== attribute.id) onReparent(id, attribute.name);
				}}
				title={t('tags.dragToMove')}
			>
				<span className="tree-toggle" onClick={toggleExpand}>
					{expanded ? '▾' : '▸'}
				</span>
				{editing ? (
					<input
						autoFocus
						className="tree-rename-input"
						value={draftName}
						onChange={(e) => setDraftName(e.target.value)}
						onBlur={saveRename}
						onKeyDown={(e) => e.key === 'Enter' && saveRename()}
					/>
				) : (
					<span className="tree-title" onClick={() => setEditing(true)} title={t('tags.clickToRename')}>
						{attribute.name}
					</span>
				)}
				<span className="tree-actions">
					<a onClick={() => setAddingChild(!addingChild)}>{t('tags.addTag')}</a>
					<a onClick={handleDelete}>{t('tags.deleteTag')}</a>
				</span>
			</div>
			{addingChild && (
				<form onSubmit={submitAddChild} className="tree-add-child-form" style={{ paddingLeft: (depth + 1) * 16 + 4 }}>
					<input autoFocus placeholder={t('tags.childTagPlaceholder')} value={childName} onChange={(e) => setChildName(e.target.value)} />
					<button type="submit">{t('tags.add')}</button>
				</form>
			)}
			{expanded &&
				children?.map((child) => (
					<AttributeNode
						key={child.id}
						attribute={child}
						depth={depth + 1}
						onChange={onChange}
						onError={onError}
						draggingId={draggingId}
						setDraggingId={setDraggingId}
						dropTarget={dropTarget}
						setDropTarget={setDropTarget}
						onReparent={onReparent}
					/>
				))}
		</div>
	);
}
