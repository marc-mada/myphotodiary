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

package org.myphotodiary.cms.gallery;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wakes the idle transcoding workers (VideoJobRunner) when a job is queued.
 * Only after the queuing transaction commits: a worker woken earlier could
 * not see the new job row yet (same lesson as the directory-creation lock in
 * ImportService, 22/09/2026). Workers also look on their own every 30 s, so
 * a missed signal only delays a job, never loses it.
 */
@Component
public class VideoJobSignal {

	private final Object monitor = new Object();
	private boolean pending;

	public void wakeAfterCommit() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					wake();
				}
			});
		} else {
			wake();
		}
	}

	public void wake() {
		synchronized (monitor) {
			pending = true;
			monitor.notifyAll();
		}
	}

	/** Waits until woken or {@code millis} elapse. */
	public void await(long millis) throws InterruptedException {
		synchronized (monitor) {
			if (!pending) {
				monitor.wait(millis);
			}
			pending = false;
		}
	}
}
