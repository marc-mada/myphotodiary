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

package org.myphotodiary.cms.gallery.dto;

/**
 * One entry in the directory browsing tree - merges what the legacy split
 * across two servlets (SubDirListSvr for plain browsing, DirIndexSvr for the
 * admin index-management tree's per-node image count / indexed state) into
 * one shape, since the new frontend has no reason to fetch the same
 * filesystem scan twice for two different trees.
 */
public record DirectoryTreeNode(String name, String path, int imageCount, boolean indexed) {
}
