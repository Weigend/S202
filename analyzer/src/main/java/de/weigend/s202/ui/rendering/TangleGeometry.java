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

import de.weigend.s202.domain.DependencyEdge;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Value types and pure geometry helpers shared by the tangle lane layout,
 * router and painter. Holds no renderer state; everything is passed in.
 */
final class TangleGeometry {

    static final double ARROW_SIZE = 6.0;
    /** Fixed distance between adjacent potential lanes in both X and Y direction. */
    static final double LANE_SPACING_PX = 6.0;
    static final double TRACK_CLEARANCE_PX = 1.0;
    /** Left/right margin for bypass routes that detour around all class boxes. */
    static final double BYPASS_MARGIN = 24.0;

    private TangleGeometry() {
    }

    static Point dockPoint(Bounds box, double trackX, double horizontalY) {
        double x = clamp(trackX, box.getMinX(), box.getMaxX());
        if (horizontalY < box.getCenterY()) {
            return new Point(x, box.getMinY());
        }
        if (horizontalY > box.getCenterY()) {
            return new Point(x, box.getMaxY());
        }
        if (trackX < box.getCenterX()) {
            return new Point(box.getMinX(), box.getCenterY());
        }
        if (trackX > box.getCenterX()) {
            return new Point(box.getMaxX(), box.getCenterY());
        }
        return new Point(x, box.getCenterY());
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    static Point edgePoint(Bounds box, double towardX, double towardY) {
        double cx = box.getCenterX();
        double cy = box.getCenterY();
        double dx = towardX - cx;
        double dy = towardY - cy;
        if (Math.abs(dx) < 0.0001 && Math.abs(dy) < 0.0001) {
            return new Point(cx, cy);
        }

        double halfW = box.getWidth() / 2.0;
        double halfH = box.getHeight() / 2.0;
        double scale = 1.0 / Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH);
        return new Point(cx + dx * scale, cy + dy * scale);
    }

    static List<VerticalSegment> collectVerticalSegments(List<RoutedTangleEdge> routed) {
        List<VerticalSegment> segments = new ArrayList<>();
        for (RoutedTangleEdge edge : routed) {
            segments.add(new VerticalSegment(edge.sourceTrack.x, edge.source.getCenterY(), edge.horizontal.y, edge));
            segments.add(new VerticalSegment(edge.targetTrack.x, edge.target.getCenterY(), edge.horizontal.y, edge));
        }
        return segments;
    }

    // Use the actual class-box extents as the bypass anchor, not the full
    // content bounds. The content padding provides space between classXLeft
    // and xLeft (or xRight) so routes stay within the visible area.
    static BypassAllocator bypassAllocator(LaneLayout laneLayout) {
        double classXLeft  = laneLayout.classBounds.isEmpty() ? laneLayout.xLeft
                : laneLayout.classBounds.stream().mapToDouble(Bounds::getMinX).min().orElse(laneLayout.xLeft);
        double classXRight = laneLayout.classBounds.isEmpty() ? laneLayout.xRight
                : laneLayout.classBounds.stream().mapToDouble(Bounds::getMaxX).max().orElse(laneLayout.xRight);
        return new BypassAllocator(classXLeft, classXRight);
    }

    static Bounds overlayBounds(Node node, Pane overlayPane) {
        if (node == null || overlayPane == null || node.getScene() == null || overlayPane.getScene() == null) {
            return null;
        }
        try {
            Bounds local = node.getBoundsInLocal();
            Bounds sceneBounds = node.localToScene(local);
            Bounds overlay = overlayPane.sceneToLocal(sceneBounds);
            if (!isUsable(overlay)) {
                return null;
            }
            return overlay;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isUsable(Bounds bounds) {
        return bounds != null
                && Double.isFinite(bounds.getMinX())
                && Double.isFinite(bounds.getMinY())
                && Double.isFinite(bounds.getWidth())
                && Double.isFinite(bounds.getHeight())
                && bounds.getWidth() > 1.0
                && bounds.getHeight() > 1.0;
    }

    static boolean isVisible(Node node) {
        if (node == null || !node.isVisible()) return false;
        Parent p = node.getParent();
        while (p != null) {
            if (!p.isVisible()) return false;
            p = p.getParent();
        }
        return true;
    }

    /**
     * Outcome of one routing pass: edges routed along the lane grid plus, in
     * original edge order, the edges the grid router could not place.
     */
    static final class RoutingResult {
        final List<RoutedTangleEdge> routed;
        final List<UnroutedEdge> unrouted;

        RoutingResult(List<RoutedTangleEdge> routed, List<UnroutedEdge> unrouted) {
            this.routed = routed;
            this.unrouted = unrouted;
        }
    }

    /**
     * An edge the grid router could not place. When source/target bounds are
     * known a {@link FallbackPath} may be attached; a {@code null} fallback
     * means the edge needs a bypass route. Without bounds ({@code source} is
     * {@code null}) the caller decides between waiting for layout and drawing
     * a plain straight line.
     */
    static final class UnroutedEdge {
        final DependencyEdge edge;
        final Bounds source;
        final Bounds target;
        final FallbackPath fallback;

        UnroutedEdge(DependencyEdge edge, Bounds source, Bounds target, FallbackPath fallback) {
            this.edge = edge;
            this.source = source;
            this.target = target;
            this.fallback = fallback;
        }
    }

    static final class LaneLayout {
        final List<HorizontalTrack> horizontalTracks;
        final Map<Bounds, List<VerticalTrack>> verticalTracks;
        final Map<String, Bounds> classBoundsByName;
        final List<Bounds> classBounds;
        final double xLeft;
        final double xRight;

        LaneLayout(List<HorizontalTrack> horizontalTracks,
                   Map<Bounds, List<VerticalTrack>> verticalTracks,
                   Map<String, Bounds> classBoundsByName,
                   List<Bounds> classBounds,
                   double xLeft,
                   double xRight) {
            this.horizontalTracks = horizontalTracks;
            this.verticalTracks = verticalTracks;
            this.classBoundsByName = classBoundsByName;
            this.classBounds = classBounds;
            this.xLeft = xLeft;
            this.xRight = xRight;
        }
    }

    static final class HorizontalTrack {
        final double y;
        final double xLeft;
        final double xRight;
        final List<Range> occupiedRanges = new ArrayList<>();

        HorizontalTrack(double y, double xLeft, double xRight) {
            this.y = y;
            this.xLeft = xLeft;
            this.xRight = xRight;
        }

        boolean canOccupy(double x1, double x2) {
            Range base = Range.of(x1, x2);
            if (base.min < xLeft || base.max > xRight) {
                return false;
            }
            Range candidate = base.inflate(TRACK_CLEARANCE_PX);
            for (Range occupied : occupiedRanges) {
                if (candidate.overlaps(occupied)) {
                    return false;
                }
            }
            return true;
        }

        void occupy(double x1, double x2) {
            occupiedRanges.add(Range.of(x1, x2).inflate(TRACK_CLEARANCE_PX));
        }

        int useCount() {
            return occupiedRanges.size();
        }
    }

    static final class Range {
        final double min;
        final double max;

        private Range(double min, double max) {
            this.min = min;
            this.max = max;
        }

        static Range of(double a, double b) {
            return new Range(Math.min(a, b), Math.max(a, b));
        }

        Range inflate(double value) {
            return new Range(min - value, max + value);
        }

        boolean overlaps(Range other) {
            return min < other.max && max > other.min;
        }
    }

    static final class VerticalTrack {
        final Bounds owner;
        final double x;
        final double centerY;
        final double topY;
        final double bottomY;
        final List<Range> occupiedRanges = new ArrayList<>();

        VerticalTrack(Bounds owner, double x, double centerY, double topY, double bottomY) {
            this.owner = owner;
            this.x = x;
            this.centerY = centerY;
            this.topY = Math.min(topY, centerY);
            this.bottomY = Math.max(bottomY, centerY);
        }

        boolean canOccupy(double y) {
            if (y < topY || y > bottomY) {
                return false;
            }
            Range candidate = segmentRange(y).inflate(TRACK_CLEARANCE_PX);
            for (Range occupied : occupiedRanges) {
                if (candidate.overlaps(occupied)) {
                    return false;
                }
            }
            return true;
        }

        void occupy(double y) {
            occupiedRanges.add(segmentRange(y).inflate(TRACK_CLEARANCE_PX));
        }

        int useCount() {
            return occupiedRanges.size();
        }

        private Range segmentRange(double y) {
            double dockY = dockPoint(owner, x, y).y;
            return Range.of(dockY, y);
        }
    }

    static final class RoutedTangleEdge {
        final DependencyEdge edge;
        final Bounds source;
        final Bounds target;
        final VerticalTrack sourceTrack;
        final HorizontalTrack horizontal;
        final VerticalTrack targetTrack;

        RoutedTangleEdge(DependencyEdge edge, Bounds source, Bounds target,
                         VerticalTrack sourceTrack, HorizontalTrack horizontal,
                         VerticalTrack targetTrack) {
            this.edge = edge;
            this.source = source;
            this.target = target;
            this.sourceTrack = sourceTrack;
            this.horizontal = horizontal;
            this.targetTrack = targetTrack;
        }
    }

    static final class VerticalSegment {
        final double x;
        final double y1;
        final double y2;
        final RoutedTangleEdge owner;

        VerticalSegment(double x, double y1, double y2, RoutedTangleEdge owner) {
            this.x = x;
            this.y1 = y1;
            this.y2 = y2;
            this.owner = owner;
        }

        boolean containsY(double y) {
            return y >= Math.min(y1, y2) && y <= Math.max(y1, y2);
        }
    }

    static final class FallbackPath {
        final Point sourceDock;
        final Point targetDock;
        final double sourceX;
        final double targetX;
        final double y;

        FallbackPath(Point sourceDock, Point targetDock, double sourceX, double targetX, double y) {
            this.sourceDock = sourceDock;
            this.targetDock = targetDock;
            this.sourceX = sourceX;
            this.targetX = targetX;
            this.y = y;
        }
    }

    /** Allocates outer-column X positions for bypass routes, one per render pass. */
    static final class BypassAllocator {
        final double xLeft;
        final double xRight;
        private int leftCount = 0;
        private int rightCount = 0;

        BypassAllocator(double xLeft, double xRight) {
            this.xLeft = xLeft;
            this.xRight = xRight;
        }

        double nextLeft()  { return xLeft  - BYPASS_MARGIN - leftCount++  * LANE_SPACING_PX; }
        double nextRight() { return xRight + BYPASS_MARGIN + rightCount++ * LANE_SPACING_PX; }
    }

    record Point(double x, double y) {}
}
