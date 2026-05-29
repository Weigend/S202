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
package de.weigend.s202.ui.wfx.view3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlyCameraTest {

    @Test
    void worldPointVisibilityFollowsCameraOrientation() {
        FlyCamera camera = new FlyCamera();
        camera.resetToLookAt(0, -400, -600, 0, 0, 0);

        assertTrue(camera.isWorldPointVisible(0, 0, 0, 800, 600));
        assertFalse(camera.isWorldPointVisible(10_000, 0, 0, 800, 600));
        assertFalse(camera.isWorldPointVisible(0, -400, -1_200, 800, 600));

        camera.resetToLookAt(0, -400, -600, 10_000, 0, 0);
        assertTrue(camera.isWorldPointVisible(10_000, 0, 0, 800, 600));
    }
}
