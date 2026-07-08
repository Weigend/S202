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
        List<Double> lanes = TangleLaneLayout.lanePositions(0.0, 10.0, 7);

        assertTrue(lanes.isEmpty());
    }

    @Test
    void regularLaneChannelDropsTracksOutsideGap() {
        List<Double> lanes = TangleLaneLayout.lanePositions(10.0, 30.0, 7);

        assertEquals(1, lanes.size());
        assertEquals(List.of(20.0), lanes);
    }

    @Test
    void wideLaneChannelKeepsVisibleFiveTracksWhenOnlyFiveFit() {
        List<Double> lanes = TangleLaneLayout.lanePositions(0.0, 40.0, 7);

        assertEquals(5, lanes.size());
        assertEquals(List.of(8.0, 14.0, 20.0, 26.0, 32.0), lanes);
    }

    @Test
    void outerLaneChannelKeepsAllSevenTracks() {
        List<Double> lanes = TangleLaneLayout.lanePositions(0.0, 52.0, 7);

        assertEquals(7, lanes.size());
        assertEquals(List.of(8.0, 14.0, 20.0, 26.0, 32.0, 38.0, 44.0), lanes);
    }

    @Test
    void dynamicLaneCountScalesWithEdgeDensity() {
        List<Double> sparse = TangleLaneLayout.lanePositions(0.0, 100.0, 3);
        List<Double> dense  = TangleLaneLayout.lanePositions(0.0, 100.0, 10);

        assertEquals(3, sparse.size());
        assertEquals(10, dense.size());
        // pitch is always LANE_SPACING_PX regardless of count
        assertEquals(6.0, sparse.get(1) - sparse.get(0), 0.0001);
        assertEquals(6.0, dense.get(1) - dense.get(0), 0.0001);
    }

    @Test
    void horizontalAndVerticalLanePositionsUseSamePitch() {
        List<Double> horizontalYs = TangleLaneLayout.lanePositions(0.0, 52.0, 7);
        List<Double> verticalXs = TangleLaneLayout.lanePositions(40.0, 92.0, 7);

        for (int i = 1; i < horizontalYs.size(); i++) {
            assertEquals(6.0, horizontalYs.get(i) - horizontalYs.get(i - 1), 0.0001);
            assertEquals(6.0, verticalXs.get(i) - verticalXs.get(i - 1), 0.0001);
        }
    }

    @Test
    void emptyLaneChannelProducesNoLines() {
        assertTrue(TangleLaneLayout.lanePositions(10.0, 10.0, 7).isEmpty());
        assertTrue(TangleLaneLayout.lanePositions(10.0, 9.0, 7).isEmpty());
        assertTrue(TangleLaneLayout.lanePositions(10.0, 13.0, 7).isEmpty());
    }

    @Test
    void verticalLaneStopsAtNearestClassAbove() {
        Bounds source = new BoundingBox(40.0, 100.0, 20.0, 20.0);
        Bounds nearAbove = new BoundingBox(40.0, 60.0, 20.0, 20.0);
        Bounds farAbove = new BoundingBox(40.0, 20.0, 20.0, 20.0);
        Bounds sideClass = new BoundingBox(80.0, 80.0, 20.0, 20.0);

        double end = TangleLaneLayout.verticalLaneEnd(
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

        double end = TangleLaneLayout.verticalLaneEnd(
                50.0, source.getCenterY(), List.of(source, nearBelow, farBelow, sideClass),
                source, 240.0, false);

        assertEquals(nearBelow.getMinY(), end, 0.0001);
    }

    @Test
    void verticalLaneUsesLimitWhenNoClassCollides() {
        Bounds source = new BoundingBox(40.0, 100.0, 20.0, 20.0);
        Bounds sideClass = new BoundingBox(80.0, 60.0, 20.0, 20.0);

        double end = TangleLaneLayout.verticalLaneEnd(
                50.0, source.getCenterY(), List.of(source, sideClass), source, 0.0, true);

        assertEquals(0.0, end, 0.0001);
    }

    @Test
    void dockPointUsesTopOrBottomEdgeForVerticalApproach() {
        Bounds box = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        TangleGeometry.Point top = TangleGeometry.dockPoint(box, 52.0, 80.0);
        TangleGeometry.Point bottom = TangleGeometry.dockPoint(box, 52.0, 140.0);

        assertEquals(52.0, top.x(), 0.0001);
        assertEquals(box.getMinY(), top.y(), 0.0001);
        assertEquals(52.0, bottom.x(), 0.0001);
        assertEquals(box.getMaxY(), bottom.y(), 0.0001);
    }

    @Test
    void dockPointUsesSideEdgeForSameRowApproach() {
        Bounds box = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        TangleGeometry.Point left = TangleGeometry.dockPoint(box, 45.0, box.getCenterY());
        TangleGeometry.Point right = TangleGeometry.dockPoint(box, 55.0, box.getCenterY());

        assertEquals(box.getMinX(), left.x(), 0.0001);
        assertEquals(box.getCenterY(), left.y(), 0.0001);
        assertEquals(box.getMaxX(), right.x(), 0.0001);
        assertEquals(box.getCenterY(), right.y(), 0.0001);
    }

    @Test
    void edgePointClipsFallbackLineToBoxPerimeter() {
        Bounds box = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        TangleGeometry.Point right = TangleGeometry.edgePoint(box, 100.0, 110.0);
        TangleGeometry.Point top = TangleGeometry.edgePoint(box, 50.0, 20.0);

        assertEquals(box.getMaxX(), right.x(), 0.0001);
        assertEquals(box.getCenterY(), right.y(), 0.0001);
        assertEquals(box.getCenterX(), top.x(), 0.0001);
        assertEquals(box.getMinY(), top.y(), 0.0001);
    }

    @Test
    void edgePointClipsDiagonalFallbackLineToNearestSide() {
        Bounds box = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        TangleGeometry.Point point = TangleGeometry.edgePoint(box, 90.0, 140.0);

        assertEquals(box.getMaxX(), point.x(), 0.0001);
        assertEquals(117.5, point.y(), 0.0001);
    }

    @Test
    void horizontalRangesOnlyConflictWhenTheyOverlap() {
        TangleGeometry.Range left = TangleGeometry.Range.of(10.0, 30.0);
        TangleGeometry.Range right = TangleGeometry.Range.of(40.0, 60.0);
        TangleGeometry.Range overlapping = TangleGeometry.Range.of(25.0, 45.0);

        assertFalse(left.overlaps(right));
        assertTrue(left.overlaps(overlapping));
        assertTrue(right.overlaps(overlapping));
    }

    @Test
    void horizontalSegmentRejectsClassIntersections() {
        Bounds classBox = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        assertTrue(TangleRouter.horizontalSegmentHitsClass(
                110.0, 10.0, 80.0, List.of(classBox)));
        assertFalse(TangleRouter.horizontalSegmentHitsClass(
                80.0, 10.0, 80.0, List.of(classBox)));
        assertFalse(TangleRouter.horizontalSegmentHitsClass(
                110.0, 10.0, 30.0, List.of(classBox)));
    }

    @Test
    void verticalSegmentRejectsClassIntersections() {
        Bounds classBox = new BoundingBox(40.0, 100.0, 20.0, 20.0);

        assertTrue(TangleRouter.verticalSegmentHitsClass(
                50.0, 80.0, 140.0, List.of(classBox), null, null));
        assertFalse(TangleRouter.verticalSegmentHitsClass(
                30.0, 80.0, 140.0, List.of(classBox), null, null));
        assertFalse(TangleRouter.verticalSegmentHitsClass(
                50.0, 80.0, 90.0, List.of(classBox), null, null));
    }

    @Test
    void verticalTrackOccupationIsSegmentBased() {
        Bounds owner = new BoundingBox(40.0, 100.0, 20.0, 20.0);
        TangleGeometry.VerticalTrack track = new TangleGeometry.VerticalTrack(
                owner, 50.0, owner.getCenterY(), 0.0, 240.0);

        track.occupy(80.0);

        assertFalse(track.canOccupy(70.0));
        assertTrue(track.canOccupy(140.0));
    }
}
