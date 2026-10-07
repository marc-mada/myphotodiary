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

/**
 * Shared tab-row renderer (explicit ask, 28/08/2026 - "don't you have
 * reusable tabs widget?") - short answer: no off-the-shelf tabs library
 * here, jQuery/generic-component widgets aren't reused as-is in this
 * project (Design.md decision #8's corollary), so this is the hand-rolled
 * equivalent, extracted once App.jsx's top-level screen tabs and
 * UserTable.jsx's Admin sub-tabs turned out to need the exact same
 * rendering logic (equal-width `.tab-button`s, one active at a time) -
 * duplicated rather than shared until this point, when the second nearly-
 * identical copy made the case for it.
 *
 * Deliberately renders a Fragment, not a wrapping element: both call sites
 * place the tab buttons directly as flex children of their own bar
 * (`.screen-nav`/`.user-admin-toolbar`), which is what makes the "touch
 * the bottom line" `.tab-button` CSS trick (align-self:stretch + a
 * negative margin matching the bar's own padding) work correctly - an
 * extra wrapping `<div>` here would break that.
 */
export function TabBar({ tabs, activeId, onSelect }) {
	return tabs.map((tab) => (
		<button key={tab.id} type="button" className={`tab-button${activeId === tab.id ? ' active' : ''}`} onClick={() => onSelect(tab.id)}>
			{tab.label}
		</button>
	));
}
