package de.weigend.s202.ui.rendering;

import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;

import java.util.ArrayList;
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

        boolean anyRendered = false;
        for (Edge edge : edges) {
            if (renderEdge(edge)) anyRendered = true;
        }
        if (!anyRendered && retriesLeft > 0) {
            scheduleRetry();
        }
    }

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
