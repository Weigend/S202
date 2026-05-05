package de.weigend.s202.ui.rendering;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TangleEdgeRendererTest {

    @Test
    void narrowLaneChannelOnlyUsesTracksInsideGap() {
        List<Double> lanes = TangleEdgeRenderer.lanePositions(0.0, 10.0, 7);

        assertTrue(lanes.isEmpty());
    }

    @Test
    void regularLaneChannelDropsTracksOutsideGap() {
        List<Double> lanes = TangleEdgeRenderer.lanePositions(10.0, 30.0, 7);

        assertEquals(1, lanes.size());
        assertEquals(List.of(20.0), lanes);
    }

    @Test
    void wideLaneChannelKeepsVisibleFiveTracksWhenOnlyFiveFit() {
        List<Double> lanes = TangleEdgeRenderer.lanePositions(0.0, 40.0, 7);

        assertEquals(5, lanes.size());
        assertEquals(List.of(8.0, 14.0, 20.0, 26.0, 32.0), lanes);
    }

    @Test
    void outerLaneChannelKeepsAllSevenTracks() {
        List<Double> lanes = TangleEdgeRenderer.lanePositions(0.0, 52.0, 7);

        assertEquals(7, lanes.size());
        assertEquals(List.of(8.0, 14.0, 20.0, 26.0, 32.0, 38.0, 44.0), lanes);
    }

    @Test
    void dynamicLaneCountScalesWithEdgeDensity() {
        List<Double> sparse = TangleEdgeRenderer.lanePositions(0.0, 100.0, 3);
        List<Double> dense  = TangleEdgeRenderer.lanePositions(0.0, 100.0, 10);

        assertEquals(3, sparse.size());
        assertEquals(10, dense.size());
        // pitch is always LANE_SPACING_PX regardless of count
        assertEquals(6.0, sparse.get(1) - sparse.get(0), 0.0001);
        assertEquals(6.0, dense.get(1) - dense.get(0), 0.0001);
    }

    @Test
    void horizontalAndVerticalLanePositionsUseSamePitch() {
        List<Double> horizontalYs = TangleEdgeRenderer.lanePositions(0.0, 52.0, 7);
        List<Double> verticalXs = TangleEdgeRenderer.lanePositions(40.0, 92.0, 7);

        for (int i = 1; i < horizontalYs.size(); i++) {
            assertEquals(6.0, horizontalYs.get(i) - horizontalYs.get(i - 1), 0.0001);
            assertEquals(6.0, verticalXs.get(i) - verticalXs.get(i - 1), 0.0001);
        }
    }

    @Test
    void emptyLaneChannelProducesNoLines() {
        assertTrue(TangleEdgeRenderer.lanePositions(10.0, 10.0, 7).isEmpty());
        assertTrue(TangleEdgeRenderer.lanePositions(10.0, 9.0, 7).isEmpty());
        assertTrue(TangleEdgeRenderer.lanePositions(10.0, 13.0, 7).isEmpty());
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

    @Test
    void dockPointUsesTopOrBottomEdgeForVerticalApproach() {
        Bounds box = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        TangleEdgeRenderer.Point top = TangleEdgeRenderer.dockPoint(box, 52.0, 80.0);
        TangleEdgeRenderer.Point bottom = TangleEdgeRenderer.dockPoint(box, 52.0, 140.0);

        assertEquals(52.0, top.x(), 0.0001);
        assertEquals(box.getMinY(), top.y(), 0.0001);
        assertEquals(52.0, bottom.x(), 0.0001);
        assertEquals(box.getMaxY(), bottom.y(), 0.0001);
    }

    @Test
    void dockPointUsesSideEdgeForSameRowApproach() {
        Bounds box = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        TangleEdgeRenderer.Point left = TangleEdgeRenderer.dockPoint(box, 45.0, box.getCenterY());
        TangleEdgeRenderer.Point right = TangleEdgeRenderer.dockPoint(box, 55.0, box.getCenterY());

        assertEquals(box.getMinX(), left.x(), 0.0001);
        assertEquals(box.getCenterY(), left.y(), 0.0001);
        assertEquals(box.getMaxX(), right.x(), 0.0001);
        assertEquals(box.getCenterY(), right.y(), 0.0001);
    }

    @Test
    void edgePointClipsFallbackLineToBoxPerimeter() {
        Bounds box = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        TangleEdgeRenderer.Point right = TangleEdgeRenderer.edgePoint(box, 100.0, 110.0);
        TangleEdgeRenderer.Point top = TangleEdgeRenderer.edgePoint(box, 50.0, 20.0);

        assertEquals(box.getMaxX(), right.x(), 0.0001);
        assertEquals(box.getCenterY(), right.y(), 0.0001);
        assertEquals(box.getCenterX(), top.x(), 0.0001);
        assertEquals(box.getMinY(), top.y(), 0.0001);
    }

    @Test
    void edgePointClipsDiagonalFallbackLineToNearestSide() {
        Bounds box = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        TangleEdgeRenderer.Point point = TangleEdgeRenderer.edgePoint(box, 90.0, 140.0);

        assertEquals(box.getMaxX(), point.x(), 0.0001);
        assertEquals(117.5, point.y(), 0.0001);
    }

    @Test
    void horizontalRangesOnlyConflictWhenTheyOverlap() {
        TangleEdgeRenderer.Range left = TangleEdgeRenderer.Range.of(10.0, 30.0);
        TangleEdgeRenderer.Range right = TangleEdgeRenderer.Range.of(40.0, 60.0);
        TangleEdgeRenderer.Range overlapping = TangleEdgeRenderer.Range.of(25.0, 45.0);

        assertFalse(left.overlaps(right));
        assertTrue(left.overlaps(overlapping));
        assertTrue(right.overlaps(overlapping));
    }

    @Test
    void horizontalSegmentRejectsClassIntersections() {
        Bounds classBox = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        assertTrue(TangleEdgeRenderer.horizontalSegmentHitsClass(
                110.0, 10.0, 80.0, List.of(classBox)));
        assertFalse(TangleEdgeRenderer.horizontalSegmentHitsClass(
                80.0, 10.0, 80.0, List.of(classBox)));
        assertFalse(TangleEdgeRenderer.horizontalSegmentHitsClass(
                110.0, 10.0, 30.0, List.of(classBox)));
    }

    @Test
    void verticalSegmentRejectsClassIntersections() {
        Bounds classBox = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        assertTrue(TangleEdgeRenderer.verticalSegmentHitsClass(
                50.0, 80.0, 140.0, List.of(classBox), null, null));
        assertFalse(TangleEdgeRenderer.verticalSegmentHitsClass(
                30.0, 80.0, 140.0, List.of(classBox), null, null));
        assertFalse(TangleEdgeRenderer.verticalSegmentHitsClass(
                50.0, 80.0, 90.0, List.of(classBox), null, null));
    }

    @Test
    void verticalTrackOccupationIsSegmentBased() {
        Bounds owner = new BoundingBox(40.0, 100.0, 20.0, 20.0);
        TangleEdgeRenderer.VerticalTrack track = new TangleEdgeRenderer.VerticalTrack(
                owner, 50.0, owner.getCenterY(), 0.0, 240.0);

        track.occupy(80.0);

        assertFalse(track.canOccupy(70.0));
        assertTrue(track.canOccupy(140.0));
    }
}
