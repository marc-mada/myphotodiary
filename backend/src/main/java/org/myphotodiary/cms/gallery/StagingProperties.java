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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filesystem root for the staging-import feature (01/09/2026) - legacy's
 * real Batch Publish behavior (an external disk mounted at an admin-chosen
 * path, sorted by EXIF and imported into the tree), which the "Batch
 * Publish" button this replaces never actually was - see
 * {@link StagingImportService}'s own javadoc for the full story.
 *
 * A separate root from {@link StorageProperties} on purpose - staging holds
 * not-yet-organized files an admin drops there (e.g. mounting an external
 * disk at this path), distinct from the real, already-organized gallery
 * tree. Same shape as {@code StorageProperties} deliberately, for the same
 * reason: a plain configurable root, {@code MPD_STAGING_ROOT} overriding the
 * default the same way {@code MPD_STORAGE_ROOT} already does.
 */
@ConfigurationProperties(prefix = "staging")
public class StagingProperties {

	private String root = "./data/staging";

	public String getRoot() {
		return root;
	}

	public void setRoot(String root) {
		this.root = root;
	}
}
