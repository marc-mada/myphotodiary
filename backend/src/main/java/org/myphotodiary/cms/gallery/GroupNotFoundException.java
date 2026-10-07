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

/**
 * Raised only by {@link DirectoryIndexerService#updateDirectoryDetail} when
 * asked to move a sequence into a group that doesn't exist (12/09/2026) -
 * every other {@code groupName} field in this backend (directory/import
 * creation) is deliberately find-or-create, but the new group *picker*
 * always submits a name it just listed from {@code GET /api/groups}/
 * {@code GET /api/me/groups}, so a name that doesn't resolve here means a
 * stale/forged request, not a legitimate "create this new group as a side
 * effect" - closes the "typo silently creates an orphan group" risk noted
 * when this field was first added (04/09/2026 backlog entry).
 */
public class GroupNotFoundException extends RuntimeException {
	public GroupNotFoundException(String groupName) {
		super("Group not found: " + groupName);
	}
}
