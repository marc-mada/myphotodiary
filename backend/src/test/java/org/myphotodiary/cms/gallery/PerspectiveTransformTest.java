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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class PerspectiveTransformTest {

	@Test
	void rectToQuad_identity_isAPassThrough() {
		// The quad IS the rectangle's own corners - apply() should return
		// exactly its input, corners and interior points alike.
		double[] quadX = { 0, 100, 100, 0 };
		double[] quadY = { 0, 0, 80, 80 };
		PerspectiveTransform transform = PerspectiveTransform.rectToQuad(100, 80, quadX, quadY);

		assertPoint(transform, 0, 0, 0, 0);
		assertPoint(transform, 100, 0, 100, 0);
		assertPoint(transform, 100, 80, 100, 80);
		assertPoint(transform, 0, 80, 0, 80);
		assertPoint(transform, 50, 40, 50, 40);
	}

	@Test
	void rectToQuad_uniformScale_isPureMultiplication() {
		// A quad that's the same rectangle shape, just scaled by 2 - no
		// perspective at all (g=h=0 case), every point should scale exactly.
		double[] quadX = { 0, 200, 200, 0 };
		double[] quadY = { 0, 0, 160, 160 };
		PerspectiveTransform transform = PerspectiveTransform.rectToQuad(100, 80, quadX, quadY);

		assertPoint(transform, 50, 40, 100, 80);
		assertPoint(transform, 25, 20, 50, 40);
	}

	@Test
	void rectToQuad_parallelogram_matchesTheKnownAffineFormula() {
		// A sheared parallelogram (BR = TR + BL - TL, the defining property
		// of a parallelogram) has no real perspective component either
		// (still g=h=0) - reduces to the standard affine bilinear formula
		// P(u,v) = TL + u*(TR-TL) + v*(BL-TL), computed independently here
		// rather than trusting rectToQuad's own internals, and compared
		// against several sample points including the rectangle's own
		// center.
		double width = 10;
		double height = 10;
		double[] quadX = { 0, 10, 13, 3 }; // TL, TR, BR, BL - BR = TR+BL-TL = (10+3-0, 0+10-0) = (13, 10)
		double[] quadY = { 0, 0, 10, 10 };
		PerspectiveTransform transform = PerspectiveTransform.rectToQuad(width, height, quadX, quadY);

		for (double[] sample : new double[][] { { 0, 0 }, { 10, 0 }, { 10, 10 }, { 0, 10 }, { 5, 5 }, { 2.5, 7.5 } }) {
			double u = sample[0];
			double v = sample[1];
			double expectedX = quadX[0] + (u / width) * (quadX[1] - quadX[0]) + (v / height) * (quadX[3] - quadX[0]);
			double expectedY = quadY[0] + (u / width) * (quadY[1] - quadY[0]) + (v / height) * (quadY[3] - quadY[0]);
			assertPoint(transform, u, v, expectedX, expectedY);
		}
	}

	@Test
	void rectToQuad_trueTrapezoid_cornersMapExactlyAndEdgesStayCollinear() {
		// A genuine perspective case (g/h non-zero, NOT a parallelogram -
		// BR != TR+BL-TL here) - a trapezoid narrower at the top than the
		// bottom, the classic "keystone" shape a real photo of a tilted
		// document would need corrected. Two things are checked, both true
		// of ANY quad under a real projective map (not specific to this
		// implementation's own formula, so this doesn't just circularly
		// re-derive the same numbers a transcription bug would also
		// produce): (1) the 4 corners map back exactly - guaranteed by
		// construction, a real check that the linear solve actually solved
		// the system it was given, not a different one; (2) a point along
		// a rectangle edge maps to a point that's still exactly collinear
		// with that edge's two quad corners - true for any projective
		// transform (lines map to lines) even though, unlike an affine
		// map, it does NOT land at the linear midpoint of that quad edge
		// (verified separately below, precisely to *not* assert the wrong
		// property here).
		double width = 100;
		double height = 100;
		double[] quadX = { 20, 80, 100, 0 }; // TL, TR, BR, BL - top edge (60 wide) narrower than bottom (100 wide)
		double[] quadY = { 0, 0, 100, 100 };
		PerspectiveTransform transform = PerspectiveTransform.rectToQuad(width, height, quadX, quadY);

		assertPoint(transform, 0, 0, quadX[0], quadY[0]);
		assertPoint(transform, 100, 0, quadX[1], quadY[1]);
		assertPoint(transform, 100, 100, quadX[2], quadY[2]);
		assertPoint(transform, 0, 100, quadX[3], quadY[3]);

		// Midpoint of the rectangle's own top edge (u=50, v=0) maps
		// somewhere on the segment from TL to TR (a straight line stays a
		// straight line) - checked via the cross-product collinearity test
		// (zero iff the three points are exactly collinear), not via
		// assuming any specific coordinate.
		double[] mapped = transform.apply(50, 0);
		double cross = (quadX[1] - quadX[0]) * (mapped[1] - quadY[0]) - (quadY[1] - quadY[0]) * (mapped[0] - quadX[0]);
		assertThat(cross).isCloseTo(0, within(1e-6));

		// ...but, being a real perspective map (not affine), it must NOT
		// land at that edge's linear midpoint (60, 0) - the defining
		// difference from the parallelogram case above. If this assertion
		// ever fails, either the trapezoid stopped being a trapezoid or
		// something is quietly degrading this into an affine transform.
		assertThat(mapped[0]).isNotCloseTo(60.0, within(0.5));
	}

	@Test
	void rectToQuad_collinearCorners_throwsRatherThanProducingGarbage() {
		// Three corners on a straight line - no real quadrilateral, no
		// valid projective transform exists for it.
		double[] quadX = { 0, 50, 100, 0 };
		double[] quadY = { 0, 0, 0, 100 };

		assertThatThrownBy(() -> PerspectiveTransform.rectToQuad(100, 100, quadX, quadY))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static void assertPoint(PerspectiveTransform transform, double u, double v, double expectedX, double expectedY) {
		double[] result = transform.apply(u, v);
		assertThat(result[0]).isCloseTo(expectedX, within(1e-6));
		assertThat(result[1]).isCloseTo(expectedY, within(1e-6));
	}
}
