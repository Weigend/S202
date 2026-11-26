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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which drawn face of a box the mouse is over. */
class FaceTest {

    @Test
    void aTiltedTopContainsWhatIsInsideIt() {
        // A top turned and squeezed: a parallelogram.
        Face face = new Face(null, new double[]{0, 100, 130, 30}, new double[]{0, 20, 80, 60});
        assertTrue(face.contains(65, 40));
        assertTrue(face.contains(5, 3));
        assertFalse(face.contains(0, 50), "left of it");
        assertFalse(face.contains(120, 10), "right above it");
    }

    @Test
    void itDoesNotMatterInWhichDirectionTheCornersRun() {
        Face clockwise = new Face(null, new double[]{0, 10, 10, 0}, new double[]{0, 0, 10, 10});
        Face counter = new Face(null, new double[]{0, 0, 10, 10}, new double[]{0, 10, 10, 0});
        assertTrue(clockwise.contains(5, 5));
        assertTrue(counter.contains(5, 5));
        assertFalse(clockwise.contains(15, 5));
        assertFalse(counter.contains(15, 5));
    }

    @Test
    void aSideSeenEdgeOnIsNotHit() {
        Face flat = new Face(null, new double[]{0, 10, 10, 0}, new double[]{5, 5, 5, 5});
        assertFalse(flat.contains(5, 5));
    }
}
