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

import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.graph.ContainerBox;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.canvas.ZoomController;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
 *   <li>Zoom-aware scaling</li>
 * </ul>
 */
public class DependencyRenderer implements DependencyRendererStrategy {

    private final Map<String, Node> elementRegistry;
    private final ZoomController zoomController;
    private final DependencyArrowPainter painter;

    private final Map<String, Integer> levelByFqn = new HashMap<>();
    private boolean dependencyLinesDrawn = false;
    private String selectedFullName;

    // Dynamic references set by ArchitectureCanvas
    private Pane zoomableContent;
    private Pane overlayPane;
    private ScrollPane scrollPane;

    /**
     * Creates a new DependencyRenderer.
     *
     * @param dependencyPane Pane where dependency lines are drawn
     * @param elementRegistry Map from element names to UI nodes
     * @param zoomController Controller for zoom-aware scaling
     * @param statusCallback Callback to update status messages
     */
    public DependencyRenderer(Pane dependencyPane, Map<String, Node> elementRegistry,
                               ZoomController zoomController, Consumer<String> statusCallback) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.zoomController = Objects.requireNonNull(zoomController, "zoomController cannot be null");
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

        // Iterate through all registered elements and draw arrows for their dependencies
        drawDependencyArrowsRecursive(rootNode, selectedFullName);

        // Mark as drawn
        dependencyLinesDrawn = true;
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
     * A dependency runs in the wrong direction when its source sits on a LOWER
     * architecture level than its target — the callee would depend on its
     * caller. Unknown levels never count as wrong.
     */
    private boolean isWrongDirection(String sourceFqn, String targetFqn) {
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
                if (sourceElement == null || !isNodeActuallyVisible(sourceElement)) {
                    continue;
                }
                // Group deps by their visible target node.
                // When a dep's class is inside a collapsed package, all deps to that
                // package collapse to one arrow pointing at the package box.
                Map<Node, String> targetToRepName = new HashMap<>();
                for (String depName : child.getDependencies()) {
                    Node target = findVisibleTarget(depName);
                    if (target != null) {
                        targetToRepName.putIfAbsent(target, depName);
                    }
                }
                for (Map.Entry<Node, String> e : targetToRepName.entrySet()) {
                    Node targetElement = e.getKey();
                    String depName    = e.getValue();
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
                    painter.drawArrow(sourceElement, targetElement,
                            child.getFullName(), targetName, isIncoming);
                }

            } else if (child.getType() == ArchitectureNode.NodeType.PACKAGE) {
                Node uiElement = elementRegistry.get(child.getFullName());
                if (uiElement instanceof ContainerBox
                        && isNodeActuallyVisible(uiElement)
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
        if (element == null) return null;
        if (isNodeActuallyVisible(element)) return element;

        // Target is inside a collapsed package/component — roll up to the nearest visible box.
        Parent parent = element.getParent();
        while (parent != null) {
            Object rollup = parent.getProperties().get("s202.rollupEndpointFqn");
            if (rollup instanceof String endpointFqn) {
                Node endpoint = elementRegistry.get(endpointFqn);
                if (endpoint != null && isNodeActuallyVisible(endpoint)) {
                    return endpoint;
                }
            }
            if (parent instanceof ContainerBox && isNodeActuallyVisible(parent)) {
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
                && isNodeActuallyVisible(node);
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
            if (n != null && isNodeActuallyVisible(n)) {
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

        // target node → {total deps, wrong-direction deps} pointing there
        Map<Node, int[]> targetCounts = new HashMap<>();
        collectSubtreeClassDeps(packageNode, srcFqn, srcPrefix, targetCounts);

        for (Map.Entry<Node, int[]> entry : targetCounts.entrySet()) {
            Node   targetElement = entry.getKey();
            int    count         = entry.getValue()[0];
            int    wrongCount    = entry.getValue()[1];
            String targetFqn     = fqnOf(targetElement);

            boolean isSourceSelected = selectedFqn != null &&
                    (srcFqn.equals(selectedFqn) || srcFqn.startsWith(selectedFqn + ".") ||
                     selectedFqn.startsWith(srcPrefix));
            boolean isTargetSelected = selectedFqn != null &&
                    (targetFqn.equals(selectedFqn) || targetFqn.startsWith(selectedFqn + ".") ||
                     selectedFqn.startsWith(targetFqn + "."));

            if (selectedFqn != null && !isSourceSelected && !isTargetSelected) continue;

            boolean isIncoming = isTargetSelected && !isSourceSelected;
            // Always show count badge; for class-level targets only when > 1.
            int badge = (targetElement instanceof ContainerBox
                    || Boolean.TRUE.equals(targetElement.getProperties().get("s202.aggregateEndpoint"))
                    || count > 1) ? count : 0;
            painter.drawArrow(sourceElement, targetElement, srcFqn, targetFqn, isIncoming,
                    badge, wrongCount > 0);
        }
    }

    /**
     * Walks the subtree of {@code node} collecting every class-level dep that
     * points outside {@code srcFqn}/{@code srcPrefix}. Each dep is resolved to
     * its visible target node (rolling up collapsed packages) and aggregated as
     * {@code {total, wrongDirection}} counts. Wrong direction is decided on the
     * underlying CLASS levels, not on the rolled-up boxes.
     */
    private void collectSubtreeClassDeps(ArchitectureNode node,
                                          String srcFqn, String srcPrefix,
                                          Map<Node, int[]> targetCounts) {
        for (ArchitectureNode child : node.getChildren()) {
            if (child.getType() == ArchitectureNode.NodeType.CLASS) {
                for (String dep : child.getDependencies()) {
                    if (dep.equals(srcFqn) || dep.startsWith(srcPrefix)) continue; // internal
                    Node target = findVisibleTarget(dep);
                    if (target != null) {
                        int[] counts = targetCounts.computeIfAbsent(target, k -> new int[2]);
                        counts[0]++;
                        if (isWrongDirection(child.getFullName(), dep)) {
                            counts[1]++;
                        }
                    }
                }
            } else {
                collectSubtreeClassDeps(child, srcFqn, srcPrefix, targetCounts);
            }
        }
    }

    /** Returns the FQN of a visible architecture box. */
    private static String fqnOf(Node node) {
        if (node instanceof ContainerBox cb) return cb.getFullName();
        if (node instanceof LevelClassBox  lcb) return lcb.getFullName();
        if (node instanceof GraphSelection.Selectable selectable) return selectable.getFullName();
        return "";
    }

    /**
     * Checks if a node is actually visible (itself and all parents are visible).
     */
    private boolean isNodeActuallyVisible(Node node) {
        if (node == null || !node.isVisible()) {
            return false;
        }

        // Check all parents up to the scene root
        Parent parent = node.getParent();
        while (parent != null) {
            if (!parent.isVisible()) {
                return false;
            }
            parent = parent.getParent();
        }

        return true;
    }
}
