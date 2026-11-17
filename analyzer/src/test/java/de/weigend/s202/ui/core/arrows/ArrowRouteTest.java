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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrowRouteTest {

    /** A box of a column: 60 wide around x = 100, between y = 40 and y = 60. */
    private static final double[] MIDDLE = {70, 40, 130, 60};
    private static final double[] LOWER = {70, 100, 130, 120};

    @Test
    void anArrowWithNothingOnItsLineStaysStraight() {
        ArrowRoute route = ArrowRoute.around(100, 0, 100, 30, List.of(MIDDLE), false);

        assertFalse(route.curved());
        assertInstanceOf(Line.class, route.toShape());
        assertEquals(Math.PI / 2, route.endAngle(), 1e-9, "it arrives heading down");
    }

    @Test
    void anArrowTakesAnArcAroundABoxOnItsLine() {
        ArrowRoute down = ArrowRoute.around(100, 0, 100, 100, List.of(MIDDLE), false);

        assertTrue(down.curved());
        assertInstanceOf(CubicCurve.class, down.toShape());
        double[] beside = down.at(0.5);
        assertEquals(50.0, beside[1], 1e-9);
        assertTrue(beside[0] >= 130 + 4, "clear of the box: " + beside[0]);
        assertEquals(100.0, down.at(0.0)[0], 1e-9, "it still starts and ends where the straight line does");
        assertEquals(100.0, down.at(1.0)[0], 1e-9);
    }

    @Test
    void arrowsOfOppositeDirectionPassAColumnOnOppositeSides() {
        ArrowRoute down = ArrowRoute.around(100, 0, 100, 100, List.of(MIDDLE), false);
        ArrowRoute up = ArrowRoute.around(100, 100, 100, 0, List.of(MIDDLE), false);

        assertTrue(down.at(0.5)[0] > 130);
        assertTrue(up.at(0.5)[0] < 70);
    }

    @Test
    void theArrowThatSkipsMoreBoxesTakesTheWiderArc() {
        // The first ends at LOWER, which is therefore not in its way.
        ArrowRoute one = ArrowRoute.around(100, 0, 100, 100, List.of(MIDDLE), false);
        ArrowRoute two = ArrowRoute.around(100, 0, 100, 160, List.of(MIDDLE, LOWER), false);

        assertTrue(two.at(0.5)[0] > one.at(0.5)[0] + 3);
    }

    @Test
    void anArrowPassesABoxOnTheNearerSideUnlessToldToKeepLeft() {
        // The line runs down at x = 75: the box reaches 5 to its right and 55 to its left.
        ArrowRoute nearer = ArrowRoute.around(75, 0, 75, 100, List.of(MIDDLE), false);
        ArrowRoute left = ArrowRoute.around(75, 0, 75, 100, List.of(MIDDLE), true);

        assertTrue(nearer.at(0.5)[0] < 70, "screen-left is nearer");
        assertTrue(left.at(0.5)[0] > 130, "left of an arrow heading down is screen-right");
    }
}
