package de.weigend.s202.ui.rendering;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
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

    public record Edge(String from, String to) {}

    private static final Color EDGE_COLOR     = Color.web("#ff5252");
    private static final Color EDGE_HOVER     = Color.web("#b71c1c");
    private static final Color SELECTED_COLOR = Color.web("#ffeb3b");
    private static final double EDGE_WIDTH    = 1.2;
    private static final double SELECTED_WIDTH = 2.0;
    private static final double ARROW_SIZE    = 6.0;

    /** Debug lane constants */
    private static final Color  LANE_COLOR      = Color.web("#00bcd4", 0.30);
    private static final double LANE_WIDTH      = 0.7;
    /** Number of potential dependency lanes shown in every free channel. */
    private static final int POTENTIAL_LANE_COUNT = 5;
    /** Fixed distance between adjacent potential lanes in both X and Y direction. */
    private static final double LANE_SPACING_PX = 6.0;
    private static final double BRIDGE_RADIUS = LANE_SPACING_PX / 2.5;
    /** Nodes within this Y-distance belong to the same layout row. */
    private static final double ROW_CLUSTER_PX  = 20.0;

    private final Pane pane;
    private final Map<String, Node> elementRegistry;
    private final Consumer<String> statusCallback;

    private List<Edge> edges = List.of();
    private String selectedFrom;
    private String selectedTo;
    private Pane zoomableContent;
    private Pane overlayPane;

    private int retriesLeft = 0;
    private static final int INITIAL_RETRIES = 8;

    private final javafx.beans.value.ChangeListener<Bounds> layoutListener =
            (obs, was, isNow) -> redraw();

    private java.util.function.BiConsumer<String, String> onEdgeClicked = (a, b) -> {};

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

    public void setEdges(List<Edge> edges) {
        this.edges = edges == null ? List.of() : List.copyOf(edges);
        retriesLeft = INITIAL_RETRIES;
        redraw();
    }

    public void setSelectedEdge(String from, String to) {
        this.selectedFrom = from;
        this.selectedTo = to;
        redraw();
    }

    public void clear() {
        pane.getChildren().clear();
        edges = List.of();
    }

    public void requestRedraw() {
        retriesLeft = INITIAL_RETRIES;
        redraw();
    }

    // -------------------------------------------------------------------------

    private void redraw() {
        pane.getChildren().clear();
        if (zoomableContent == null || overlayPane == null || edges.isEmpty()) {
            return;
        }
        zoomableContent.applyCss();
        zoomableContent.layout();

        // Debug lanes drawn first so edges appear on top
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
            for (Edge edge : edges) {
                if (renderEdge(edge)) anyRendered = true;
            }
        }

        if (!anyRendered && retriesLeft > 0) {
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
            Bounds b = overlayPane.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            if (b.getWidth() > 0 && b.getHeight() > 0) {
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
        Bounds cb = overlayPane.sceneToLocal(
                zoomableContent.localToScene(zoomableContent.getBoundsInLocal()));
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
        // Draw the same number of potential lanes with fixed pitch in every gap.
        for (double[] gap : gaps) {
            double top    = gap[0];
            double bottom = gap[1];
            for (double y : lanePositions(top, bottom)) {
                horizontalTracks.add(new HorizontalTrack(y, xLeft, xRight));
                drawDebugLine(xLeft, y, xRight, y);
            }
        }

        Map<Bounds, List<VerticalTrack>> verticalTracks = drawVerticalDebugLanes(classBounds, cb.getMinY(), cb.getMaxY());
        return new LaneLayout(horizontalTracks, verticalTracks, classBoundsByName);
    }

    static List<Double> lanePositions(double min, double max) {
        if (max <= min) {
            return List.of();
        }

        List<Double> lanes = new ArrayList<>();
        double center = (min + max) / 2.0;
        double firstOffset = -LANE_SPACING_PX * (POTENTIAL_LANE_COUNT - 1) / 2.0;
        for (int i = 0; i < POTENTIAL_LANE_COUNT; i++) {
            lanes.add(center + firstOffset + i * LANE_SPACING_PX);
        }
        return lanes;
    }

    private Map<Bounds, List<VerticalTrack>> drawVerticalDebugLanes(List<Bounds> classBounds, double topLimit, double bottomLimit) {
        Map<Bounds, List<VerticalTrack>> out = new IdentityHashMap<>();
        for (Bounds source : classBounds) {
            double y = source.getCenterY();
            List<VerticalTrack> tracks = new ArrayList<>();
            for (double x : lanePositions(source.getMinX(), source.getMaxX())) {
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
        for (Edge edge : edges) {
            Bounds source = laneLayout.classBoundsByName.get(edge.from());
            Bounds target = laneLayout.classBoundsByName.get(edge.to());
            if (source == null || target == null) {
                if (renderEdge(edge)) {
                    anyRendered = true;
                    // Legacy fallback: keep visible even if not enough lane data exists yet.
                }
                continue;
            }

            RoutedTangleEdge routedEdge = routeEdge(edge, source, target, laneLayout);
            if (routedEdge != null) {
                routed.add(routedEdge);
                anyRendered = true;
            } else {
                if (renderEdge(edge)) {
                    anyRendered = true;
                }
            }
        }
        return new RoutingResult(routed, anyRendered);
    }

    private RoutedTangleEdge routeEdge(Edge edge, Bounds source, Bounds target, LaneLayout laneLayout) {
        double idealY = (source.getCenterY() + target.getCenterY()) / 2.0;
        List<HorizontalTrack> horizontalCandidates = new ArrayList<>(laneLayout.horizontalTracks);
        horizontalCandidates.removeIf(HorizontalTrack::isOccupied);
        horizontalCandidates.sort(Comparator.comparingDouble(h -> Math.abs(h.y - idealY)));

        for (HorizontalTrack horizontal : horizontalCandidates) {
            VerticalTrack sourceTrack = pickVerticalTrack(laneLayout.verticalTracks.get(source), horizontal.y);
            if (sourceTrack == null) continue;
            VerticalTrack targetTrack = pickVerticalTrack(laneLayout.verticalTracks.get(target), horizontal.y);
            if (targetTrack == null) continue;

            horizontal.occupied = true;
            sourceTrack.occupy(horizontal.y);
            targetTrack.occupy(horizontal.y);
            return new RoutedTangleEdge(edge, source, target, sourceTrack, horizontal, targetTrack);
        }
        return null;
    }

    private static VerticalTrack pickVerticalTrack(List<VerticalTrack> tracks, double y) {
        if (tracks == null) return null;
        return tracks.stream()
                .filter(track -> track.canOccupy(y))
                .min(Comparator.comparingDouble(track -> Math.abs(track.x - track.owner.getCenterX())))
                .orElse(null);
    }

    private void paintRoutedEdge(RoutedTangleEdge routed, List<VerticalSegment> verticalSegments) {
        Edge edge = routed.edge;
        boolean selected = isSelected(edge);
        Color color = selected ? SELECTED_COLOR : EDGE_COLOR;
        double width = selected ? SELECTED_WIDTH : EDGE_WIDTH;

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
            path.setStroke(isSelected(edge) ? SELECTED_COLOR : EDGE_COLOR);
            path.setCursor(Cursor.DEFAULT);
        });
        path.setOnMouseClicked(e -> {
            statusCallback.accept(simple(edge.from()) + " \u2192 " + simple(edge.to()));
            setSelectedEdge(edge.from(), edge.to());
            onEdgeClicked.accept(edge.from(), edge.to());
            e.consume();
        });

        pane.getChildren().addAll(path, arrow);
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

        LaneLayout(List<HorizontalTrack> horizontalTracks,
                   Map<Bounds, List<VerticalTrack>> verticalTracks,
                   Map<String, Bounds> classBoundsByName) {
            this.horizontalTracks = horizontalTracks;
            this.verticalTracks = verticalTracks;
            this.classBoundsByName = classBoundsByName;
        }
    }

    private static final class HorizontalTrack {
        final double y;
        final double xLeft;
        final double xRight;
        boolean occupied;

        HorizontalTrack(double y, double xLeft, double xRight) {
            this.y = y;
            this.xLeft = xLeft;
            this.xRight = xRight;
        }

        boolean isOccupied() {
            return occupied;
        }
    }

    private static final class VerticalTrack {
        final Bounds owner;
        final double x;
        final double centerY;
        final double topY;
        final double bottomY;
        boolean upwardOccupied;
        boolean downwardOccupied;

        VerticalTrack(Bounds owner, double x, double centerY, double topY, double bottomY) {
            this.owner = owner;
            this.x = x;
            this.centerY = centerY;
            this.topY = Math.min(topY, centerY);
            this.bottomY = Math.max(bottomY, centerY);
        }

        boolean canOccupy(double y) {
            if (y < centerY) {
                return !upwardOccupied && y >= topY;
            }
            return !downwardOccupied && y <= bottomY;
        }

        void occupy(double y) {
            if (y < centerY) {
                upwardOccupied = true;
            } else {
                downwardOccupied = true;
            }
        }
    }

    private static final class RoutedTangleEdge {
        final Edge edge;
        final Bounds source;
        final Bounds target;
        final VerticalTrack sourceTrack;
        final HorizontalTrack horizontal;
        final VerticalTrack targetTrack;

        RoutedTangleEdge(Edge edge, Bounds source, Bounds target,
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

    record Point(double x, double y) {}

    /**
     * Draws a straight line from the centre of the source box to the centre of
     * the target box, with a filled arrowhead at the target end.
     *
     * @return {@code true} when both nodes were found and drawn.
     */
    private boolean renderEdge(Edge edge) {
        Node source = elementRegistry.get(edge.from());
        Node target = elementRegistry.get(edge.to());
        if (source == null || target == null) return false;
        if (!isVisible(source) || !isVisible(target)) return false;

        Bounds sb = overlayPane.sceneToLocal(source.localToScene(source.getBoundsInLocal()));
        Bounds tb = overlayPane.sceneToLocal(target.localToScene(target.getBoundsInLocal()));

        double x1 = sb.getCenterX();
        double y1 = sb.getCenterY();
        double x2 = tb.getCenterX();
        double y2 = tb.getCenterY();

        boolean selected = isSelected(edge);
        Color color = selected ? SELECTED_COLOR : EDGE_COLOR;
        double width = selected ? SELECTED_WIDTH : EDGE_WIDTH;

        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(color);
        line.setStrokeWidth(width);

        Polygon arrow = makeArrow(x2, y2, x2 - x1, y2 - y1, color);

        line.setOnMouseEntered(e -> {
            if (!isSelected(edge)) line.setStroke(EDGE_HOVER);
            line.setCursor(Cursor.HAND);
        });
        line.setOnMouseExited(e -> {
            line.setStroke(isSelected(edge) ? SELECTED_COLOR : EDGE_COLOR);
            line.setCursor(Cursor.DEFAULT);
        });
        line.setOnMouseClicked(e -> {
            statusCallback.accept(simple(edge.from()) + " \u2192 " + simple(edge.to()));
            setSelectedEdge(edge.from(), edge.to());
            onEdgeClicked.accept(edge.from(), edge.to());
            e.consume();
        });

        pane.getChildren().addAll(line, arrow);
        return true;
    }

    private boolean isSelected(Edge edge) {
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
