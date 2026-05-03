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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** Minimum effective half-size for zero-size (point) nodes. */
    private static final double MIN_BOX_HALF    = 5.0;
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

        // 1. Collect bounds of all tangle-edge endpoints.
        //    We intentionally use only the class-level nodes that participate in
        //    the tangle — NOT their parent package containers. Routing into a
        //    package container is expected (target lives inside it); routing
        //    through a sibling class node is what we want to avoid.
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

        // 2. Build routes.  Per-edge obstacles = all tangle nodes EXCEPT the
        //    current edge's own source and target (their stubs already exit
        //    outside the box; including them would block the corridor search).
        record ReadyEdge(Edge e, List<Double> pts) {}
        List<ReadyEdge> ready = new ArrayList<>();
        for (Edge e : edges) {
            Bounds sb = boundsMap.get(e.from());
            Bounds tb = boundsMap.get(e.to());
            if (sb == null || tb == null) continue;

            List<Bounds> obstacles = new ArrayList<>(boundsMap.size());
            for (Map.Entry<String, Bounds> entry : boundsMap.entrySet()) {
                if (!entry.getKey().equals(e.from()) && !entry.getKey().equals(e.to())) {
                    obstacles.add(entry.getValue());
                }
            }

            Port sp = sourcePort(sb, tb);
            Port tp = targetPort(tb, sb);
            ready.add(new ReadyEdge(e, buildRoute(sp, tp, obstacles)));
        }
        if (ready.isEmpty()) { scheduleRetry(); return; }

        // Selected edge rendered on top
        ready.sort((a, b) -> Boolean.compare(isSelected(a.e()), isSelected(b.e())));
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
     * Build a 3-segment orthogonal route from sp to tp.
     *
     * <p>Routing is based on the source port axis:
     * <ul>
     *   <li>Source exits <b>vertically</b> (TOP/BOTTOM): find a clear horizontal
     *       corridor Y, then route sp.x,sp.y → sp.x,cy → tp.x,cy → tp.x,tp.y.</li>
     *   <li>Source exits <b>horizontally</b> (LEFT/RIGHT): find a clear vertical
     *       corridor X, then route sp.x,sp.y → cx,sp.y → cx,tp.y → tp.x,tp.y.</li>
     * </ul>
     * This handles all cases (both-V, both-H, L-turn) uniformly with
     * obstacle avoidance on the corridor segment.
     */
    private static List<Double> buildRoute(Port sp, Port tp,
                                            Collection<Bounds> obstacles) {
        List<Double> pts = new ArrayList<>();
        pts.add(sp.x); pts.add(sp.y);

        boolean spV = (sp.side == Side.TOP || sp.side == Side.BOTTOM);
        if (spV) {
            // Horizontal corridor between the two vertical stubs
            double preferred = (sp.y + tp.y) / 2.0;
            double cy = findClearY(preferred,
                    Math.min(sp.x, tp.x), Math.max(sp.x, tp.x), obstacles);
            pts.add(sp.x); pts.add(cy);
            pts.add(tp.x); pts.add(cy);
        } else {
            // Vertical corridor between the two horizontal stubs
            double preferred = (sp.x + tp.x) / 2.0;
            double cx = findClearX(preferred,
                    Math.min(sp.y, tp.y), Math.max(sp.y, tp.y), obstacles);
            pts.add(cx); pts.add(sp.y);
            pts.add(cx); pts.add(tp.y);
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
