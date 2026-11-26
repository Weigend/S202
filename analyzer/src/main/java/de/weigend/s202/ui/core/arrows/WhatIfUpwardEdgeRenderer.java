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
package de.weigend.s202.ui.core.arrows;

import de.weigend.s202.ui.core.graph.BoxVisibility;
import de.weigend.s202.ui.core.graph.ElementLookup;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.EndpointPair;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.graph.ContainerBox;
import de.weigend.s202.ui.core.arrows.ClassEdge;
import javafx.geometry.Bounds;
import javafx.scene.Node;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Renderer for style-specific dependency violations in the architecture view.
 * The default is the layered/What-If UPWARD overlay; component views pass their
 * own violation kinds while reusing the same visible-endpoint rollup.
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

    static final String OVERLAY = "violations";

    private static final Color BADGE_FG = Color.WHITE;
    private static final double LINE_WIDTH = 1.2;
    private static final double ARROW_SIZE = 8.0;
    private static final double HIT_WIDTH = 8.0;
    /** Horizontal control-point offset as a fraction of the vertical span. */
    private static final double CURVE_BOW = 0.18;
    private static final Font BADGE_FONT = Font.font(null, FontWeight.BOLD, 10.0);
    private static final double BADGE_PADDING = 3.0;
    private static final double BADGE_MIN_RADIUS = 8.0;

    private final Pane pane;
    private final Map<String, Node> elementRegistry;

    private Pane zoomableContent;
    private Pane overlayPane;
    private BiPredicate<String, String> edgeFilter = (from, to) -> true;
    private LineSelection selection = new LineSelection(status -> { });

    public WhatIfUpwardEdgeRenderer(Pane pane, Map<String, Node> elementRegistry) {
        this.pane = Objects.requireNonNull(pane, "pane");
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry");
    }

    public void setCoordinateContext(Pane zoomableContent, Pane overlayPane) {
        this.zoomableContent = zoomableContent;
        this.overlayPane = overlayPane;
    }

    /** The class dependencies to draw at all; the others are left out of the picture. */
    public void setEdgeFilter(BiPredicate<String, String> edgeFilter) {
        this.edgeFilter = edgeFilter == null ? (from, to) -> true : edgeFilter;
    }

    /** Shares the selected line with the other overlays. */
    public void setLineSelection(LineSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public void clear() {
        pane.getChildren().clear();
        selection.cleared(OVERLAY);
    }

    public void redraw(Architecture arch) {
        redraw(arch, Set.of(ViolationKind.UPWARD));
    }

    public void redraw(Architecture arch, Set<ViolationKind> kinds) {
        clear();
        if (arch == null || zoomableContent == null || overlayPane == null) {
            return;
        }
        for (Violation violation : groupByVisibleEndpoint(arch, kinds)) {
            boolean bothClasses = violation.source() instanceof LevelClassBox
                    && violation.target() instanceof LevelClassBox;
            String badge = bothClasses ? null : Integer.toString(violation.classEdges().size());
            if (violation.source() == violation.target()) {
                drawSelfLoop(violation, badge == null
                        ? Integer.toString(violation.classEdges().size())
                        : badge);
            } else {
                drawCurvedArrow(violation, badge);
            }
        }
    }

    /**
     * Against the layers is red, and dotted if only a move made it so; a broken
     * rule of another style is black.
     */
    private static Color colorOf(Violation violation) {
        return violation.kinds().contains(ViolationKind.UPWARD) ? LineColors.AGAINST_LAYERS : LineColors.BROKEN_RULE;
    }

    /** Adds the line of a violation with its head and number, and lets the user select and cut it. */
    private void add(Violation violation, QuadCurve curve, List<Line> head, Group badge) {
        Color color = colorOf(violation);
        curve.setFill(null);
        if (violation.kinds().contains(ViolationKind.UPWARD) && !violation.containsBackEdge()) {
            LineColors.dot(curve);
        }
        LineSelection.Look look = new LineSelection.Look() {
            @Override
            public void selected(boolean selected) {
                stroke(selected ? LineColors.SELECTED : color, selected ? LINE_WIDTH * 2 : LINE_WIDTH);
            }

            @Override
            public void hovered(boolean hovered) {
                stroke(color, hovered ? LINE_WIDTH * 2 : LINE_WIDTH);
            }

            private void stroke(Color stroke, double width) {
                curve.setStroke(stroke);
                curve.setStrokeWidth(width);
                head.forEach(part -> {
                    part.setStroke(stroke);
                    part.setStrokeWidth(width);
                });
            }
        };
        look.selected(false);
        QuadCurve area = LineSelection.hitArea(new QuadCurve(curve.getStartX(), curve.getStartY(),
                curve.getControlX(), curve.getControlY(), curve.getEndX(), curve.getEndY()), HIT_WIDTH);
        LineSelection.Key key = new LineSelection.Key(OVERLAY, violation.from(), violation.to(),
                violation.classEdges().stream().map(e -> new DependencyEdge(e.source(), e.target())).toList());
        List<Node> catching = badge == null ? List.of(area, curve) : List.of(area, curve, badge);
        for (Node node : catching) {
            selection.install(node, key, look);
            if (violation.source() instanceof LevelClassBox && violation.target() instanceof LevelClassBox) {
                selection.installCutMenu(node, violation.from(), violation.to());
            }
        }
        head.forEach(part -> part.setMouseTransparent(true));
        pane.getChildren().addAll(area, curve);
        pane.getChildren().addAll(head);
        if (badge != null) {
            pane.getChildren().add(badge);
        }
        selection.drawn(key, look);
    }

    /**
     * Resolve style-specific violations in {@code arch} to currently-visible
     * scene-graph endpoint pairs. The aggregation itself runs in the domain;
     * this method only contributes the UI-specific rollup function and
     * FQN-to-Node lookup.
     */
    public List<Violation> groupByVisibleEndpoint(Architecture arch) {
        return groupByVisibleEndpoint(arch, Set.of(ViolationKind.UPWARD));
    }

    public List<Violation> groupByVisibleEndpoint(Architecture arch, Set<ViolationKind> kinds) {
        if (arch == null || zoomableContent == null) {
            return List.of();
        }
        Map<EndpointPair, List<de.weigend.s202.domain.architecture.Violation>> grouped =
                arch.groupViolations(this::visibleEndpointFqn, kinds);
        List<Violation> result = new ArrayList<>(grouped.size());
        for (Map.Entry<EndpointPair, List<de.weigend.s202.domain.architecture.Violation>> entry
                : grouped.entrySet()) {
            Node src = elementRegistry.get(entry.getKey().source());
            Node tgt = elementRegistry.get(entry.getKey().target());
            if (src == null || tgt == null) {
                continue;
            }
            List<ClassEdge> edges = new ArrayList<>(entry.getValue().size());
            Set<ViolationKind> found = EnumSet.noneOf(ViolationKind.class);
            boolean containsBackEdge = false;
            for (de.weigend.s202.domain.architecture.Violation v : entry.getValue()) {
                if (edgeFilter.test(v.sourceFqn(), v.targetFqn())) {
                    edges.add(new ClassEdge(v.sourceFqn(), v.targetFqn(), 1));
                    containsBackEdge |= v.backEdge();
                    found.add(v.kind());
                }
            }
            if (edges.isEmpty()) {
                continue;
            }
            result.add(new Violation(entry.getKey().source(), entry.getKey().target(), src, tgt,
                    Collections.unmodifiableList(edges), found, containsBackEdge));
        }
        return result;
    }

    /**
     * Class FQN → FQN of the closest currently-visible
     * {@link ContainerBox}-Container (Paket- oder Komponenten-Box)
     * (or the class itself if visible). Returns {@code null} when the
     * class isn't reachable through a visible ancestor — the architecture drops those
     * violations from the aggregation.
     */
    private String visibleEndpointFqn(String fqcn) {
        Node node = elementRegistry.get(fqcn);
        if (node == null) {
            // No box yet: the class lies in a collapsed package that builds
            // its content lazily. That package stands in for it.
            LevelPackageBox pending = ElementLookup.pendingContainer(elementRegistry, fqcn);
            if (pending == null) {
                return null;
            }
            if (isShown(pending)) {
                return pending.getFullName();
            }
            node = pending;
        } else if (isShown(node)) {
            return fqcn;
        }
        Node n = node.getParent();
        while (n != null) {
            Object rollup = n.getProperties().get("s202.rollupEndpointFqn");
            if (rollup instanceof String endpointFqn) {
                Node endpoint = elementRegistry.get(endpointFqn);
                if (endpoint != null && isShown(endpoint)) {
                    return endpointFqn;
                }
            }
            if (n instanceof ContainerBox cb && isShown(n)) {
                return cb.getFullName();
            }
            n = n.getParent();
        }
        return null;
    }

    private boolean isShown(Node node) {
        return BoxVisibility.isShown(node, zoomableContent);
    }

    /**
     * Render an upward-violation self-loop above {@code box} with a badge
     * showing how many internal class edges roll up to this single visible
     * endpoint. Drawn as an arc that exits the top of the box on the left,
     * curves up and over, and re-enters from the right with an arrowhead
     * pointing down into the box.
     */
    private void drawSelfLoop(Violation violation, String badge) {
        double[] b = boundsInPane(violation.source());
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

        double tangentX = endX - controlX;
        double tangentY = topY - controlY;
        double angle = Math.atan2(tangentY, tangentX);
        double ax1 = endX - ARROW_SIZE * Math.cos(angle - Math.PI / 6);
        double ay1 = topY - ARROW_SIZE * Math.sin(angle - Math.PI / 6);
        double ax2 = endX - ARROW_SIZE * Math.cos(angle + Math.PI / 6);
        double ay2 = topY - ARROW_SIZE * Math.sin(angle + Math.PI / 6);
        Line arrow1 = new Line(endX, topY, ax1, ay1);
        Line arrow2 = new Line(endX, topY, ax2, ay2);
        add(violation, curve, List.of(arrow1, arrow2),
                badge == null ? null : buildBadge(badge, controlX, controlY, colorOf(violation)));
    }

    private void drawCurvedArrow(Violation violation, String badge) {
        double[] srcBounds = boundsInPane(violation.source());
        double[] tgtBounds = boundsInPane(violation.target());
        if (srcBounds == null || tgtBounds == null) {
            return;
        }
        double sourceCenterY = (srcBounds[1] + srcBounds[3]) / 2.0;
        double targetCenterY = (tgtBounds[1] + tgtBounds[3]) / 2.0;
        boolean sourceAboveTarget = sourceCenterY <= targetCenterY;

        // Direction follows the actual visual order: down-going dependencies
        // leave the source bottom and enter the target top; up-going
        // violations leave the source top and enter the target bottom.
        double startX = (srcBounds[0] + srcBounds[2]) / 2.0;
        double startY = sourceAboveTarget ? srcBounds[3] : srcBounds[1];
        double endX = (tgtBounds[0] + tgtBounds[2]) / 2.0;
        double endY = sourceAboveTarget ? tgtBounds[1] : tgtBounds[3];

        // Quadratic control point: midway vertically, bowed slightly to the
        // right so multiple arrows between the same lane stay distinguishable.
        double midX = (startX + endX) / 2.0;
        double midY = (startY + endY) / 2.0;
        double verticalSpan = Math.abs(startY - endY);
        double controlX = midX + CURVE_BOW * verticalSpan;
        double controlY = midY;

        QuadCurve curve = new QuadCurve(startX, startY, controlX, controlY, endX, endY);

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
        add(violation, curve, List.of(arrow1, arrow2),
                badge == null ? null : buildBadge(badge, controlX, controlY, colorOf(violation)));
    }

    /**
     * Build a small filled circle with the count rendered in
     * white at its centre. The circle's radius scales with the text so
     * two- or three-digit counts still fit, and the constant fill ensures
     * the number stays readable even when the badge ends up over another
     * arrow.
     */
    private static Group buildBadge(String text, double cx, double cy, Color color) {
        Text label = new Text(text);
        label.setFont(BADGE_FONT);
        label.setFill(BADGE_FG);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setTextOrigin(VPos.CENTER);
        double textW = label.getLayoutBounds().getWidth();
        double textH = label.getLayoutBounds().getHeight();
        double radius = Math.max(BADGE_MIN_RADIUS, Math.max(textW, textH) / 2.0 + BADGE_PADDING);

        Circle circle = new Circle(0, 0, radius);
        circle.setFill(color);
        circle.setStroke(null);

        label.setX(-textW / 2.0);
        label.setY(0);

        Group group = new Group(circle, label);
        group.setLayoutX(cx);
        group.setLayoutY(cy);
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
     * box in the overlay pane's coordinate space, or {@code null} if the
     * transform can't be computed. Uses JavaFX's scene transform APIs instead
     * of manually walking bounds, which avoids stale offsets after layout/CSS
     * changes.
     */
    private double[] boundsInPane(Node node) {
        try {
            if (node == null || pane == null || node.getScene() != pane.getScene()) {
                return null;
            }
            Bounds bounds = pane.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            if (bounds == null
                    || !Double.isFinite(bounds.getMinX())
                    || !Double.isFinite(bounds.getMinY())
                    || !Double.isFinite(bounds.getWidth())
                    || !Double.isFinite(bounds.getHeight())
                    || bounds.getWidth() <= 1.0
                    || bounds.getHeight() <= 1.0) {
                return null;
            }
            return new double[]{bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()};
        } catch (Exception ex) {
            return null;
        }
    }

    /** A class edge pair after rollup to currently-visible boxes. */
    public record Violation(String from, String to, Node source, Node target, List<ClassEdge> classEdges,
                            Set<ViolationKind> kinds, boolean containsBackEdge) {}
}
