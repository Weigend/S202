package de.weigend.s202.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
