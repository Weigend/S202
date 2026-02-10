package de.weigend.s202.ui.rendering;

import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.ui.model.ArchitectureNode;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

import java.util.*;
import java.util.function.Consumer;

/**
 * Renders SCC (Strongly Connected Components) visualization.
 * Detects cyclic dependencies using Tarjan's algorithm and draws red arrows.
 *
 * <p>Features:
 * <ul>
 *   <li>Detects cycles using Tarjan's SCC algorithm</li>
 *   <li>Draws red arrows for cyclic dependencies only</li>
 *   <li>Interactive hover effects with status updates</li>
 *   <li>Visibility filtering (only draws visible elements)</li>
 *   <li>Zoom-aware scaling</li>
 * </ul>
 */
public class SCCRenderer {

    private static final Color SCC_COLOR = Color.RED;
    private static final double SCC_WIDTH = 1.0;

    private final Pane sccPane;
    private final Map<String, Node> elementRegistry;
    private final Consumer<String> statusCallback;

    private final List<Line> sccLines = new ArrayList<>();
    private boolean sccLinesDrawn = false;

    // Dynamic references set by ArchitectureView
    private Pane zoomableContent;
    private Pane overlayPane;
    private ScrollPane scrollPane;

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

    /**
     * Clears all SCC lines and resets the drawn flag.
     */
    public void clearSccLines() {
        sccPane.getChildren().clear();
        sccLines.clear();
        sccLinesDrawn = false;
    }

    /**
     * Returns whether SCC lines have been drawn.
     */
    public boolean isSccLinesDrawn() {
        return sccLinesDrawn;
    }

    /**
     * Draws SCC lines connecting all visible classes that are part of the same SCC (cycle).
     */
    public void drawSccLines(ArchitectureNode rootNode) {
        if (rootNode == null) {
            return;
        }

        // Clear existing SCC lines
        sccPane.getChildren().clear();
        sccLines.clear();

        // Step 1: Collect all visible classes and their dependencies
        Map<String, Set<String>> classDependencies = new HashMap<>();
        collectVisibleClassDependencies(rootNode, classDependencies);

        if (classDependencies.isEmpty()) {
            sccLinesDrawn = true;
            statusCallback.accept("No visible classes for SCC analysis");
            return;
        }

        // Step 2: Find SCCs using Tarjan algorithm
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(classDependencies);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();

        // Step 3: Draw lines for each SCC with more than 1 member (cycles only)
        int sccCount = 0;
        for (StronglyConnectedComponent scc : sccs) {
            if (scc.isTangle()) { // Only draw for cycles (size > 1)
                drawSccComponentLines(scc, classDependencies);
                sccCount++;
            }
        }

        // Mark as drawn
        sccLinesDrawn = true;

        if (sccCount > 0) {
            statusCallback.accept("Showing " + sccCount + " SCC cycle(s) in red");
        } else {
            statusCallback.accept("No cycles found among visible classes");
        }
    }

    /**
     * Recursively collects class dependencies from visible (expanded) nodes.
     */
    private void collectVisibleClassDependencies(ArchitectureNode node, Map<String, Set<String>> result) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            // Check if this class is actually visible in the UI
            Node uiNode = elementRegistry.get(node.getFullName());
            if (uiNode != null && isNodeActuallyVisible(uiNode)) {
                // Filter dependencies to only include classes we know about
                Set<String> visibleDeps = new HashSet<>();
                for (String dep : node.getDependencies()) {
                    if (elementRegistry.containsKey(dep)) {
                        visibleDeps.add(dep);
                    }
                }
                result.put(node.getFullName(), visibleDeps);
            }
        }

        // Recurse into children
        for (ArchitectureNode child : node.getChildren()) {
            collectVisibleClassDependencies(child, result);
        }
    }

    /**
     * Draws lines connecting all members of a single SCC that are visible.
     * Only draws the actual dependency edges within the SCC, not all pairs.
     */
    private void drawSccComponentLines(StronglyConnectedComponent scc, Map<String, Set<String>> classDependencies) {
        Set<String> members = scc.getMembers();

        // Draw dependency edges between members of this SCC
        for (String member : members) {
            Node sourceNode = elementRegistry.get(member);
            if (sourceNode == null || !isNodeActuallyVisible(sourceNode)) {
                continue;
            }

            Set<String> deps = classDependencies.getOrDefault(member, Set.of());
            for (String dep : deps) {
                if (members.contains(dep)) {
                    Node targetNode = elementRegistry.get(dep);
                    if (targetNode != null && isNodeActuallyVisible(targetNode)) {
                        createSccLine(sourceNode, targetNode, member, dep);
                    }
                }
            }
        }
    }

    /**
     * Creates a single SCC line between two elements.
     */
    private void createSccLine(Node source, Node target, String sourceName, String targetName) {
        try {
            // Get coordinates
            double[] sourceCenter = getNodeCenterInPane(source);
            double[] targetCenter = getNodeCenterInPane(target);

            if (sourceCenter == null || targetCenter == null) {
                return;
            }

            double startX = sourceCenter[0];
            double startY = sourceCenter[1];
            double endX = targetCenter[0];
            double endY = targetCenter[1];

            // Create the line
            double scaledWidth = getScaledLineWidth();
            Line line = new Line(startX, startY, endX, endY);
            line.setStroke(SCC_COLOR);
            line.setStrokeWidth(scaledWidth);

            // Hover effects
            line.setOnMouseEntered(e -> {
                line.setStroke(Color.DARKRED);
                line.setCursor(javafx.scene.Cursor.HAND);
            });

            line.setOnMouseExited(e -> {
                line.setStroke(SCC_COLOR);
                line.setCursor(javafx.scene.Cursor.DEFAULT);
            });

            // Click handler
            line.setOnMouseClicked(e -> {
                String simpleSource = sourceName.contains(".") ?
                        sourceName.substring(sourceName.lastIndexOf('.') + 1) : sourceName;
                String simpleTarget = targetName.contains(".") ?
                        targetName.substring(targetName.lastIndexOf('.') + 1) : targetName;
                statusCallback.accept("SCC Edge: " + simpleSource + " ↔ " + simpleTarget);
                e.consume();
            });

            // Create arrowhead
            double arrowSize = getScaledArrowSize();
            double angle = Math.atan2(endY - startY, endX - startX);

            double x1 = endX - arrowSize * Math.cos(angle - Math.PI / 6);
            double y1 = endY - arrowSize * Math.sin(angle - Math.PI / 6);
            double x2 = endX - arrowSize * Math.cos(angle + Math.PI / 6);
            double y2 = endY - arrowSize * Math.sin(angle + Math.PI / 6);

            Line arrow1 = new Line(endX, endY, x1, y1);
            Line arrow2 = new Line(endX, endY, x2, y2);
            arrow1.setStroke(SCC_COLOR);
            arrow2.setStroke(SCC_COLOR);
            arrow1.setStrokeWidth(scaledWidth);
            arrow2.setStrokeWidth(scaledWidth);
            arrow1.setMouseTransparent(true);
            arrow2.setMouseTransparent(true);

            // Store metadata
            line.setUserData(new Object[]{arrow1, arrow2, SCC_COLOR, sourceName, targetName});

            sccPane.getChildren().addAll(line, arrow1, arrow2);
            sccLines.add(line);
            sccLines.add(arrow1);
            sccLines.add(arrow2);

        } catch (Exception e) {
            // Ignore drawing errors for elements not in scene
        }
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

    /**
     * Calculates the center point of a node relative to the overlay pane.
     */
    private double[] getNodeCenterInPane(Node node) {
        try {
            if (zoomableContent == null || overlayPane == null) {
                return null;
            }

            // Get bounds in local coordinates
            Bounds localBounds = node.getBoundsInLocal();

            // Transform to zoomableContent's coordinate space
            Node current = node;
            double centerX = localBounds.getMinX() + localBounds.getWidth() / 2;
            double centerY = localBounds.getMinY() + localBounds.getHeight() / 2;

            // Walk up the parent chain to zoomableContent, accumulating transforms
            while (current != null && current != zoomableContent) {
                Bounds boundsInParent = current.getBoundsInParent();
                Bounds localB = current.getBoundsInLocal();

                // Adjust for the offset from local to parent bounds
                centerX = centerX - localB.getMinX() + boundsInParent.getMinX();
                centerY = centerY - localB.getMinY() + boundsInParent.getMinY();

                current = current.getParent();
            }

            return new double[]{centerX, centerY};
        } catch (Exception e) {
            return null;
        }
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
