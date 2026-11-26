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

import javafx.animation.AnimationTimer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import de.weigend.s202.analysis.metrics.Metric;
import de.weigend.s202.analysis.metrics.MetricMapping;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The picture of the layered view, tilted back so that the boxes show their
 * heights: drawn with JavaFX 2D, without a 3D engine.
 *
 * <p>Seen from above without perspective, a plane stays a plane and its
 * picture is an affine image of it. Every top of a box is therefore drawn
 * like the 2D view, rectangle, border, texts and icons, with one affine
 * transform: turned about the middle of the picture, squeezed by the cosine
 * of the tilt, lifted by its height times the sine. Texts are glyphs of the
 * same fonts as in 2D and stay sharp at every zoom. The sides of the boxes
 * are polygons between the corners of the top and of the foot.
 *
 * <p>Drawn from back to front: a box before the boxes on it, and of boxes on
 * the same box the one behind before the one in front. Boxes on one box do
 * not overlap, so for any direction of view one of two boxes that lie on a
 * common line of sight is in front of the other; which one says the axis
 * that separates them.
 *
 * <p>Heights as in the 3D view: a package lies 12 above the package it is
 * in and is 3 thick, a class stands on its package, 8 high and 8 more for
 * each architecture level.
 */
final class View25D extends StackPane {

    static final double PACKAGE_STACK_STEP = 12.0;
    static final double PACKAGE_THICKNESS = 3.0;
    /** Height of a class or closed package with the smallest value. */
    static final double MIN_HEIGHT = 4.0;
    /** The sides of a box whose value is not known. */
    private static final Color UNKNOWN = Color.web("#9e9e9e");
    static final double MAX_TILT = 80;
    private static final double DRAG_DEGREES_PER_PIXEL = 0.3;
    private static final double FIT_MARGIN = 0.94;
    private static final double MIN_ZOOM = 0.02;
    private static final double MAX_ZOOM = 64;
    /** Texts smaller than this on screen are not drawn. */
    private static final double MIN_TEXT_PIXELS = 2.0;
    /** Light seen from the viewer: from the left and from the front. */
    private static final double LIGHT_X = -0.45;
    private static final double LIGHT_Y = 0.89;
    /** How far from an arrow on the screen the mouse still means it. */
    private static final double ARROW_REACH = 4.0;
    private static final boolean STATS = Boolean.getBoolean("s202.view25d.stats");
    /** Pixels of a texture that every graphics card JavaFX runs on can hold. */
    private static final int MAX_TEXTURE = 4096;
    /** Room between an arrow and the tops it passes over. */
    private static final double ARROW_CLEARANCE = 6.0;
    /** An arrow rises at least by this part of its length in its middle, ... */
    private static final double ARROW_RISE = 0.12;
    /** ... but not by more than this, unless boxes are in the way. */
    private static final double MAX_ARROW_RISE = 40.0;

    /**
     * The picture is drawn in tiles. JavaFX keeps the pixels of a canvas in one
     * texture, which the graphics card limits, usually to 4096 pixels; a window
     * on a screen with twice the pixels would exceed it and stay empty.
     */
    private final List<Canvas> tiles = new ArrayList<>();
    private final Pane holder = new Pane();
    /** The tile drawn now, in the coordinates of the view. */
    private double tileX;
    private double tileY;
    private double tileW;
    private double tileH;
    /** Whether the faces and arrows of the tile drawn now are kept for the mouse: those of the first. */
    private boolean recording;
    /** What is drawn at all: the tile, but the whole view for the first, whose faces the mouse needs. */
    private double cullX;
    private double cullY;
    private double cullW;
    private double cullH;
    private final DoubleProperty tilt = new SimpleDoubleProperty(0);
    private final DoubleProperty turn = new SimpleDoubleProperty(0);
    private double zoom = 1;
    private double panX;
    private double panY;

    private TopViewModel model = TopViewModel.EMPTY;
    private double centerX;
    private double centerY;
    /** Height of the top of every box. */
    private final Map<TopViewModel.Box, Double> tops = new IdentityHashMap<>();
    /** Height of the foot of every box. */
    private final Map<TopViewModel.Box, Double> feet = new IdentityHashMap<>();
    /** Boxes on a box in the order they are drawn, for the turn they were sorted for. */
    private final Map<Object, List<TopViewModel.Box>> order = new IdentityHashMap<>();
    private double orderTurn = Double.NaN;
    private MetricMapping mapping = MetricMapping.DEFAULT;
    private MetricSource metrics = MetricSource.NONE;
    /** The value each class and closed package stands as high as. */
    private final Map<TopViewModel.Box, Double> heightValues = new IdentityHashMap<>();
    /** The colour the metric gives the sides of a box; none for the colours of 2D. */
    private final Map<TopViewModel.Box, Color> sideColors = new IdentityHashMap<>();
    private Legend legend = new Legend(Metric.LEVEL, Double.NaN, Double.NaN, null, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, MetricMapping.Palette.GREEN_RED, false);
    private Consumer<Legend> onLegend = l -> {};
    /** The values of the box under the mouse. */
    private final Label info = new Label();
    /** Height of every point of every arrow. */
    private final Map<TopViewModel.Arrow, double[]> arrowHeights = new IdentityHashMap<>();
    /** Height of the numbers on the arrows. */
    private final Map<TopViewModel.Badge, Double> badgeHeights = new IdentityHashMap<>();
    private final Object rootKey = new Object();

    /** The faces drawn in the last frame, back to front, for picking. */
    private final List<Face> faces = new ArrayList<>();
    private String selected;
    /** The line and the box in 2D that the mouse is over here. */
    private Node hoveredArrow;
    private Node hoveredBox;
    private Runnable onPictureChanged = () -> {};
    /** The arrows drawn in the last frame, for picking. */
    private final List<DrawnArrow> drawnArrows = new ArrayList<>();
    private Consumer<String> onSelect = fqn -> {};
    private boolean fitPending;

    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;

    private long frames;
    private long frameNanos;

    private final AnimationTimer redraw = new AnimationTimer() {
        @Override
        public void handle(long now) {
            stop();
            draw();
        }
    };

    View25D() {
        holder.setMinSize(0, 0);
        holder.setPrefSize(0, 0);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(holder.widthProperty());
        clip.heightProperty().bind(holder.heightProperty());
        holder.setClip(clip);
        holder.widthProperty().addListener((obs, was, isNow) -> sized());
        holder.heightProperty().addListener((obs, was, isNow) -> sized());
        tilt.addListener((obs, was, isNow) -> requestDraw());
        turn.addListener((obs, was, isNow) -> requestDraw());
        info.setMouseTransparent(true);
        info.setManaged(false);
        info.setVisible(false);
        info.setStyle("-fx-background-color: rgba(20, 24, 32, 0.9); -fx-background-radius: 3; "
                + "-fx-text-fill: #f0f0f0; -fx-font-size: 11px; -fx-padding: 4 7 4 7;");
        getChildren().addAll(holder, info);
        installMouse();
    }

    /** Shows the values of the box at the mouse beside it; {@code null} hides them. */
    private void showInfo(TopViewModel.Box box, double x, double y) {
        if (box == null) {
            info.setVisible(false);
            return;
        }
        info.setText(describe(box));
        info.autosize();
        double left = Math.min(x + 16, getWidth() - info.getWidth() - 4);
        double top = y + 18 + info.getHeight() > getHeight() ? y - info.getHeight() - 8 : y + 18;
        info.relocate(Math.max(4, left), Math.max(4, top));
        info.setVisible(true);
    }

    // -----------------------------------------------------------------------
    // Data
    // -----------------------------------------------------------------------

    /** The picture to draw; {@code reset} goes back to the view from above, fitted. */
    void setModel(TopViewModel model, boolean reset) {
        this.model = model == null ? TopViewModel.EMPTY : model;
        centerX = (this.model.minX() + this.model.maxX()) / 2;
        centerY = (this.model.minY() + this.model.maxY()) / 2;
        restyle();
        order.clear();
        orderTurn = Double.NaN;
        // A new tab may be read before it is laid out: its picture has no extent yet.
        // Fitting that would leave the camera far from the picture that comes next,
        // so the fit waits for a picture with an extent and a view with a size.
        fitPending |= reset;
        fitIfPending();
        requestDraw();
    }

    private void fitIfPending() {
        if (fitPending && holder.getWidth() > 0 && holder.getHeight() > 0 && hasExtent()) {
            fitPending = false;
            top();
        }
    }

    private boolean hasExtent() {
        return !model.roots().isEmpty() && model.maxX() - model.minX() > 1 && model.maxY() - model.minY() > 1;
    }

    /** Told the element the user selected, {@code null} when the user took the selection away. */
    void setOnSelect(Consumer<String> onSelect) {
        this.onSelect = onSelect == null ? fqn -> {} : onSelect;
    }

    /** Marks the box of the element as selected, without telling anyone. */
    void select(String fqn) {
        selected = fqn;
        requestDraw();
    }

    /** What the heights and the colours of the sides say, and where the values come from. */
    void setMetrics(MetricSource metrics, MetricMapping mapping) {
        MetricMapping before = this.mapping;
        this.metrics = metrics == null ? MetricSource.NONE : metrics;
        this.mapping = mapping == null ? MetricMapping.DEFAULT : mapping;
        restyle();
        // Another metric or scale gives other heights, which may leave the tab: the whole
        // picture comes back into it. The size of the heights and the colours keep the camera.
        if (before.height() != this.mapping.height() || before.scale() != this.mapping.scale()) {
            fit();
        }
        requestDraw();
    }

    /** Told the ranges of the values each time they change, for a legend. */
    void setOnLegend(Consumer<Legend> onLegend) {
        this.onLegend = onLegend == null ? legend -> {} : onLegend;
        this.onLegend.accept(legend);
    }

    /**
     * The values that the colours stand for.
     *
     * @param heightMin value of the lowest box: 0, or a negative value if there is one
     * @param heightMax value of the highest box, {@code NaN} if no value is known
     * @param classMin smallest value of a class shown, {@code NaN} if there is none
     */
    record Legend(Metric height, double heightMin, double heightMax,
                  Metric metric, double classMin, double classMax, double packageMin, double packageMax,
                  MetricMapping.Palette palette, boolean reversed) {
    }

    /**
     * Heights and colours from the metrics. A class and a closed package stand
     * on the box they are in, as high as their value says: the smallest at
     * {@link #MIN_HEIGHT}, the largest of their kind at the height of the
     * mapping. An open package is a base for what is in it, as before.
     */
    private void restyle() {
        List<TopViewModel.Box> boxes = model.allBoxes();
        heightValues.clear();
        sideColors.clear();
        // Classes and closed packages share one scale: a closed package stands for what is
        // in it, as high as its highest class. Closing a package does not change the skyline.
        double[] standing = range();
        for (TopViewModel.Box box : boxes) {
            if (isStanding(box)) {
                double value = shownValue(mapping.height(), box);
                heightValues.put(box, value);
                include(standing, value);
            }
        }
        tops.clear();
        feet.clear();
        for (TopViewModel.Box box : model.roots()) {
            heights(box, 0, standing);
        }
        // The colour of an open package, a base, is its own value among the open packages.
        double[] classColors = range();
        double[] packageColors = range();
        Map<TopViewModel.Box, Double> colorValues = new IdentityHashMap<>();
        if (mapping.color() != null) {
            for (TopViewModel.Box box : boxes) {
                double value = shownValue(mapping.color(), box);
                colorValues.put(box, value);
                include(isStanding(box) ? classColors : packageColors, value);
            }
            for (TopViewModel.Box box : boxes) {
                double[] range = isStanding(box) ? classColors : packageColors;
                double min = mapping.colorMin() != null ? mapping.colorMin() : range[0];
                double max = mapping.colorMax() != null ? mapping.colorMax() : range[1];
                double value = colorValues.get(box);
                sideColors.put(box, Double.isNaN(value) ? UNKNOWN
                        : Palettes.color(mapping.palette(), position(value, min, max), mapping.reversed()));
            }
        }
        // Heights start at 0, see heights(): the lowest box stands for 0, not for the smallest value.
        legend = new Legend(mapping.height(), Double.isNaN(standing[0]) ? Double.NaN : Math.min(0, standing[0]),
                standing[1], mapping.color(), classColors[0], classColors[1], packageColors[0], packageColors[1],
                mapping.palette(), mapping.reversed());
        onLegend.accept(legend);
        liftArrows();
    }

    /** The value a box shows: a closed package that of its highest class, any other box its own. */
    private double shownValue(Metric metric, TopViewModel.Box box) {
        return isClosedPackage(box) ? metrics.highestClass(metric, box) : metrics.value(metric, box);
    }

    private static boolean isClosedPackage(TopViewModel.Box box) {
        return box.isPackage() && box.children().isEmpty();
    }

    /** A class, or a closed package: it stands for what is in it. */
    private static boolean isStanding(TopViewModel.Box box) {
        return !box.isPackage() || box.children().isEmpty();
    }

    private static double[] range() {
        return new double[]{Double.NaN, Double.NaN};
    }

    private static void include(double[] range, double value) {
        if (Double.isNaN(value)) {
            return;
        }
        range[0] = Double.isNaN(range[0]) ? value : Math.min(range[0], value);
        range[1] = Double.isNaN(range[1]) ? value : Math.max(range[1], value);
    }

    private static double position(double value, double min, double max) {
        if (Double.isNaN(min) || Double.isNaN(max)) {
            return 0;
        }
        return max <= min ? (value >= max ? 1 : 0) : Math.clamp((value - min) / (max - min), 0, 1);
    }

    private void heights(TopViewModel.Box box, double foot, double[] range) {
        double top;
        if (isStanding(box)) {
            // Counts start at 0: a class used by 3 others is not as low as one used by none.
            double min = Double.isNaN(range[0]) ? 0 : Math.min(0, range[0]);
            double max = Double.isNaN(range[1]) ? 0 : range[1];
            double t = mapping.scale().position(heightValues.getOrDefault(box, Double.NaN), min, max);
            top = foot + MIN_HEIGHT + Math.max(0, largestHeight() - MIN_HEIGHT) * t;
        } else {
            top = Math.max(foot, box.depth() * PACKAGE_STACK_STEP) + PACKAGE_THICKNESS;
        }
        feet.put(box, foot);
        tops.put(box, top);
        for (TopViewModel.Box child : box.children()) {
            heights(child, top, range);
        }
    }

    /** Height of the largest value: a share of the size of the picture, so that it fits any system. */
    private double largestHeight() {
        double size = Math.sqrt(Math.max(1, model.maxX() - model.minX()) * Math.max(1, model.maxY() - model.minY()));
        return mapping.heightPercent() / 100 * size;
    }

    /** The values of the metrics of a box, for the line under the mouse. */
    private String describe(TopViewModel.Box box) {
        StringBuilder text = new StringBuilder(box.fqn().substring(box.fqn().lastIndexOf('.') + 1));
        for (Metric metric : mapping.color() == null || mapping.color() == mapping.height()
                ? List.of(mapping.height()) : List.of(mapping.height(), mapping.color())) {
            double value = shownValue(metric, box);
            text.append("\n").append(metric.label()).append(": ").append(format(value));
            if (isClosedPackage(box)) {
                text.append(" (highest class)");
            }
        }
        return text.toString();
    }

    static String format(double value) {
        if (Double.isNaN(value)) {
            return "not measured";
        }
        return value == Math.rint(value) && Math.abs(value) < 1e9
                ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /**
     * Bends every arrow into an arc: it leaves the top of its source and
     * reaches the top of its target, and in between rises over every box
     * under it. From above it is the line of the 2D picture.
     */
    private void liftArrows() {
        arrowHeights.clear();
        badgeHeights.clear();
        for (TopViewModel.Arrow arrow : model.arrows()) {
            double[] xs = arrow.xs();
            double[] ys = arrow.ys();
            int n = xs.length;
            double start = topAt(xs[0], ys[0], 0.75);
            double end = topAt(xs[n - 1], ys[n - 1], 0.75);
            double length = 0;
            for (int i = 1; i < n; i++) {
                length += Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
            }
            double rise = Math.min(MAX_ARROW_RISE, ARROW_RISE * length);
            for (int i = 1; i < n - 1; i++) {
                double t = i / (double) (n - 1);
                if (t < 0.1 || t > 0.9) {
                    continue;
                }
                double needed = topAt(xs[i], ys[i], 2) + ARROW_CLEARANCE;
                double straight = start + (end - start) * t;
                rise = Math.max(rise, (needed - straight) / (4 * t * (1 - t)));
            }
            double[] heights = new double[n];
            for (int i = 0; i < n; i++) {
                double t = i / (double) (n - 1);
                heights[i] = start + (end - start) * t + rise * 4 * t * (1 - t);
            }
            arrowHeights.put(arrow, heights);
        }
        // A number sits beside or on its arrow, at the height of its nearest point.
        for (TopViewModel.Badge badge : model.badges()) {
            double best = Double.MAX_VALUE;
            double height = topAt(badge.x(), badge.y(), 0);
            for (TopViewModel.Arrow arrow : model.arrows()) {
                double[] heights = arrowHeights.get(arrow);
                for (int i = 0; i < arrow.xs().length; i++) {
                    double d = Math.hypot(arrow.xs()[i] - badge.x(), arrow.ys()[i] - badge.y());
                    if (d < best) {
                        best = d;
                        height = heights[i];
                    }
                }
            }
            badgeHeights.put(badge, height);
        }
    }

    /** The height of the highest top at a point of the picture: of the innermost box there. */
    private double topAt(double x, double y, double tolerance) {
        double top = 0;
        List<TopViewModel.Box> boxes = model.roots();
        boolean found = true;
        while (found) {
            found = false;
            for (TopViewModel.Box box : boxes) {
                if (x >= box.x() - tolerance && x <= box.x() + box.w() + tolerance
                        && y >= box.y() - tolerance && y <= box.y() + box.h() + tolerance) {
                    top = tops.get(box);
                    boxes = box.children();
                    found = true;
                    break;
                }
            }
        }
        return top;
    }

    // -----------------------------------------------------------------------
    // Camera
    // -----------------------------------------------------------------------

    DoubleProperty tiltProperty() {
        return tilt;
    }

    DoubleProperty turnProperty() {
        return turn;
    }

    /** From above, the whole picture in the window. */
    void top() {
        turn.set(0);
        tilt.set(0);
        fit();
    }

    /** The whole picture in the window, as it is tilted and turned. */
    void fit() {
        double width = holder.getWidth();
        double height = holder.getHeight();
        if (width <= 0 || height <= 0 || model.roots().isEmpty()) {
            return;
        }
        // The bounds of the picture at zoom 1 with its middle at 0, 0.
        double saveZoom = zoom;
        double savePanX = panX;
        double savePanY = panY;
        zoom = 1;
        panX = 0;
        panY = 0;
        Projection p = projection();
        double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (TopViewModel.Box box : model.roots()) {
            extend(bounds, p, box);
        }
        zoom = saveZoom;
        panX = savePanX;
        panY = savePanY;
        double w = Math.max(1, bounds[2] - bounds[0]);
        double h = Math.max(1, bounds[3] - bounds[1]);
        zoom = clamp(Math.min(width / w, height / h) * FIT_MARGIN, MIN_ZOOM, MAX_ZOOM);
        panX = width / 2 - zoom * (bounds[0] + bounds[2]) / 2;
        panY = height / 2 - zoom * (bounds[1] + bounds[3]) / 2;
        requestDraw();
    }

    private void extend(double[] bounds, Projection p, TopViewModel.Box box) {
        double top = tops.get(box);
        double[][] corners = {{box.x(), box.y()}, {box.x() + box.w(), box.y()},
                {box.x() + box.w(), box.y() + box.h()}, {box.x(), box.y() + box.h()}};
        for (double[] c : corners) {
            for (double hgt : new double[]{feet.get(box), top}) {
                double sx = p.sx(c[0], c[1]);
                double sy = p.sy(c[0], c[1], hgt);
                bounds[0] = Math.min(bounds[0], sx);
                bounds[1] = Math.min(bounds[1], sy);
                bounds[2] = Math.max(bounds[2], sx);
                bounds[3] = Math.max(bounds[3], sy);
            }
        }
        for (TopViewModel.Box child : box.children()) {
            extend(bounds, p, child);
        }
    }

    /**
     * Changes the camera so that the point of the ground under the screen
     * point stays where it is.
     */
    private void keeping(double sx, double sy, Runnable change) {
        Projection before = projection();
        double[] world = before.ground(sx, sy);
        change.run();
        Projection after = projection();
        panX += sx - after.sx(world[0], world[1]);
        panY += sy - after.sy(world[0], world[1], 0);
        requestDraw();
    }

    private void installMouse() {
        holder.setOnMousePressed(event -> {
            pressX = lastX = event.getX();
            pressY = lastY = event.getY();
            requestFocus();
        });
        holder.setOnMouseDragged(event -> {
            double dx = event.getX() - lastX;
            double dy = event.getY() - lastY;
            lastX = event.getX();
            lastY = event.getY();
            if (event.isPrimaryButtonDown() && !event.isShiftDown()) {
                // The picture follows the hand: down tilts it back, the front edge turns with the mouse.
                double cx = holder.getWidth() / 2;
                double cy = holder.getHeight() / 2;
                keeping(cx, cy, () -> {
                    tilt.set(clamp(tilt.get() + dy * DRAG_DEGREES_PER_PIXEL, 0, MAX_TILT));
                    turn.set(wrap(turn.get() - dx * DRAG_DEGREES_PER_PIXEL));
                });
            } else {
                panX += dx;
                panY += dy;
                requestDraw();
            }
        });
        // As in 2D: Ctrl and the wheel zoom, the wheel alone moves the picture up and down,
        // with Shift left and right; the right button moves it, clicked it opens the menu.
        de.weigend.s202.ui.core.canvas.RightButton.install(holder, (dx, dy) -> {
            panX += dx;
            panY += dy;
            requestDraw();
        });
        holder.setOnScroll((ScrollEvent event) -> {
            if (!event.isControlDown()) {
                double dx = event.getDeltaX();
                double dy = event.getDeltaY();
                if (event.isShiftDown() && dx == 0) {
                    dx = dy;
                    dy = 0;
                }
                panX += dx;
                panY += dy;
                requestDraw();
                event.consume();
                return;
            }
            if (event.getDeltaY() == 0) {
                return;
            }
            double factor = Math.pow(1.0015, event.getDeltaY());
            double next = clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
            double ratio = next / zoom;
            panX = event.getX() - (event.getX() - panX) * ratio;
            panY = event.getY() - (event.getY() - panY) * ratio;
            zoom = next;
            requestDraw();
            event.consume();
        });
        // The mouse over an arrow or a box is passed on to it in 2D, which reacts as it does
        // there: the arrows of a box stand out, an arrow thickens, is selected, has its menu.
        // How 2D then looks, this view reads anew; a click on a box may change the picture.
        holder.setOnMouseMoved(event -> hover(event, event.getX(), event.getY()));
        holder.setOnMouseExited(event -> hover(event, Double.NaN, Double.NaN));
        holder.setOnMouseClicked((MouseEvent event) -> {
            if (event.getButton() != MouseButton.PRIMARY
                    || Math.abs(event.getX() - pressX) > 3 || Math.abs(event.getY() - pressY) > 3) {
                return;
            }
            TopViewModel.Arrow arrow = pickArrow(event.getX(), event.getY());
            if (arrow != null) {
                pass(arrow.node(), MouseEvent.MOUSE_CLICKED, event);
                refreshLooks();
                return;
            }
            TopViewModel.Box box = pick(event.getX(), event.getY());
            if (box == null) {
                // Beside all boxes: no selection, here and in 2D.
                selected = null;
                onSelect.accept(null);
                return;
            }
            // On a box the click goes to what it hits in 2D: the toggle of a package opens
            // or closes it, anything else selects; a double click is one there too.
            TopViewModel.Label label = labelAt(box, event.getX(), event.getY());
            pass(label != null ? label.node() : box.node(), MouseEvent.MOUSE_CLICKED, event);
            onPictureChanged.run();
        });
        holder.setOnContextMenuRequested(event -> {
            TopViewModel.Arrow arrow = pickArrow(event.getX(), event.getY());
            TopViewModel.Box box = arrow == null ? pick(event.getX(), event.getY()) : null;
            Node target = arrow != null ? arrow.node() : box != null ? box.node() : model.content();
            if (target != null) {
                javafx.event.Event.fireEvent(target, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        0, 0, event.getScreenX(), event.getScreenY(), event.isKeyboardTrigger(), null));
            }
            event.consume();
        });
    }

    /** Called when the mouse made 2D draw something else, which this view has to read anew. */
    void setOnPictureChanged(Runnable onPictureChanged) {
        this.onPictureChanged = onPictureChanged == null ? () -> {} : onPictureChanged;
    }

    /** The mouse at {@code x, y}, NaN when it left the view: over an arrow, a box, or nothing. */
    private void hover(MouseEvent event, double x, double y) {
        TopViewModel.Arrow arrow = Double.isNaN(x) ? null : pickArrow(x, y);
        TopViewModel.Box box = arrow != null || Double.isNaN(x) ? null : pick(x, y);
        Node arrowNode = arrow == null ? null : arrow.node();
        Node boxNode = box == null ? null : box.node();
        boolean changed = false;
        if (arrowNode != hoveredArrow) {
            if (hoveredArrow != null) {
                pass(hoveredArrow, MouseEvent.MOUSE_EXITED, event);
            }
            if (arrowNode != null) {
                pass(arrowNode, MouseEvent.MOUSE_ENTERED, event);
            }
            hoveredArrow = arrowNode;
            changed = true;
        }
        if (boxNode != hoveredBox) {
            if (hoveredBox != null) {
                pass(hoveredBox, MouseEvent.MOUSE_EXITED, event);
            }
            if (boxNode != null) {
                pass(boxNode, MouseEvent.MOUSE_ENTERED, event);
                pass(boxNode, MouseEvent.MOUSE_MOVED, event);
            } else if (model.content() != null) {
                // Over nothing: 2D lets all arrows have their look back.
                pass(model.content(), MouseEvent.MOUSE_MOVED, event);
            }
            hoveredBox = boxNode;
            changed = true;
        }
        holder.setCursor(arrowNode != null ? Cursor.HAND : Cursor.DEFAULT);
        showInfo(box, x, y);
        if (changed) {
            refreshLooks();
        }
    }

    /** The text or icon on the top of the box at the screen point, {@code null} for none. */
    private TopViewModel.Label labelAt(TopViewModel.Box box, double x, double y) {
        double[] at = projection().plane(x, y, tops.get(box));
        for (TopViewModel.Label label : box.labels()) {
            double size = label.font().getSize();
            // Small marks like the toggle of a package are hit a little beside them, too.
            double margin = Math.max(0, (8 - label.w()) / 2) + 1;
            if (at[0] >= label.x() - margin && at[0] <= label.x() + label.w() + margin
                    && at[1] >= label.baseline() - size && at[1] <= label.baseline() + size * 0.3) {
                return label;
            }
        }
        return null;
    }

    /**
     * What the mouse changes in 2D are colours, widths and opacity: they are
     * read anew from the same nodes, at once, without reading the picture.
     */
    private void refreshLooks() {
        model.refreshLooks();
        requestDraw();
    }

    /** Hands a mouse event to a node of the 2D picture, where the mouse is on the screen. */
    private static void pass(Node node, javafx.event.EventType<MouseEvent> type, MouseEvent from) {
        javafx.event.Event.fireEvent(node, new MouseEvent(type, 0, 0, from.getScreenX(), from.getScreenY(),
                type == MouseEvent.MOUSE_CLICKED ? MouseButton.PRIMARY : MouseButton.NONE,
                type == MouseEvent.MOUSE_CLICKED ? Math.max(1, from.getClickCount()) : 0, from.isShiftDown(), from.isControlDown(),
                from.isAltDown(), from.isMetaDown(), false, false, false, false, false, true, null));
    }

    private TopViewModel.Box pick(double x, double y) {
        for (int i = faces.size() - 1; i >= 0; i--) {
            Face face = faces.get(i);
            if (face.contains(x, y)) {
                return face.box();
            }
        }
        return null;
    }

    /** The arrow drawn last under the screen point, or near enough to it to be meant. */
    private TopViewModel.Arrow pickArrow(double x, double y) {
        for (int i = drawnArrows.size() - 1; i >= 0; i--) {
            DrawnArrow drawn = drawnArrows.get(i);
            double reach = Math.max(ARROW_REACH, drawn.lineWidth() / 2 + 2);
            for (int k = 1; k < drawn.xs().length; k++) {
                if (distanceToSegment(x, y, drawn.xs()[k - 1], drawn.ys()[k - 1], drawn.xs()[k], drawn.ys()[k]) <= reach) {
                    return drawn.arrow();
                }
            }
        }
        return null;
    }

    private static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : clamp(((px - ax) * dx + (py - ay) * dy) / lengthSquared, 0, 1);
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** An arrow as it was drawn in the last frame, on screen. */
    private record DrawnArrow(TopViewModel.Arrow arrow, double[] xs, double[] ys, double lineWidth) {
    }

    private void sized() {
        fitIfPending();
        requestDraw();
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    void requestDraw() {
        redraw.start();
    }

    private void draw() {
        long start = System.nanoTime();
        double width = holder.getWidth();
        double height = holder.getHeight();
        layoutTiles(width, height);
        faces.clear();
        drawnArrows.clear();
        Projection p = projection();
        if (p.turn() != orderTurn) {
            order.clear();
            orderTurn = p.turn();
        }
        LinearGradient background = new LinearGradient(0, 0, 0, Math.max(1, height), false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#2c3444")), new Stop(1, Color.web("#101319")));
        for (int i = 0; i < tiles.size(); i++) {
            Canvas tile = tiles.get(i);
            GraphicsContext g = tile.getGraphicsContext2D();
            tileX = tile.getLayoutX();
            tileY = tile.getLayoutY();
            tileW = tile.getWidth();
            tileH = tile.getHeight();
            recording = i == 0;
            cullX = recording ? 0 : tileX;
            cullY = recording ? 0 : tileY;
            cullW = recording ? width : tileW;
            cullH = recording ? height : tileH;
            screen(g);
            g.setFill(background);
            g.fillRect(tileX, tileY, tileW, tileH);
            for (TopViewModel.Box box : ordered(rootKey, model.roots(), p)) {
                draw(g, p, box, width, height);
            }
            screen(g);
            // The arrows arc over the boxes: drawn after all of them.
            drawArrows(g, p, width, height);
        }
        if (STATS) {
            frameNanos += System.nanoTime() - start;
            if (++frames % 30 == 0) {
                System.out.printf("View25D: %.2f ms per frame, %d faces%n", frameNanos / 30 / 1e6, faces.size());
                frameNanos = 0;
            }
        }
    }

    /**
     * As many tiles as the view needs, each small enough for one texture at
     * the pixel density of the screen the window is on.
     */
    private void layoutTiles(double width, double height) {
        double density = 1;
        if (getScene() != null && getScene().getWindow() != null) {
            density = Math.max(1, Math.max(getScene().getWindow().getRenderScaleX(),
                    getScene().getWindow().getRenderScaleY()));
        }
        int limit = Math.min(MAX_TEXTURE, Integer.getInteger("prism.maxTextureSize", MAX_TEXTURE));
        double size = Math.max(64, Math.floor((limit - 2) / density));
        int columns = Math.max(1, (int) Math.ceil(width / size));
        int rows = Math.max(1, (int) Math.ceil(height / size));
        while (tiles.size() < columns * rows) {
            Canvas tile = new Canvas();
            tile.setMouseTransparent(true);
            tiles.add(tile);
            holder.getChildren().add(tile);
        }
        while (tiles.size() > columns * rows) {
            holder.getChildren().remove(tiles.remove(tiles.size() - 1));
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                Canvas tile = tiles.get(r * columns + c);
                tile.relocate(c * size, r * size);
                tile.setWidth(Math.max(1, Math.min(size, width - c * size)));
                tile.setHeight(Math.max(1, Math.min(size, height - r * size)));
            }
        }
    }

    /** Draws in the coordinates of the view, onto the tile drawn now. */
    private void screen(GraphicsContext g) {
        g.setTransform(1, 0, 0, 1, -tileX, -tileY);
    }

    /** Draws the plane at {@code height} of the picture, onto the tile drawn now. */
    private void plane(GraphicsContext g, Projection p, double height) {
        javafx.scene.transform.Affine affine = p.affine(height);
        affine.prependTranslation(-tileX, -tileY);
        g.setTransform(affine);
    }

    /**
     * Whether the rectangle, in the coordinates of the view, is drawn: whether
     * it reaches into the tile, or for the first tile into the view.
     */
    private boolean inTile(double minX, double minY, double maxX, double maxY) {
        return maxX >= cullX && minX <= cullX + cullW && maxY >= cullY && minY <= cullY + cullH;
    }

    private void record(Face face) {
        if (recording) {
            faces.add(face);
        }
    }

    private void draw(GraphicsContext g, Projection p, TopViewModel.Box box, double width, double height) {
        double top = tops.get(box);
        double foot = feet.get(box);
        double x0 = box.x();
        double y0 = box.y();
        double x1 = x0 + box.w();
        double y1 = y0 + box.h();
        // Corners of the top on screen, in the order of the box: top left, top right, bottom right, bottom left.
        double[] tx = {p.sx(x0, y0), p.sx(x1, y0), p.sx(x1, y1), p.sx(x0, y1)};
        double[] ty = {p.sy(x0, y0, top), p.sy(x1, y0, top), p.sy(x1, y1, top), p.sy(x0, y1, top)};
        double lift = (top - foot) * p.zoom() * p.sinTilt();
        boolean onScreen = isOnScreen(tx, ty, lift);
        if (onScreen && box.fill() != null) {
            if (lift > 0.25) {
                drawSides(g, p, box, tx, ty, lift);
            }
            drawTop(g, p, box, top, tx, ty);
        } else if (onScreen) {
            // Without a background the box is only its texts: picked by its top all the same.
            plane(g, p, top);
            drawLabels(g, p, box);
            screen(g);
            record(new Face(box, tx, ty));
        }
        for (TopViewModel.Box child : ordered(box, box.children(), p)) {
            draw(g, p, child, width, height);
        }
    }

    private void drawArrows(GraphicsContext g, Projection p, double width, double height) {
        for (TopViewModel.Arrow arrow : model.arrows()) {
            double[] heights = arrowHeights.get(arrow);
            int n = arrow.xs().length;
            double[] xs = new double[n];
            double[] ys = new double[n];
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                xs[i] = p.sx(arrow.xs()[i], arrow.ys()[i]);
                ys[i] = p.sy(arrow.xs()[i], arrow.ys()[i], heights[i]);
                minX = Math.min(minX, xs[i]);
                minY = Math.min(minY, ys[i]);
                maxX = Math.max(maxX, xs[i]);
                maxY = Math.max(maxY, ys[i]);
            }
            if (!inTile(minX, minY, maxX, maxY)) {
                continue;
            }
            g.setGlobalAlpha(arrow.opacity());
            g.setStroke(arrow.color());
            double lineWidth = Math.max(1.0, arrow.width() * p.zoom());
            g.setLineWidth(lineWidth);
            if (recording) {
                drawnArrows.add(new DrawnArrow(arrow, xs, ys, lineWidth));
            }
            if (arrow.dash().length > 0) {
                double[] dash = new double[arrow.dash().length];
                for (int i = 0; i < dash.length; i++) {
                    dash[i] = arrow.dash()[i] * p.zoom();
                }
                g.setLineDashes(dash);
            } else {
                g.setLineDashes((double[]) null);
            }
            g.strokePolyline(xs, ys, n);
            g.setLineDashes((double[]) null);
            if (arrow.headSize() > 0) {
                // The head points along the end of the arc as it is seen.
                double size = arrow.headSize() * p.zoom();
                int back = n - 2;
                while (back > 0 && Math.hypot(xs[n - 1] - xs[back], ys[n - 1] - ys[back]) < size / 2) {
                    back--;
                }
                double angle = Math.atan2(ys[n - 1] - ys[back], xs[n - 1] - xs[back]);
                g.strokeLine(xs[n - 1], ys[n - 1],
                        xs[n - 1] - size * Math.cos(angle - Math.PI / 6), ys[n - 1] - size * Math.sin(angle - Math.PI / 6));
                g.strokeLine(xs[n - 1], ys[n - 1],
                        xs[n - 1] - size * Math.cos(angle + Math.PI / 6), ys[n - 1] - size * Math.sin(angle + Math.PI / 6));
            }
        }
        // The numbers stand upright, so that they can be read however the picture is turned.
        g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        g.setTextBaseline(javafx.geometry.VPos.CENTER);
        for (TopViewModel.Badge badge : model.badges()) {
            double z = p.zoom();
            double sx = p.sx(badge.x(), badge.y());
            double sy = p.sy(badge.x(), badge.y(), badgeHeights.get(badge));
            double w = badge.w() * z;
            double h = badge.h() * z;
            if (w < 2 || !inTile(sx - w, sy - h, sx + w, sy + h)) {
                continue;
            }
            g.setGlobalAlpha(badge.opacity());
            if (badge.background() != null) {
                g.setFill(badge.background());
                if (badge.round()) {
                    g.fillOval(sx - w / 2, sy - h / 2, w, h);
                } else {
                    g.fillRect(sx - w / 2, sy - h / 2, w, h);
                }
            }
            if (badge.font().getSize() * z >= MIN_TEXT_PIXELS) {
                g.setFont(new javafx.scene.text.Font(badge.font().getName(), badge.font().getSize() * z));
                g.setFill(badge.textFill());
                g.fillText(badge.text(), sx, sy);
            }
        }
        g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        g.setTextBaseline(javafx.geometry.VPos.BASELINE);
        g.setGlobalAlpha(1);
    }

    private boolean isOnScreen(double[] xs, double[] ys, double lift) {
        double minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        double maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        double minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        double maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3])) + lift;
        return inTile(minX, minY, maxX, maxY);
    }

    /** The sides that face the viewer, lit from the front left, darker toward the foot. */
    private void drawSides(GraphicsContext g, Projection p, TopViewModel.Box box, double[] tx, double[] ty, double lift) {
        screen(g);
        // The sides of a class take a little of its blue border, so that it reads blue from the side
        // too; a package keeps its own colour, the grey of its border would make it grey.
        Color edge = box.stroke() == null ? box.fill().darker() : box.stroke();
        Color base = sideColors.getOrDefault(box,
                box.isPackage() ? box.fill().deriveColor(0, 1.15, 0.97, 1) : box.fill().interpolate(edge, 0.3));
        // Side i runs from corner i to corner i + 1; its outward normal in the plane of the box.
        double[][] normals = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            double nx = normals[i][0] * p.cosTurn() - normals[i][1] * p.sinTurn();
            double ny = normals[i][0] * p.sinTurn() + normals[i][1] * p.cosTurn();
            if (ny <= 1e-6) {
                continue;
            }
            int j = (i + 1) % 4;
            double[] xs = {tx[i], tx[j], tx[j], tx[i]};
            double[] ys = {ty[i], ty[j], ty[j] + lift, ty[i] + lift};
            double light = 0.62 + 0.32 * Math.max(0, nx * LIGHT_X + ny * LIGHT_Y);
            Color upper = shade(base, light);
            Color lower = shade(base, light * 0.78);
            double topY = Math.min(ty[i], ty[j]);
            g.setFill(new LinearGradient(0, topY, 0, topY + lift, false, CycleMethod.NO_CYCLE,
                    new Stop(0, upper), new Stop(1, lower)));
            g.fillPolygon(xs, ys, 4);
            g.setStroke(shade(edge, 0.85));
            g.setLineWidth(Math.min(1, Math.max(0.4, box.strokeWidth() * p.zoom())));
            g.strokePolygon(xs, ys, 4);
            record(new Face(box, xs, ys));
        }
    }

    /** The top as in 2D: background, border, texts, icons. */
    private void drawTop(GraphicsContext g, Projection p, TopViewModel.Box box, double top, double[] tx, double[] ty) {
        plane(g, p, top);
        g.setFill(box.fill());
        g.fillRect(box.x(), box.y(), box.w(), box.h());
        if (box.stroke() != null && box.strokeWidth() > 0) {
            double sw = box.strokeWidth();
            g.setStroke(box.stroke());
            g.setLineWidth(sw);
            g.strokeRect(box.x() + sw / 2, box.y() + sw / 2, box.w() - sw, box.h() - sw);
        }
        drawLabels(g, p, box);
        screen(g);
        record(new Face(box, tx, ty));
    }

    private static void drawLabels(GraphicsContext g, Projection p, TopViewModel.Box box) {
        for (TopViewModel.Label label : box.labels()) {
            if (label.font().getSize() * p.zoom() * p.cosTilt() < MIN_TEXT_PIXELS) {
                continue;
            }
            g.setFont(label.font());
            g.setFill(label.fill());
            g.fillText(label.text(), label.x(), label.baseline());
        }
    }

    private static Color shade(Color color, double factor) {
        return new Color(clamp(color.getRed() * factor, 0, 1), clamp(color.getGreen() * factor, 0, 1),
                clamp(color.getBlue() * factor, 0, 1), color.getOpacity());
    }

    // -----------------------------------------------------------------------
    // Order
    // -----------------------------------------------------------------------

    /** The boxes back to front, kept until the picture is turned or changes. */
    private List<TopViewModel.Box> ordered(Object key, List<TopViewModel.Box> boxes, Projection p) {
        if (boxes.size() < 2) {
            return boxes;
        }
        return order.computeIfAbsent(key, k -> PaintOrder.backToFront(boxes, p));
    }

    private Projection projection() {
        return Projection.of(tilt.get(), turn.get(), zoom, panX, panY, centerX, centerY);
    }

    // -----------------------------------------------------------------------
    // Controls
    // -----------------------------------------------------------------------

    /** Tilt and turn sliders, Top and Fit. */
    HBox controls() {
        Slider tiltSlider = new Slider(0, MAX_TILT, 0);
        tiltSlider.valueProperty().bindBidirectional(tilt);
        tiltSlider.setPrefWidth(140);
        tiltSlider.setTooltip(new Tooltip("Tilt the picture back: 0° is the view from above, as in 2D"));
        Slider turnSlider = new Slider(-180, 180, 0);
        turnSlider.valueProperty().bindBidirectional(turn);
        turnSlider.setPrefWidth(140);
        turnSlider.setTooltip(new Tooltip("Turn the picture about the vertical axis"));
        Button topButton = new Button("Top");
        topButton.setTooltip(new Tooltip("Back to the view from above"));
        topButton.setOnAction(event -> top());
        Button fitButton = new Button("Fit");
        fitButton.setTooltip(new Tooltip("The whole picture in the window"));
        fitButton.setOnAction(event -> fit());
        HBox bar = new HBox(8, label("Tilt"), tiltSlider, label("Turn"), turnSlider, topButton, fitButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        bar.setStyle("-fx-background-color: rgba(30, 36, 48, 0.88); -fx-background-radius: 4;");
        for (Button button : new Button[]{topButton, fitButton}) {
            button.setStyle("-fx-font-size: 11px; -fx-padding: 1 8;");
        }
        return bar;
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 11px;");
        return label;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double wrap(double degrees) {
        double d = ((degrees + 180) % 360 + 360) % 360 - 180;
        return d;
    }
}
