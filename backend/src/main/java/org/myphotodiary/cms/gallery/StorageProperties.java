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
 * Filesystem storage root, the new-stack equivalent of the legacy
 * `imageRootPath` (Design.md §16.7/§16.8.2). Originals are stored at
 * {@code {root}/{directory.path}/{image.name}}; thumbnails are cached
 * separately under {@code {root}/.thumbnails/...} so they can be wiped and
 * regenerated without touching originals.
 */
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

	private String root = "./data/images";

	public String getRoot() {
		return root;
	}

	public void setRoot(String root) {
		this.root = root;
	}
}
