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

import java.util.List;

/**
 * The external-viewer payload for a sequence-link (ShareController) - the
 * sequence's own description (the post-it's top half, same as
 * {@code DirectoryResponse.description}) plus every image in it. Narrower
 * than {@code DirectoryResponse} on purpose, same reasoning as
 * {@link PublicImageResponse}: no {@code path}/{@code groupName}/
 * {@code latitude}/{@code longitude}/{@code attributeNames} - none of this
 * app's own internal organization is something to expose to someone with no
 * account, only what the sequence actually looks like.
 *
 * {@code images} is image-only (no videos) - a deliberate, documented scope
 * limit for this first pass (ShareController's own javadoc), not an
 * oversight: publicly streaming video would need its own public,
 * token-gated Range-request endpoint mirroring VideoController, a
 * meaningfully bigger surface than reusing the already-public web/thumbnail
 * image endpoints this DTO's own images point at.
 */
public record PublicSequenceResponse(Long id, String description, List<PublicImageResponse> images) {
}
