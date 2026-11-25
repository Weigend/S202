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
import de.weigend.s202.ui.core.graph.BoxVisibility;
import de.weigend.s202.ui.core.graph.ElementLookup;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.graph.ContainerBox;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Renders dependency arrows between architecture elements.
 * Handles line drawing, selection, visibility checking, and coordinate transformations.
 *
 * <p>Features:
 * <ul>
 *   <li>Draws dependency arrows with directional arrowheads</li>
 *   <li>Color-coded: anthrazit (outgoing), green (incoming)</li>
 *   <li>Interactive line selection with status updates</li>
 *   <li>Visibility filtering (only draws visible elements)</li>
 * </ul>
 */
public class DependencyRenderer implements DependencyRendererStrategy {

    private final Map<String, Node> elementRegistry;
    private final DependencyArrowPainter painter;

    private final Map<String, Integer> levelByFqn = new HashMap<>();
    /** The arrows of the current redraw, collected before any is painted. */
    private final List<DependencyArrowPainter.Arrow> arrows = new ArrayList<>();
    private boolean dependencyLinesDrawn = false;
    private String selectedFullName;

    // What the model knows about each class dependency; null until a model is bound.
    private EdgeCycleClassifier edgeCycles;
    private boolean backEdgesDecideDirection;
    // Non-null in a tangle view: which dependencies it draws and how.
    private TangleEdgeScope tangleScope;
    private BiPredicate<String, String> edgeFilter = (from, to) -> true;

    // Dynamic references set by ArchitectureCanvas
    private Pane zoomableContent;
    private Pane overlayPane;
    private ScrollPane scrollPane;

    /**
     * Creates a new DependencyRenderer.
     *
     * @param dependencyPane Pane where dependency lines are drawn
     * @param elementRegistry Map from element names to UI nodes
     * @param statusCallback Callback to update status messages
     */
    public DependencyRenderer(Pane dependencyPane, Map<String, Node> elementRegistry,
                              Consumer<String> statusCallback) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.painter = new DependencyArrowPainter(dependencyPane, statusCallback);
    }

    /**
     * Sets dynamic references needed for coordinate calculations.
     * Must be called before drawing.
     */
    public void setCoordinateContext(Pane zoomableContent, Pane overlayPane, ScrollPane scrollPane) {
        this.zoomableContent = zoomableContent;
        this.overlayPane = overlayPane;
        this.scrollPane = scrollPane;
    }

    /** Selection handling for all arrows, cut and restore for class-to-class ones; {@code null} disables it. */
    public void setEdgeActions(DependencyEdgeActions edgeActions) {
        painter.setEdgeActions(edgeActions);
    }

    /**
     * Binds what the model knows about each class dependency.
     *
     * @param backEdgesDecideDirection when true, a dependency counts as wrong
     *        direction exactly when it is a back-edge of the levelization —
     *        the right question in the layered layout. Otherwise the
     *        architecture levels of both ends decide.
     */
    public void setEdgeCycles(EdgeCycleClassifier edgeCycles, boolean backEdgesDecideDirection) {
        this.edgeCycles = edgeCycles;
        this.backEdgesDecideDirection = backEdgesDecideDirection;
    }

    /** Turns this renderer into the one of a tangle view; {@code null} restores the regular look. */
    public void setTangleScope(TangleEdgeScope tangleScope) {
        this.tangleScope = tangleScope;
    }

    /** The class dependencies to draw at all; the others are left out of the picture. */
    public void setEdgeFilter(BiPredicate<String, String> edgeFilter) {
        this.edgeFilter = edgeFilter == null ? (from, to) -> true : edgeFilter;
    }

    private boolean draws(String from, String to) {
        return edgeFilter.test(from, to) && (tangleScope == null || tangleScope.draws(from, to));
    }

    @Override
    public void setSelectedFullName(String selectedFullName) {
        this.selectedFullName = selectedFullName == null || selectedFullName.isBlank() ? null : selectedFullName;
    }

    /**
     * Clears all dependency arrows and resets the drawn flag.
     */
    public void clearDependencyArrows() {
        painter.clear();
        dependencyLinesDrawn = false;
    }

    /**
     * Draws dependency arrows between visible elements.
     */
    public void drawDependencyArrows(ArchitectureNode rootNode) {
        if (rootNode == null) {
            return;
        }

        // Clear existing lines
        painter.clear();

        // Index the architecture levels once per redraw: wrong-direction
        // detection must come from the semantic levels, never from pixel
        // positions — in the radial hexagonal layout the screen Y axis carries
        // no level meaning at all.
        levelByFqn.clear();
        indexArchitectureLevels(rootNode);

        // Collect first: an arrow has to know whether its reverse is drawn too.
        arrows.clear();
        drawDependencyArrowsRecursive(rootNode, selectedFullName);
        paintArrows();

        // Mark as drawn
        dependencyLinesDrawn = true;
    }

    /**
     * Paints the collected arrows. Two boxes that depend on each other get
     * two arrows along the same line; those are told to keep apart.
     */
    private void paintArrows() {
        Map<Node, Set<Node>> targetsBySource = new HashMap<>();
        for (DependencyArrowPainter.Arrow arrow : arrows) {
            targetsBySource.computeIfAbsent(arrow.source(), k -> new HashSet<>()).add(arrow.target());
        }
        Map<Node, double[]> boxes = tangleScope != null ? nodeBoxes() : Map.of();
        for (DependencyArrowPainter.Arrow arrow : arrows) {
            boolean opposed = targetsBySource.getOrDefault(arrow.target(), Set.of()).contains(arrow.source());
            painter.draw(arrow, opposed, boxes);
        }
        arrows.clear();
    }

    /**
     * The visible boxes that show as one node — classes and collapsed
     * packages — with their bounds. A tangle view draws them without a frame,
     * so an arrow through one would run across its name.
     */
    private Map<Node, double[]> nodeBoxes() {
        Map<Node, double[]> boxes = new HashMap<>();
        for (Node node : elementRegistry.values()) {
            if (boxes.containsKey(node) || !isShown(node)) {
                continue;
            }
            boolean single = node instanceof LevelPackageBox pkg ? !pkg.isExpanded() : node instanceof LevelClassBox;
            if (single) {
                double[] bounds = painter.getBoundsInPane(node);
                if (bounds != null) {
                    boxes.put(node, bounds);
                }
            }
        }
        return boxes;
    }

    private void indexArchitectureLevels(ArchitectureNode node) {
        if (node.getFullName() != null) {
            levelByFqn.put(node.getFullName(), node.getArchitectureLevel());
        }
        for (ArchitectureNode child : node.getChildren()) {
            indexArchitectureLevels(child);
        }
    }

    /**
     * A dependency runs in the wrong direction when the levelization had to
     * cut it, or — where back-edges do not decide — when its source sits on a
     * LOWER architecture level than its target: the callee would depend on
     * its caller. Unknown levels never count as wrong.
     */
    private boolean isWrongDirection(String sourceFqn, String targetFqn) {
        if (edgeCycles != null && backEdgesDecideDirection) {
            return edgeCycles.isBackEdge(sourceFqn, targetFqn);
        }
        Integer sourceLevel = levelByFqn.get(sourceFqn);
        Integer targetLevel = levelByFqn.get(targetFqn);
        return sourceLevel != null && targetLevel != null
                && sourceLevel >= 0 && targetLevel >= 0
                && sourceLevel < targetLevel;
    }

    /**
     * Returns whether dependency lines have been drawn.
     */
    public boolean isDependencyLinesDrawn() {
        return dependencyLinesDrawn;
    }

    /**
     * Recursively draws arrows for visible nodes according to the four-state matrix:
     * <pre>
     *  source open  / target open       → class → class (individual)
     *  source open  / target collapsed  → class → package-top (individual, deduplicated)
     *  source closed / target open      → package-bottom → class (individual)
     *  source closed / target collapsed → package → package (aggregated + badge)
     * </pre>
     */
    private void drawDependencyArrowsRecursive(ArchitectureNode node, String selectedFqn) {
        for (ArchitectureNode child : node.getChildren()) {
            if (child.getType() == ArchitectureNode.NodeType.CLASS) {
                Node sourceElement = elementRegistry.get(child.getFullName());
                if (sourceElement == null || !isShown(sourceElement)) {
                    continue;
                }
                // Group deps by their visible target node.
                // When a dep's class is inside a collapsed package, all deps to that
                // package collapse to one arrow pointing at the package box.
                Map<Node, EdgeGroup> groups = new HashMap<>();
                for (String depName : child.getDependencies()) {
                    if (!draws(child.getFullName(), depName)) {
                        continue;
                    }
                    Node target = findVisibleTarget(depName);
                    if (target != null) {
                        groups.computeIfAbsent(target, k -> new EdgeGroup(depName)).add(child.getFullName(), depName);
                    }
                }
                for (Map.Entry<Node, EdgeGroup> e : groups.entrySet()) {
                    Node targetElement = e.getKey();
                    EdgeGroup group   = e.getValue();
                    String depName    = group.representative;
                    boolean isSourceSelected = selectedFqn != null &&
                            (child.getFullName().equals(selectedFqn) ||
                             child.getFullName().startsWith(selectedFqn + "."));
                    boolean isTargetSelected = selectedFqn != null &&
                            (depName.equals(selectedFqn) ||
                             depName.startsWith(selectedFqn + "."));
                    if (selectedFqn != null && !isSourceSelected && !isTargetSelected) {
                        continue;
                    }
                    boolean isIncoming = isTargetSelected && !isSourceSelected;
                    String targetName = fqnOf(targetElement);
                    if (targetName.isEmpty()) {
                        targetName = depName;
                    }
                    // Not rolled up — one real class dependency: cuttable.
                    boolean classEdge = targetElement == elementRegistry.get(depName);
                    arrows.add(new DependencyArrowPainter.Arrow(sourceElement, targetElement,
                            child.getFullName(), targetName, isIncoming, classEdge,
                            group.edges, null, false, weightOf(group), styleOf(group)));
                }

            } else if (child.getType() == ArchitectureNode.NodeType.PACKAGE) {
                Node uiElement = elementRegistry.get(child.getFullName());
                if (uiElement instanceof ContainerBox
                        && isShown(uiElement)
                        && isPackageCollapsed(child)) {
                    // Closed source: draw aggregated or class-level arrows from the package
                    drawCollapsedPackageArrows(child, uiElement, selectedFqn);
                } else if (isCollapsedAggregateEndpoint(uiElement)) {
                    drawCollapsedPackageArrows(child, uiElement, selectedFqn);
                } else {
                    // Open source: recurse so class-level arrows are drawn
                    drawDependencyArrowsRecursive(child, selectedFqn);
                }
            }
        }
    }

    /**
     * Finds the visible UI node to use as the arrow target for {@code targetFqn}.
     * <ul>
     *   <li>If the element itself is visible → return it directly.</li>
     *   <li>If it is hidden (inside a collapsed package/component) → walk up
     *       the JavaFX parent chain and return the nearest visible
     *       {@link ContainerBox} (package or component box).</li>
     * </ul>
     */
    private Node findVisibleTarget(String targetFqn) {
        Node element = elementRegistry.get(targetFqn);
        if (element == null) {
            // No box yet: the target lies in a collapsed package that builds
            // its content lazily. That package stands in for it.
            element = ElementLookup.pendingContainer(elementRegistry, targetFqn);
            if (element == null) return null;
        }
        if (isShown(element)) return element;

        // Target is inside a collapsed package/component — roll up to the nearest visible box.
        Parent parent = element.getParent();
        while (parent != null) {
            Object rollup = parent.getProperties().get("s202.rollupEndpointFqn");
            if (rollup instanceof String endpointFqn) {
                Node endpoint = elementRegistry.get(endpointFqn);
                if (endpoint != null && isShown(endpoint)) {
                    return endpoint;
                }
            }
            if (parent instanceof ContainerBox && isShown(parent)) {
                return parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private boolean isCollapsedAggregateEndpoint(Node node) {
        return node != null
                && Boolean.TRUE.equals(node.getProperties().get("s202.aggregateEndpoint"))
                && Boolean.TRUE.equals(node.getProperties().get("s202.collapsed"))
                && isShown(node);
    }

    /**
     * Returns true when none of the package's registered children are actually
     * visible in the scene graph — i.e. the package is effectively collapsed.
     * Reads live JavaFX visibility state rather than relying on the isExpanded()
     * field, which can lag behind after expand/collapse transitions.
     */
    private boolean isPackageCollapsed(ArchitectureNode pkg) {
        for (ArchitectureNode child : pkg.getChildren()) {
            Node n = elementRegistry.get(child.getFullName());
            if (n != null && isShown(n)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Draws arrows from a collapsed source package to all external visible targets.
     *
     * <p>Does NOT rely on {@code packageNode.getDependencies()} because ancestor
     * packages (e.g. {@code com}, {@code net}) may have no DomainModel entry and
     * therefore an empty dependency set. Instead, every class-level dep from the
     * entire subtree is collected and mapped to its visible target via
     * {@link #findVisibleTarget}, which rolls a hidden class up to the nearest
     * visible collapsed parent package automatically.
     *
     * <p>Results are grouped by the visible target node so that many deps into
     * the same collapsed package produce exactly one aggregated arrow.
     */
    private void drawCollapsedPackageArrows(ArchitectureNode packageNode, Node sourceElement, String selectedFqn) {
        String srcFqn    = packageNode.getFullName();
        String srcPrefix = srcFqn != null ? srcFqn + "." : "";

        // target node → the class dependencies pointing there
        Map<Node, EdgeGroup> targetGroups = new HashMap<>();
        collectSubtreeClassDeps(packageNode, srcFqn, srcPrefix, targetGroups);

        for (Map.Entry<Node, EdgeGroup> entry : targetGroups.entrySet()) {
            Node      targetElement = entry.getKey();
            EdgeGroup group         = entry.getValue();
            int       count         = group.total;
            String    targetFqn     = fqnOf(targetElement);

            boolean isSourceSelected = selectedFqn != null &&
                    (srcFqn.equals(selectedFqn) || srcFqn.startsWith(selectedFqn + ".") ||
                     selectedFqn.startsWith(srcPrefix));
            boolean isTargetSelected = selectedFqn != null &&
                    (targetFqn.equals(selectedFqn) || targetFqn.startsWith(selectedFqn + ".") ||
                     selectedFqn.startsWith(targetFqn + "."));

            if (selectedFqn != null && !isSourceSelected && !isTargetSelected) continue;

            boolean isIncoming = isTargetSelected && !isSourceSelected;
            // Always show count badge; for class-level targets only when > 1.
            boolean showBadge = targetElement instanceof ContainerBox
                    || Boolean.TRUE.equals(targetElement.getProperties().get("s202.aggregateEndpoint"))
                    || count > 1;
            // A tangle view writes the weight on the line; that replaces the count.
            String weight = weightOf(group);
            arrows.add(new DependencyArrowPainter.Arrow(sourceElement, targetElement, srcFqn, targetFqn,
                    isIncoming, false, group.edges, showBadge && weight == null ? group.badge() : null,
                    group.wrongDirection > 0, weight, styleOf(group)));
        }
    }

    /**
     * Walks the subtree of {@code node} collecting every class-level dep that
     * points outside {@code srcFqn}/{@code srcPrefix}. Each dep is resolved to
     * its visible target node (rolling up collapsed packages) and aggregated
     * into an {@link EdgeGroup}. Wrong direction is decided on the underlying
     * CLASS dependencies, not on the rolled-up boxes.
     */
    private void collectSubtreeClassDeps(ArchitectureNode node,
                                          String srcFqn, String srcPrefix,
                                          Map<Node, EdgeGroup> targetGroups) {
        for (ArchitectureNode child : node.getChildren()) {
            if (child.getType() == ArchitectureNode.NodeType.CLASS) {
                for (String dep : child.getDependencies()) {
                    if (dep.equals(srcFqn) || dep.startsWith(srcPrefix)) continue; // internal
                    if (!draws(child.getFullName(), dep)) continue;
                    Node target = findVisibleTarget(dep);
                    if (target != null) {
                        targetGroups.computeIfAbsent(target, k -> new EdgeGroup(dep)).add(child.getFullName(), dep);
                    }
                }
            } else {
                collectSubtreeClassDeps(child, srcFqn, srcPrefix, targetGroups);
            }
        }
    }

    /**
     * The class dependencies one arrow stands for, with what the model knows
     * about them. A class-to-class arrow is a group of one.
     */
    private final class EdgeGroup {
        final String representative;
        final List<DependencyEdge> edges = new ArrayList<>();
        int total;
        int wrongDirection;
        int backEdges;
        int classCycle;
        int packageCycle;
        int context;
        int weight;
        int backEdgeWeight;

        EdgeGroup(String representative) {
            this.representative = representative;
        }

        void add(String from, String to) {
            total++;
            edges.add(new DependencyEdge(from, to));
            if (isWrongDirection(from, to)) {
                wrongDirection++;
            }
            if (edgeCycles != null) {
                int edgeWeight = edgeCycles.weightOf(from, to);
                weight += edgeWeight;
                if (edgeCycles.isBackEdge(from, to)) {
                    backEdges++;
                    backEdgeWeight += edgeWeight;
                }
                CycleKind kind = edgeCycles.cycleOf(from, to);
                if (kind == CycleKind.CLASS_CYCLE) {
                    classCycle++;
                } else if (kind == CycleKind.PACKAGE_CYCLE) {
                    packageCycle++;
                }
            }
            if (tangleScope != null && !tangleScope.isTangleEdge(from, to)) {
                context++;
            }
        }

        /** The count; a group that mixes directions also names its wrong-direction share. */
        String badge() {
            return wrongDirection > 0 && wrongDirection < total
                    ? total + " / " + wrongDirection + "\u2191"
                    : Integer.toString(total);
        }
    }

    /**
     * Colour, weight and pattern of an arrow in a tangle view; {@code null}
     * in a regular view, which keeps its direction-based look.
     */
    private ArrowStyle styleOf(EdgeGroup group) {
        if (tangleScope == null || edgeCycles == null) {
            return null;
        }
        if (group.context == group.total) {
            return ArrowStyle.CONTEXT;
        }
        boolean backEdge = group.backEdges > 0;
        CycleKind kind = group.classCycle > 0 ? CycleKind.CLASS_CYCLE
                : group.packageCycle > 0 ? CycleKind.PACKAGE_CYCLE
                : CycleKind.NONE;
        ArrowStyle style = ArrowStyle.of(backEdge, kind);
        return tangleScope.dims(backEdge, group.classCycle > 0, group.packageCycle > 0) ? style.dimmed() : style;
    }

    /**
     * The number a tangle view writes on an arrow: the method calls behind
     * it — what cutting it costs. An arrow that mixes back-edges with other
     * dependencies also names the share of its back-edges. {@code null} in a
     * regular view and for an arrow that is context only.
     */
    private String weightOf(EdgeGroup group) {
        if (tangleScope == null || edgeCycles == null || group.context == group.total) {
            return null;
        }
        return group.backEdges > 0 && group.backEdges < group.total
                ? group.weight + " / " + group.backEdgeWeight + "\u2191"
                : Integer.toString(group.weight);
    }

    /** Returns the FQN of a visible architecture box. */
    private static String fqnOf(Node node) {
        if (node instanceof ContainerBox cb) return cb.getFullName();
        if (node instanceof LevelClassBox  lcb) return lcb.getFullName();
        if (node instanceof GraphSelection.Selectable selectable) return selectable.getFullName();
        return "";
    }

    private boolean isShown(Node node) {
        return BoxVisibility.isShown(node, zoomableContent);
    }
}
