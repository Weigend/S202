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
package de.weigend.s202.ui.core.arrows;

import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Line;
import javafx.scene.shape.Shape;

import java.util.Collection;

/**
 * The path of one arrow: a straight line, or — when boxes lie on that line —
 * an arc that passes them on one side.
 *
 * <p>Boxes stacked in one column have their anchor points on one vertical
 * line, so every arrow that skips a level would run through the boxes in
 * between and along the other arrows. The arc avoids both: it clears the boxes,
 * and an arrow that skips more boxes takes the wider arc.
 *
 * @param bow distance of the two control points from the straight line, to the
 *            left of the direction of travel when positive; 0 for a straight line
 */
record ArrowRoute(double startX, double startY, double endX, double endY, double bow) {

    /** Space between an arc and a box it passes. */
    private static final double CLEARANCE = 5.0;
    /** How much wider the arc gets with every further box it passes. */
    private static final double NESTING_STEP = 5.0;
    /** A box counts as lying on the line when the line comes this close. */
    private static final double TOUCH_MARGIN = 1.5;
    /** The arc reaches three quarters of the control-point distance. */
    private static final double BOW_REACH = 0.75;

    static ArrowRoute straight(double startX, double startY, double endX, double endY) {
        return new ArrowRoute(startX, startY, endX, endY, 0.0);
    }

    /**
     * @param boxes    bounds {@code [minX, minY, maxX, maxY]} of the boxes the
     *                 arrow must not run through; its own two ends are not among them
     * @param keepLeft pass every box on the left, even where the right is
     *                 nearer. The two arrows of a mutual dependency do, and so
     *                 end up on opposite sides instead of on one arc.
     */
    static ArrowRoute around(double startX, double startY, double endX, double endY,
                             Collection<double[]> boxes, boolean keepLeft) {
        double dx = endX - startX;
        double dy = endY - startY;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0) {
            return straight(startX, startY, endX, endY);
        }
        // Unit normal, to the left of the direction of travel (y grows downward).
        double nx = dy / length;
        double ny = -dx / length;

        double left = 0.0;
        double right = 0.0;
        int passed = 0;
        for (double[] box : boxes) {
            if (!crosses(startX, startY, endX, endY, box)) {
                continue;
            }
            passed++;
            // The arc is widest in its middle: a box near an end needs more bow.
            double t = (((box[0] + box[2]) / 2.0 - startX) * dx + ((box[1] + box[3]) / 2.0 - startY) * dy)
                    / (length * length);
            t = Math.max(0.2, Math.min(0.8, t));
            double reach = 3.0 * t * (1.0 - t);
            double farLeft = Double.NEGATIVE_INFINITY;
            double farRight = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                double cx = box[corner < 2 ? 0 : 2];
                double cy = box[corner % 2 == 0 ? 1 : 3];
                double distance = (cx - startX) * nx + (cy - startY) * ny;
                farLeft = Math.max(farLeft, distance);
                farRight = Math.max(farRight, -distance);
            }
            left = Math.max(left, (farLeft + CLEARANCE) / reach);
            right = Math.max(right, (farRight + CLEARANCE) / reach);
        }
        if (passed == 0) {
            return straight(startX, startY, endX, endY);
        }
        double nesting = NESTING_STEP * (passed - 1) / BOW_REACH;
        // Pass on the nearer side; undecided, keep to the left. Arrows of
        // opposite direction then take opposite sides of a column.
        double bow = !keepLeft && right + 1.0 < left ? -(right + nesting) : left + nesting;
        return new ArrowRoute(startX, startY, endX, endY, bow);
    }

    boolean curved() {
        return bow != 0.0;
    }

    /** The point after the fraction {@code t} of the way, 0 = start, 1 = end. */
    double[] at(double t) {
        double x = startX + t * (endX - startX);
        double y = startY + t * (endY - startY);
        if (!curved()) {
            return new double[]{x, y};
        }
        double[] offset = controlOffset();
        double reach = 3.0 * t * (1.0 - t);
        return new double[]{x + reach * offset[0], y + reach * offset[1]};
    }

    /** Direction in which the arrow arrives at its end, as an angle. */
    double endAngle() {
        if (!curved()) {
            return Math.atan2(endY - startY, endX - startX);
        }
        double[] offset = controlOffset();
        return Math.atan2((endY - startY) / 3.0 - offset[1], (endX - startX) / 3.0 - offset[0]);
    }

    Shape toShape() {
        if (!curved()) {
            return new Line(startX, startY, endX, endY);
        }
        double[] offset = controlOffset();
        double thirdX = (endX - startX) / 3.0;
        double thirdY = (endY - startY) / 3.0;
        CubicCurve curve = new CubicCurve(startX, startY,
                startX + thirdX + offset[0], startY + thirdY + offset[1],
                startX + 2 * thirdX + offset[0], startY + 2 * thirdY + offset[1],
                endX, endY);
        curve.setFill(null);
        return curve;
    }

    private double[] controlOffset() {
        double dx = endX - startX;
        double dy = endY - startY;
        double length = Math.sqrt(dx * dx + dy * dy);
        return new double[]{bow * dy / length, bow * -dx / length};
    }

    /** True when the straight line comes within {@link #TOUCH_MARGIN} of {@code box} (Liang–Barsky). */
    private static boolean crosses(double x0, double y0, double x1, double y1, double[] box) {
        double minX = box[0] - TOUCH_MARGIN;
        double minY = box[1] - TOUCH_MARGIN;
        double maxX = box[2] + TOUCH_MARGIN;
        double maxY = box[3] + TOUCH_MARGIN;
        double dx = x1 - x0;
        double dy = y1 - y0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 - minX, maxX - x0, y0 - minY, maxY - y0};
        double enter = 0.0;
        double leave = 1.0;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0) {
                if (q[i] < 0.0) {
                    return false;
                }
                continue;
            }
            double t = q[i] / p[i];
            if (p[i] < 0.0) {
                enter = Math.max(enter, t);
            } else {
                leave = Math.min(leave, t);
            }
            if (enter > leave) {
                return false;
            }
        }
        return true;
    }
}
