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
 * A rename/move whose target is already an existing sequence (10/10/2026):
 * the caller has to confirm a merge (RenameDirectoryRequest#merge) first.
 * Answered 409 with these details (GalleryApiExceptionHandler), so the
 * confirmation can say what will happen.
 *
 * @param targetImageCount pictures already in the target (0 if it is a
 *                         folder that was never indexed)
 * @param targetGroup      null if the target has no sequence row yet
 */
public class SequenceMergeRequiredException extends RuntimeException {

	private final String targetPath;
	private final long sourceImageCount;
	private final long targetImageCount;
	private final String sourceGroup;
	private final String targetGroup;

	public SequenceMergeRequiredException(String targetPath, long sourceImageCount, long targetImageCount, String sourceGroup, String targetGroup) {
		super("Sequence already exists: " + targetPath);
		this.targetPath = targetPath;
		this.sourceImageCount = sourceImageCount;
		this.targetImageCount = targetImageCount;
		this.sourceGroup = sourceGroup;
		this.targetGroup = targetGroup;
	}

	public String getTargetPath() {
		return targetPath;
	}

	public long getSourceImageCount() {
		return sourceImageCount;
	}

	public long getTargetImageCount() {
		return targetImageCount;
	}

	public String getSourceGroup() {
		return sourceGroup;
	}

	public String getTargetGroup() {
		return targetGroup;
	}
}
