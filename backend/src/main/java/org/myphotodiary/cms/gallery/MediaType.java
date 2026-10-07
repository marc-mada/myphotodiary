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
 * Discriminates what kind of file an {@link Image} row actually points to -
 * new (28/08/2026, explicit ask: "have a talk about a new topic not in the
 * legacy: Video") rather than a port. Named after the entity it lives on
 * ({@code Image}) rather than introducing a parallel {@code Video} entity:
 * reusing the same row/table for both keeps every existing feature
 * (description/post-it, rating, tags, search, directory association, RBAC,
 * mobile presentation) working unmodified for video too - exactly the
 * "remaining features strictly equivalent to images" requirement, gotten for
 * free rather than re-implemented on a second model.
 */
public enum MediaType {
	IMAGE,
	VIDEO,
}
