package de.weigend.s202.ui.rendering;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.whatif.ClassEdge;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.QuadCurve;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 4 renderer for "wrong-direction" dependency edges in the
 * architecture view. The visual layout itself encodes the architecture
 * direction — sources are placed above their dependencies. A class edge is
 * a violation precisely when its source box is now positioned <i>below</i>
 * its target box (larger scene-Y). After every DnD move or layout pulse
 * the renderer iterates the static class-edge list, rolls each endpoint up
 * to the closest currently-visible ancestor box, and paints an orange
 * arrow for any edge whose source-Y is greater than its target-Y.
 *
 * <p>Class-to-class edges with both endpoints expanded render as single
 * lines. Edges that roll up to a package on either side aggregate into a
 * single line per (source-box, target-box) pair with an "↑ N" count badge.
 *
 * <p>The same rollup-and-Y-compare logic is exposed via
 * {@link #findVisibleViolations} so the Dependencies-View side panel
 * displays exactly the same violations it sees on the canvas.
 */
public final class WhatIfUpwardEdgeRenderer {

    private static final Color UPWARD_COLOR = Color.BLACK;
    private static final Color BADGE_BG = Color.rgb(30, 60, 120);
    private static final Color BADGE_FG = Color.WHITE;
    private static final double LINE_WIDTH = 1.2;
    private static final double ARROW_SIZE = 8.0;
    private static final double DASH_ON = 6.0;
    private static final double DASH_OFF = 4.0;
    /** Horizontal control-point offset as a fraction of the vertical span. */
    private static final double CURVE_BOW = 0.18;
    private static final Font BADGE_FONT = Font.font(null, FontWeight.BOLD, 10.0);
    private static final double BADGE_PADDING = 3.0;
    private static final double BADGE_MIN_RADIUS = 8.0;

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

    public void redraw(Architecture arch) {
        clear();
        if (arch == null || zoomableContent == null || overlayPane == null) {
            return;
        }
        for (Violation violation : findVisibleViolations(arch)) {
            boolean bothClasses = violation.source() instanceof LevelClassBox
                    && violation.target() instanceof LevelClassBox;
            String badge = bothClasses ? null : Integer.toString(violation.classEdges().size());
            drawCurvedArrow(violation.source(), violation.target(), badge);
        }
    }

    /**
     * Group the architecture's UPWARD violations by their currently-visible
     * source/target box pair. Endpoints are resolved through the element
     * registry — if a class isn't visible (its enclosing package is
     * collapsed), the closest visible ancestor box stands in. Edges whose
     * rollups coincide are filtered out (degenerate self-loop). The model
     * is the single source of truth — the renderer no longer second-
     * guesses it with a scene-Y comparison.
     */
    public List<Violation> findVisibleViolations(Architecture arch) {
        if (arch == null || zoomableContent == null) {
            return List.of();
        }
        Map<EndpointKey, List<ClassEdge>> grouped = new LinkedHashMap<>();
        for (de.weigend.s202.domain.architecture.Violation v : arch.violations()) {
            if (v.kind() != ViolationKind.UPWARD) {
                continue;
            }
            Node src = findVisibleEndpoint(v.sourceFqn());
            Node tgt = findVisibleEndpoint(v.targetFqn());
            if (src == null || tgt == null || src == tgt) {
                continue;
            }
            grouped.computeIfAbsent(new EndpointKey(src, tgt), k -> new ArrayList<>())
                    .add(new ClassEdge(v.sourceFqn(), v.targetFqn(), 1));
        }
        List<Violation> result = new ArrayList<>(grouped.size());
        for (Map.Entry<EndpointKey, List<ClassEdge>> entry : grouped.entrySet()) {
            result.add(new Violation(entry.getKey().source(), entry.getKey().target(),
                    Collections.unmodifiableList(entry.getValue())));
        }
        return result;
    }

    private Node findVisibleEndpoint(String fqcn) {
        Node node = elementRegistry.get(fqcn);
        if (node == null) {
            return null;
        }
        if (isActuallyVisible(node)) {
            return node;
        }
        Node n = node.getParent();
        while (n != null) {
            if (n instanceof de.weigend.s202.ui.LevelPackageBox && isActuallyVisible(n)) {
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

    private void drawCurvedArrow(Node source, Node target, String badge) {
        double[] srcBounds = boundsInPane(source);
        double[] tgtBounds = boundsInPane(target);
        if (srcBounds == null || tgtBounds == null) {
            return;
        }
        // Source: centre-X at top edge (smaller Y). Target: centre-X at bottom edge.
        double startX = (srcBounds[0] + srcBounds[2]) / 2.0;
        double startY = srcBounds[1];
        double endX = (tgtBounds[0] + tgtBounds[2]) / 2.0;
        double endY = tgtBounds[3];

        // Quadratic control point: midway vertically, bowed slightly to the
        // right so multiple arrows between the same lane stay distinguishable.
        double midX = (startX + endX) / 2.0;
        double midY = (startY + endY) / 2.0;
        double verticalSpan = Math.abs(startY - endY);
        double controlX = midX + CURVE_BOW * verticalSpan;
        double controlY = midY;

        QuadCurve curve = new QuadCurve(startX, startY, controlX, controlY, endX, endY);
        curve.setStroke(UPWARD_COLOR);
        curve.setStrokeWidth(LINE_WIDTH);
        curve.setFill(null);
        curve.getStrokeDashArray().setAll(DASH_ON, DASH_OFF);
        curve.setMouseTransparent(true);

        // Arrowhead tangent at the end of the curve (towards target). For a
        // quadratic Bezier the end tangent is endpoint - control.
        double tangentX = endX - controlX;
        double tangentY = endY - controlY;
        double angle = Math.atan2(tangentY, tangentX);
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

        pane.getChildren().addAll(curve, arrow1, arrow2);

        if (badge != null) {
            pane.getChildren().add(buildBadge(badge, controlX, controlY));
        }
    }

    /**
     * Build a small dark-blue filled circle with the count rendered in
     * white at its centre. The circle's radius scales with the text so
     * two- or three-digit counts still fit, and the constant fill ensures
     * the number stays readable even when the badge ends up over another
     * arrow.
     */
    private static Group buildBadge(String text, double cx, double cy) {
        Text label = new Text(text);
        label.setFont(BADGE_FONT);
        label.setFill(BADGE_FG);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setTextOrigin(VPos.CENTER);
        double textW = label.getLayoutBounds().getWidth();
        double textH = label.getLayoutBounds().getHeight();
        double radius = Math.max(BADGE_MIN_RADIUS, Math.max(textW, textH) / 2.0 + BADGE_PADDING);

        Circle circle = new Circle(0, 0, radius);
        circle.setFill(BADGE_BG);
        circle.setStroke(null);

        label.setX(-textW / 2.0);
        label.setY(0);

        Group group = new Group(circle, label);
        group.setLayoutX(cx);
        group.setLayoutY(cy);
        group.setMouseTransparent(true);
        return group;
    }

    private double[] centerInPane(Node node) {
        double[] b = boundsInPane(node);
        if (b == null) {
            return null;
        }
        return new double[]{(b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0};
    }

    /**
     * Returns {@code [minX, minY, maxX, maxY]} of {@code node}'s bounding
     * box in the zoomable-content coordinate space, or {@code null} if the
     * transform can't be computed. Walks the parent chain accumulating
     * per-node {@code boundsInParent} offsets so the result reflects any
     * zoom/scale applied above the node.
     */
    private double[] boundsInPane(Node node) {
        try {
            Bounds localBounds = node.getBoundsInLocal();
            double minX = localBounds.getMinX();
            double minY = localBounds.getMinY();
            double maxX = localBounds.getMaxX();
            double maxY = localBounds.getMaxY();
            Node current = node;
            while (current != null && current != zoomableContent) {
                Bounds boundsInParent = current.getBoundsInParent();
                Bounds localB = current.getBoundsInLocal();
                double dx = boundsInParent.getMinX() - localB.getMinX();
                double dy = boundsInParent.getMinY() - localB.getMinY();
                minX += dx;
                maxX += dx;
                minY += dy;
                maxY += dy;
                current = current.getParent();
            }
            return new double[]{minX, minY, maxX, maxY};
        } catch (Exception ex) {
            return null;
        }
    }

    /** A class edge pair after rollup to currently-visible boxes. */
    public record Violation(Node source, Node target, List<ClassEdge> classEdges) {}

    private record EndpointKey(Node source, Node target) {}
}
