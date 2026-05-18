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
package de.weigend.s202.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the drag controller's slot-picking arithmetic.
 * Keeps the test free of a JavaFX runtime — bounds-based picking is
 * exercised in the JavaFX-driven integration test {@code mvn javafx:run}.
 */
class ArchitectureDragControllerTest {

    @Test
    void pointerLeftOfFirstChildPicksSlotZero() {
        List<Double> midpoints = List.of(50.0, 150.0, 250.0);

        assertEquals(0, ArchitectureDragController.slotIndexForMidpoints(midpoints, 10.0));
    }

    @Test
    void pointerBetweenChildrenPicksRightChildIndex() {
        List<Double> midpoints = List.of(50.0, 150.0, 250.0);

        assertEquals(1, ArchitectureDragController.slotIndexForMidpoints(midpoints, 100.0));
        assertEquals(2, ArchitectureDragController.slotIndexForMidpoints(midpoints, 200.0));
    }

    @Test
    void pointerRightOfLastChildPicksTrailingSlot() {
        List<Double> midpoints = List.of(50.0, 150.0, 250.0);

        assertEquals(3, ArchitectureDragController.slotIndexForMidpoints(midpoints, 300.0));
    }

    @Test
    void pointerExactlyOnMidpointTreatsItAsRightSideOfPreviousChild() {
        List<Double> midpoints = List.of(50.0, 150.0);

        // sceneX == midpoint of child[1] → "not strictly less than 150" → falls
        // through to slot 2 (after both children). This is the boundary
        // contract: insert markers don't flicker at the centre line.
        assertEquals(2, ArchitectureDragController.slotIndexForMidpoints(midpoints, 150.0));
    }

    @Test
    void emptyRowAlwaysPicksSlotZero() {
        assertEquals(0, ArchitectureDragController.slotIndexForMidpoints(List.of(), 42.0));
    }

    @Test
    void sourceAdjacentSlotsAreNoOpPositionsButStillTargetable() {
        assertTrue(ArchitectureDragController.isCurrentAdjacentSlot(1, 1));
        assertTrue(ArchitectureDragController.isCurrentAdjacentSlot(1, 2));
        assertFalse(ArchitectureDragController.isCurrentAdjacentSlot(1, 0));
        assertFalse(ArchitectureDragController.isCurrentAdjacentSlot(1, 3));
    }
}
