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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order in which boxes on one box are drawn. Checked against the
 * geometry, not against the rule of the order: for two boxes on a common line
 * of sight, the one further from the viewer along that line must come first.
 */
class PaintOrderTest {

    private static TopViewModel.Box box(String name, double x, double y, double w, double h) {
        return new TopViewModel.Box(null, name, false, x, y, w, h, 0, 0,
                TopViewModel.Look.of(null, null, 0), List.of(), List.of());
    }

    private static List<String> names(List<TopViewModel.Box> boxes) {
        return boxes.stream().map(TopViewModel.Box::fqn).toList();
    }

    @Test
    void ofTwoNeighboursTheOneAwayFromTheViewerComesFirst() {
        TopViewModel.Box left = box("left", 0, 0, 10, 10);
        TopViewModel.Box right = box("right", 20, 0, 10, 10);
        // Turned by +30°, X grows toward the viewer: the left one is behind.
        assertEquals(List.of("left", "right"),
                names(PaintOrder.backToFront(List.of(right, left), Projection.of(45, 30, 1, 0, 0, 15, 5))));
        assertEquals(List.of("right", "left"),
                names(PaintOrder.backToFront(List.of(left, right), Projection.of(45, -30, 1, 0, 0, 15, 5))));
    }

    @Test
    void ofTwoInAColumnTheUpperOneIsBehindUnlessTurnedRound() {
        TopViewModel.Box upper = box("upper", 0, 0, 10, 10);
        TopViewModel.Box lower = box("lower", 0, 20, 10, 10);
        assertEquals(List.of("upper", "lower"),
                names(PaintOrder.backToFront(List.of(lower, upper), Projection.of(45, 0, 1, 0, 0, 5, 15))));
        assertEquals(List.of("lower", "upper"),
                names(PaintOrder.backToFront(List.of(upper, lower), Projection.of(45, 180, 1, 0, 0, 5, 15))));
    }

    @Test
    void anyBoxBehindAnotherOnALineOfSightIsDrawnBeforeIt() {
        Random random = new Random(11);
        for (int round = 0; round < 40; round++) {
            List<TopViewModel.Box> boxes = randomBoxes(random, round);
            for (double turn = -180; turn < 180; turn += 7.5) {
                Projection p = Projection.of(50, turn, 1, 0, 0, 100, 100);
                List<TopViewModel.Box> order = PaintOrder.backToFront(boxes, p);
                assertEquals(boxes.size(), new HashSet<>(order).size(), "every box once");
                for (int i = 0; i < order.size(); i++) {
                    for (int j = i + 1; j < order.size(); j++) {
                        assertTrue(!isBehind(order.get(j), order.get(i), p),
                                order.get(j).fqn() + " is behind " + order.get(i).fqn() + " but drawn after it, turn "
                                        + turn);
                    }
                }
            }
        }
    }

    /** Boxes of different sizes in the cells of a grid, so that none overlaps another. */
    private static List<TopViewModel.Box> randomBoxes(Random random, int round) {
        List<TopViewModel.Box> boxes = new ArrayList<>();
        int columns = 2 + random.nextInt(4);
        int rows = 2 + random.nextInt(4);
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                if (random.nextInt(4) == 0) {
                    continue;
                }
                double w = 5 + random.nextDouble() * 35;
                double h = 5 + random.nextDouble() * 35;
                double x = c * 50 + random.nextDouble() * (45 - w);
                double y = r * 50 + random.nextDouble() * (45 - h);
                boxes.add(box(round + "-" + c + "-" + r, x, y, w, h));
            }
        }
        return boxes;
    }

    /**
     * Whether {@code a} lies behind {@code b}: there is a line of sight through
     * both, and on it {@code a} is further from the viewer.
     */
    private static boolean isBehind(TopViewModel.Box a, TopViewModel.Box b, Projection p) {
        double[] across = overlap(span(a, p, true), span(b, p, true));
        if (across == null || across[1] - across[0] < 1e-6) {
            return false;
        }
        double u = (across[0] + across[1]) / 2;
        double[] nearA = along(a, p, u);
        double[] nearB = along(b, p, u);
        return nearA != null && nearB != null && nearA[1] <= nearB[0] + 1e-9;
    }

    /** The coordinates of the corners across the view (u) or toward the viewer (v). */
    private static double[][] corners(TopViewModel.Box box, Projection p) {
        double[][] xy = {{box.x(), box.y()}, {box.x() + box.w(), box.y()},
                {box.x() + box.w(), box.y() + box.h()}, {box.x(), box.y() + box.h()}};
        double[][] uv = new double[4][];
        for (int i = 0; i < 4; i++) {
            double dx = xy[i][0] - p.cx();
            double dy = xy[i][1] - p.cy();
            uv[i] = new double[]{dx * p.cosTurn() - dy * p.sinTurn(), dx * p.sinTurn() + dy * p.cosTurn()};
        }
        return uv;
    }

    private static double[] span(TopViewModel.Box box, Projection p, boolean acrossTheView) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] c : corners(box, p)) {
            double value = acrossTheView ? c[0] : c[1];
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new double[]{min, max};
    }

    private static double[] overlap(double[] a, double[] b) {
        double low = Math.max(a[0], b[0]);
        double high = Math.min(a[1], b[1]);
        return low < high ? new double[]{low, high} : null;
    }

    /** Where the line of sight at {@code u} runs through the box, toward the viewer. */
    private static double[] along(TopViewModel.Box box, Projection p, double u) {
        double[][] c = corners(box, p);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double[] s = c[i];
            double[] e = c[(i + 1) % 4];
            if ((s[0] - u) * (e[0] - u) > 0 || s[0] == e[0]) {
                continue;
            }
            double v = s[1] + (u - s[0]) / (e[0] - s[0]) * (e[1] - s[1]);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return min <= max ? new double[]{min, max} : null;
    }
}
