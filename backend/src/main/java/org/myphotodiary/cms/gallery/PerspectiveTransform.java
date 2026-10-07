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
 * A 2D projective (perspective) transform mapping an axis-aligned rectangle
 * onto an arbitrary quadrilateral - the "Transform" half of the rudimentary
 * image editor (25/09/2026, explicit ask). Java has no built-in support for
 * this: {@link java.awt.geom.AffineTransform} can only express rotate/scale/
 * shear/translate, which by construction always preserves parallel lines -
 * exactly what a quad-to-rectangle perspective correction needs to be able
 * to break. This is the general 8-degree-of-freedom projective mapping,
 * solved directly from the 4 corner correspondences via a small 8x8 linear
 * system (Gaussian elimination with partial pivoting - no external linear
 * algebra dependency needed for a system this size), rather than a
 * closed-form decomposition recalled from memory - safer to verify
 * (PerspectiveTransformTest's own identity/pure-scale/parallelogram/
 * trapezoid cases) than to trust a formula transcribed wrong.
 *
 * <p>Corner order is fixed throughout this project's use of this class -
 * top-left, top-right, bottom-right, bottom-left (clockwise from the
 * origin) - matching both the rectangle's own natural corners
 * {@code (0,0), (width,0), (width,height), (0,height)} and
 * ImageEditorPopup.jsx's own corner-handle naming.
 */
public final class PerspectiveTransform {

	private final double a;
	private final double b;
	private final double c;
	private final double d;
	private final double e;
	private final double f;
	private final double g;
	private final double h;

	private PerspectiveTransform(double[] coeffs) {
		this.a = coeffs[0];
		this.b = coeffs[1];
		this.c = coeffs[2];
		this.d = coeffs[3];
		this.e = coeffs[4];
		this.f = coeffs[5];
		this.g = coeffs[6];
		this.h = coeffs[7];
	}

	/**
	 * The transform mapping a point inside a {@code width}x{@code height}
	 * rectangle (implicit corners {@code (0,0), (width,0), (width,height),
	 * (0,height)}, top-left/top-right/bottom-right/bottom-left) to the
	 * corresponding point inside the given quadrilateral (same corner
	 * order). Applying {@link #apply} to an output pixel's own (x, y) then
	 * gives exactly the source pixel to sample from - the direction
	 * ImageStorageService's own rasterizer actually needs (an inverse
	 * mapping from destination back to source), not the other way around.
	 *
	 * @throws IllegalArgumentException if the 4 corners don't admit a valid
	 *     projective transform - a degenerate quad (three or more corners
	 *     collinear, or self-intersecting to the point of having no real
	 *     solution)
	 */
	public static PerspectiveTransform rectToQuad(double width, double height, double[] quadX, double[] quadY) {
		if (quadX.length != 4 || quadY.length != 4) {
			throw new IllegalArgumentException("A quad needs exactly 4 corners (top-left, top-right, bottom-right, bottom-left)");
		}
		// The rectangle's own 4 corners, same TL/TR/BR/BL order as the quad.
		double[] u = { 0, width, width, 0 };
		double[] v = { 0, 0, height, height };

		// x = (a*u + b*v + c) / (g*u + h*v + 1)  =>  a*u + b*v + c - g*u*x - h*v*x = x
		// y = (d*u + e*v + f) / (g*u + h*v + 1)  =>  d*u + e*v + f - g*u*y - h*v*y = y
		// 4 corners * 2 equations each = 8 equations in 8 unknowns (a..h).
		double[][] m = new double[8][8];
		double[] rhs = new double[8];
		for (int i = 0; i < 4; i++) {
			int rowX = i * 2;
			int rowY = i * 2 + 1;
			double ui = u[i];
			double vi = v[i];
			double xi = quadX[i];
			double yi = quadY[i];

			m[rowX][0] = ui;
			m[rowX][1] = vi;
			m[rowX][2] = 1;
			m[rowX][6] = -ui * xi;
			m[rowX][7] = -vi * xi;
			rhs[rowX] = xi;

			m[rowY][3] = ui;
			m[rowY][4] = vi;
			m[rowY][5] = 1;
			m[rowY][6] = -ui * yi;
			m[rowY][7] = -vi * yi;
			rhs[rowY] = yi;
		}
		double[] coeffs = solve(m, rhs);
		requireNoSingularityWithinRectangle(coeffs, width, height);
		return new PerspectiveTransform(coeffs);
	}

	/**
	 * Rejects a quad whose homography has a pole (a point mapping to
	 * infinity) inside the very rectangle it's about to be used to warp -
	 * found for real, not merely anticipated: a first version of this class
	 * only checked for a singular *linear system* during elimination (no
	 * row's pivot going to zero), which is a real but different failure
	 * mode from this one - three near-collinear quad corners produced a
	 * perfectly solvable 8x8 system whose resulting transform still
	 * collapsed the entire rectangle's interior onto a single line and sent
	 * one corner to {@code NaN} (confirmed by
	 * {@code PerspectiveTransformTest}'s own collinear-corners case, red
	 * against the pivot-only check before this was added). The fix: the
	 * denominator {@code g*u + h*v + 1} is linear in (u, v), so its
	 * extremes over a convex rectangle always occur at the 4 corners -
	 * checking those 4 values are non-zero *and* all the same sign is
	 * enough to guarantee no pole exists anywhere inside the rectangle,
	 * without having to check every pixel.
	 */
	private static void requireNoSingularityWithinRectangle(double[] coeffs, double width, double height) {
		double g = coeffs[6];
		double h = coeffs[7];
		double[] denomAtCorners = {
				g * 0 + h * 0 + 1,
				g * width + h * 0 + 1,
				g * width + h * height + 1,
				g * 0 + h * height + 1,
		};
		boolean anyPositive = false;
		boolean anyNonPositive = false;
		for (double denom : denomAtCorners) {
			if (Math.abs(denom) < 1e-6) {
				throw new IllegalArgumentException("Degenerate quad - the perspective transform has a singularity inside the rectangle");
			}
			if (denom > 0) anyPositive = true;
			else anyNonPositive = true;
		}
		if (anyPositive && anyNonPositive) {
			throw new IllegalArgumentException("Degenerate quad - the perspective transform has a singularity inside the rectangle");
		}
	}

	/** Maps (u, v) - a point in the rectangle's own coordinate space passed to {@link #rectToQuad} - to its corresponding point inside the quad. */
	public double[] apply(double u, double v) {
		double denom = g * u + h * v + 1.0;
		return new double[] { (a * u + b * v + c) / denom, (d * u + e * v + f) / denom };
	}

	/**
	 * Plain Gaussian elimination with partial pivoting on an 8x8 augmented
	 * matrix - small and well within double precision's comfort zone for a
	 * system this size, no need for a general-purpose linear algebra
	 * library just for this. A near-zero pivot means the 4 corners don't
	 * admit a solution (a genuinely degenerate quad, not just numerical
	 * noise - caught here rather than silently propagating NaN/Infinity
	 * into every rasterized pixel downstream).
	 */
	private static double[] solve(double[][] m, double[] rhs) {
		int n = rhs.length;
		double[][] a = new double[n][n + 1];
		for (int i = 0; i < n; i++) {
			System.arraycopy(m[i], 0, a[i], 0, n);
			a[i][n] = rhs[i];
		}
		for (int col = 0; col < n; col++) {
			int pivotRow = col;
			double pivotMagnitude = Math.abs(a[col][col]);
			for (int row = col + 1; row < n; row++) {
				if (Math.abs(a[row][col]) > pivotMagnitude) {
					pivotMagnitude = Math.abs(a[row][col]);
					pivotRow = row;
				}
			}
			if (pivotMagnitude < 1e-9) {
				throw new IllegalArgumentException("Degenerate quad - no valid perspective transform exists for these corners");
			}
			double[] swap = a[col];
			a[col] = a[pivotRow];
			a[pivotRow] = swap;

			double pivotValue = a[col][col];
			for (int j = col; j <= n; j++) {
				a[col][j] /= pivotValue;
			}
			for (int row = 0; row < n; row++) {
				if (row == col) continue;
				double factor = a[row][col];
				if (factor == 0) continue;
				for (int j = col; j <= n; j++) {
					a[row][j] -= factor * a[col][j];
				}
			}
		}
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			result[i] = a[i][n];
		}
		return result;
	}
}
