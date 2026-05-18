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
import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tangle-edge overlay renderer.
 *
 * <p>Renders the intra-SCC (tangle) dependency edges as straight lines with
 * filled arrowheads. Source and target are looked up via the element registry;
 * lines run from box-centre to box-centre. Selection highlights a single edge.
 *
 * <p>Intentionally minimal — no grid, no channel graph, no routing.
 * The routing infrastructure lives on feature/tangle-routing-attempt and will
 * be re-introduced once the basic visualisation is solid.
 */
public class TangleEdgeRenderer {

    private static final Color NON_TANGLE_EDGE_COLOR = Color.web("#202020");
    private static final Color EDGE_COLOR     = Color.web("#ff5252");
    private static final Color EDGE_HOVER     = Color.web("#b71c1c");
    private static final Color SELECTED_COLOR = Color.web("#d50000");
    private static final Color CUT_EDGE_COLOR = Color.web("#ff9800");
    private static final Color APPLIED_CUT_EDGE_COLOR = Color.web("#2ecc71");
    private static final double NON_TANGLE_EDGE_WIDTH = 0.8;
    private static final double EDGE_WIDTH    = 1.2;
    private static final double SELECTED_WIDTH = 3.0;
    private static final double CUT_EDGE_WIDTH = 2.2;
    private static final double ARROW_SIZE    = 6.0;

    /** Debug lane constants */
    private static final Color  LANE_COLOR      = Color.web("#00bcd4", 0.30);
    private static final double LANE_WIDTH      = 0.7;
    /** Minimum lanes per channel even when no edges cross it (fallback capacity). */
    private static final int MIN_LANE_COUNT = 3;
    /** Maximum lanes per channel regardless of edge density (prevents visual clutter). */
    private static final int MAX_LANE_COUNT = 15;
    /** Fixed distance between adjacent potential lanes in both X and Y direction. */
    private static final double LANE_SPACING_PX = 6.0;
    private static final double LANE_EDGE_PADDING_PX = ARROW_SIZE + 2.0;
    private static final double TRACK_CLEARANCE_PX = 1.0;
    private static final double TRACK_REUSE_PENALTY = LANE_SPACING_PX * 2.0;
    private static final double BRIDGE_RADIUS = LANE_SPACING_PX / 2.5;
    /** Nodes within this Y-distance belong to the same layout row. */
    private static final double ROW_CLUSTER_PX  = 20.0;

    private final Pane pane;
    private final Map<String, Node> elementRegistry;
    private final Consumer<String> statusCallback;

    private List<DependencyEdge> edges = List.of();
    private String selectedFrom;
    private String selectedTo;
    private Set<DependencyEdge> cycleBreakEdges = Set.of();
    private Set<DependencyEdge> appliedCutEdges = Set.of();
    private Set<DependencyEdge> activeTangleEdges = Set.of();
    private Pane zoomableContent;
    private Pane overlayPane;
    private boolean showDebugLines = true;

    private int retriesLeft = 0;
    private static final int INITIAL_RETRIES = 8;
    private int settleRedrawsLeft = 0;
    private static final int INITIAL_SETTLE_REDRAWS = 3;
    private boolean layoutPending;

    private final javafx.beans.value.ChangeListener<Bounds> layoutListener =
            (obs, was, isNow) -> redraw();

    private java.util.function.BiConsumer<String, String> onEdgeClicked = (a, b) -> {};
    private java.util.function.BiConsumer<String, String> onEdgeCut = (a, b) -> {};
    private java.util.function.BiConsumer<String, String> onEdgeRestore = (a, b) -> {};

    public TangleEdgeRenderer(Pane pane, Map<String, Node> elementRegistry,
                               Consumer<String> statusCallback) {
        this.pane = Objects.requireNonNull(pane, "pane");
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry");
        this.statusCallback = Objects.requireNonNull(statusCallback, "statusCallback");
    }

    public void setCoordinateContext(Pane zoomableContent, Pane overlayPane) {
        if (this.zoomableContent != null) {
            this.zoomableContent.layoutBoundsProperty().removeListener(layoutListener);
        }
        this.zoomableContent = zoomableContent;
        this.overlayPane = overlayPane;
        if (zoomableContent != null) {
            zoomableContent.layoutBoundsProperty().addListener(layoutListener);
        }
    }

    public void setOnEdgeClicked(java.util.function.BiConsumer<String, String> handler) {
        this.onEdgeClicked = handler == null ? (a, b) -> {} : handler;
    }

    public void setOnEdgeCut(java.util.function.BiConsumer<String, String> handler) {
        this.onEdgeCut = handler == null ? (a, b) -> {} : handler;
    }

    public void setOnEdgeRestore(java.util.function.BiConsumer<String, String> handler) {
        this.onEdgeRestore = handler == null ? (a, b) -> {} : handler;
    }

    public void setEdges(List<DependencyEdge> edges) {
        this.edges = edges == null ? List.of() : List.copyOf(edges);
        recomputeActiveTangleEdges();
        retriesLeft = INITIAL_RETRIES;
        settleRedrawsLeft = INITIAL_SETTLE_REDRAWS;
        redraw();
    }

    public void setSelectedEdge(String from, String to) {
        this.selectedFrom = from;
        this.selectedTo = to;
        redraw();
    }

    public void setCycleBreakEdges(Set<DependencyEdge> cycleBreakEdges) {
        this.cycleBreakEdges = cycleBreakEdges == null ? Set.of() : Set.copyOf(cycleBreakEdges);
        redraw();
    }

    public void setAppliedCutEdges(Set<DependencyEdge> appliedCutEdges) {
        this.appliedCutEdges = appliedCutEdges == null ? Set.of() : Set.copyOf(appliedCutEdges);
        recomputeActiveTangleEdges();
        redraw();
    }

    public void setShowDebugLines(boolean showDebugLines) {
        if (this.showDebugLines == showDebugLines) {
            return;
        }
        this.showDebugLines = showDebugLines;
        redraw();
    }

    public void clear() {
        pane.getChildren().clear();
        edges = List.of();
        settleRedrawsLeft = 0;
    }

    public void requestRedraw() {
        retriesLeft = INITIAL_RETRIES;
        settleRedrawsLeft = INITIAL_SETTLE_REDRAWS;
        redraw();
    }

    // -------------------------------------------------------------------------

    private void redraw() {
        pane.getChildren().clear();
        layoutPending = false;
        if (zoomableContent == null || overlayPane == null || edges.isEmpty()) {
            return;
        }
        zoomableContent.applyCss();
        zoomableContent.layout();

        // Routing lanes are built first; visible debug lines, when enabled,
        // are drawn before edges so the dependencies stay on top.
        LaneLayout lanes = drawDebugLanes();

        boolean anyRendered = false;
        if (lanes != null) {
            RoutingResult routing = routeEdges(lanes);
            List<RoutedTangleEdge> routed = routing.routed;
            List<VerticalSegment> verticalSegments = collectVerticalSegments(routed);
            for (RoutedTangleEdge edge : routed) {
                paintRoutedEdge(edge, verticalSegments);
            }
            anyRendered = routing.anyRendered;
        } else {
            for (DependencyEdge edge : edges) {
                if (renderEdge(edge)) anyRendered = true;
            }
        }

        boolean needsSettleRedraw = settleRedrawsLeft > 0;
        if ((!anyRendered || layoutPending || needsSettleRedraw) && retriesLeft > 0) {
            if (needsSettleRedraw) {
                settleRedrawsLeft--;
            }
            scheduleRetry();
        }
    }

    /**
     * Draws horizontal debug lanes above, below and between layout rows.
     * Rows are detected by clustering the Y-extents of all visible
     * {@link LevelClassBox} and {@link LevelPackageBox} nodes.
     * Within each gap, lanes keep the same pitch used by vertical lanes.
     */
    private LaneLayout drawDebugLanes() {
        // Collect Y-extents of all visible layout nodes in overlay coordinates.
        List<Double> yCenters = new ArrayList<>();
        List<Bounds> classBounds = new ArrayList<>();
        Map<String, Bounds> classBoundsByName = new HashMap<>();
        for (Map.Entry<String, Node> entry : elementRegistry.entrySet()) {
            Node node = entry.getValue();
            if (!(node instanceof LevelClassBox) && !(node instanceof LevelPackageBox)) continue;
            if (!isVisible(node)) continue;
            Bounds b = overlayBounds(node);
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
        Bounds cb = overlayBounds(zoomableContent);
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

        List<HorizontalTrack> horizontalTracks = new ArrayList<>();
        // Lane count per gap scales with the number of edges whose endpoints
        // straddle that gap, clamped to [MIN_LANE_COUNT, MAX_LANE_COUNT].
        for (double[] gap : gaps) {
            double top    = gap[0];
            double bottom = gap[1];
            int laneCount = clampLaneCount(countEdgesCrossing(top, bottom, classBoundsByName));
            for (double y : lanePositions(top, bottom, laneCount)) {
                horizontalTracks.add(new HorizontalTrack(y, xLeft, xRight));
                drawDebugLine(xLeft, y, xRight, y);
            }
        }

        Map<Bounds, List<VerticalTrack>> verticalTracks = drawVerticalDebugLanes(classBounds, classBoundsByName, cb.getMinY(), cb.getMaxY());
        return new LaneLayout(horizontalTracks, verticalTracks, classBoundsByName, classBounds);
    }

    static List<Double> lanePositions(double min, double max, int count) {
        double firstAllowed = min + LANE_EDGE_PADDING_PX;
        double lastAllowed = max - LANE_EDGE_PADDING_PX;
        if (lastAllowed < firstAllowed) {
            return List.of();
        }

        List<Double> lanes = new ArrayList<>();
        double center = (min + max) / 2.0;
        double firstOffset = -LANE_SPACING_PX * (count - 1) / 2.0;
        for (int i = 0; i < count; i++) {
            double lane = center + firstOffset + i * LANE_SPACING_PX;
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
            Bounds t = classBoundsByName.get(edge.to());
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
        return Math.max(MIN_LANE_COUNT, Math.min(MAX_LANE_COUNT, count));
    }

    private Map<Bounds, List<VerticalTrack>> drawVerticalDebugLanes(List<Bounds> classBounds,
                                                                   Map<String, Bounds> classBoundsByName,
                                                                   double topLimit, double bottomLimit) {
        // Count how many edges touch each box (degree = in + out).
        Map<Bounds, Integer> degreeMap = new IdentityHashMap<>();
        for (DependencyEdge edge : edges) {
            Bounds s = classBoundsByName.get(edge.from());
            Bounds t = classBoundsByName.get(edge.to());
            if (s != null) degreeMap.merge(s, 1, Integer::sum);
            if (t != null) degreeMap.merge(t, 1, Integer::sum);
        }

        Map<Bounds, List<VerticalTrack>> out = new IdentityHashMap<>();
        for (Bounds source : classBounds) {
            int laneCount = clampLaneCount(degreeMap.getOrDefault(source, 0));
            double y = source.getCenterY();
            List<VerticalTrack> tracks = new ArrayList<>();
            for (double x : lanePositions(source.getMinX(), source.getMaxX(), laneCount)) {
                double top = verticalLaneEnd(x, y, classBounds, source, topLimit, true);
                double bottom = verticalLaneEnd(x, y, classBounds, source, bottomLimit, false);
                drawDebugLine(x, y, x, top);
                drawDebugLine(x, y, x, bottom);
                tracks.add(new VerticalTrack(source, x, y, top, bottom));
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

    private RoutingResult routeEdges(LaneLayout laneLayout) {
        List<RoutedTangleEdge> routed = new ArrayList<>();
        boolean anyRendered = false;
        for (DependencyEdge edge : edges) {
            Bounds source = laneLayout.classBoundsByName.get(edge.from());
            Bounds target = laneLayout.classBoundsByName.get(edge.to());
            if (source == null || target == null) {
                if (hasVisibleEndpointWaitingForLayout(edge)) {
                    layoutPending = true;
                } else if (renderEdge(edge)) {
                    anyRendered = true;
                }
                continue;
            }

            RoutedTangleEdge routedEdge = routeEdge(edge, source, target, laneLayout);
            if (routedEdge != null) {
                routed.add(routedEdge);
                anyRendered = true;
            } else {
                if (renderFallbackEdge(edge, source, target, laneLayout)) {
                    anyRendered = true;
                }
            }
        }
        return new RoutingResult(routed, anyRendered);
    }

    private RoutedTangleEdge routeEdge(DependencyEdge edge, Bounds source, Bounds target, LaneLayout laneLayout) {
        double idealY = (source.getCenterY() + target.getCenterY()) / 2.0;
        List<HorizontalTrack> horizontalCandidates = new ArrayList<>(laneLayout.horizontalTracks);
        horizontalCandidates.sort(Comparator.comparingDouble(h -> horizontalScore(h, idealY)));

        for (HorizontalTrack horizontal : horizontalCandidates) {
            List<VerticalTrack> sourceTracks = candidateVerticalTracks(laneLayout.verticalTracks.get(source), horizontal.y);
            List<VerticalTrack> targetTracks = candidateVerticalTracks(laneLayout.verticalTracks.get(target), horizontal.y);
            for (VerticalTrack sourceTrack : sourceTracks) {
                for (VerticalTrack targetTrack : targetTracks) {
                    if (!horizontal.canOccupy(sourceTrack.x, targetTrack.x)) continue;
                    if (horizontalSegmentHitsClass(horizontal.y, sourceTrack.x, targetTrack.x, laneLayout.classBounds)) continue;

                    horizontal.occupy(sourceTrack.x, targetTrack.x);
                    sourceTrack.occupy(horizontal.y);
                    targetTrack.occupy(horizontal.y);
                    return new RoutedTangleEdge(edge, source, target, sourceTrack, horizontal, targetTrack);
                }
            }
        }
        return null;
    }

    private static List<VerticalTrack> candidateVerticalTracks(List<VerticalTrack> tracks, double y) {
        if (tracks == null) return List.of();
        return tracks.stream()
                .filter(track -> track.canOccupy(y))
                .sorted(Comparator.comparingDouble(TangleEdgeRenderer::verticalScore))
                .toList();
    }

    /**
     * Like {@link #candidateVerticalTracks} but skips the occupancy check so
     * already-used tracks remain candidates for forced (double-occupancy) routing.
     * Physical reachability (track must span the horizontal Y) is still enforced.
     */
    private static List<VerticalTrack> candidateVerticalTracksForced(List<VerticalTrack> tracks, double y) {
        if (tracks == null) return List.of();
        return tracks.stream()
                .filter(track -> y >= track.topY && y <= track.bottomY)
                .sorted(Comparator.comparingDouble(TangleEdgeRenderer::verticalScore))
                .toList();
    }

    private static double horizontalScore(HorizontalTrack track, double idealY) {
        return Math.abs(track.y - idealY) + track.useCount() * TRACK_REUSE_PENALTY;
    }

    private static double verticalScore(VerticalTrack track) {
        return Math.abs(track.x - track.owner.getCenterX()) + track.useCount() * TRACK_REUSE_PENALTY;
    }

    static boolean horizontalSegmentHitsClass(double y, double x1, double x2, List<Bounds> classBounds) {
        return horizontalSegmentHitsClass(y, x1, x2, classBounds, null, null);
    }

    static boolean horizontalSegmentHitsClass(double y, double x1, double x2, List<Bounds> classBounds,
                                             Bounds allowedA, Bounds allowedB) {
        Range xRange = Range.of(x1, x2).inflate(TRACK_CLEARANCE_PX);
        for (Bounds box : classBounds) {
            if (box == allowedA || box == allowedB) {
                continue;
            }
            if (y <= box.getMinY() - TRACK_CLEARANCE_PX || y >= box.getMaxY() + TRACK_CLEARANCE_PX) {
                continue;
            }
            Range boxRange = new Range(
                    box.getMinX() - TRACK_CLEARANCE_PX,
                    box.getMaxX() + TRACK_CLEARANCE_PX);
            if (xRange.overlaps(boxRange)) {
                return true;
            }
        }
        return false;
    }

    static boolean verticalSegmentHitsClass(double x, double y1, double y2, List<Bounds> classBounds,
                                            Bounds allowedA, Bounds allowedB) {
        Range yRange = Range.of(y1, y2).inflate(TRACK_CLEARANCE_PX);
        for (Bounds box : classBounds) {
            if (box == allowedA || box == allowedB) {
                continue;
            }
            if (x <= box.getMinX() - TRACK_CLEARANCE_PX || x >= box.getMaxX() + TRACK_CLEARANCE_PX) {
                continue;
            }
            Range boxRange = new Range(
                    box.getMinY() - TRACK_CLEARANCE_PX,
                    box.getMaxY() + TRACK_CLEARANCE_PX);
            if (yRange.overlaps(boxRange)) {
                return true;
            }
        }
        return false;
    }

    private void paintRoutedEdge(RoutedTangleEdge routed, List<VerticalSegment> verticalSegments) {
        DependencyEdge edge = routed.edge;
        Color color = edgeColor(edge);
        double width = edgeWidth(edge);

        double y = routed.horizontal.y;
        Point sourceDock = dockPoint(routed.source, routed.sourceTrack.x, y);
        Point targetDock = dockPoint(routed.target, routed.targetTrack.x, y);

        Path path = new Path();
        path.setStroke(color);
        path.setStrokeWidth(width);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.MITER);
        path.setFill(null);
        path.setCursor(Cursor.HAND);
        applyEdgeDash(path, edge);

        path.getElements().add(new MoveTo(sourceDock.x, sourceDock.y));
        path.getElements().add(new LineTo(routed.sourceTrack.x, sourceDock.y));
        path.getElements().add(new LineTo(routed.sourceTrack.x, y));
        emitHorizontalWithBridges(path, routed.sourceTrack.x, routed.targetTrack.x, y, routed, verticalSegments);
        path.getElements().add(new LineTo(routed.targetTrack.x, targetDock.y));
        path.getElements().add(new LineTo(targetDock.x, targetDock.y));

        double arrowDx = targetDock.x - routed.targetTrack.x;
        double arrowDy = Math.abs(arrowDx) < 0.0001 ? targetDock.y - y : 0.0;
        Polygon arrow = makeArrow(targetDock.x, targetDock.y, arrowDx, arrowDy, color);

        path.setOnMouseEntered(e -> {
            if (!isSelected(edge)) path.setStroke(EDGE_HOVER);
            path.setCursor(Cursor.HAND);
        });
        path.setOnMouseExited(e -> {
            path.setStroke(edgeColor(edge));
            path.setCursor(Cursor.DEFAULT);
        });
        installEdgeInteractions(path, edge);

        pane.getChildren().addAll(path, arrow);
    }

    private boolean renderFallbackEdge(DependencyEdge edge, Bounds source, Bounds target, LaneLayout laneLayout) {
        FallbackPath fallback = findFallbackPath(source, target, laneLayout);
        if (fallback == null) {
            fallback = findFallbackPathForced(source, target, laneLayout);
        }
        if (fallback == null) {
            return renderEdge(edge);
        }

        Color color = edgeColor(edge);
        double width = edgeWidth(edge);

        Path path = new Path();
        path.setStroke(color);
        path.setStrokeWidth(width);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.MITER);
        path.setFill(null);
        path.setCursor(Cursor.HAND);
        applyEdgeDash(path, edge);

        path.getElements().add(new MoveTo(fallback.sourceDock.x, fallback.sourceDock.y));
        path.getElements().add(new LineTo(fallback.sourceX, fallback.sourceDock.y));
        path.getElements().add(new LineTo(fallback.sourceX, fallback.y));
        path.getElements().add(new LineTo(fallback.targetX, fallback.y));
        path.getElements().add(new LineTo(fallback.targetX, fallback.targetDock.y));
        path.getElements().add(new LineTo(fallback.targetDock.x, fallback.targetDock.y));

        double arrowDx = fallback.targetDock.x - fallback.targetX;
        double arrowDy = Math.abs(arrowDx) < 0.0001 ? fallback.targetDock.y - fallback.y : 0.0;
        Polygon arrow = makeArrow(fallback.targetDock.x, fallback.targetDock.y, arrowDx, arrowDy, color);

        path.setOnMouseEntered(e -> {
            if (!isSelected(edge)) path.setStroke(EDGE_HOVER);
            path.setCursor(Cursor.HAND);
        });
        path.setOnMouseExited(e -> {
            path.setStroke(edgeColor(edge));
            path.setCursor(Cursor.DEFAULT);
        });
        installEdgeInteractions(path, edge);

        pane.getChildren().addAll(path, arrow);
        return true;
    }

    private FallbackPath findFallbackPath(Bounds source, Bounds target, LaneLayout laneLayout) {
        double idealY = (source.getCenterY() + target.getCenterY()) / 2.0;
        List<HorizontalTrack> horizontalCandidates = new ArrayList<>(laneLayout.horizontalTracks);
        horizontalCandidates.sort(Comparator.comparingDouble(h -> horizontalScore(h, idealY)));

        for (HorizontalTrack horizontal : horizontalCandidates) {
            List<VerticalTrack> sourceTracks = candidateVerticalTracks(laneLayout.verticalTracks.get(source), horizontal.y);
            List<VerticalTrack> targetTracks = candidateVerticalTracks(laneLayout.verticalTracks.get(target), horizontal.y);
            for (VerticalTrack sourceTrack : sourceTracks) {
                Point sourceDock = dockPoint(source, sourceTrack.x, horizontal.y);
                if (verticalSegmentHitsClass(sourceTrack.x, sourceDock.y, horizontal.y,
                        laneLayout.classBounds, source, target)) {
                    continue;
                }
                for (VerticalTrack targetTrack : targetTracks) {
                    Point targetDock = dockPoint(target, targetTrack.x, horizontal.y);
                    if (!horizontal.canOccupy(sourceTrack.x, targetTrack.x)) {
                        continue;
                    }
                    if (verticalSegmentHitsClass(targetTrack.x, horizontal.y, targetDock.y,
                            laneLayout.classBounds, source, target)) {
                        continue;
                    }
                    if (horizontalSegmentHitsClass(horizontal.y, sourceTrack.x, targetTrack.x,
                            laneLayout.classBounds, source, target)) {
                        continue;
                    }
                    horizontal.occupy(sourceTrack.x, targetTrack.x);
                    sourceTrack.occupy(horizontal.y);
                    targetTrack.occupy(horizontal.y);
                    return new FallbackPath(sourceDock, targetDock, sourceTrack.x, targetTrack.x, horizontal.y);
                }
            }
        }
        return null;
    }

    /**
     * Like {@link #findFallbackPath} but skips the {@code canOccupy} checks on
     * both horizontal and vertical tracks, allowing already-used tracks to be
     * shared (double-occupancy). Class-box hit detection is still enforced so
     * lines never run through boxes. {@code occupy()} is still called so the
     * use-count score steers subsequent edges away from the busiest tracks.
     */
    private FallbackPath findFallbackPathForced(Bounds source, Bounds target, LaneLayout laneLayout) {
        double idealY = (source.getCenterY() + target.getCenterY()) / 2.0;
        List<HorizontalTrack> horizontalCandidates = new ArrayList<>(laneLayout.horizontalTracks);
        horizontalCandidates.sort(Comparator.comparingDouble(h -> horizontalScore(h, idealY)));

        for (HorizontalTrack horizontal : horizontalCandidates) {
            List<VerticalTrack> sourceTracks = candidateVerticalTracksForced(laneLayout.verticalTracks.get(source), horizontal.y);
            List<VerticalTrack> targetTracks = candidateVerticalTracksForced(laneLayout.verticalTracks.get(target), horizontal.y);
            for (VerticalTrack sourceTrack : sourceTracks) {
                Point sourceDock = dockPoint(source, sourceTrack.x, horizontal.y);
                if (verticalSegmentHitsClass(sourceTrack.x, sourceDock.y, horizontal.y,
                        laneLayout.classBounds, source, target)) {
                    continue;
                }
                for (VerticalTrack targetTrack : targetTracks) {
                    Point targetDock = dockPoint(target, targetTrack.x, horizontal.y);
                    if (verticalSegmentHitsClass(targetTrack.x, horizontal.y, targetDock.y,
                            laneLayout.classBounds, source, target)) {
                        continue;
                    }
                    if (horizontalSegmentHitsClass(horizontal.y, sourceTrack.x, targetTrack.x,
                            laneLayout.classBounds, source, target)) {
                        continue;
                    }
                    horizontal.occupy(sourceTrack.x, targetTrack.x);
                    sourceTrack.occupy(horizontal.y);
                    targetTrack.occupy(horizontal.y);
                    return new FallbackPath(sourceDock, targetDock, sourceTrack.x, targetTrack.x, horizontal.y);
                }
            }
        }
        return null;
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

    private static List<VerticalSegment> collectVerticalSegments(List<RoutedTangleEdge> routed) {
        List<VerticalSegment> segments = new ArrayList<>();
        for (RoutedTangleEdge edge : routed) {
            segments.add(new VerticalSegment(edge.sourceTrack.x, edge.source.getCenterY(), edge.horizontal.y, edge));
            segments.add(new VerticalSegment(edge.targetTrack.x, edge.target.getCenterY(), edge.horizontal.y, edge));
        }
        return segments;
    }

    private void emitHorizontalWithBridges(Path path, double xFrom, double xTo, double y,
                                           RoutedTangleEdge owner, List<VerticalSegment> verticalSegments) {
        double lo = Math.min(xFrom, xTo);
        double hi = Math.max(xFrom, xTo);
        int direction = xTo >= xFrom ? 1 : -1;

        List<Double> crossings = new ArrayList<>();
        for (VerticalSegment vertical : verticalSegments) {
            if (vertical.owner == owner) continue;
            if (vertical.x <= lo || vertical.x >= hi) continue;
            if (vertical.containsY(y)) {
                crossings.add(vertical.x);
            }
        }
        crossings.sort(direction > 0 ? Comparator.naturalOrder() : Comparator.reverseOrder());

        for (double x : crossings) {
            double before = x - direction * BRIDGE_RADIUS;
            double after = x + direction * BRIDGE_RADIUS;
            path.getElements().add(new LineTo(before, y));
            ArcTo arc = new ArcTo();
            arc.setX(after);
            arc.setY(y);
            arc.setRadiusX(BRIDGE_RADIUS);
            arc.setRadiusY(BRIDGE_RADIUS);
            arc.setLargeArcFlag(false);
            arc.setSweepFlag(direction > 0);
            path.getElements().add(arc);
        }
        path.getElements().add(new LineTo(xTo, y));
    }

    private static final class RoutingResult {
        final List<RoutedTangleEdge> routed;
        final boolean anyRendered;

        RoutingResult(List<RoutedTangleEdge> routed, boolean anyRendered) {
            this.routed = routed;
            this.anyRendered = anyRendered;
        }
    }

    private static final class LaneLayout {
        final List<HorizontalTrack> horizontalTracks;
        final Map<Bounds, List<VerticalTrack>> verticalTracks;
        final Map<String, Bounds> classBoundsByName;
        final List<Bounds> classBounds;

        LaneLayout(List<HorizontalTrack> horizontalTracks,
                   Map<Bounds, List<VerticalTrack>> verticalTracks,
                   Map<String, Bounds> classBoundsByName,
                   List<Bounds> classBounds) {
            this.horizontalTracks = horizontalTracks;
            this.verticalTracks = verticalTracks;
            this.classBoundsByName = classBoundsByName;
            this.classBounds = classBounds;
        }
    }

    private static final class HorizontalTrack {
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

    private static final class RoutedTangleEdge {
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

    private static final class VerticalSegment {
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

    private static final class FallbackPath {
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

    record Point(double x, double y) {}

    /**
     * Draws a straight line from the centre of the source box to the centre of
     * the target box, with a filled arrowhead at the target end.
     *
     * @return {@code true} when both nodes were found and drawn.
     */
    private boolean renderEdge(DependencyEdge edge) {
        Node source = elementRegistry.get(edge.from());
        Node target = elementRegistry.get(edge.to());
        if (source == null || target == null) return false;
        if (!isVisible(source) || !isVisible(target)) return false;

        Bounds sb = overlayBounds(source);
        Bounds tb = overlayBounds(target);
        if (sb == null || tb == null) {
            layoutPending = true;
            return false;
        }

        Point start = edgePoint(sb, tb.getCenterX(), tb.getCenterY());
        Point end = edgePoint(tb, sb.getCenterX(), sb.getCenterY());

        Color color = edgeColor(edge);
        double width = edgeWidth(edge);

        Line line = new Line(start.x, start.y, end.x, end.y);
        line.setStroke(color);
        line.setStrokeWidth(width);
        applyEdgeDash(line, edge);

        Polygon arrow = makeArrow(end.x, end.y, end.x - start.x, end.y - start.y, color);

        line.setOnMouseEntered(e -> {
            if (!isSelected(edge)) line.setStroke(EDGE_HOVER);
            line.setCursor(Cursor.HAND);
        });
        line.setOnMouseExited(e -> {
            line.setStroke(edgeColor(edge));
            line.setCursor(Cursor.DEFAULT);
        });
        installEdgeInteractions(line, edge);

        pane.getChildren().addAll(line, arrow);
        return true;
    }

    private void installEdgeInteractions(javafx.scene.shape.Shape shape, DependencyEdge edge) {
        shape.setOnMouseClicked(e -> {
            handleEdgeClick(edge);
            e.consume();
        });
        if (isAppliedCutEdge(edge)) {
            shape.setOnContextMenuRequested(e -> {
                ContextMenu menu = new ContextMenu();
                MenuItem restoreItem = new MenuItem("Restore");
                restoreItem.setOnAction(action -> onEdgeRestore.accept(edge.from(), edge.to()));
                menu.getItems().setAll(restoreItem);
                menu.show(shape, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        } else if (isCycleBreakEdge(edge) && isActiveTangleEdge(edge)) {
            shape.setOnContextMenuRequested(e -> {
                ContextMenu menu = new ContextMenu();
                MenuItem cutItem = new MenuItem("Cut");
                cutItem.setOnAction(action -> onEdgeCut.accept(edge.from(), edge.to()));
                menu.getItems().setAll(cutItem);
                menu.show(shape, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        } else {
            shape.setOnContextMenuRequested(null);
        }
    }

    private void handleEdgeClick(DependencyEdge edge) {
        if (isSelected(edge)) {
            statusCallback.accept("Tangle edge deselected");
            setSelectedEdge(null, null);
            onEdgeClicked.accept(null, null);
            return;
        }
        String label = simple(edge.from()) + " \u2192 " + simple(edge.to());
        if (isAppliedCutEdge(edge)) {
            statusCallback.accept("Refactoring Preview: " + label);
        } else {
            statusCallback.accept(isCycleBreakEdge(edge) && isActiveTangleEdge(edge) ? "Recommended cut: " + label : label);
        }
        setSelectedEdge(edge.from(), edge.to());
        onEdgeClicked.accept(edge.from(), edge.to());
    }

    private Color edgeColor(DependencyEdge edge) {
        if (isSelected(edge)) {
            return SELECTED_COLOR;
        }
        if (isAppliedCutEdge(edge)) {
            return APPLIED_CUT_EDGE_COLOR;
        }
        if (!isActiveTangleEdge(edge)) {
            return NON_TANGLE_EDGE_COLOR;
        }
        return isCycleBreakEdge(edge) ? CUT_EDGE_COLOR : EDGE_COLOR;
    }

    private double edgeWidth(DependencyEdge edge) {
        if (isSelected(edge)) {
            return SELECTED_WIDTH;
        }
        if (isAppliedCutEdge(edge)) {
            return CUT_EDGE_WIDTH;
        }
        if (!isActiveTangleEdge(edge)) {
            return NON_TANGLE_EDGE_WIDTH;
        }
        return isCycleBreakEdge(edge) && isActiveTangleEdge(edge) ? CUT_EDGE_WIDTH : EDGE_WIDTH;
    }

    private void applyEdgeDash(javafx.scene.shape.Shape shape, DependencyEdge edge) {
        if (isAppliedCutEdge(edge) || (isCycleBreakEdge(edge) && isActiveTangleEdge(edge))) {
            shape.getStrokeDashArray().setAll(9.0, 5.0);
        } else {
            shape.getStrokeDashArray().clear();
        }
    }

    private boolean isCycleBreakEdge(DependencyEdge edge) {
        return cycleBreakEdges.contains(edge);
    }

    private boolean isAppliedCutEdge(DependencyEdge edge) {
        return appliedCutEdges.contains(edge);
    }

    private boolean isActiveTangleEdge(DependencyEdge edge) {
        return activeTangleEdges.contains(edge);
    }

    private void recomputeActiveTangleEdges() {
        Map<String, Set<String>> graph = new HashMap<>();
        for (DependencyEdge edge : edges) {
            graph.computeIfAbsent(edge.from(), k -> new java.util.HashSet<>());
            graph.computeIfAbsent(edge.to(), k -> new java.util.HashSet<>());
            if (!appliedCutEdges.contains(edge)) {
                graph.get(edge.from()).add(edge.to());
            }
        }

        List<Set<String>> activeComponents = stronglyConnectedComponents(graph).stream()
                .filter(component -> component.size() > 1)
                .toList();

        activeTangleEdges = edges.stream()
                .filter(edge -> !appliedCutEdges.contains(edge))
                .filter(edge -> activeComponents.stream()
                        .anyMatch(component -> component.contains(edge.from()) && component.contains(edge.to())))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<Set<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
        List<Set<String>> components = new ArrayList<>();
        Map<String, Integer> indexByNode = new HashMap<>();
        Map<String, Integer> lowlinkByNode = new HashMap<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        int[] nextIndex = {0};

        for (String node : graph.keySet()) {
            if (!indexByNode.containsKey(node)) {
                strongConnect(node, graph, indexByNode, lowlinkByNode, stack, onStack, nextIndex, components);
            }
        }
        return components;
    }

    private static void strongConnect(String node,
                                      Map<String, Set<String>> graph,
                                      Map<String, Integer> indexByNode,
                                      Map<String, Integer> lowlinkByNode,
                                      java.util.Deque<String> stack,
                                      Set<String> onStack,
                                      int[] nextIndex,
                                      List<Set<String>> components) {
        indexByNode.put(node, nextIndex[0]);
        lowlinkByNode.put(node, nextIndex[0]);
        nextIndex[0]++;
        stack.push(node);
        onStack.add(node);

        for (String target : graph.getOrDefault(node, Set.of())) {
            if (!indexByNode.containsKey(target)) {
                strongConnect(target, graph, indexByNode, lowlinkByNode, stack, onStack, nextIndex, components);
                lowlinkByNode.put(node, Math.min(lowlinkByNode.get(node), lowlinkByNode.get(target)));
            } else if (onStack.contains(target)) {
                lowlinkByNode.put(node, Math.min(lowlinkByNode.get(node), indexByNode.get(target)));
            }
        }

        if (!lowlinkByNode.get(node).equals(indexByNode.get(node))) {
            return;
        }
        Set<String> component = new java.util.HashSet<>();
        String member;
        do {
            member = stack.pop();
            onStack.remove(member);
            component.add(member);
        } while (!node.equals(member));
        components.add(component);
    }

    private boolean hasVisibleEndpointWaitingForLayout(DependencyEdge edge) {
        Node source = elementRegistry.get(edge.from());
        Node target = elementRegistry.get(edge.to());
        if (source == null || target == null) {
            return false;
        }
        return (isVisible(source) && overlayBounds(source) == null)
                || (isVisible(target) && overlayBounds(target) == null);
    }

    private Bounds overlayBounds(Node node) {
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

    private boolean isSelected(DependencyEdge edge) {
        return selectedFrom != null && selectedTo != null
                && selectedFrom.equals(edge.from()) && selectedTo.equals(edge.to());
    }

    private static boolean isVisible(Node node) {
        if (node == null || !node.isVisible()) return false;
        Parent p = node.getParent();
        while (p != null) {
            if (!p.isVisible()) return false;
            p = p.getParent();
        }
        return true;
    }

    private static Polygon makeArrow(double tipX, double tipY, double dx, double dy, Color fill) {
        double angle = Math.atan2(dy, dx);
        double lx = tipX - ARROW_SIZE * Math.cos(angle - Math.PI / 7);
        double ly = tipY - ARROW_SIZE * Math.sin(angle - Math.PI / 7);
        double rx = tipX - ARROW_SIZE * Math.cos(angle + Math.PI / 7);
        double ry = tipY - ARROW_SIZE * Math.sin(angle + Math.PI / 7);
        Polygon p = new Polygon(tipX, tipY, lx, ly, rx, ry);
        p.setFill(fill);
        p.setStroke(fill);
        p.setMouseTransparent(true);
        return p;
    }

    private void scheduleRetry() {
        if (retriesLeft > 0) {
            retriesLeft--;
            javafx.application.Platform.runLater(this::redraw);
        }
    }

    private static String simple(String fqn) {
        if (fqn == null) return "";
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    List<Node> getRenderedShapes() {
        return new ArrayList<>(pane.getChildren());
    }
}
