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

import de.weigend.s202.domain.DependencyEdge;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Draws and selects the actual dependency arrow shapes on the dependency pane.
 * {@link DependencyRenderer} decides WHICH arrows to draw (traversal,
 * aggregation, rollup); this painter owns the geometry, colors, hover/click
 * selection, the optional aggregate count badge and the optional weight.
 */
final class DependencyArrowPainter {

    /**
     * One arrow between two visible boxes.
     *
     * @param classEdge the arrow stands for exactly one class dependency, so
     *                  it can be cut and restored via its context menu; a cut
     *                  arrow is drawn dashed
     * @param edges     the class dependencies the arrow stands for
     * @param badge     shown in a filled circle beside the line — usually the
     *                  number of underlying class dependencies; {@code null}
     *                  for none. It turns to the violation colour only when
     *                  {@code containsWrongDirection} says the aggregate holds
     *                  a semantic wrong-direction edge — never based on pixel
     *                  direction
     * @param weight    written on the line; {@code null} for none
     * @param style     colour, weight and pattern of a tangle view;
     *                  {@code null} for the regular look
     */
    record Arrow(Node source, Node target, String sourceName, String targetName, boolean incoming,
                 boolean classEdge, List<DependencyEdge> edges, String badge, boolean containsWrongDirection,
                 String weight, ArrowStyle style) {
    }

    static final String OVERLAY = "dependencies";

    private static final double DEPENDENCY_WIDTH = 0.6;

    private static final Color BADGE_BG           = Color.rgb(0, 140, 0);    // normal direction
    private static final Color BADGE_FG  = Color.WHITE;
    private static final Font  BADGE_FONT = Font.font(null, FontWeight.BOLD, 5.0);
    private static final double BADGE_PADDING    = 1.5;
    private static final double BADGE_MIN_RADIUS = 4.0;

    /**
     * How wide the invisible area is that catches the mouse for an arrow of a
     * tangle view, in pixels on the screen — and at most in the view's own
     * units, so that zoomed out the areas do not cover everything.
     */
    private static final double HIT_WIDTH = 12.0;
    private static final double MAX_HIT_WIDTH = 16.0;
    private static final double HOVER_WIDTH_FACTOR = 2.0;
    /** The arrows of the box under the mouse, and what becomes of the others. */
    private static final double EMPHASIS_WIDTH_FACTOR = 2.2;
    private static final double OTHERS_OPACITY = 0.35;

    /** Distance between the two arrows of a mutual dependency. */
    private static final double OPPOSED_GAP = 6.0;
    /** How far along its line an arrow carries its weight; opposed arrows keep theirs near the source. */
    private static final double WEIGHT_POSITION = 0.5;
    private static final double OPPOSED_WEIGHT_POSITION = 0.35;
    private static final Font WEIGHT_FONT = Font.font(null, FontWeight.BOLD, 7.0);
    private static final double WEIGHT_PADDING = 1.0;
    /** The canvas colour: the weight interrupts its line instead of sitting beside it. */
    private static final Color WEIGHT_BACKGROUND = Color.web("#f5f5f0");

    private final Pane dependencyPane;
    private LineSelection selection;

    private final List<Drawn> drawn = new ArrayList<>();
    /** The box whose arrows stand out; {@code null} for none. */
    private Node emphasised;
    /** The mouse areas of the arrows, below all lines: a line hit directly wins over a neighbour's area. */
    private final Group hitAreas = new Group();

    /** An arrow in the picture, and how it looks when nothing is the matter with it. */
    private final class Drawn implements LineSelection.Look {
        private final Arrow arrow;
        private final Shape line;
        private final List<Line> head;
        private final List<Node> labels = new ArrayList<>();
        private final Color color;
        private final double width;
        private final double opacity;

        private Drawn(Arrow arrow, Shape line, List<Line> head, Color color, double width, double opacity) {
            this.arrow = arrow;
            this.line = line;
            this.head = head;
            this.color = color;
            this.width = width;
            this.opacity = opacity;
        }

        @Override
        public void selected(boolean selected) {
            if (selected) {
                stroke(LineColors.SELECTED, Math.max(width, DEPENDENCY_WIDTH) * 2);
                opacity(1.0);
            } else {
                normal();
            }
        }

        /** The colours have a meaning: an arrow under the mouse only gets thicker. */
        @Override
        public void hovered(boolean hovered) {
            stroke(color, width * (hovered ? HOVER_WIDTH_FACTOR : 1.0));
        }

        /** As it is when not selected: its own look, or that the box under the mouse gives it. */
        private void normal() {
            if (emphasised == null) {
                stroke(color, width);
                opacity(opacity);
                return;
            }
            boolean outgoing = arrow.source() == emphasised;
            boolean incoming = arrow.target() == emphasised;
            if (outgoing || incoming) {
                boolean plain = color.equals(LineColors.USES) || color.equals(LineColors.USED_BY);
                stroke(!plain ? color : outgoing ? LineColors.USES : LineColors.USED_BY, width * EMPHASIS_WIDTH_FACTOR);
                opacity(1.0);
            } else {
                stroke(color, width);
                opacity(Math.min(opacity, OTHERS_OPACITY));
            }
        }

        private void stroke(Color newColor, double newWidth) {
            line.setStroke(newColor);
            line.setStrokeWidth(newWidth);
            head.forEach(part -> {
                part.setStroke(newColor);
                part.setStrokeWidth(newWidth);
            });
        }

        private void opacity(double newOpacity) {
            line.setOpacity(newOpacity);
            head.forEach(part -> part.setOpacity(newOpacity));
            labels.forEach(label -> label.setOpacity(newOpacity));
        }
    }

    DependencyArrowPainter(Pane dependencyPane, Consumer<String> statusCallback) {
        this.dependencyPane = Objects.requireNonNull(dependencyPane, "dependencyPane cannot be null");
        this.selection = new LineSelection(statusCallback);
    }

    /** Shares the selection with the other overlays of the view. */
    void setSelection(LineSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection cannot be null");
    }

    /** Selection handling for all arrows, cut and restore for class-to-class ones; {@code null} disables it. */
    void setEdgeActions(DependencyEdgeActions edgeActions) {
        selection.setActions(edgeActions);
    }

    /** Removes all drawn arrows. */
    void clear() {
        dependencyPane.getChildren().clear();
        hitAreas.getChildren().clear();
        drawn.clear();
        selection.cleared(OVERLAY);
    }

    /**
     * Lets the arrows of {@code box} stand out; the others step back, but stay
     * in the picture. {@code null} gives all of them their look back.
     *
     * @return the boxes at the other ends of its arrows
     */
    List<Node> emphasise(Node box) {
        emphasised = box;
        List<Node> connected = new ArrayList<>();
        for (Drawn arrow : drawn) {
            if (!selection.isSelected(arrow)) {
                arrow.normal();
            }
            if (box != null && arrow.arrow.source() == box) {
                connected.add(arrow.arrow.target());
            } else if (box != null && arrow.arrow.target() == box) {
                connected.add(arrow.arrow.source());
            }
        }
        return connected;
    }

    /**
     * Draws one selectable dependency arrow: a straight line from box to box.
     *
     * @param opposed the arrow of the reverse direction is drawn as well. Both
     *                would run along the same line, so each moves to its own side.
     * @param boxes   the boxes no arrow may run through, with their bounds; an
     *                arrow whose line crosses one takes an arc around it. Empty
     *                where arrows simply run straight.
     */
    void draw(Arrow arrow, boolean opposed, Map<Node, double[]> boxes) {
        Node source = arrow.source();
        Node target = arrow.target();
        String sourceName = arrow.sourceName();
        String targetName = arrow.targetName();
        boolean isIncoming = arrow.incoming();
        String badge = arrow.badge();
        boolean containsWrongDirection = arrow.containsWrongDirection();
        boolean classEdge = arrow.classEdge();
        ArrowStyle style = arrow.style();
        try {
            double[] srcB = getBoundsInPane(source);
            double[] tgtB = getBoundsInPane(target);

            if (srcB == null || tgtB == null) {
                return;
            }

            double srcCenterY = (srcB[1] + srcB[3]) / 2.0;
            double tgtCenterY = (tgtB[1] + tgtB[3]) / 2.0;
            double startX;
            double startY;
            double endX;
            double endY;
            if (sideBySide(srcB, tgtB)) {
                // Neighbours in one row: from the facing side to the facing
                // side. Top and bottom edges would send the line across both.
                boolean rightward = srcB[2] <= tgtB[0];
                startX = rightward ? srcB[2] : srcB[0];
                endX   = rightward ? tgtB[0] : tgtB[2];
                startY = srcCenterY;
                endY   = tgtCenterY;
                if (opposed) {
                    // Each arrow moves to its own left: rightward up, leftward down.
                    double room = Math.max(0.0, Math.min(srcB[3] - srcB[1], tgtB[3] - tgtB[1]) / 2.0 - 1.0);
                    double shift = Math.min(OPPOSED_GAP / 2.0, room);
                    startY += rightward ? -shift : shift;
                    endY   += rightward ? -shift : shift;
                }
            } else {
                // Normal direction: source above target → bottom edge to top edge.
                // Violation: source below target → top edge to bottom edge.
                boolean normalDir = srcCenterY <= tgtCenterY;
                startX = (srcB[0] + srcB[2]) / 2.0;
                startY = normalDir ? srcB[3] : srcB[1];
                endX   = (tgtB[0] + tgtB[2]) / 2.0;
                endY   = normalDir ? tgtB[1] : tgtB[3];
                if (opposed) {
                    double shift = opposedShift(startX, startY, endX, endY, srcB, tgtB);
                    startX += normalDir ? shift : -shift;
                    endX   += normalDir ? shift : -shift;
                }
            }

            boolean cut = classEdge && selection.isCut(sourceName, targetName);

            // A cut dependency has its own color; a tangle view styles the
            // arrow by what the dependency is; otherwise the direction decides.
            Color lineColor = cut ? LineColors.CUT
                    : style != null ? style.color()
                    : isIncoming ? LineColors.USED_BY : LineColors.USES;
            double opacity = style != null ? style.opacity() : 1.0;

            // Create the line
            double scaledWidth = style != null && !cut ? style.width() : getScaledLineWidth();
            ArrowRoute route = route(startX, startY, endX, endY, source, target, boxes, opposed);
            Shape line = route.toShape();
            line.setStroke(lineColor);
            line.setStrokeWidth(scaledWidth);
            line.setOpacity(opacity);
            if (cut) {
                LineColors.dot(line);
            } else if (style != null && style.dashed()) {
                LineColors.dash(line);
            }

            // Create arrowhead
            double arrowSize = getScaledArrowSize();
            double angle = route.endAngle();

            double x1 = endX - arrowSize * Math.cos(angle - Math.PI / 6);
            double y1 = endY - arrowSize * Math.sin(angle - Math.PI / 6);
            double x2 = endX - arrowSize * Math.cos(angle + Math.PI / 6);
            double y2 = endY - arrowSize * Math.sin(angle + Math.PI / 6);

            Line arrow1 = new Line(endX, endY, x1, y1);
            Line arrow2 = new Line(endX, endY, x2, y2);
            arrow1.setStroke(lineColor);
            arrow2.setStroke(lineColor);
            arrow1.setStrokeWidth(scaledWidth);
            arrow2.setStrokeWidth(scaledWidth);
            arrow1.setOpacity(opacity);
            arrow2.setOpacity(opacity);
            arrow1.setMouseTransparent(true);
            arrow2.setMouseTransparent(true);

            Drawn look = new Drawn(arrow, line, List.of(arrow1, arrow2), lineColor, scaledWidth, opacity);
            LineSelection.Key key = new LineSelection.Key(OVERLAY, sourceName, targetName, arrow.edges());
            installInteraction(line, key, look);
            // A highlight pushes the others back; those must not be in the way of the mouse.
            boolean easyToHit = style != null && style.opacity() == 1.0;
            if (easyToHit) {
                addHitArea(route.toShape(), key, look);
            }

            dependencyPane.getChildren().addAll(line, arrow1, arrow2);

            if (badge != null) {
                // Badge beside the line midpoint, offset perpendicular to the line
                double[] mid = route.at(0.5);
                double midX = mid[0];
                double midY = mid[1];
                double dx   = endX - startX;
                double dy   = endY - startY;
                double len  = Math.max(1.0, Math.sqrt(dx * dx + dy * dy));
                double offset = BADGE_MIN_RADIUS + 2.0;
                double badgeX = midX + (-dy / len) * offset;
                double badgeY = midY + ( dx / len) * offset;
                Color badgeBg = containsWrongDirection ? LineColors.AGAINST_LAYERS : BADGE_BG;
                Group badgeNode = buildBadge(badge, badgeX, badgeY, badgeBg);
                badgeNode.setOpacity(opacity);
                look.labels.add(badgeNode);
                dependencyPane.getChildren().add(badgeNode);
            }

            if (arrow.weight() != null) {
                double[] at = route.at(opposed ? OPPOSED_WEIGHT_POSITION : WEIGHT_POSITION);
                Group weightNode = buildWeight(arrow.weight(), at[0], at[1], lineColor);
                weightNode.setOpacity(opacity);
                look.labels.add(weightNode);
                if (easyToHit) {
                    weightNode.setMouseTransparent(false);
                    installInteraction(weightNode, key, look);
                }
                dependencyPane.getChildren().add(weightNode);
            }

            drawn.add(look);
            if (emphasised != null) {
                look.normal();
            }
            selection.drawn(key, look);

        } catch (Exception e) {
            // Ignore drawing errors for elements not in scene
        }
    }

    private void installInteraction(Node target, LineSelection.Key key, Drawn look) {
        selection.install(target, key, look);
        if (look.arrow.classEdge()) {
            selection.installCutMenu(target, look.arrow.sourceName(), look.arrow.targetName());
        }
    }

    private void addHitArea(Shape area, LineSelection.Key key, Drawn look) {
        installInteraction(LineSelection.hitArea(area, hitWidth()), key, look);
        if (hitAreas.getParent() != dependencyPane) {
            hitAreas.getChildren().clear();
            dependencyPane.getChildren().add(0, hitAreas);
        }
        hitAreas.getChildren().add(area);
    }

    private double hitWidth() {
        double scale = dependencyPane.getLocalToSceneTransform().getMxx();
        return scale > 0 ? Math.min(MAX_HIT_WIDTH, HIT_WIDTH / scale) : HIT_WIDTH;
    }

    /** The straight line, or an arc around the boxes that lie on it. */
    private static ArrowRoute route(double startX, double startY, double endX, double endY,
                                    Node source, Node target, Map<Node, double[]> boxes, boolean opposed) {
        if (boxes.isEmpty()) {
            return ArrowRoute.straight(startX, startY, endX, endY);
        }
        List<double[]> others = new ArrayList<>(boxes.size());
        for (Map.Entry<Node, double[]> box : boxes.entrySet()) {
            if (box.getKey() != source && box.getKey() != target) {
                others.add(box.getValue());
            }
        }
        return ArrowRoute.around(startX, startY, endX, endY, others, opposed);
    }

    /**
     * True when two boxes stand beside each other in one row: each one's
     * vertical centre lies within the height of the other, and they do not
     * overlap horizontally.
     */
    static boolean sideBySide(double[] a, double[] b) {
        double aCenterY = (a[1] + a[3]) / 2.0;
        double bCenterY = (b[1] + b[3]) / 2.0;
        boolean oneRow = aCenterY >= b[1] && aCenterY <= b[3] && bCenterY >= a[1] && bCenterY <= a[3];
        return oneRow && (a[2] <= b[0] || b[2] <= a[0]);
    }

    /**
     * How far an opposed arrow moves along the box edges so that it ends up
     * half the {@link #OPPOSED_GAP} beside the shared line. A flat line needs a
     * longer way; the arrow never leaves the narrower of its two boxes.
     */
    private static double opposedShift(double startX, double startY, double endX, double endY,
                                       double[] srcB, double[] tgtB) {
        double dx = endX - startX;
        double dy = Math.abs(endY - startY);
        double room = Math.max(0.0, Math.min(srcB[2] - srcB[0], tgtB[2] - tgtB[0]) / 2.0 - 1.0);
        if (dy < 1.0) {
            return Math.min(OPPOSED_GAP / 2.0, room);
        }
        return Math.min(OPPOSED_GAP / 2.0 * Math.sqrt(dx * dx + dy * dy) / dy, room);
    }

    /** Builds the weight of an arrow: a number in the arrow's colour, centred at (cx, cy). */
    private static Group buildWeight(String text, double cx, double cy, Color color) {
        Text label = new Text(text);
        label.setFont(WEIGHT_FONT);
        label.setFill(color);
        label.setTextOrigin(VPos.CENTER);
        double textW = label.getLayoutBounds().getWidth();
        double textH = label.getLayoutBounds().getHeight();
        label.setX(-textW / 2.0);
        label.setY(0);

        Rectangle background = new Rectangle(
                -textW / 2.0 - WEIGHT_PADDING, -textH / 2.0,
                textW + 2 * WEIGHT_PADDING, textH);
        background.setFill(WEIGHT_BACKGROUND);

        Group group = new Group(background, label);
        group.setLayoutX(cx);
        group.setLayoutY(cy);
        group.setMouseTransparent(true);
        return group;
    }

    /** Builds a filled circle badge with white bold count label, centred at (cx, cy). */
    private static Group buildBadge(String text, double cx, double cy, Color bg) {
        Text label = new Text(text);
        label.setFont(BADGE_FONT);
        label.setFill(BADGE_FG);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setTextOrigin(VPos.CENTER);
        double textW = label.getLayoutBounds().getWidth();
        double textH = label.getLayoutBounds().getHeight();
        double radius = Math.max(BADGE_MIN_RADIUS, Math.max(textW, textH) / 2.0 + BADGE_PADDING);

        Circle circle = new Circle(0, 0, radius);
        circle.setFill(bg);
        circle.setStroke(null);

        label.setX(-textW / 2.0);
        label.setY(0);

        Group group = new Group(circle, label);
        group.setLayoutX(cx);
        group.setLayoutY(cy);
        group.setMouseTransparent(true);
        return group;
    }

    /**
     * Returns [minX, minY, maxX, maxY] of {@code node} in the dependency pane's
     * coordinate space using JavaFX's built-in scene-graph transform API.
     *
     * <p>{@code localToScene} + {@code sceneToLocal} is always consistent with
     * the committed scene graph state and avoids the stale-bounds issue that
     * arises when walking {@code getBoundsInParent()} manually across nodes
     * whose layout may not yet have fully propagated.
     */
    double[] getBoundsInPane(Node node) {
        try {
            if (node == null || dependencyPane == null || node.getScene() != dependencyPane.getScene()) {
                return null;
            }
            Bounds inScene = node.localToScene(node.getBoundsInLocal());
            Bounds inPane  = dependencyPane.sceneToLocal(inScene);
            return new double[]{inPane.getMinX(), inPane.getMinY(),
                                inPane.getMaxX(), inPane.getMaxY()};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the line width (scales automatically with content).
     */
    private double getScaledLineWidth() {
        return DEPENDENCY_WIDTH;
    }

    /**
     * Returns the arrow size (scales automatically with content).
     */
    private double getScaledArrowSize() {
        return 4.0;
    }
}
