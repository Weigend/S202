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
package de.weigend.s202.ui.views.threed;

import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.graph.BoxVisibility;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.QuadCurve;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the layered view draws, read off its JavaFX scene: every shown box
 * with its place, size, colours and border, the boxes inside it, its level,
 * and every text and icon on it with font, colour and baseline. Nothing is
 * computed; the coordinates are the layout coordinates of the picture.
 *
 * <p>Another renderer can draw the same top view from it, and then more:
 * the boxes stand on each other, so that a tilted view shows heights.
 *
 * @param content the node of the 2D picture, to which events for nothing in particular go
 * @param roots  the outermost boxes
 * @param arrows the dependency, cycle and violation arrows over the boxes
 * @param badges the numbers and weights on the arrows
 * @param minX   left edge of the picture
 * @param minY  top edge of the picture
 * @param maxX  right edge of the picture
 * @param maxY  bottom edge of the picture
 */
public record TopViewModel(Node content, List<Box> roots, List<Arrow> arrows, List<Badge> badges,
                           double minX, double minY, double maxX, double maxY) {

    /** An empty picture. */
    public static final TopViewModel EMPTY = new TopViewModel(null, List.of(), List.of(), List.of(), 0, 0, 1, 1);

    /** Points along an arrow; a straight line gets as many as a curve, so that it can be bent. */
    static final int ARROW_SAMPLES = 25;

    /**
     * A box of the picture.
     *
     * @param node        the box in 2D, which the mouse in another view is passed on to
     * @param fqn         full name of the package or class
     * @param isPackage   package or class
     * @param depth       number of boxes around it
     * @param level       global architecture level of the element, -1 if unknown
     * @param look        background and border, which change under the mouse
     * @param labels      the texts and icons on it
     * @param children    the boxes inside it
     */
    public record Box(Node node, String fqn, boolean isPackage, double x, double y, double w, double h, int depth, int level,
                      Look look, List<Label> labels, List<Box> children) {

        /** The background, {@code null} for a box without one. */
        public Color fill() {
            return look.fill();
        }

        /** The border colour, {@code null} for a box without one. */
        public Color stroke() {
            return look.stroke();
        }

        public double strokeWidth() {
            return look.strokeWidth();
        }
    }

    /**
     * How a box, an arrow or a number looks in 2D now: the parts that change
     * while the mouse moves - colours, widths, opacity. Read anew without
     * reading the picture, which keeps the mouse quick on a large system.
     */
    public static final class Look {
        private Color fill;
        private Color stroke;
        private double strokeWidth;
        private double[] dash = new double[0];
        private double opacity = 1;

        Look() {
        }

        /** A look given by its values, for a picture not read off a scene. */
        public static Look of(Color fill, Color stroke, double strokeWidth) {
            Look look = new Look();
            look.fill = fill;
            look.stroke = stroke;
            look.strokeWidth = strokeWidth;
            return look;
        }

        public Color fill() {
            return fill;
        }

        public Color stroke() {
            return stroke;
        }

        public double strokeWidth() {
            return strokeWidth;
        }

        /** Dash pattern in picture units, empty for a solid line. */
        public double[] dash() {
            return dash;
        }

        public double opacity() {
            return opacity;
        }

        /** Reads the look of the node: a box, a line or anything else, of which only the opacity counts. */
        void read(Node node) {
            opacity = effectiveOpacity(node);
            if (node instanceof Region region) {
                fill = color(TopViewModel.fill(region.getBackground()));
                stroke = color(TopViewModel.stroke(region.getBorder()));
                strokeWidth = TopViewModel.strokeWidth(region.getBorder());
            } else if (node instanceof Shape shape && !(shape instanceof Text)) {
                stroke = color(shape.getStroke());
                strokeWidth = shape.getStrokeWidth();
                dash = shape.getStrokeDashArray().stream().mapToDouble(Double::doubleValue).toArray();
            }
        }

        static Look of(Node node) {
            Look look = new Look();
            look.read(node);
            return look;
        }
    }

    /**
     * A text or icon on a box; icons are glyphs of an icon font.
     *
     * @param node     the text in 2D, to which a click on it is passed on
     * @param x        left end of the baseline
     * @param baseline y of the baseline
     * @param w        width of the text
     */
    public record Label(Node node, String text, Font font, Color fill, double x, double baseline, double w) {
    }

    /**
     * An arrow over the picture: its line as points from the source to the
     * target, with the look it has in 2D.
     *
     * @param node     the line in 2D, which reacts to the mouse
     * @param dash     dash pattern in picture units, empty for a solid line
     * @param headSize length of the two strokes of its head, 0 for none
     */
    public record Arrow(Node node, double[] xs, double[] ys, Look look, double headSize) {

        public Color color() {
            return look.stroke();
        }

        public double width() {
            return look.strokeWidth();
        }

        public double[] dash() {
            return look.dash();
        }

        public double opacity() {
            return look.opacity();
        }
    }

    /**
     * A number on an arrow: a text on a circle or rectangle.
     *
     * @param node the number in 2D
     * @param x    middle
     * @param y    middle
     */
    public record Badge(Node node, double x, double y, double w, double h, boolean round, Color background,
                        String text, Font font, Color textFill, Look look) {

        public double opacity() {
            return look.opacity();
        }
    }

    /**
     * Reads anew how the boxes, arrows and numbers look, from the same nodes;
     * where they are stays. For what the mouse changes in 2D. On the JavaFX thread.
     */
    public void refreshLooks() {
        if (content == null) {
            return;
        }
        content.applyCss();
        for (Box box : allBoxes()) {
            box.look().read(box.node());
        }
        for (Arrow arrow : arrows) {
            arrow.look().read(arrow.node());
        }
        for (Badge badge : badges) {
            badge.look().read(badge.node());
        }
    }

    /** Reads the picture of {@code canvas} as it is drawn now. On the JavaFX thread. */
    public static TopViewModel read(ArchitectureCanvas canvas) {
        Node content = canvas.snapshotContentNode();
        // The 2D view may lie in a tab in the back, where JavaFX applies no styles:
        // a box marked as connected would keep its old colour.
        content.applyCss();
        Map<String, ArchitectureNode> nodes = new HashMap<>();
        collect(canvas.getArchitectureRoot(), nodes);
        Builder root = new Builder(null);
        Overlays overlays = new Overlays(content);
        walk(content, content, root, 0, nodes, overlays);
        List<Box> roots = root.childBoxes();
        if (roots.isEmpty()) {
            return EMPTY;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Box box : roots) {
            minX = Math.min(minX, box.x());
            minY = Math.min(minY, box.y());
            maxX = Math.max(maxX, box.x() + box.w());
            maxY = Math.max(maxY, box.y() + box.h());
        }
        return new TopViewModel(content, roots, List.copyOf(overlays.arrows), List.copyOf(overlays.badges),
                minX, minY, maxX, maxY);
    }

    /** Every box of the picture, outer boxes before the boxes in them. */
    public List<Box> allBoxes() {
        List<Box> all = new ArrayList<>();
        for (Box box : roots) {
            addAll(box, all);
        }
        return all;
    }

    private static void addAll(Box box, List<Box> all) {
        all.add(box);
        box.children().forEach(child -> addAll(child, all));
    }

    /** A box while it is read, to which texts and inner boxes are added. */
    private static final class Builder {
        final Region region;
        String fqn;
        boolean isPackage;
        Bounds bounds;
        int depth;
        int level;
        final List<Label> labels = new ArrayList<>();
        final List<Builder> children = new ArrayList<>();

        Builder(Region region) {
            this.region = region;
        }

        Box build() {
            return new Box(region, fqn, isPackage, bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight(),
                    depth, level, Look.of(region), List.copyOf(labels), childBoxes());
        }

        List<Box> childBoxes() {
            List<Box> boxes = new ArrayList<>(children.size());
            children.forEach(child -> boxes.add(child.build()));
            return List.copyOf(boxes);
        }
    }

    private static void walk(Node node, Node content, Builder owner, int depth, Map<String, ArchitectureNode> nodes,
                             Overlays overlays) {
        if (!node.isVisible()) {
            return;
        }
        if (owner.region == null && overlays.read(node)) {
            return;
        }
        Builder current = owner;
        int childDepth = depth;
        if ((node instanceof LevelPackageBox || node instanceof LevelClassBox)
                && node instanceof GraphSelection.Selectable selectable
                && BoxVisibility.isShown(node, content)) {
            current = new Builder((Region) node);
            current.fqn = selectable.getFullName();
            current.isPackage = node instanceof LevelPackageBox;
            current.bounds = bounds(node, content);
            current.depth = depth;
            ArchitectureNode model = nodes.get(current.fqn);
            current.level = model == null ? -1 : model.getArchitectureLevel();
            owner.children.add(current);
            childDepth = depth + 1;
        }
        if (node instanceof Text text && current.region != null && !text.getText().isEmpty()
                && BoxVisibility.isShown(node, content)) {
            Bounds lb = text.getLayoutBounds();
            Point2D baseline = content.sceneToLocal(
                    text.localToScene(lb.getMinX(), lb.getMinY() + text.getBaselineOffset()));
            Color fill = color(text.getFill());
            if (fill != null) {
                current.labels.add(new Label(text, text.getText(), text.getFont(), fill,
                        baseline.getX(), baseline.getY(), bounds(text, content).getWidth()));
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walk(child, content, current, childDepth, nodes, overlays);
            }
        }
    }

    /**
     * The arrows outside the boxes. A renderer adds the head of an arrow
     * right after its line, as strokes that start at its end; the head is
     * not kept as it is but drawn anew where the line ends.
     */
    private static final class Overlays {
        final Node content;
        /** The panes of the arrows lie over the boxes: the last child of the picture. */
        final Node overlay;
        final List<Arrow> arrows = new ArrayList<>();
        final List<Badge> badges = new ArrayList<>();

        Overlays(Node content) {
            this.content = content;
            List<Node> children = content instanceof Parent parent ? parent.getChildrenUnmodifiable() : List.of();
            overlay = children.size() >= 2 ? children.get(children.size() - 1) : null;
        }

        /** Takes the node if it is part of an arrow; {@code false} for anything else. */
        boolean read(Node node) {
            if (!isOverlay(node)) {
                return false;
            }
            if (node instanceof Shape shape && !(shape instanceof Text)) {
                Color stroke = color(shape.getStroke());
                if (stroke == null || shape.getStrokeWidth() <= 0) {
                    return true;
                }
                if (isHead(shape)) {
                    return true;
                }
                double[][] points = samples(shape);
                if (points != null) {
                    arrows.add(new Arrow(shape, points[0], points[1], Look.of(shape), 0));
                }
                return true;
            }
            if (node instanceof javafx.scene.Group group && group.getChildren().size() == 2
                    && group.getChildren().get(0) instanceof Shape back
                    && group.getChildren().get(1) instanceof Text text) {
                Bounds b = bounds(back, content);
                Color background = color(back.getFill());
                Color textFill = color(text.getFill());
                if (textFill != null) {
                    badges.add(new Badge(group, b.getCenterX(), b.getCenterY(), b.getWidth(), b.getHeight(),
                            back instanceof Circle, background, text.getText(), text.getFont(), textFill,
                            Look.of(group)));
                }
                return true;
            }
            return false;
        }

        private boolean isOverlay(Node node) {
            for (Node n = node; n != null && n != content; n = n.getParent()) {
                if (n == overlay) {
                    return true;
                }
            }
            return false;
        }

        /** A stroke of the head of the arrow read last: it starts where that one ends. */
        private boolean isHead(Shape shape) {
            if (arrows.isEmpty()) {
                return false;
            }
            Arrow last = arrows.get(arrows.size() - 1);
            double endX = last.xs()[last.xs().length - 1];
            double endY = last.ys()[last.ys().length - 1];
            Point2D tip;
            Point2D tail;
            if (shape instanceof Line line) {
                tip = point(line, line.getStartX(), line.getStartY());
                tail = point(line, line.getEndX(), line.getEndY());
            } else if (shape instanceof Polyline polyline && polyline.getPoints().size() == 6) {
                tip = point(polyline, polyline.getPoints().get(2), polyline.getPoints().get(3));
                tail = point(polyline, polyline.getPoints().get(0), polyline.getPoints().get(1));
            } else {
                return false;
            }
            if (Math.abs(tip.getX() - endX) > 0.01 || Math.abs(tip.getY() - endY) > 0.01) {
                return false;
            }
            if (last.headSize() == 0) {
                arrows.set(arrows.size() - 1, new Arrow(last.node(), last.xs(), last.ys(), last.look(),
                        tip.distance(tail)));
            }
            return true;
        }

        /** Points along a line or curve in picture coordinates; {@code null} for other shapes. */
        private double[][] samples(Shape shape) {
            int n = ARROW_SAMPLES;
            double[] xs = new double[n];
            double[] ys = new double[n];
            for (int i = 0; i < n; i++) {
                double t = i / (double) (n - 1);
                double u = 1 - t;
                double x;
                double y;
                if (shape instanceof Line l) {
                    x = u * l.getStartX() + t * l.getEndX();
                    y = u * l.getStartY() + t * l.getEndY();
                } else if (shape instanceof QuadCurve q) {
                    x = u * u * q.getStartX() + 2 * u * t * q.getControlX() + t * t * q.getEndX();
                    y = u * u * q.getStartY() + 2 * u * t * q.getControlY() + t * t * q.getEndY();
                } else if (shape instanceof CubicCurve c) {
                    x = u * u * u * c.getStartX() + 3 * u * u * t * c.getControlX1()
                            + 3 * u * t * t * c.getControlX2() + t * t * t * c.getEndX();
                    y = u * u * u * c.getStartY() + 3 * u * u * t * c.getControlY1()
                            + 3 * u * t * t * c.getControlY2() + t * t * t * c.getEndY();
                } else {
                    return null;
                }
                Point2D p = point(shape, x, y);
                xs[i] = p.getX();
                ys[i] = p.getY();
            }
            return new double[][]{xs, ys};
        }

        private Point2D point(Node node, double x, double y) {
            return content.sceneToLocal(node.localToScene(x, y));
        }

    }

    /** The opacity the node is seen with: its own and that of everything around it. */
    private static double effectiveOpacity(Node node) {
        double opacity = 1;
        for (Node n = node; n != null; n = n.getParent()) {
            opacity *= n.getOpacity();
        }
        return opacity;
    }

    private static Bounds bounds(Node node, Node content) {
        return content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
    }

    private static Paint fill(Background background) {
        if (background == null || background.getFills().isEmpty()) {
            return null;
        }
        BackgroundFill fill = background.getFills().get(background.getFills().size() - 1);
        return fill.getFill();
    }

    private static Paint stroke(Border border) {
        if (border == null || border.getStrokes().isEmpty()) {
            return null;
        }
        return border.getStrokes().get(0).getTopStroke();
    }

    private static double strokeWidth(Border border) {
        if (border == null || border.getStrokes().isEmpty()) {
            return 0;
        }
        return border.getStrokes().get(0).getWidths().getTop();
    }

    /** The paint as a colour; {@code null} for none, a transparent one or another kind of paint. */
    static Color color(Paint paint) {
        return paint instanceof Color c && c.getOpacity() > 0 ? c : null;
    }

    private static void collect(ArchitectureNode node, Map<String, ArchitectureNode> nodes) {
        if (node == null) {
            return;
        }
        nodes.put(node.getFullName(), node);
        node.getChildren().forEach(child -> collect(child, nodes));
    }
}
