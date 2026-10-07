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
 * A crop rectangle in the true original's own pixel coordinates, not the
 * scaled-down "web" derivative the editing popup actually displays
 * (Design.md, image editor §, 25/09/2026) - the frontend scales the edge
 * handles' on-screen positions up to original-pixel space before sending
 * this. Bounds (non-negative, non-empty, within the original's real
 * dimensions) can only be checked once the original is actually decoded -
 * see {@code ImageStorageService#cropImage} - so nothing is validated here
 * beyond what a record's own fields already guarantee (correct types).
 */
public record CropImageRequest(int x, int y, int width, int height) {
}
