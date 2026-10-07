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
import { directoryTreeApi } from '../api/navigation';
import { useForbiddenAwareError } from '../temporaryMessage';

/**
 * Hand-rolled replacement for jquery.fancytree (Design.md decision #8
 * corollary), lazy-loaded exactly like the legacy SubDirListSvr-backed
 * tree: each level is fetched from disk only when expanded, not the whole
 * tree up front. Styled to match idx.css's own fancytree overrides (black
 * background, light-gray text, thin gray border) rather than fancytree's
 * default skin - those overrides ARE app-specific here (idx.css lines
 * ~868-885), unlike jTable's untouched "metro" skin.
 *
 * A row click both expands *and* selects a node - deliberately not split
 * across "click the tiny triangle to expand, click the name to select"
 * (the original version worked that way and was genuinely hard to
 * discover - a real user reported getting stuck on it). The triangle stays
 * as a secondary, explicit way to collapse a node without navigating away.
 *
 * `expandToPath`, when set, auto-expands every ancestor of that path and
 * selects it once reached - used to jump straight to wherever an import
 * just landed (GalleryScreen), instead of leaving the user to hunt for it.
 *
 * The tree's own first row represents the root itself, above every year
 * (explicit ask) - a folder icon (white, on the tree's own black
 * background) rather than a name, since "" has no name of its own the way
 * every other node does. Clicking it calls `onSelectRoot` - a plain lookup
 * (GalleryScreen.handleSelectRoot), not `onSelect`, since a synthetic root
 * "node" has no real indexed/imageCount metadata from listChildren the way
 * an actual child does.
 */
export function DirectoryTree({ selectedPath, onSelect, onSelectRoot, expandToPath }) {
	const { t } = useTranslation();
	const [rootNodes, setRootNodes] = useState(null);
	const [error, setError] = useState(null);
	const reportError = useForbiddenAwareError(setError);

	useEffect(() => {
		directoryTreeApi.listChildren('').then(setRootNodes).catch(reportError);
	}, []);

	if (error) return <p className="form-error">{error}</p>;

	return (
		<div className="directory-tree">
			<div className={`tree-row tree-root-row${selectedPath === '' ? ' tree-row-selected' : ''}`} onClick={onSelectRoot}>
				<span className="tree-toggle tree-root-icon" aria-hidden="true">
					<svg viewBox="0 0 24 24" fill="white" stroke="none">
						<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z" />
					</svg>
				</span>
				<span className="tree-title">{t('directoryTree.root')}</span>
			</div>
			{rootNodes === null ? (
				<p className="tree-loading">{t('common.loading')}</p>
			) : rootNodes.length === 0 ? (
				<p className="tree-empty">{t('directoryTree.noDirectories')}</p>
			) : (
				rootNodes.map((node) => (
					<TreeNode
						key={node.path}
						node={node}
						depth={0}
						selectedPath={selectedPath}
						onSelect={onSelect}
						expandToPath={expandToPath}
					/>
				))
			)}
		</div>
	);
}

function TreeNode({ node, depth, selectedPath, onSelect, expandToPath }) {
	const { t } = useTranslation();
	const [expanded, setExpanded] = useState(false);
	const [children, setChildren] = useState(null);
	const [loading, setLoading] = useState(false);

	async function loadChildren() {
		setLoading(true);
		try {
			const kids = await directoryTreeApi.listChildren(node.path);
			setChildren(kids);
			return kids;
		} finally {
			setLoading(false);
		}
	}

	async function handleRowClick() {
		if (!expanded && children === null) {
			await loadChildren();
		}
		setExpanded(true);
		onSelect(node);
	}

	async function toggleCollapse(e) {
		e.stopPropagation();
		if (!expanded && children === null) {
			await loadChildren();
		}
		setExpanded(!expanded);
	}

	// Auto-expand every ancestor of expandToPath, and select the target
	// itself once reached - the "jump to where the import landed" feature.
	useEffect(() => {
		if (!expandToPath || expandToPath === node.path) return;
		if (!expandToPath.startsWith(node.path + '/')) return;
		if (expanded) return;
		let cancelled = false;
		loadChildren().then(() => {
			if (!cancelled) setExpanded(true);
		});
		return () => {
			cancelled = true;
		};
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [expandToPath]);

	useEffect(() => {
		if (expandToPath && expandToPath === node.path) {
			onSelect(node);
		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [expandToPath]);

	const isSelected = node.path === selectedPath;

	return (
		<div className="tree-node">
			<div
				className={`tree-row${isSelected ? ' tree-row-selected' : ''}${node.indexed ? ' tree-row-indexed' : ''}`}
				style={{ paddingLeft: depth * 16 + 4 }}
				onClick={handleRowClick}
			>
				<span className="tree-toggle" onClick={toggleCollapse}>
					{loading ? '…' : expanded ? '▾' : '▸'}
				</span>
				<span className="tree-title">{node.name}</span>
				{node.imageCount > 0 && <span className="tree-count">{node.imageCount}</span>}
			</div>
			{/* Only worth flagging when there's truly nothing under this node -
			   a sequence with photos but no sub-sequences (the normal case for
			   most sequences) has zero children too, but that's not a noteworthy
			   "empty" state, just an ordinary leaf. */}
			{expanded && children && children.length === 0 && node.imageCount === 0 && (
				<p className="tree-empty" style={{ paddingLeft: (depth + 1) * 16 + 4 }}>
					{t('directoryTree.empty')}
				</p>
			)}
			{expanded &&
				children?.map((child) => (
					<TreeNode
						key={child.path}
						node={child}
						depth={depth + 1}
						selectedPath={selectedPath}
						onSelect={onSelect}
						expandToPath={expandToPath}
					/>
				))}
		</div>
	);
}
