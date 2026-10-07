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

import { Component } from 'react';

/**
 * Top-level React error boundary (01/09/2026) - added after a real
 * production report (one Xiaomi/Chrome device): navigating into a mobile
 * nav-panel year/month directory would "hang" with a complete black screen,
 * every control gone, recoverable only by a full page reload. Root cause
 * of *that specific* trigger was never pinned down from code alone (nothing
 * in the mobile nav-click render path - MobileNavPanel/MobileApp/
 * MobileImageViewer/AuthVideo/MobilePostIt - showed an obvious unguarded
 * throw), but a `grep` across this whole codebase for
 * ErrorBoundary/componentDidCatch/getDerivedStateFromError turned up
 * *zero* matches anywhere - meaning this app had no error boundary at all,
 * on desktop or mobile, before this component. Under that condition ANY
 * uncaught exception during render, on any device, for any reason
 * (a device-specific rendering quirk, a transient network hiccup turned
 * into an unguarded throw, anything) produces exactly the reported
 * symptom: React unmounts the entire tree below the nearest boundary -
 * which, with none present, means the whole app - leaving a blank/black
 * page with nothing left to click, since even the "Menu"/"Go" controls
 * that would let the user navigate back are themselves part of what just
 * got unmounted. This is therefore a real, independently-justified
 * resilience fix regardless of whether it turns out to be the exact
 * mechanism behind the Xiaomi report - it converts "unrecoverable blank
 * screen, only a full page reload works" into "an in-app fallback screen
 * with a Reload button, and (for whoever is looking, e.g. over USB remote
 * debugging) the actual error message/stack visible on screen" - which
 * also makes the *next* occurrence, on this device or any other,
 * diagnosable instead of a dead end.
 *
 * Class component deliberately - React error boundaries are not currently
 * expressible as a hook, only as componentDidCatch/getDerivedStateFromError
 * on a class.
 */
export default class ErrorBoundary extends Component {
	constructor(props) {
		super(props);
		this.state = { error: null };
	}

	static getDerivedStateFromError(error) {
		return { error };
	}

	componentDidCatch(error, info) {
		// Also explicitly logged here (React already logs in dev mode on its
		// own) so the error survives in the browser console in production
		// builds too, in case someone gets to inspect the device remotely.
		console.error('Uncaught render error caught by ErrorBoundary:', error, info?.componentStack);
	}

	handleReload = () => {
		window.location.reload();
	};

	render() {
		const { error } = this.state;
		if (!error) {
			return this.props.children;
		}
		return (
			<div
				style={{
					position: 'fixed',
					inset: 0,
					background: 'rgb(25,25,25)',
					color: 'rgb(220,220,220)',
					display: 'flex',
					flexDirection: 'column',
					alignItems: 'center',
					justifyContent: 'center',
					padding: '2rem',
					boxSizing: 'border-box',
					zIndex: 9999,
					fontFamily: 'sans-serif',
				}}
			>
				<h2 style={{ marginBottom: '0.5rem' }}>Something went wrong</h2>
				<p style={{ color: 'rgb(150,150,150)', marginBottom: '1rem' }}>
					An unexpected error stopped the app from continuing. Reloading should
					recover it - if it keeps happening, please report the message below.
				</p>
				<pre
					style={{
						maxWidth: '90vw',
						maxHeight: '40vh',
						overflow: 'auto',
						background: 'black',
						color: '#f87171',
						padding: '1rem',
						borderRadius: '4px',
						fontSize: '0.8rem',
						whiteSpace: 'pre-wrap',
						wordBreak: 'break-word',
					}}
				>
					{error?.message || String(error)}
					{error?.stack ? `\n\n${error.stack}` : ''}
				</pre>
				<button
					type="button"
					onClick={this.handleReload}
					style={{
						marginTop: '1.5rem',
						padding: '0.6rem 1.5rem',
						fontSize: '1rem',
						cursor: 'pointer',
					}}
				>
					Reload
				</button>
			</div>
		);
	}
}
