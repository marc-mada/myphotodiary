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
 * The "Transform" half of the rudimentary image editor (25/09/2026) - maps
 * the quadrilateral given by the four corner points onto a
 * {@code destWidth}x{@code destHeight} rectangle (the same rectangle the
 * edge handles already define for {@link CropImageRequest}, shared by
 * design, not a second independent size concept). All 9 numbers are in the
 * true original's own pixel coordinates, same convention as
 * {@code CropImageRequest} - the frontend scales screen positions up before
 * sending this.
 *
 * <p>Flat, individually-named corner fields - not a {@code double[4]} for x
 * and another for y - specifically so a corner can never silently land in
 * the wrong array slot the way an index-based mix-up could. Corner order is
 * fixed project-wide (top-left, top-right, bottom-right, bottom-left,
 * {@link org.myphotodiary.cms.gallery.PerspectiveTransform}'s own doc).
 */
public record TransformImageRequest(
		int destWidth,
		int destHeight,
		double topLeftX,
		double topLeftY,
		double topRightX,
		double topRightY,
		double bottomRightX,
		double bottomRightY,
		double bottomLeftX,
		double bottomLeftY) {

	public double[] quadX() {
		return new double[] { topLeftX, topRightX, bottomRightX, bottomLeftX };
	}

	public double[] quadY() {
		return new double[] { topLeftY, topRightY, bottomRightY, bottomLeftY };
	}
}
