package de.weigend.s202.ui.rendering;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TangleEdgeRendererTest {

    @Test
    void narrowLaneChannelKeepsFixedPitch() {
        List<Double> lanes = TangleEdgeRenderer.lanePositions(0.0, 10.0);

        assertEquals(5, lanes.size());
        assertEquals(List.of(-7.0, -1.0, 5.0, 11.0, 17.0), lanes);
    }

    @Test
    void regularLaneChannelKeepsSameFixedPitch() {
        List<Double> lanes = TangleEdgeRenderer.lanePositions(10.0, 30.0);

        assertEquals(5, lanes.size());
        assertEquals(List.of(8.0, 14.0, 20.0, 26.0, 32.0), lanes);
    }

    @Test
    void horizontalAndVerticalLanePositionsUseSamePitch() {
        List<Double> horizontalYs = TangleEdgeRenderer.lanePositions(10.0, 30.0);
        List<Double> verticalXs = TangleEdgeRenderer.lanePositions(40.0, 60.0);

        for (int i = 1; i < horizontalYs.size(); i++) {
            assertEquals(6.0, horizontalYs.get(i) - horizontalYs.get(i - 1), 0.0001);
            assertEquals(6.0, verticalXs.get(i) - verticalXs.get(i - 1), 0.0001);
        }
    }

    @Test
    void emptyLaneChannelProducesNoLines() {
        assertTrue(TangleEdgeRenderer.lanePositions(10.0, 10.0).isEmpty());
        assertTrue(TangleEdgeRenderer.lanePositions(10.0, 9.0).isEmpty());
    }

    @Test
    void verticalLaneStopsAtNearestClassAbove() {
        Bounds source = new BoundingBox(40.0, 100.0, 20.0, 20.0);
        Bounds nearAbove = new BoundingBox(40.0, 60.0, 20.0, 20.0);
        Bounds farAbove = new BoundingBox(40.0, 20.0, 20.0, 20.0);
        Bounds sideClass = new BoundingBox(80.0, 80.0, 20.0, 20.0);

        double end = TangleEdgeRenderer.verticalLaneEnd(
                50.0, source.getCenterY(), List.of(source, nearAbove, farAbove, sideClass),
                source, 0.0, true);

        assertEquals(nearAbove.getMaxY(), end, 0.0001);
    }

    @Test
    void verticalLaneStopsAtNearestClassBelow() {
        Bounds source = new BoundingBox(40.0, 100.0, 20.0, 20.0);
        Bounds nearBelow = new BoundingBox(40.0, 150.0, 20.0, 20.0);
        Bounds farBelow = new BoundingBox(40.0, 200.0, 20.0, 20.0);
        Bounds sideClass = new BoundingBox(80.0, 130.0, 20.0, 20.0);

        double end = TangleEdgeRenderer.verticalLaneEnd(
                50.0, source.getCenterY(), List.of(source, nearBelow, farBelow, sideClass),
                source, 240.0, false);

        assertEquals(nearBelow.getMinY(), end, 0.0001);
    }

    @Test
    void verticalLaneUsesLimitWhenNoClassCollides() {
        Bounds source = new BoundingBox(40.0, 100.0, 20.0, 20.0);
        Bounds sideClass = new BoundingBox(80.0, 60.0, 20.0, 20.0);

        double end = TangleEdgeRenderer.verticalLaneEnd(
                50.0, source.getCenterY(), List.of(source, sideClass), source, 0.0, true);

        assertEquals(0.0, end, 0.0001);
    }
}
