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

import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The parallel projection of the 2.5D view. */
class ProjectionTest {

    private static final double EPS = 1e-9;

    @Test
    void fromAboveItIsThePictureMovedAndZoomed() {
        Projection p = Projection.of(0, 0, 2, 100, 50, 10, 20);
        assertEquals(100, p.sx(10, 20), EPS);
        assertEquals(50, p.sy(10, 20, 0), EPS);
        assertEquals(110, p.sx(15, 20), EPS);
        assertEquals(60, p.sy(10, 25, 0), EPS);
        assertEquals(50, p.sy(10, 20, 30), EPS, "from above a height cannot be seen");
    }

    @Test
    void tiltedTheGroundShrinksAndHeightsRise() {
        Projection p = Projection.of(60, 0, 1, 0, 0, 0, 0);
        assertEquals(10 * Math.cos(Math.toRadians(60)), p.sy(0, 10, 0), EPS);
        assertEquals(-10 * Math.sin(Math.toRadians(60)), p.sy(0, 0, 10), EPS, "higher is further up on screen");
    }

    @Test
    void turnedAQuarterTheXAxisPointsTowardTheViewer() {
        Projection p = Projection.of(0, 90, 1, 0, 0, 0, 0);
        assertEquals(0, p.sx(1, 0), EPS);
        assertEquals(1, p.sy(1, 0, 0), EPS);
    }

    @Test
    void theTransformOfAPlaneDrawsItsPointsWhereTheProjectionPutsThem() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            Projection p = Projection.of(random.nextDouble() * 80, random.nextDouble() * 360 - 180,
                    0.1 + random.nextDouble() * 5, random.nextDouble() * 800, random.nextDouble() * 600,
                    random.nextDouble() * 1000, random.nextDouble() * 1000);
            double x = random.nextDouble() * 1000;
            double y = random.nextDouble() * 1000;
            double h = random.nextDouble() * 200;
            Point2D drawn = p.affine(h).transform(x, y);
            assertEquals(p.sx(x, y), drawn.getX(), 1e-6);
            assertEquals(p.sy(x, y, h), drawn.getY(), 1e-6);
            double[] back = p.plane(p.sx(x, y), p.sy(x, y, h), h);
            assertEquals(x, back[0], 1e-6, "the point under the mouse on a top");
            assertEquals(y, back[1], 1e-6);
        }
    }
}
