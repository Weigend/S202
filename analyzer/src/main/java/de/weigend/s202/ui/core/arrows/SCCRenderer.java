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
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import de.weigend.s202.domain.StronglyConnectedComponent;
import io.softwareecg.wfx.lookup.api.Lookup;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.ui.core.graph.BoxVisibility;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.QuadCurve;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Renders SCC (Strongly Connected Components) visualization.
 * Draws red arrows for the dependencies that lie on a class cycle.
 *
 * <p>Features:
 * <ul>
 *   <li>Class cycles come from the model ({@link EdgeCycleClassifier}), so
 *       the answer does not depend on which classes are currently visible;
 *       without a bound model the visible classes are searched for cycles</li>
 *   <li>Draws the dependencies that lie on a cycle only, in the colours of {@link LineColors}</li>
 *   <li>Interactive hover effects with status updates</li>
 *   <li>A class hidden in a collapsed package is stood for by that package</li>
 *   <li>Zoom-aware scaling</li>
 * </ul>
 */
public class SCCRenderer {

    static final String OVERLAY = "cycles";

    private static final Color SCC_COLOR = LineColors.CLASS_CYCLE;
    private static final Color PKG_CYCLE_COLOR = LineColors.PACKAGE_CYCLE;
    private static final Color SCC_HIGHLIGHT_COLOR = Color.web("#ffeb3b"); // bright yellow
    private static final double SCC_WIDTH = 1.0;
    private static final double SCC_HIGHLIGHT_WIDTH_FACTOR = 3.0;
    private static final Font BADGE_FONT = Font.font(null, FontWeight.BOLD, 5.0);
    private static final double BADGE_PADDING = 1.5;
    private static final double BADGE_MIN_RADIUS = 4.0;
    private static final double BADGE_GAP = 2.0;
    private static final double HIT_WIDTH = 6.0;
    /** With more lines than this, the areas that catch the mouse would take too long and cover too much. */
    private static final int MAX_HIT_AREAS = 400;
    /** Distance between the two arrows of a cycle between packages. */
    private static final double OPPOSED_GAP = 6.0;
    private static final double LOOP_WIDTH = 24.0;
    private static final double LOOP_HEIGHT = 18.0;
    private static final double LOOP_INSET = 10.0;

    private final Pane sccPane;
    private Pane zoomableContent;
    private final Map<String, Node> elementRegistry;
    private final Consumer<String> statusCallback;
    private LineSelection selection;
    /** The mouse areas of the lines, below all of them. */
    private final Group hitAreas = new Group();

    /** Package tangles for the active architecture — used to draw cross-package cycle contributions. */
    private List<Set<String>> packageTangles = List.of();
    private Function<String, String> packageResolver = SCCRenderer::staticPackage;

    /** Class cycles of the whole model; null until a model is bound. */
    private EdgeCycleClassifier edgeCycles;
    private BiPredicate<String, String> edgeFilter = (from, to) -> true;

    private boolean showClassScc = false;
    private boolean showPackageCycles = false;

    private final List<Line> sccLines = new ArrayList<>();
    /** Where the boxes are, while the lines are drawn: most boxes are the end of many. */
    private final Map<Node, double[]> boxBounds = new HashMap<>();
    private boolean sccLinesDrawn = false;

    /** Currently highlighted edge (sourceName, targetName), or null when none. */
    private String highlightFrom;
    private String highlightTo;

    /**
     * Creates a new SCCRenderer.
     *
     * @param sccPane Pane where SCC lines are drawn
     * @param elementRegistry Map from element names to UI nodes
     * @param statusCallback Callback to update status messages
     */
    public SCCRenderer(Pane sccPane, Map<String, Node> elementRegistry, Consumer<String> statusCallback) {
        this.sccPane = Objects.requireNonNull(sccPane, "sccPane cannot be null");
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.statusCallback = Objects.requireNonNull(statusCallback, "statusCallback cannot be null");
        this.selection = new LineSelection(statusCallback);
    }

    /** Shares the selected line with the other overlays. */
    public void setLineSelection(LineSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection cannot be null");
    }

    public void setPackageTangles(List<Set<String>> packageTangles) {
        this.packageTangles = packageTangles == null ? List.of() : List.copyOf(packageTangles);
    }

    public void setPackageResolver(Function<String, String> packageResolver) {
        this.packageResolver = packageResolver == null ? SCCRenderer::staticPackage : packageResolver;
    }

    /** Binds the model's class cycles; with {@code null} the visible classes are searched for cycles. */
    public void setEdgeCycles(EdgeCycleClassifier edgeCycles) {
        this.edgeCycles = edgeCycles;
    }

    /** The class dependencies to draw at all; the others are left out of the picture. */
    public void setEdgeFilter(BiPredicate<String, String> edgeFilter) {
        this.edgeFilter = edgeFilter == null ? (from, to) -> true : edgeFilter;
    }

    public void setShowClassScc(boolean show) { this.showClassScc = show; }
    public void setShowPackageCycles(boolean show) { this.showPackageCycles = show; }

    public void setCoordinateContext(Pane zoomableContent, Pane overlayPane, ScrollPane scrollPane) {
        // SCC coordinates are derived from sccPane directly.
        this.zoomableContent = zoomableContent;
    }

    /**
     * Clears all SCC lines and resets the drawn flag. The highlighted edge
     * marker is preserved across clears so a subsequent re-draw (e.g. after
     * zoom) can restore it.
     */
    public void clearSccLines() {
        sccPane.getChildren().clear();
        sccLines.clear();
        sccLinesDrawn = false;
        selection.cleared(OVERLAY);
    }

    /**
     * Mark the (from → to) SCC edge as the user-selected one. Subsequent
     * draws render that line in {@link #SCC_HIGHLIGHT_COLOR} with a thicker
     * stroke. Already-drawn lines are restyled in place. Pass either argument
     * as null to clear.
     */
    public void highlightEdge(String from, String to) {
        this.highlightFrom = from;
        this.highlightTo = to;
        applyHighlightToExistingLines();
    }

    private boolean isHighlighted(String from, String to) {
        return highlightFrom != null && highlightTo != null
                && highlightFrom.equals(from) && highlightTo.equals(to);
    }

    /**
     * Restyle already-drawn lines so the currently selected edge — if any —
     * stands out. Called after {@link #highlightEdge} and after each draw.
     */
    private void applyHighlightToExistingLines() {
        sccLines.forEach(this::restyle);
    }

    private void restyle(Line line) {
        if (!(line.getUserData() instanceof Object[] meta) || meta.length < 4) {
            return;
        }
        boolean hi = isHighlighted((String) meta[2], (String) meta[3]);
        Color color = hi ? SCC_HIGHLIGHT_COLOR : (Color) meta[1];
        double width = hi ? SCC_WIDTH * SCC_HIGHLIGHT_WIDTH_FACTOR : SCC_WIDTH;
        line.setStroke(color);
        line.setStrokeWidth(width);
        ((Polyline) meta[0]).setStroke(color);
        ((Polyline) meta[0]).setStrokeWidth(width);
    }

    /**
     * Returns whether SCC lines have been drawn.
     */
    public boolean isSccLinesDrawn() {
        return sccLinesDrawn;
    }

    /**
     * Draws every dependency that lies on a cycle: on a class cycle of the model
     * in one colour, on a package cycle only in another. A dependency whose class is
     * hidden in a collapsed package ends at that package; dependencies between
     * the same two boxes become one line, with their number if there are several.
     */
    public void drawSccLines(ArchitectureNode rootNode) {
        if (rootNode == null) {
            return;
        }

        // Clear existing SCC lines
        sccPane.getChildren().clear();
        sccLines.clear();
        selection.cleared(OVERLAY);

        Map<String, Set<String>> classDependencies = new LinkedHashMap<>();
        collectClassDependencies(rootNode, classDependencies);
        Map<String, Node> endpoints = new HashMap<>();
        Map<Ends, Bundle> bundles = new LinkedHashMap<>();

        // The class cycles.
        Set<String> classSccMembers = new HashSet<>();
        int sccCount = 0;
        if (showClassScc && edgeCycles != null) {
            sccCount = collectModelClassCycles(classDependencies, classSccMembers, endpoints, bundles);
        } else if (showClassScc) {
            sccCount = collectVisibleClassCycles(classDependencies, classSccMembers, endpoints, bundles);
        }

        // What realises a package tangle and is not covered by a class cycle.
        int pkgCycleCount = 0;
        if (showPackageCycles) {
            pkgCycleCount = collectPackageCycles(classDependencies, classSccMembers, endpoints, bundles);
        }

        // All at once: thousands of single additions are slow in JavaFX.
        List<Node> shapes = new ArrayList<>();
        boxBounds.clear();
        hitAreas.getChildren().clear();
        boolean easyToHit = bundles.size() <= MAX_HIT_AREAS;
        bundles.forEach((ends, bundle) -> drawBundle(
                ends, bundle, bundles.containsKey(new Ends(ends.target(), ends.source())), easyToHit, shapes));
        boxBounds.clear();
        if (!hitAreas.getChildren().isEmpty()) {
            shapes.add(0, hitAreas);
        }
        sccPane.getChildren().setAll(shapes);

        // Mark as drawn
        sccLinesDrawn = true;

        // Re-apply the highlight (if any) on the freshly drawn lines.
        applyHighlightToExistingLines();

        if (sccCount > 0 || pkgCycleCount > 0) {
            statusCallback.accept("Cycles: " + sccCount + " class SCC(s) (purple)"
                    + (pkgCycleCount > 0 ? ", " + pkgCycleCount + " package cycle edge(s) (orange)" : ""));
        } else {
            statusCallback.accept("No cycles found");
        }
    }

    /** The two boxes a line connects; the same box twice for what lies within a collapsed package. */
    private record Ends(Node source, Node target) {}

    /** The class dependencies one line stands for. */
    private static final class Bundle {
        private final String from;
        private final String to;
        private final List<DependencyEdge> edges = new ArrayList<>();
        private boolean classCycle;
        private boolean collapsed;

        private Bundle(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    /**
     * Adds a class dependency to the line between the boxes that stand for its
     * classes. False when one of them has none in this view.
     */
    private boolean collect(String from, String to, boolean classCycle,
                            Map<String, Node> endpoints, Map<Ends, Bundle> bundles) {
        Node source = endpointOf(from, endpoints);
        Node target = endpointOf(to, endpoints);
        if (source == null || target == null) {
            return false;
        }
        Bundle bundle = bundles.computeIfAbsent(new Ends(source, target), ends -> new Bundle(from, to));
        bundle.edges.add(new DependencyEdge(from, to));
        bundle.classCycle |= classCycle;
        bundle.collapsed |= source != elementRegistry.get(from) || target != elementRegistry.get(to);
        return true;
    }

    private Node endpointOf(String fqn, Map<String, Node> endpoints) {
        // computeIfAbsent would look again for every element that has no box.
        if (!endpoints.containsKey(fqn)) {
            endpoints.put(fqn, BoxVisibility.visibleEndpoint(elementRegistry, fqn, zoomableContent));
        }
        return endpoints.get(fqn);
    }

    /**
     * Collects every dependency that lies on a class cycle of the model. The
     * cycle may close through classes that are not part of this view at all.
     *
     * @return the number of distinct class cycles
     */
    private int collectModelClassCycles(Map<String, Set<String>> classDependencies, Set<String> cycleMembers,
                                        Map<String, Node> endpoints, Map<Ends, Bundle> bundles) {
        Set<Integer> cycles = new HashSet<>();
        classDependencies.forEach((from, dependencies) -> {
            for (String to : dependencies) {
                if (edgeCycles.cycleOf(from, to) == CycleKind.CLASS_CYCLE
                        && collect(from, to, true, endpoints, bundles)) {
                    cycleMembers.add(from);
                    cycleMembers.add(to);
                    cycles.add(edgeCycles.classCycleId(from));
                }
            }
        });
        return cycles.size();
    }

    /** Without a model: the cycles among the classes whose boxes are shown. */
    private int collectVisibleClassCycles(Map<String, Set<String>> classDependencies, Set<String> cycleMembers,
                                          Map<String, Node> endpoints, Map<Ends, Bundle> bundles) {
        Map<String, Set<String>> visible = new HashMap<>();
        classDependencies.forEach((from, dependencies) -> {
            if (isShown(elementRegistry.get(from))) {
                visible.put(from, dependencies.stream()
                        .filter(elementRegistry::containsKey)
                        .collect(Collectors.toSet()));
            }
        });
        int count = 0;
        for (StronglyConnectedComponent scc : Lookup.lookup(SCCFinder.class).findSCCs(visible)) {
            if (!scc.isTangle()) {
                continue;
            }
            count++;
            cycleMembers.addAll(scc.getMembers());
            for (String from : scc.getMembers()) {
                for (String to : visible.getOrDefault(from, Set.of())) {
                    if (scc.getMembers().contains(to) && isShown(elementRegistry.get(to))) {
                        collect(from, to, true, endpoints, bundles);
                    }
                }
            }
        }
        return count;
    }

    /**
     * Collects the class dependencies that realise a container tangle: every
     * edge between classes that belong to two different members of the tangle.
     * Members are siblings of one container — packages (covering their whole
     * subtree) or classes.
     */
    private int collectPackageCycles(Map<String, Set<String>> classDependencies, Set<String> skipClasses,
                                     Map<String, Node> endpoints, Map<Ends, Bundle> bundles) {
        int count = 0;
        for (Set<String> tangle : packageTangles) {
            Map<String, String> memberOf = new HashMap<>();
            for (String cls : classDependencies.keySet()) {
                String member = tangleMemberOf(tangle, cls);
                if (member != null) {
                    memberOf.put(cls, member);
                }
            }
            for (Map.Entry<String, String> entry : memberOf.entrySet()) {
                String from = entry.getKey();
                String fromMember = entry.getValue();
                for (String to : classDependencies.get(from)) {
                    String toMember = memberOf.get(to);
                    if (toMember == null || toMember.equals(fromMember)
                            || skipClasses.contains(from) && skipClasses.contains(to)) {
                        continue;
                    }
                    if (collect(from, to, false, endpoints, bundles)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void collectClassDependencies(ArchitectureNode node, Map<String, Set<String>> result) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            Set<String> dependencies = new HashSet<>();
            for (String dependency : node.getDependencies()) {
                if (edgeFilter.test(node.getFullName(), dependency)) {
                    dependencies.add(dependency);
                }
            }
            result.put(node.getFullName(), dependencies);
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectClassDependencies(child, result);
        }
    }

    private void drawBundle(Ends ends, Bundle bundle, boolean opposed, boolean easyToHit, List<Node> shapes) {
        Color color = bundle.classCycle ? SCC_COLOR : PKG_CYCLE_COLOR;
        LineSelection.Key key = new LineSelection.Key(OVERLAY, nameOf(ends.source(), bundle.from),
                nameOf(ends.target(), bundle.to), bundle.edges);
        if (ends.source() == ends.target()) {
            drawLoop(ends.source(), bundle, key, color, shapes);
            return;
        }
        // Cycles run both ways. Between two packages, which may be close
        // together, each arrow moves to its own side; its number is beside it.
        double shift = opposed && bundle.collapsed ? OPPOSED_GAP / 2.0 : 0.0;
        Line line = createSccLine(ends.source(), ends.target(), bundle.from, bundle.to, color, shift, shapes);
        if (line == null) {
            return;
        }
        Group number = null;
        if (bundle.collapsed && bundle.edges.size() > 1) {
            number = numberBeside(line, bundle.edges.size(), color);
            shapes.add(number);
        }
        if (!bundle.classCycle) {
            LineColors.dash(line);
        }
        LineSelection.Look look = new LineLook(line);
        selection.install(line, key, look);
        if (number != null) {
            selection.install(number, key, look);
        }
        if (easyToHit) {
            Line area = LineSelection.hitArea(
                    new Line(line.getStartX(), line.getStartY(), line.getEndX(), line.getEndY()), HIT_WIDTH);
            selection.install(area, key, look);
            hitAreas.getChildren().add(area);
        }
        if (!bundle.collapsed) {
            selection.installCutMenu(line, bundle.from, bundle.to);
        }
        selection.drawn(key, look);
    }

    /** The box that stands for the element: itself, or the package it is hidden in. */
    private static String nameOf(Node box, String fqn) {
        return box instanceof GraphSelection.Selectable selectable ? selectable.getFullName() : fqn;
    }

    private static Group numberBeside(Line line, int count, Color color) {
        double dx = line.getEndX() - line.getStartX();
        double dy = line.getEndY() - line.getStartY();
        double length = Math.max(1.0, Math.hypot(dx, dy));
        double beside = BADGE_MIN_RADIUS + BADGE_GAP;
        return badge(count, color,
                (line.getStartX() + line.getEndX()) / 2.0 + dy / length * beside,
                (line.getStartY() + line.getEndY()) / 2.0 - dx / length * beside);
    }

    /** A line and its head, selected, under the mouse and otherwise. */
    private final class LineLook implements LineSelection.Look {
        private final Line line;

        private LineLook(Line line) {
            this.line = line;
        }

        @Override
        public void selected(boolean selected) {
            if (selected) {
                stroke(LineColors.SELECTED, SCC_WIDTH * 2);
            } else {
                restyle(line);
            }
        }

        @Override
        public void hovered(boolean hovered) {
            if (hovered) {
                stroke((Color) line.getStroke(), SCC_WIDTH * 2);
            } else {
                restyle(line);
            }
        }

        private void stroke(Color color, double width) {
            line.setStroke(color);
            line.setStrokeWidth(width);
            Polyline head = (Polyline) ((Object[]) line.getUserData())[0];
            head.setStroke(color);
            head.setStrokeWidth(width);
        }
    }

    /** Cycles within a collapsed package: a loop at its upper right corner with the number of dependencies. */
    private void drawLoop(Node box, Bundle bundle, LineSelection.Key key, Color color, List<Node> shapes) {
        double[] bounds = boxBounds.computeIfAbsent(box, this::getBoundsInPane);
        if (bounds == null) {
            return;
        }
        double endX = bounds[2] - LOOP_INSET;
        double startX = endX - Math.min(LOOP_WIDTH, (bounds[2] - bounds[0]) / 3.0);
        double top = bounds[1];
        double middle = (startX + endX) / 2.0;
        QuadCurve loop = new QuadCurve(startX, top, middle, top - LOOP_HEIGHT, endX, top);
        loop.setFill(null);
        loop.setStroke(color);
        loop.setStrokeWidth(getScaledLineWidth());
        Group number = badge(bundle.edges.size(), color, middle, top - LOOP_HEIGHT / 2.0);
        LineSelection.Look look = new LineSelection.Look() {
            @Override
            public void selected(boolean selected) {
                loop.setStroke(selected ? LineColors.SELECTED : color);
                hovered(selected);
            }

            @Override
            public void hovered(boolean hovered) {
                loop.setStrokeWidth(hovered ? SCC_WIDTH * 2 : SCC_WIDTH);
            }
        };
        selection.install(loop, key, look);
        selection.install(number, key, look);
        selection.drawn(key, look);
        shapes.add(loop);
        shapes.add(number);
    }

    private static Group badge(int count, Color color, double x, double y) {
        Text label = new Text(Integer.toString(count));
        label.setFont(BADGE_FONT);
        label.setFill(Color.WHITE);
        label.setTextOrigin(VPos.CENTER);
        double width = label.getLayoutBounds().getWidth();
        double height = label.getLayoutBounds().getHeight();
        label.setX(-width / 2.0);
        Group group = new Group(new Circle(Math.max(BADGE_MIN_RADIUS, Math.max(width, height) / 2.0 + BADGE_PADDING), color), label);
        group.setLayoutX(x);
        group.setLayoutY(y);
        return group;
    }

    /** The tangle member that contains {@code fqn}: the class itself or its nearest enclosing package member. */
    private String tangleMemberOf(Set<String> tangle, String fqn) {
        if (tangle.contains(fqn)) return fqn;
        String pkg = packageOf(fqn);
        while (pkg != null && !pkg.isEmpty()) {
            if (tangle.contains(pkg)) return pkg;
            pkg = staticPackage(pkg);
        }
        return null;
    }

    private String packageOf(String fqn) {
        String packageName = packageResolver.apply(fqn);
        return packageName == null ? staticPackage(fqn) : packageName;
    }

    private static String staticPackage(String fqn) {
        int dot = fqn == null ? -1 : fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /** Draws a line with its arrowhead; {@code null} if it could not be drawn. */
    private Line createSccLine(Node source, Node target, String sourceName, String targetName, Color color,
                               double shift, List<Node> shapes) {
        try {
            double[] sourceBounds = boxBounds.computeIfAbsent(source, this::getBoundsInPane);
            double[] targetBounds = boxBounds.computeIfAbsent(target, this::getBoundsInPane);

            if (sourceBounds == null || targetBounds == null) {
                return null;
            }

            double sourceCenterX = (sourceBounds[0] + sourceBounds[2]) / 2.0;
            double sourceCenterY = (sourceBounds[1] + sourceBounds[3]) / 2.0;
            double targetCenterX = (targetBounds[0] + targetBounds[2]) / 2.0;
            double targetCenterY = (targetBounds[1] + targetBounds[3]) / 2.0;
            double[] start = edgePoint(sourceBounds, targetCenterX, targetCenterY);
            double[] end = edgePoint(targetBounds, sourceCenterX, sourceCenterY);
            double length = Math.max(1.0, Math.hypot(end[0] - start[0], end[1] - start[1]));
            double shiftX = (end[1] - start[1]) / length * shift;
            double shiftY = -(end[0] - start[0]) / length * shift;
            double startX = start[0] + shiftX;
            double startY = start[1] + shiftY;
            double endX = end[0] + shiftX;
            double endY = end[1] + shiftY;

            // Create the line
            double scaledWidth = getScaledLineWidth();
            Line line = new Line(startX, startY, endX, endY);
            line.setStroke(color);
            line.setStrokeWidth(scaledWidth);

            // Create arrowhead
            double arrowSize = getScaledArrowSize();
            double angle = Math.atan2(endY - startY, endX - startX);

            double x1 = endX - arrowSize * Math.cos(angle - Math.PI / 6);
            double y1 = endY - arrowSize * Math.sin(angle - Math.PI / 6);
            double x2 = endX - arrowSize * Math.cos(angle + Math.PI / 6);
            double y2 = endY - arrowSize * Math.sin(angle + Math.PI / 6);

            // One shape, not two lines: the number of nodes decides how fast
            // the cycles of a large system appear.
            Polyline arrowhead = new Polyline(x1, y1, endX, endY, x2, y2);
            arrowhead.setStroke(color);
            arrowhead.setStrokeWidth(scaledWidth);
            arrowhead.setMouseTransparent(true);

            // Store metadata
            line.setUserData(new Object[]{arrowhead, color, sourceName, targetName});

            shapes.add(line);
            shapes.add(arrowhead);
            sccLines.add(line);
            return line;

        } catch (Exception e) {
            // Ignore drawing errors for elements not in scene
            return null;
        }
    }

    private boolean isShown(Node node) {
        return BoxVisibility.isShown(node, zoomableContent);
    }

    /**
     * Returns [minX, minY, maxX, maxY] in the SCC pane's coordinate space.
     */
    private double[] getBoundsInPane(Node node) {
        try {
            if (node == null || sccPane == null || node.getScene() != sccPane.getScene()) {
                return null;
            }
            Bounds bounds = sccPane.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            if (!isUsable(bounds)) {
                return null;
            }
            return new double[]{bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()};
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isUsable(Bounds bounds) {
        return bounds != null
                && Double.isFinite(bounds.getMinX())
                && Double.isFinite(bounds.getMinY())
                && Double.isFinite(bounds.getWidth())
                && Double.isFinite(bounds.getHeight())
                && bounds.getWidth() > 1.0
                && bounds.getHeight() > 1.0;
    }

    private static double[] edgePoint(double[] box, double towardX, double towardY) {
        double minX = box[0];
        double minY = box[1];
        double maxX = box[2];
        double maxY = box[3];
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        double dx = towardX - cx;
        double dy = towardY - cy;
        if (Math.abs(dx) < 0.0001 && Math.abs(dy) < 0.0001) {
            return new double[]{cx, cy};
        }
        double halfW = (maxX - minX) / 2.0;
        double halfH = (maxY - minY) / 2.0;
        double scale = 1.0 / Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH);
        return new double[]{cx + dx * scale, cy + dy * scale};
    }

    /**
     * Returns the line width (scales automatically with content).
     */
    private double getScaledLineWidth() {
        return SCC_WIDTH;
    }

    /**
     * Returns the arrow size (scales automatically with content).
     */
    private double getScaledArrowSize() {
        return 4.0;
    }
}
