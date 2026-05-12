package de.weigend.s202.ui.rendering;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import de.weigend.s202.ui.whatif.ClassEdge;
import de.weigend.s202.ui.whatif.PackageAggregate;
import de.weigend.s202.ui.whatif.VirtualPackageGraph;
import de.weigend.s202.ui.whatif.WhatIfModel;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 4 renderer for What-If upward dependency edges (ADR §2.5). After
 * each {@link WhatIfModel} change the renderer paints every class-to-class
 * dependency whose virtual source-package level is strictly less than its
 * virtual target-package level — i.e. the edge runs <i>upward</i> against
 * the architectural direction.
 *
 * <p>Rendering granularity follows the user spec:
 * <ul>
 *   <li>If both source and target class boxes are visible (their enclosing
 *       packages are expanded), draw an individual class-to-class line.</li>
 *   <li>Otherwise roll up to the closest visible ancestor box on each end
 *       and draw a single aggregated line with an "↑ N" count label.</li>
 *   <li>If the rollups on both ends coincide, the edge is hidden (it
 *       degenerates into a self-loop on one collapsed package).</li>
 * </ul>
 *
 * <p>Style: warning orange, thicker than the regular dependency stroke,
 * filled-triangle-style arrowhead at the target endpoint.
 */
public final class WhatIfUpwardEdgeRenderer {

    private static final Color UPWARD_COLOR = Color.rgb(217, 70, 30);
    private static final double LINE_WIDTH = 1.5;
    private static final double ARROW_SIZE = 8.0;
    private static final Font BADGE_FONT = Font.font(11.0);

    private final Pane pane;
    private final Map<String, Node> elementRegistry;

    private Pane zoomableContent;
    private Pane overlayPane;

    public WhatIfUpwardEdgeRenderer(Pane pane, Map<String, Node> elementRegistry) {
        this.pane = Objects.requireNonNull(pane, "pane");
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry");
    }

    public void setCoordinateContext(Pane zoomableContent, Pane overlayPane) {
        this.zoomableContent = zoomableContent;
        this.overlayPane = overlayPane;
    }

    public void clear() {
        pane.getChildren().clear();
    }

    /**
     * Repaint all upward edges for the given What-If model. Idempotent — a
     * {@code null} model simply clears the pane.
     */
    public void redraw(WhatIfModel model) {
        clear();
        if (model == null || zoomableContent == null || overlayPane == null) {
            return;
        }
        VirtualPackageGraph graph = model.graph();
        Map<EndpointKey, MutableCount> rollups = new HashMap<>();

        for (PackageAggregate aggregate : model.aggregator().aggregates().values()) {
            if (!isWrongDirection(graph, aggregate)) {
                continue;
            }
            for (ClassEdge edge : aggregate.classEdges()) {
                Node srcEndpoint = findVisibleEndpoint(edge.source());
                Node tgtEndpoint = findVisibleEndpoint(edge.target());
                if (srcEndpoint == null || tgtEndpoint == null || srcEndpoint == tgtEndpoint) {
                    continue;
                }
                boolean bothClassesVisible =
                        srcEndpoint instanceof LevelClassBox && tgtEndpoint instanceof LevelClassBox;
                if (bothClassesVisible) {
                    drawArrow(srcEndpoint, tgtEndpoint, null);
                } else {
                    rollups.computeIfAbsent(new EndpointKey(srcEndpoint, tgtEndpoint),
                            k -> new MutableCount()).count++;
                }
            }
        }

        for (Map.Entry<EndpointKey, MutableCount> entry : rollups.entrySet()) {
            EndpointKey key = entry.getKey();
            drawArrow(key.source(), key.target(), "↑ " + entry.getValue().count);
        }
    }

    /**
     * An aggregated package edge is "wrong-direction" if its source virtual
     * level lies below the target's (a classic level-violation), or if both
     * endpoints sit inside the same virtual tangle. Cycle edges have equal
     * SCC levels by construction, so the plain srcLevel &lt; tgtLevel check
     * misses them — but they are exactly the edges the user wants to see
     * after a move that introduces or preserves a cycle.
     */
    public static boolean isWrongDirection(VirtualPackageGraph graph, PackageAggregate aggregate) {
        int srcLevel = graph.levelOf(aggregate.source());
        int tgtLevel = graph.levelOf(aggregate.target());
        if (srcLevel >= 0 && tgtLevel >= 0 && srcLevel < tgtLevel) {
            return true;
        }
        int srcScc = graph.sccIdOf(aggregate.source());
        int tgtScc = graph.sccIdOf(aggregate.target());
        return srcScc >= 0 && srcScc == tgtScc && graph.isInTangle(aggregate.source());
    }

    private Node findVisibleEndpoint(String fqcn) {
        Node node = elementRegistry.get(fqcn);
        if (node == null) {
            return null;
        }
        if (isActuallyVisible(node)) {
            return node;
        }
        // Roll up via the scene graph — reflects where the box actually lives
        // after any What-If move, not the static package chain.
        Node n = node.getParent();
        while (n != null) {
            if (n instanceof LevelPackageBox && isActuallyVisible(n)) {
                return n;
            }
            n = n.getParent();
        }
        return null;
    }

    private static boolean isActuallyVisible(Node node) {
        if (node == null || !node.isVisible()) {
            return false;
        }
        Parent parent = node.getParent();
        while (parent != null) {
            if (!parent.isVisible()) {
                return false;
            }
            parent = parent.getParent();
        }
        return true;
    }

    private void drawArrow(Node source, Node target, String badge) {
        double[] sc = centerInPane(source);
        double[] tc = centerInPane(target);
        if (sc == null || tc == null) {
            return;
        }
        double startX = sc[0];
        double startY = sc[1];
        double endX = tc[0];
        double endY = tc[1];

        Line line = new Line(startX, startY, endX, endY);
        line.setStroke(UPWARD_COLOR);
        line.setStrokeWidth(LINE_WIDTH);
        line.setMouseTransparent(true);

        double angle = Math.atan2(endY - startY, endX - startX);
        double ax1 = endX - ARROW_SIZE * Math.cos(angle - Math.PI / 6);
        double ay1 = endY - ARROW_SIZE * Math.sin(angle - Math.PI / 6);
        double ax2 = endX - ARROW_SIZE * Math.cos(angle + Math.PI / 6);
        double ay2 = endY - ARROW_SIZE * Math.sin(angle + Math.PI / 6);
        Line arrow1 = new Line(endX, endY, ax1, ay1);
        Line arrow2 = new Line(endX, endY, ax2, ay2);
        for (Line a : new Line[]{arrow1, arrow2}) {
            a.setStroke(UPWARD_COLOR);
            a.setStrokeWidth(LINE_WIDTH);
            a.setMouseTransparent(true);
        }

        pane.getChildren().addAll(line, arrow1, arrow2);

        if (badge != null) {
            double midX = (startX + endX) / 2.0;
            double midY = (startY + endY) / 2.0;
            Text label = new Text(midX + 4, midY - 4, badge);
            label.setFont(BADGE_FONT);
            label.setFill(UPWARD_COLOR);
            label.setMouseTransparent(true);
            pane.getChildren().add(label);
        }
    }

    /**
     * Coordinate transform from {@code node}'s local space to the overlay
     * pane. Mirrors {@code DependencyRenderer.getNodeCenterInPane} so both
     * renderers stay locked to the same overlay frame.
     */
    private double[] centerInPane(Node node) {
        try {
            Bounds localBounds = node.getBoundsInLocal();
            Node current = node;
            double centerX = localBounds.getMinX() + localBounds.getWidth() / 2;
            double centerY = localBounds.getMinY() + localBounds.getHeight() / 2;
            while (current != null && current != zoomableContent) {
                Bounds boundsInParent = current.getBoundsInParent();
                Bounds localB = current.getBoundsInLocal();
                centerX = centerX - localB.getMinX() + boundsInParent.getMinX();
                centerY = centerY - localB.getMinY() + boundsInParent.getMinY();
                current = current.getParent();
            }
            return new double[]{centerX, centerY};
        } catch (Exception ex) {
            return null;
        }
    }

    private record EndpointKey(Node source, Node target) {}

    private static final class MutableCount {
        int count;
    }
}
