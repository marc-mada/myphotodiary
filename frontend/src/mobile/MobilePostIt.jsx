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

import { useEffect } from 'react';
import { PostIt } from '../gallery/PostIt';

const AUTO_HIDE_MS = 10000;

/**
 * Wraps the shared `PostIt` (same yellow sticky-note visual identity,
 * decision #8 - the postit isn't a widget skin, it's real app identity, so
 * it stays the same component/CSS on both trees) with mobile's own
 * visibility behavior, ported from midx.js's `postit` object: hidden by
 * default, shown for 10s then auto-hidden again (`show()`'s own
 * `setTimeout`), toggled by the footer "Edit" button
 * (`mpd.pi.toggle()` <- `id="edit"` in mindex.jsp's footer). Desktop's
 * postit has no such visibility state at all - it's always on screen -
 * this component is mobile-only for exactly that reason.
 *
 * Tapping either postit area still hides it immediately and navigates to
 * the corresponding edit page (`dirEdit()`/`imgEdit()` both call
 * `this.hide()` before changing page) - `onOpenSequence`/`onOpenImage` do
 * that navigation, `handleOpen` below adds the immediate hide on top.
 *
 * Desktop's own post-it now fades on a delay too (28/08/2026), but via a
 * different mechanism - PostIt's own `fadeDelayMs`/`resetKey` props, a
 * plain CSS opacity fade with hover-to-reveal, not a hard unmount/remount
 * on a fixed timer. This component doesn't pass either prop, so that fade
 * logic stays inert here - mobile keeps its own show/hide/auto-hide timing
 * unchanged, on purpose (no per-user config for it, unlike desktop's).
 */
export function MobilePostIt({ visible, onHide, sequenceDescription, imageDescription, onOpenSequence, onOpenImage }) {
	useEffect(() => {
		if (!visible) return;
		const timer = setTimeout(onHide, AUTO_HIDE_MS);
		return () => clearTimeout(timer);
	}, [visible, onHide]);

	if (!visible) return null;

	function handleOpen(callback) {
		onHide();
		callback();
	}

	return (
		<PostIt
			sequenceDescription={sequenceDescription}
			imageDescription={imageDescription}
			onOpenSequence={() => handleOpen(onOpenSequence)}
			onOpenImage={() => handleOpen(onOpenImage)}
		/>
	);
}
