package de.weigend.s202.ui.rendering;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.EndpointPair;
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
 * <p>The rollup grouping is exposed via {@link #groupByVisibleEndpoint}
 * so the redraw can paint one arrow per visible endpoint pair, with a
 * badge counting the class-level violations the pair rolls up.
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
        for (Violation violation : groupByVisibleEndpoint(arch)) {
            boolean bothClasses = violation.source() instanceof LevelClassBox
                    && violation.target() instanceof LevelClassBox;
            String badge = bothClasses ? null : Integer.toString(violation.classEdges().size());
            if (violation.source() == violation.target()) {
                drawSelfLoop(violation.source(), badge == null
                        ? Integer.toString(violation.classEdges().size())
                        : badge);
            } else {
                drawCurvedArrow(violation.source(), violation.target(), badge);
            }
        }
    }

    /**
     * Resolve every UPWARD violation in {@code arch} to a pair of
     * currently-visible scene-graph nodes and group the violations by
     * that pair. The aggregation itself runs in the domain via
     * {@link Architecture#groupUpwardViolations(java.util.function.Function)};
     * this method only contributes the UI-specific rollup function
     * (class FQN → currently-visible box FQN) and the FQN→Node lookup.
     */
    public List<Violation> groupByVisibleEndpoint(Architecture arch) {
        if (arch == null || zoomableContent == null) {
            return List.of();
        }
        Map<EndpointPair, List<de.weigend.s202.domain.architecture.Violation>> grouped =
                arch.groupUpwardViolations(this::visibleEndpointFqn);
        List<Violation> result = new ArrayList<>(grouped.size());
        for (Map.Entry<EndpointPair, List<de.weigend.s202.domain.architecture.Violation>> entry
                : grouped.entrySet()) {
            Node src = elementRegistry.get(entry.getKey().source());
            Node tgt = elementRegistry.get(entry.getKey().target());
            if (src == null || tgt == null) {
                continue;
            }
            List<ClassEdge> edges = new ArrayList<>(entry.getValue().size());
            for (de.weigend.s202.domain.architecture.Violation v : entry.getValue()) {
                edges.add(new ClassEdge(v.sourceFqn(), v.targetFqn(), 1));
            }
            result.add(new Violation(src, tgt, Collections.unmodifiableList(edges)));
        }
        return result;
    }

    /**
     * Class FQN → FQN of the closest currently-visible
     * {@link de.weigend.s202.ui.LevelPackageBox} (or the class itself if
     * visible). Returns {@code null} when the class isn't reachable
     * through a visible ancestor — the architecture drops those
     * violations from the aggregation.
     */
    private String visibleEndpointFqn(String fqcn) {
        Node node = elementRegistry.get(fqcn);
        if (node == null) {
            return null;
        }
        if (isActuallyVisible(node)) {
            return fqcn;
        }
        Node n = node.getParent();
        while (n != null) {
            if (n instanceof de.weigend.s202.ui.LevelPackageBox lpb && isActuallyVisible(n)) {
                return lpb.getFullName();
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

    /**
     * Render an upward-violation self-loop above {@code box} with a badge
     * showing how many internal class edges roll up to this single visible
     * endpoint. Drawn as an arc that exits the top of the box on the left,
     * curves up and over, and re-enters from the right with an arrowhead
     * pointing down into the box.
     */
    private void drawSelfLoop(Node box, String badge) {
        double[] b = boundsInPane(box);
        if (b == null) {
            return;
        }
        double width = b[2] - b[0];
        double height = b[3] - b[1];
        double loopHeight = Math.max(20.0, Math.min(width, height) * 0.4);
        double startX = b[0] + width * 0.30;
        double endX = b[0] + width * 0.70;
        double topY = b[1];
        double controlX = (startX + endX) / 2.0;
        double controlY = topY - loopHeight;

        QuadCurve curve = new QuadCurve(startX, topY, controlX, controlY, endX, topY);
        curve.setStroke(UPWARD_COLOR);
        curve.setStrokeWidth(LINE_WIDTH);
        curve.setFill(null);
        curve.getStrokeDashArray().setAll(DASH_ON, DASH_OFF);
        curve.setMouseTransparent(true);

        double tangentX = endX - controlX;
        double tangentY = topY - controlY;
        double angle = Math.atan2(tangentY, tangentX);
        double ax1 = endX - ARROW_SIZE * Math.cos(angle - Math.PI / 6);
        double ay1 = topY - ARROW_SIZE * Math.sin(angle - Math.PI / 6);
        double ax2 = endX - ARROW_SIZE * Math.cos(angle + Math.PI / 6);
        double ay2 = topY - ARROW_SIZE * Math.sin(angle + Math.PI / 6);
        Line arrow1 = new Line(endX, topY, ax1, ay1);
        Line arrow2 = new Line(endX, topY, ax2, ay2);
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
}
