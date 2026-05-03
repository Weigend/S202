package de.weigend.s202.ui.rendering;

import de.weigend.s202.ui.rendering.circuit.AStarEdgeRouter;
import de.weigend.s202.ui.rendering.circuit.ChannelGraph;
import de.weigend.s202.ui.rendering.circuit.ChannelGraphBuilder;
import de.weigend.s202.ui.rendering.circuit.ChannelPainter;
import de.weigend.s202.ui.rendering.circuit.ChannelRouter;
import de.weigend.s202.ui.rendering.circuit.GridBuilder;
import de.weigend.s202.ui.rendering.circuit.RoutingGrid;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bespoke SCC-edge renderer for the {@code Tangle} architecture tab.
 * <p>
 * Differences from {@code SCCRenderer}:
 * <ul>
 *   <li>Takes an explicit list of {@code (from, to)} edges — no Tarjan run.
 *       Caller already knows which edges belong to the tangle.</li>
 *   <li>Lines are clipped to the source/target box perimeters (ray-rect
 *       intersection) instead of running through the box centres.</li>
 *   <li>Filled triangle arrowhead anchored to the target box edge.</li>
 *   <li>Single-edge selection — the selected edge renders in a highlight
 *       colour with a thicker stroke; click on a line selects it.</li>
 *   <li>Listens to the layout bounds of the zoomable content and redraws
 *       automatically once layout settles, so the first draw doesn't have
 *       to fight the FX pulse timing.</li>
 * </ul>
 */
public class TangleEdgeRenderer {

    public record Edge(String from, String to) {}

    private static final Color EDGE_COLOR = Color.web("#ff5252");
    private static final Color EDGE_HOVER = Color.web("#b71c1c");
    private static final Color SELECTED_COLOR = Color.web("#ffeb3b");
    private static final double EDGE_WIDTH = 0.6;
    private static final double SELECTED_WIDTH = 1.4;
    private static final double ARROW_SIZE = 3.0;

    /**
     * Routing pipeline.
     * <ul>
     *   <li>{@code true} (default) — channel-graph router; sparse, scales to
     *       large architectures, K-track corridors with bridge symbols at
     *       crossings.</li>
     *   <li>{@code false} — legacy pixel-grid router; kept for A/B comparison
     *       until the channel pipeline has soaked.</li>
     * </ul>
     */
    private static final boolean USE_CHANNEL_GRAPH = true;

    /** Diagnostics: when {@code true}, draws every H/V track as a faint guide line
     *  so it's easy to see whether a track was placed inside a class box. */
    private static final boolean DEBUG_TRACKS = true;

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

    /** Optional sink notified when the user clicks an edge in the overlay. */
    private java.util.function.BiConsumer<String, String> onEdgeClicked = (a, b) -> {};

    public TangleEdgeRenderer(Pane pane, Map<String, Node> elementRegistry,
                              Consumer<String> statusCallback) {
        this.pane = Objects.requireNonNull(pane, "pane");
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry");
        this.statusCallback = Objects.requireNonNull(statusCallback, "statusCallback");
    }

    /**
     * Bind the renderer to the zoomable content + overlay pane so it can
     * compute box coordinates, build the routing grid, and react when the
     * layout changes (initial layout pulse, zoom, expand/collapse).
     */
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

    /** Force a re-render — useful after host-driven layout changes. */
    public void requestRedraw() {
        retriesLeft = INITIAL_RETRIES;
        redraw();
    }

    /**
     * Render all edges. If the boxes haven't been laid out yet (the synchronous
     * call lands before the FX layout pulse), nothing draws — the layout
     * listener catches the next bounds change. As an additional safety net we
     * also requeue ourselves a bounded number of times via runLater, since
     * {@code layoutBoundsProperty} doesn't reliably fire when the bounds
     * already have their final value at listener-attach time.
     */
    /**
     * Render all edges via the shared circuit-board routing infrastructure
     * (GridBuilder + RoutingGrid + AStarEdgeRouter). Boxes are obstacles in
     * the grid so lines never cross them; A* tracks per-cell usage so
     * subsequent routes pay a penalty for hugging an existing trace —
     * bidirectional pairs and any edges sharing a corridor naturally
     * separate into parallel runs.
     */
    private void redraw() {
        pane.getChildren().clear();
        if (zoomableContent == null || overlayPane == null || edges.isEmpty()) {
            return;
        }
        // Force layout so the grid sees the boxes' final positions.
        zoomableContent.applyCss();
        zoomableContent.layout();

        if (USE_CHANNEL_GRAPH) {
            redrawWithChannelGraph();
        } else {
            redrawWithPixelGrid();
        }
    }

    private void redrawWithPixelGrid() {
        GridBuilder.Result gb;
        try {
            gb = GridBuilder.build(overlayPane, zoomableContent);
        } catch (Exception ex) {
            scheduleRetry();
            return;
        }
        if (gb.ports.isEmpty()) {
            scheduleRetry();
            return;
        }

        RoutingGrid grid = gb.grid;
        Map<Node, GridBuilder.BoxPorts> ports = gb.ports;
        AStarEdgeRouter router = new AStarEdgeRouter(grid);

        // Render the selected edge LAST so its highlight colour sits on top
        // of any sibling polyline it would otherwise be obscured by.
        List<Edge> rendered = new ArrayList<>(edges);
        rendered.sort((a, b) -> Boolean.compare(isSelected(a), isSelected(b)));

        for (Edge edge : rendered) {
            renderEdge(edge, router, grid, ports);
        }
    }

    private void redrawWithChannelGraph() {
        ChannelGraphBuilder.Result cgb;
        try {
            cgb = ChannelGraphBuilder.build(overlayPane, zoomableContent);
        } catch (Exception ex) {
            scheduleRetry();
            return;
        }
        if (cgb.ports.isEmpty()) {
            scheduleRetry();
            return;
        }
        ChannelGraph graph = cgb.graph;
        ChannelRouter router = new ChannelRouter(graph);
        ChannelPainter painter = new ChannelPainter(graph);

        if (DEBUG_TRACKS) {
            drawDebugTracks(graph);
        }

        // Order: shortest edges first so they grab the cleanest corridors;
        // longer edges then naturally bundle along whatever space remains.
        // Selected edge always rendered LAST so its highlight sits on top.
        List<Edge> rendered = new ArrayList<>(edges);
        rendered.sort((a, b) -> {
            boolean sa = isSelected(a);
            boolean sb = isSelected(b);
            if (sa != sb) return Boolean.compare(sa, sb);
            return Integer.compare(channelDistance(a, cgb.ports), channelDistance(b, cgb.ports));
        });

        for (Edge edge : rendered) {
            renderEdgeViaChannel(edge, router, painter, cgb.ports);
        }
    }

    private int channelDistance(Edge edge, Map<Node, ChannelGraphBuilder.BoxPorts> ports) {
        Node s = elementRegistry.get(edge.from());
        Node t = elementRegistry.get(edge.to());
        if (s == null || t == null) return Integer.MAX_VALUE;
        ChannelGraphBuilder.BoxPorts sp = ports.get(s);
        ChannelGraphBuilder.BoxPorts tp = ports.get(t);
        if (sp == null || tp == null) return Integer.MAX_VALUE;
        ChannelGraphBuilder.Port sAny = firstNonNull(sp);
        ChannelGraphBuilder.Port tAny = firstNonNull(tp);
        if (sAny == null || tAny == null) return Integer.MAX_VALUE;
        return Math.abs(sAny.hIdx - tAny.hIdx) + Math.abs(sAny.vIdx - tAny.vIdx);
    }

    private void drawDebugTracks(ChannelGraph graph) {
        Color guide = Color.color(0.4, 0.6, 1.0, 0.35);
        double w = 0.2;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < graph.nV(); i++) {
            minX = Math.min(minX, graph.vX(i));
            maxX = Math.max(maxX, graph.vX(i));
        }
        for (int i = 0; i < graph.nH(); i++) {
            minY = Math.min(minY, graph.hY(i));
            maxY = Math.max(maxY, graph.hY(i));
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY)) return;
        for (int i = 0; i < graph.nH(); i++) {
            double y = graph.hY(i);
            javafx.scene.shape.Line ln = new javafx.scene.shape.Line(minX, y, maxX, y);
            ln.setStroke(guide);
            ln.setStrokeWidth(w);
            ln.setMouseTransparent(true);
            pane.getChildren().add(ln);
        }
        for (int i = 0; i < graph.nV(); i++) {
            double x = graph.vX(i);
            javafx.scene.shape.Line ln = new javafx.scene.shape.Line(x, minY, x, maxY);
            ln.setStroke(guide);
            ln.setStrokeWidth(w);
            ln.setMouseTransparent(true);
            pane.getChildren().add(ln);
        }
    }

    private static ChannelGraphBuilder.Port firstNonNull(ChannelGraphBuilder.BoxPorts bp) {
        if (bp.top != null) return bp.top;
        if (bp.bottom != null) return bp.bottom;
        if (bp.left != null) return bp.left;
        return bp.right;
    }

    private void scheduleRetry() {
        if (retriesLeft > 0) {
            retriesLeft--;
            javafx.application.Platform.runLater(this::redraw);
        }
    }

    private void renderEdgeViaChannel(Edge edge, ChannelRouter router, ChannelPainter painter,
                                      Map<Node, ChannelGraphBuilder.BoxPorts> ports) {
        Node source = elementRegistry.get(edge.from());
        Node target = elementRegistry.get(edge.to());
        if (source == null || target == null) return;
        if (!isNodeActuallyVisible(source) || !isNodeActuallyVisible(target)) return;

        ChannelGraphBuilder.BoxPorts sPorts = ports.get(source);
        ChannelGraphBuilder.BoxPorts tPorts = ports.get(target);
        if (sPorts == null || tPorts == null) return;

        ChannelGraphBuilder.Port[] picked = ChannelGraphBuilder.pickDirectional(sPorts, tPorts);
        if (picked == null) return;
        ChannelGraphBuilder.Port sp = picked[0];
        ChannelGraphBuilder.Port tp = picked[1];

        List<int[]> path = router.route(sp, tp);
        if (path == null || path.isEmpty()) return;

        boolean selected = isSelected(edge);
        Color color = selected ? SELECTED_COLOR : EDGE_COLOR;
        double width = selected ? SELECTED_WIDTH : EDGE_WIDTH;
        ChannelPainter.Style style = new ChannelPainter.Style(color, width, ARROW_SIZE);

        ChannelPainter.Painted painted = painter.paint(sp, tp, path, style);
        if (painted == null) return;

        painted.line.setOnMouseEntered(e -> {
            if (!isSelected(edge)) painted.line.setStroke(EDGE_HOVER);
            painted.line.setCursor(Cursor.HAND);
        });
        painted.line.setOnMouseExited(e -> {
            painted.line.setStroke(isSelected(edge) ? SELECTED_COLOR : EDGE_COLOR);
            painted.line.setCursor(Cursor.DEFAULT);
        });
        painted.line.setOnMouseClicked(e -> {
            statusCallback.accept(simple(edge.from()) + " → " + simple(edge.to()));
            setSelectedEdge(edge.from(), edge.to());
            onEdgeClicked.accept(edge.from(), edge.to());
            e.consume();
        });

        pane.getChildren().addAll(painted.line, painted.arrow);
    }

    private void renderEdge(Edge edge, AStarEdgeRouter router, RoutingGrid grid,
                            Map<Node, GridBuilder.BoxPorts> ports) {
        Node source = elementRegistry.get(edge.from());
        Node target = elementRegistry.get(edge.to());
        if (source == null || target == null) return;
        if (!isNodeActuallyVisible(source) || !isNodeActuallyVisible(target)) return;

        GridBuilder.BoxPorts sPorts = ports.get(source);
        GridBuilder.BoxPorts tPorts = ports.get(target);
        if (sPorts == null || tPorts == null) return;

        // Direction-aware: source exits BOTTOM/LEFT/RIGHT, target enters
        // TOP/LEFT/RIGHT. Heuristic on relative position picks the cleanest
        // pair; A* then routes the actual obstacle-aware path.
        GridBuilder.Port[] picked = GridBuilder.pickDirectional(sPorts, tPorts);
        GridBuilder.Port sp = picked[0];
        GridBuilder.Port tp = picked[1];

        List<int[]> cells = router.route(sp, tp);
        if (cells == null || cells.isEmpty()) {
            // Fallback: simple L on the grid (may overlap obstacles, but
            // dropping the edge silently is worse — at least the user sees it).
            cells = new ArrayList<>();
            cells.add(new int[]{sp.col, sp.row});
            if (sp.col != tp.col && sp.row != tp.row) {
                cells.add(new int[]{tp.col, sp.row});
            }
            cells.add(new int[]{tp.col, tp.row});
        }

        boolean selected = isSelected(edge);
        Color color = selected ? SELECTED_COLOR : EDGE_COLOR;
        double width = selected ? SELECTED_WIDTH : EDGE_WIDTH;

        Polyline polyline = new Polyline();
        // Start at the actual box-edge stub so the arrow tip aligns with the
        // perimeter, then dog-leg into the first grid cell on the dominant axis.
        polyline.getPoints().addAll(sp.stubX, sp.stubY);
        double firstX = grid.toWorldX(cells.get(0)[0]);
        double firstY = grid.toWorldY(cells.get(0)[1]);
        if (Math.abs(sp.stubX - firstX) > Math.abs(sp.stubY - firstY)) {
            polyline.getPoints().addAll(firstX, sp.stubY);
        } else {
            polyline.getPoints().addAll(sp.stubX, firstY);
        }
        for (int[] cell : cells) {
            polyline.getPoints().addAll(grid.toWorldX(cell[0]), grid.toWorldY(cell[1]));
        }
        // Symmetric dog-leg from last cell to target box-edge stub.
        double lastX = grid.toWorldX(cells.get(cells.size() - 1)[0]);
        double lastY = grid.toWorldY(cells.get(cells.size() - 1)[1]);
        if (Math.abs(tp.stubX - lastX) > Math.abs(tp.stubY - lastY)) {
            polyline.getPoints().addAll(tp.stubX, lastY);
        } else {
            polyline.getPoints().addAll(lastX, tp.stubY);
        }
        polyline.getPoints().addAll(tp.stubX, tp.stubY);

        polyline.setStroke(color);
        polyline.setStrokeWidth(width);
        polyline.setFill(null);
        polyline.setStrokeLineJoin(StrokeLineJoin.MITER);

        // Arrowhead at the box edge — direction is "from last grid cell into
        // the stub", so the head sits exactly on the perimeter and points
        // along the actual approach.
        double pdx = tp.stubX - lastX;
        double pdy = tp.stubY - lastY;
        if (pdx == 0 && pdy == 0) {
            // Degenerate: stub and last cell coincide. Use the prior cell
            // for a usable direction.
            if (cells.size() >= 2) {
                int[] prev = cells.get(cells.size() - 2);
                pdx = tp.stubX - grid.toWorldX(prev[0]);
                pdy = tp.stubY - grid.toWorldY(prev[1]);
            } else {
                pdx = 1;
            }
        }
        Polygon arrow = makeArrowhead(tp.stubX, tp.stubY, pdx, pdy, color);

        polyline.setOnMouseEntered(e -> {
            if (!selected) polyline.setStroke(EDGE_HOVER);
            polyline.setCursor(Cursor.HAND);
        });
        polyline.setOnMouseExited(e -> {
            polyline.setStroke(isSelected(edge) ? SELECTED_COLOR : EDGE_COLOR);
            polyline.setCursor(Cursor.DEFAULT);
        });
        polyline.setOnMouseClicked(e -> {
            statusCallback.accept(simple(edge.from()) + " → " + simple(edge.to()));
            setSelectedEdge(edge.from(), edge.to());
            onEdgeClicked.accept(edge.from(), edge.to());
            e.consume();
        });

        pane.getChildren().addAll(polyline, arrow);
    }

    private boolean isSelected(Edge edge) {
        return selectedFrom != null && selectedTo != null
                && selectedFrom.equals(edge.from()) && selectedTo.equals(edge.to());
    }

    private static Polygon makeArrowhead(double tipX, double tipY, double dx, double dy, Color fill) {
        double angle = Math.atan2(dy, dx);
        double leftX = tipX - ARROW_SIZE * Math.cos(angle - Math.PI / 7);
        double leftY = tipY - ARROW_SIZE * Math.sin(angle - Math.PI / 7);
        double rightX = tipX - ARROW_SIZE * Math.cos(angle + Math.PI / 7);
        double rightY = tipY - ARROW_SIZE * Math.sin(angle + Math.PI / 7);
        Polygon p = new Polygon(tipX, tipY, leftX, leftY, rightX, rightY);
        p.setFill(fill);
        p.setStroke(fill);
        p.setMouseTransparent(true);
        return p;
    }

    private boolean isNodeActuallyVisible(Node node) {
        if (node == null || !node.isVisible()) return false;
        Parent p = node.getParent();
        while (p != null) {
            if (!p.isVisible()) return false;
            p = p.getParent();
        }
        return true;
    }

    private static String simple(String fqn) {
        if (fqn == null) return "";
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    /** All currently rendered shapes — exposed for tests / coverage probes. */
    List<javafx.scene.Node> getRenderedShapes() {
        return new ArrayList<>(pane.getChildren());
    }
}
