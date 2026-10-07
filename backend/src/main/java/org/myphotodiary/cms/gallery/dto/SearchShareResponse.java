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
 * A freshly shared search result. {@code matchCount} = shareable pictures
 * the search matched (videos excluded), {@code sharedCount} = how many were
 * frozen into the link; {@code truncated} when the first was larger than
 * {@code maxShareSize}, so the caller can warn the sharer.
 */
public record SearchShareResponse(Long id, String token, int matchCount, int sharedCount, boolean truncated, int maxShareSize) {
}
