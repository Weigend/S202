package de.weigend.s202.ui.rendering;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.StrokeLineJoin;

import java.util.*;
import java.util.function.Consumer;

/**
 * Tangle-edge overlay renderer -- orthogonal (Manhattan) routing.
 *
 * <p>Each edge exits its source box on the face pointing toward the target
 * and enters the target box on the facing side.  Edges are routed through a
 * shared corridor:
 * <ul>
 *   <li><b>Both vertical</b> (top/bottom ports): three segments via a shared
 *       horizontal corridor Y (obstacle-aware midpoint).</li>
 *   <li><b>Both horizontal</b> (left/right ports): three segments via a shared
 *       vertical corridor X (obstacle-aware midpoint).</li>
 *   <li><b>Mixed L-turn</b>: two segments, single 90 degree bend.</li>
 * </ul>
 * Edges sharing the same corridor bucket are spread by {@code PARALLEL_GAP}
 * pixels so parallel traces remain visually distinct.
 */
public class TangleEdgeRenderer {

    public record Edge(String from, String to) {}

    private static final Color  EDGE_COLOR      = Color.web("#ff5252");
    private static final Color  EDGE_HOVER      = Color.web("#b71c1c");
    private static final Color  SELECTED_COLOR  = Color.web("#ffeb3b");
    private static final double EDGE_WIDTH      = 1.2;
    private static final double SELECTED_WIDTH  = 2.0;
    private static final double ARROW_SIZE      = 6.0;
    /** Clearance from the box edge to the port attachment stub. */
    private static final double PORT_GAP        = 4.0;
    /** Spacing between parallel edges that share the same corridor. */
    private static final double PARALLEL_GAP    = 3.5;
    /** Minimum effective half-size for zero-size (point) nodes. */
    private static final double MIN_BOX_HALF    = 5.0;
    /** Corridor grouping tolerance. */
    private static final double CORRIDOR_BUCKET = 24.0;
    /** Step when searching for an obstacle-free corridor position. */
    private static final double CLEAR_STEP      = 6.0;
    /** Extra margin around box bounds for clearance checks. */
    private static final double CLEAR_MARGIN    = 2.0;

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

    // -- routing types -------------------------------------------------------
    private enum Side { TOP, BOTTOM, LEFT, RIGHT }
    private record Port(double x, double y, Side side) {}

    // -- constructor & public API --------------------------------------------

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

    // -- redraw --------------------------------------------------------------

    private void redraw() {
        pane.getChildren().clear();
        if (zoomableContent == null || overlayPane == null || edges.isEmpty()) return;
        zoomableContent.applyCss();
        zoomableContent.layout();

        // 1. Collect effective bounds for all referenced nodes
        Map<String, Bounds> boundsMap = new LinkedHashMap<>();
        for (Edge e : edges) {
            for (String name : List.of(e.from(), e.to())) {
                if (!boundsMap.containsKey(name)) {
                    Node node = elementRegistry.get(name);
                    if (node == null || !isVisible(node)) continue;
                    Bounds raw = overlayPane.sceneToLocal(
                            node.localToScene(node.getBoundsInLocal()));
                    boundsMap.put(name, effectiveBounds(raw));
                }
            }
        }
        if (boundsMap.isEmpty()) { scheduleRetry(); return; }
        Collection<Bounds> obstacles = boundsMap.values();

        // 2. Compute ports and canonical corridor midpoints
        record PortedEdge(Edge e, Port sp, Port tp,
                          boolean bothV, boolean bothH, double mid) {}

        List<PortedEdge> ported = new ArrayList<>();
        for (Edge e : edges) {
            Bounds sb = boundsMap.get(e.from());
            Bounds tb = boundsMap.get(e.to());
            if (sb == null || tb == null) continue;

            Port sp = sourcePort(sb, tb);
            Port tp = targetPort(tb, sb);

            boolean spV = (sp.side == Side.TOP  || sp.side == Side.BOTTOM);
            boolean tpV = (tp.side == Side.TOP  || tp.side == Side.BOTTOM);
            boolean bothV = spV && tpV;
            boolean bothH = !spV && !tpV;

            // Corridor midpoint:
            //   bothV: shared horizontal Y between the two vertical stubs
            //   bothH: shared vertical X between the two horizontal stubs
            //   L-turn: no shared corridor
            double mid = bothV ? (sp.y + tp.y) / 2.0
                       : bothH ? (sp.x + tp.x) / 2.0
                       : 0.0;

            ported.add(new PortedEdge(e, sp, tp, bothV, bothH, mid));
        }
        if (ported.isEmpty()) { scheduleRetry(); return; }

        // 3. Group by corridor bucket; assign parallel offsets
        Map<String, List<PortedEdge>> corridorGroups = new LinkedHashMap<>();
        for (PortedEdge pe : ported) {
            String key;
            if (pe.bothV() || pe.bothH()) {
                long bucket = Math.round(pe.mid() / CORRIDOR_BUCKET);
                key = (pe.bothV() ? "V" : "H") + bucket;
            } else {
                long bx = Math.round(pe.sp().x / CORRIDOR_BUCKET);
                long by = Math.round(pe.tp().y / CORRIDOR_BUCKET);
                key = "L" + bx + "_" + by;
            }
            corridorGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(pe);
        }

        Map<PortedEdge, Double> offsets = new IdentityHashMap<>();
        for (List<PortedEdge> grp : corridorGroups.values()) {
            int n = grp.size();
            double base = -(n - 1) * PARALLEL_GAP / 2.0;
            for (int i = 0; i < n; i++) {
                offsets.put(grp.get(i), base + i * PARALLEL_GAP);
            }
        }

        // 4. Build obstacle-aware routes
        record ReadyEdge(Edge e, List<Double> pts) {}
        List<ReadyEdge> ready = new ArrayList<>();
        for (PortedEdge pe : ported) {
            double offset = offsets.getOrDefault(pe, 0.0);
            List<Double> pts = buildRoute(
                    pe.sp(), pe.tp(), pe.bothV(), pe.bothH(),
                    pe.mid() + offset, obstacles);
            ready.add(new ReadyEdge(pe.e(), pts));
        }

        // Selected edge rendered on top
        ready.sort((a, b) -> Boolean.compare(isSelected(a.e()), isSelected(b.e())));

        // 5. Render
        for (ReadyEdge re : ready) {
            renderPolyline(re.e(), re.pts());
        }
    }

    // -- routing helpers -----------------------------------------------------

    /** Expand zero-size (point-like) bounds to a small effective box. */
    private static Bounds effectiveBounds(Bounds raw) {
        double cx = raw.getCenterX();
        double cy = raw.getCenterY();
        double hw = Math.max(raw.getWidth()  / 2.0, MIN_BOX_HALF);
        double hh = Math.max(raw.getHeight() / 2.0, MIN_BOX_HALF);
        return new BoundingBox(cx - hw, cy - hh, 2 * hw, 2 * hh);
    }

    /** Exit port on {@code s} facing toward {@code t}. */
    private static Port sourcePort(Bounds s, Bounds t) {
        double dx = t.getCenterX() - s.getCenterX();
        double dy = t.getCenterY() - s.getCenterY();
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0
                    ? new Port(s.getMaxX() + PORT_GAP, s.getCenterY(), Side.RIGHT)
                    : new Port(s.getMinX() - PORT_GAP, s.getCenterY(), Side.LEFT);
        } else {
            return dy >= 0
                    ? new Port(s.getCenterX(), s.getMaxY() + PORT_GAP, Side.BOTTOM)
                    : new Port(s.getCenterX(), s.getMinY() - PORT_GAP, Side.TOP);
        }
    }

    /** Entry port on {@code t} on the face closest to {@code s}. */
    private static Port targetPort(Bounds t, Bounds s) {
        double dx = t.getCenterX() - s.getCenterX();
        double dy = t.getCenterY() - s.getCenterY();
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0
                    ? new Port(t.getMinX() - PORT_GAP, t.getCenterY(), Side.LEFT)
                    : new Port(t.getMaxX() + PORT_GAP, t.getCenterY(), Side.RIGHT);
        } else {
            return dy >= 0
                    ? new Port(t.getCenterX(), t.getMinY() - PORT_GAP, Side.TOP)
                    : new Port(t.getCenterX(), t.getMaxY() + PORT_GAP, Side.BOTTOM);
        }
    }

    /**
     * Build an orthogonal polyline.
     * bothV  -> sp.x,sp.y | sp.x,cy | tp.x,cy | tp.x,tp.y
     * bothH  -> sp.x,sp.y | cx,sp.y | cx,tp.y | tp.x,tp.y
     * L-turn -> sp.x,sp.y | corner  | tp.x,tp.y
     */
    private static List<Double> buildRoute(Port sp, Port tp,
                                            boolean bothV, boolean bothH,
                                            double corridor,
                                            Collection<Bounds> obstacles) {
        List<Double> pts = new ArrayList<>();
        pts.add(sp.x); pts.add(sp.y);

        if (bothV) {
            double cy = findClearY(corridor,
                    Math.min(sp.x, tp.x), Math.max(sp.x, tp.x), obstacles);
            pts.add(sp.x); pts.add(cy);
            pts.add(tp.x); pts.add(cy);
        } else if (bothH) {
            double cx = findClearX(corridor,
                    Math.min(sp.y, tp.y), Math.max(sp.y, tp.y), obstacles);
            pts.add(cx); pts.add(sp.y);
            pts.add(cx); pts.add(tp.y);
        } else {
            // L-turn
            boolean spH = (sp.side == Side.LEFT || sp.side == Side.RIGHT);
            if (spH) {
                pts.add(tp.x); pts.add(sp.y);
            } else {
                pts.add(sp.x); pts.add(tp.y);
            }
        }

        pts.add(tp.x); pts.add(tp.y);
        return pts;
    }

    private static double findClearX(double preferred, double yMin, double yMax,
                                      Collection<Bounds> obstacles) {
        if (isClearVertSeg(preferred, yMin, yMax, obstacles)) return preferred;
        for (int i = 1; i <= 50; i++) {
            double l = preferred - i * CLEAR_STEP;
            double r = preferred + i * CLEAR_STEP;
            if (isClearVertSeg(l, yMin, yMax, obstacles)) return l;
            if (isClearVertSeg(r, yMin, yMax, obstacles)) return r;
        }
        return preferred;
    }

    private static double findClearY(double preferred, double xMin, double xMax,
                                      Collection<Bounds> obstacles) {
        if (isClearHorizSeg(preferred, xMin, xMax, obstacles)) return preferred;
        for (int i = 1; i <= 50; i++) {
            double u = preferred - i * CLEAR_STEP;
            double d = preferred + i * CLEAR_STEP;
            if (isClearHorizSeg(u, xMin, xMax, obstacles)) return u;
            if (isClearHorizSeg(d, xMin, xMax, obstacles)) return d;
        }
        return preferred;
    }

    private static boolean isClearVertSeg(double x, double yMin, double yMax,
                                           Collection<Bounds> obstacles) {
        for (Bounds b : obstacles) {
            if (x    >= b.getMinX() - CLEAR_MARGIN && x    <= b.getMaxX() + CLEAR_MARGIN
             && yMax >= b.getMinY() - CLEAR_MARGIN && yMin <= b.getMaxY() + CLEAR_MARGIN) {
                return false;
            }
        }
        return true;
    }

    private static boolean isClearHorizSeg(double y, double xMin, double xMax,
                                            Collection<Bounds> obstacles) {
        for (Bounds b : obstacles) {
            if (y    >= b.getMinY() - CLEAR_MARGIN && y    <= b.getMaxY() + CLEAR_MARGIN
             && xMax >= b.getMinX() - CLEAR_MARGIN && xMin <= b.getMaxX() + CLEAR_MARGIN) {
                return false;
            }
        }
        return true;
    }

    // -- render --------------------------------------------------------------

    private void renderPolyline(Edge edge, List<Double> pts) {
        if (pts.size() < 4) return;

        boolean selected = isSelected(edge);
        Color  color = selected ? SELECTED_COLOR : EDGE_COLOR;
        double width = selected ? SELECTED_WIDTH  : EDGE_WIDTH;

        Polyline poly = new Polyline();
        poly.getPoints().addAll(pts);
        poly.setStroke(color);
        poly.setStrokeWidth(width);
        poly.setFill(null);
        poly.setStrokeLineJoin(StrokeLineJoin.MITER);

        // Arrowhead direction: second-to-last point -> tip
        int    n     = pts.size();
        double tipX  = pts.get(n - 2);
        double tipY  = pts.get(n - 1);
        double prevX = pts.get(n - 4);
        double prevY = pts.get(n - 3);
        double dx    = tipX - prevX;
        double dy    = tipY - prevY;
        if (dx == 0 && dy == 0 && n >= 6) {
            prevX = pts.get(n - 6);
            prevY = pts.get(n - 5);
            dx = tipX - prevX;
            dy = tipY - prevY;
        }
        Polygon arrow = makeArrow(tipX, tipY, dx, dy, color);

        poly.setOnMouseEntered(e -> {
            if (!isSelected(edge)) poly.setStroke(EDGE_HOVER);
            poly.setCursor(Cursor.HAND);
        });
        poly.setOnMouseExited(e -> {
            poly.setStroke(isSelected(edge) ? SELECTED_COLOR : EDGE_COLOR);
            poly.setCursor(Cursor.DEFAULT);
        });
        poly.setOnMouseClicked(e -> {
            statusCallback.accept(simple(edge.from()) + " -> " + simple(edge.to()));
            setSelectedEdge(edge.from(), edge.to());
            onEdgeClicked.accept(edge.from(), edge.to());
            e.consume();
        });

        pane.getChildren().addAll(poly, arrow);
    }

    // -- misc helpers --------------------------------------------------------

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

    private static Polygon makeArrow(double tipX, double tipY,
                                      double dx, double dy, Color fill) {
        if (dx == 0 && dy == 0) dx = 1;
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
