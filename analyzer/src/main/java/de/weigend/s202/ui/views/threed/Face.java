/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui.views.threed;

/** A drawn face of a box on screen, convex, for picking. */
record Face(TopViewModel.Box box, double[] xs, double[] ys) {

    boolean contains(double x, double y) {
        int n = xs.length;
        double sign = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double cross = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i]);
            if (Math.abs(cross) < 1e-12) {
                continue;
            }
            if (sign == 0) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        return sign != 0;
    }
}
