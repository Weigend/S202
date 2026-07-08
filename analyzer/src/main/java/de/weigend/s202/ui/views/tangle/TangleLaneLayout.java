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
package de.weigend.s202.ui.views.tangle;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the routing lane grid for one tangle render pass and, when enabled,
 * draws the matching debug lines onto the overlay pane.
 */
final class TangleLaneLayout {

    /** Debug lane constants */
    private static final Color  LANE_COLOR      = Color.web("#00bcd4", 0.30);
    private static final double LANE_WIDTH      = 0.7;
    /** Minimum lanes per channel even when no edges cross it (fallback capacity). */
    private static final int MIN_LANE_COUNT = 3;
    private static final double LANE_EDGE_PADDING_PX = TangleGeometry.ARROW_SIZE + 2.0;
    /** Nodes within this Y-distance belong to the same layout row. */
    private static final double ROW_CLUSTER_PX  = 20.0;

    private final Pane pane;
    private final Map<String, Node> elementRegistry;
    private final List<DependencyEdge> edges;
    private final boolean showDebugLines;

    private boolean layoutPending;

    TangleLaneLayout(Pane pane, Map<String, Node> elementRegistry,
                     List<DependencyEdge> edges, boolean showDebugLines) {
        this.pane = pane;
        this.elementRegistry = elementRegistry;
        this.edges = edges;
        this.showDebugLines = showDebugLines;
    }

    /** True when usable bounds were still missing and the caller should retry after layout. */
    boolean isLayoutPending() {
        return layoutPending;
    }

    /**
     * Draws horizontal debug lanes above, below and between layout rows.
     * Rows are detected by clustering the Y-extents of all visible
     * {@link LevelClassBox} and {@link LevelPackageBox} nodes.
     * Within each gap, lanes keep the same pitch used by vertical lanes.
     */
    TangleGeometry.LaneLayout build(Pane zoomableContent, Pane overlayPane) {
        // Collect Y-extents of all visible layout nodes in overlay coordinates.
        List<Double> yCenters = new ArrayList<>();
        List<Bounds> classBounds = new ArrayList<>();
        Map<String, Bounds> classBoundsByName = new HashMap<>();
        for (Map.Entry<String, Node> entry : elementRegistry.entrySet()) {
            Node node = entry.getValue();
            if (!(node instanceof LevelClassBox) && !(node instanceof LevelPackageBox)) continue;
            if (!TangleGeometry.isVisible(node)) continue;
            Bounds b = TangleGeometry.overlayBounds(node, overlayPane);
            if (b != null) {
                // Use min and max Y so large package boxes contribute proper extents.
                yCenters.add(b.getMinY());
                yCenters.add(b.getMaxY());
                if (node instanceof LevelClassBox) {
                    classBounds.add(b);
                    classBoundsByName.put(entry.getKey(), b);
                }
            }
        }
        if (yCenters.size() < 2) return null;
        Collections.sort(yCenters);

        // Cluster into distinct horizontal rows.
        List<double[]> rows = new ArrayList<>();  // each entry: [minY, maxY]
        double grpMin = yCenters.get(0);
        double grpMax = yCenters.get(0);
        for (double y : yCenters) {
            if (y - grpMax > ROW_CLUSTER_PX) {
                rows.add(new double[]{grpMin, grpMax});
                grpMin = y;
            }
            grpMax = y;
        }
        rows.add(new double[]{grpMin, grpMax});

        // X-extent of all content in overlay coordinates.
        Bounds cb = TangleGeometry.overlayBounds(zoomableContent, overlayPane);
        if (cb == null) {
            layoutPending = true;
            return null;
        }
        double xLeft  = cb.getMinX();
        double xRight = cb.getMaxX();

        // Build the list of gaps: before first row, between rows, after last row.
        List<double[]> gaps = new ArrayList<>();
        gaps.add(new double[]{cb.getMinY(), rows.get(0)[0]});                 // above first row
        for (int r = 0; r < rows.size() - 1; r++) {
            gaps.add(new double[]{rows.get(r)[1], rows.get(r + 1)[0]});       // between rows
        }
        gaps.add(new double[]{rows.get(rows.size() - 1)[1], cb.getMaxY()});   // below last row

        List<TangleGeometry.HorizontalTrack> horizontalTracks = new ArrayList<>();
        // Lane count per gap scales with the number of edges whose endpoints
        // straddle that gap, bounded below by MIN_LANE_COUNT.
        for (double[] gap : gaps) {
            double top    = gap[0];
            double bottom = gap[1];
            int laneCount = clampLaneCount(countEdgesCrossing(top, bottom, classBoundsByName));
            for (double y : lanePositions(top, bottom, laneCount)) {
                horizontalTracks.add(new TangleGeometry.HorizontalTrack(y, xLeft, xRight));
                drawDebugLine(xLeft, y, xRight, y);
            }
        }

        Map<Bounds, List<TangleGeometry.VerticalTrack>> verticalTracks =
                drawVerticalDebugLanes(classBounds, classBoundsByName, cb.getMinY(), cb.getMaxY());
        return new TangleGeometry.LaneLayout(horizontalTracks, verticalTracks, classBoundsByName, classBounds, xLeft, xRight);
    }

    static List<Double> lanePositions(double min, double max, int count) {
        double firstAllowed = min + LANE_EDGE_PADDING_PX;
        double lastAllowed = max - LANE_EDGE_PADDING_PX;
        if (lastAllowed < firstAllowed) {
            return List.of();
        }

        List<Double> lanes = new ArrayList<>();
        double center = (min + max) / 2.0;
        double firstOffset = -TangleGeometry.LANE_SPACING_PX * (count - 1) / 2.0;
        for (int i = 0; i < count; i++) {
            double lane = center + firstOffset + i * TangleGeometry.LANE_SPACING_PX;
            if (lane >= firstAllowed && lane <= lastAllowed) {
                lanes.add(lane);
            }
        }
        return lanes;
    }

    private int countEdgesCrossing(double gapTop, double gapBottom, Map<String, Bounds> classBoundsByName) {
        int count = 0;
        for (DependencyEdge edge : edges) {
            Bounds s = classBoundsByName.get(edge.from());
            Bounds t = classBoundsByName.get(targetClassOf(edge.to()));
            if (s == null || t == null) continue;
            double sY = s.getCenterY();
            double tY = t.getCenterY();
            if (Math.min(sY, tY) < gapBottom && Math.max(sY, tY) > gapTop) {
                count++;
            }
        }
        return count;
    }

    private static int clampLaneCount(int count) {
        return Math.max(MIN_LANE_COUNT, count);
    }

    /**
     * Extracts the class FQN from a dependency edge {@code to} value.
     * Method-level edges encode the target as {@code "com.example.B|methodName"};
     * class-level edges are plain FQNs. The {@code |} separator is safe because
     * it never appears in Java class or method names.
     */
    static String targetClassOf(String to) {
        int pipe = to.indexOf('|');
        return pipe >= 0 ? to.substring(0, pipe) : to;
    }

    private Map<Bounds, List<TangleGeometry.VerticalTrack>> drawVerticalDebugLanes(List<Bounds> classBounds,
                                                                   Map<String, Bounds> classBoundsByName,
                                                                   double topLimit, double bottomLimit) {
        // Count how many edges touch each box (degree = in + out).
        Map<Bounds, Integer> degreeMap = new IdentityHashMap<>();
        for (DependencyEdge edge : edges) {
            Bounds s = classBoundsByName.get(edge.from());
            Bounds t = classBoundsByName.get(targetClassOf(edge.to()));
            if (s != null) degreeMap.merge(s, 1, Integer::sum);
            if (t != null) degreeMap.merge(t, 1, Integer::sum);
        }

        Map<Bounds, List<TangleGeometry.VerticalTrack>> out = new IdentityHashMap<>();
        for (Bounds source : classBounds) {
            int laneCount = clampLaneCount(degreeMap.getOrDefault(source, 0));
            double y = source.getCenterY();
            List<TangleGeometry.VerticalTrack> tracks = new ArrayList<>();
            for (double x : lanePositions(source.getMinX(), source.getMaxX(), laneCount)) {
                double top = verticalLaneEnd(x, y, classBounds, source, topLimit, true);
                double bottom = verticalLaneEnd(x, y, classBounds, source, bottomLimit, false);
                drawDebugLine(x, y, x, top);
                drawDebugLine(x, y, x, bottom);
                tracks.add(new TangleGeometry.VerticalTrack(source, x, y, top, bottom));
            }
            out.put(source, tracks);
        }
        return out;
    }

    static double verticalLaneEnd(double x, double startY, List<Bounds> obstacles,
                                  Bounds source, double limitY, boolean upward) {
        double end = limitY;
        for (Bounds obstacle : obstacles) {
            if (obstacle == source) continue;
            if (x <= obstacle.getMinX() || x >= obstacle.getMaxX()) continue;

            if (upward) {
                if (obstacle.getMaxY() <= startY && obstacle.getMaxY() > end) {
                    end = obstacle.getMaxY();
                }
            } else if (obstacle.getMinY() >= startY && obstacle.getMinY() < end) {
                end = obstacle.getMinY();
            }
        }
        return end;
    }

    private void drawDebugLine(double x1, double y1, double x2, double y2) {
        if (Math.abs(x2 - x1) < 0.0001 && Math.abs(y2 - y1) < 0.0001) {
            return;
        }
        if (!showDebugLines) {
            return;
        }
        Line lane = new Line(x1, y1, x2, y2);
        lane.setStroke(LANE_COLOR);
        lane.setStrokeWidth(LANE_WIDTH);
        lane.getStrokeDashArray().addAll(6.0, 4.0);
        lane.setMouseTransparent(true);
        pane.getChildren().add(lane);
    }
}
