package de.weigend.s202.ui.rendering;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.zoom.ZoomController;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.List;
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

    private static final Color OUTGOING_DEPENDENCY_COLOR = Color.rgb(90, 94, 98);  // Anthrazit
    private static final Color INCOMING_DEPENDENCY_COLOR = Color.rgb(0, 128, 0);  // Grün
    private static final double DEPENDENCY_WIDTH = 0.6;

    private final Pane dependencyPane;
    private final Map<String, Node> elementRegistry;
    private final ZoomController zoomController;
    private final Consumer<String> statusCallback;

    private final List<Line> dependencyLines = new ArrayList<>();
    private Line selectedLine = null;
    private boolean dependencyLinesDrawn = false;

    // Dynamic references set by ArchitectureView
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
        this.dependencyPane = Objects.requireNonNull(dependencyPane, "dependencyPane cannot be null");
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.zoomController = Objects.requireNonNull(zoomController, "zoomController cannot be null");
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
     * Clears all dependency arrows and resets the drawn flag.
     */
    public void clearDependencyArrows() {
        dependencyPane.getChildren().clear();
        dependencyLines.clear();
        selectedLine = null;
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
        dependencyPane.getChildren().clear();
        dependencyLines.clear();
        selectedLine = null;

        // Iterate through all registered elements and draw arrows for their dependencies
        drawDependencyArrowsRecursive(rootNode);

        // Mark as drawn
        dependencyLinesDrawn = true;
    }

    /**
     * Returns whether dependency lines have been drawn.
     */
    public boolean isDependencyLinesDrawn() {
        return dependencyLinesDrawn;
    }

    /**
     * Recursively draws arrows for all visible CLASS nodes (not packages).
     * If a class is selected, only draws arrows to/from that class.
     */
    private void drawDependencyArrowsRecursive(ArchitectureNode node) {
        String selectedClass = LevelClassBox.getSelectedClassName();

        for (ArchitectureNode child : node.getChildren()) {
            // Only draw arrows for CLASS nodes, not packages
            if (child.getType() == ArchitectureNode.NodeType.CLASS) {
                // Get the UI element for this node
                Node sourceElement = elementRegistry.get(child.getFullName());

                if (sourceElement != null && isNodeActuallyVisible(sourceElement)) {
                    // Draw arrows for each dependency
                    for (String depName : child.getDependencies()) {
                        // If a class is selected, only show dependencies involving that class
                        boolean isSourceSelected = selectedClass != null && child.getFullName().equals(selectedClass);
                        boolean isTargetSelected = selectedClass != null && depName.equals(selectedClass);

                        if (selectedClass != null && !isSourceSelected && !isTargetSelected) {
                            continue; // Skip this dependency
                        }

                        Node targetElement = findBestTargetElement(depName);

                        if (targetElement != null && isNodeActuallyVisible(targetElement)) {
                            // Determine arrow direction relative to selected class
                            boolean isIncoming = isTargetSelected && !isSourceSelected;
                            createDependencyLine(sourceElement, targetElement,
                                    child.getFullName(), depName, isIncoming);
                        }
                    }
                }
            }

            // Recurse into children (packages contain classes)
            drawDependencyArrowsRecursive(child);
        }
    }

    /**
     * Finds the target element for a dependency.
     * Only returns exact matches - no fallback to parent packages.
     */
    private Node findBestTargetElement(String targetName) {
        Node element = elementRegistry.get(targetName);
        if (element != null && isNodeActuallyVisible(element)) {
            return element;
        }
        return null;
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
     * Creates a selectable dependency line between source and target.
     */
    private void createDependencyLine(Node source, Node target, String sourceName, String targetName,
                                       boolean isIncoming) {
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

            // Choose color based on direction
            Color lineColor = isIncoming ? INCOMING_DEPENDENCY_COLOR : OUTGOING_DEPENDENCY_COLOR;

            // Create the line
            double scaledWidth = getScaledLineWidth();
            Line line = new Line(startX, startY, endX, endY);
            line.setStroke(lineColor);
            line.setStrokeWidth(scaledWidth);

            // Store original color for hover restore
            final Color originalColor = lineColor;

            // Hover effects
            line.setOnMouseEntered(e -> {
                if (line != selectedLine) {
                    line.setStroke(Color.GRAY);
                }
                line.setCursor(javafx.scene.Cursor.HAND);
            });

            line.setOnMouseExited(e -> {
                if (line != selectedLine) {
                    line.setStroke(originalColor);
                }
                line.setCursor(javafx.scene.Cursor.DEFAULT);
            });

            // Click handler for selection
            line.setOnMouseClicked(e -> {
                selectLine(line, sourceName, targetName);
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
            arrow1.setStroke(lineColor);
            arrow2.setStroke(lineColor);
            arrow1.setStrokeWidth(scaledWidth);
            arrow2.setStrokeWidth(scaledWidth);
            arrow1.setMouseTransparent(true);
            arrow2.setMouseTransparent(true);

            // Store reference to arrow lines and original color
            line.setUserData(new Object[]{arrow1, arrow2, lineColor});

            dependencyPane.getChildren().addAll(line, arrow1, arrow2);
            dependencyLines.add(line);

        } catch (Exception e) {
            // Ignore drawing errors for elements not in scene
        }
    }

    /**
     * Selects a dependency line and highlights it.
     */
    private void selectLine(Line line, String sourceName, String targetName) {
        double scaledWidth = getScaledLineWidth();
        double selectedWidth = scaledWidth * 2;

        // Deselect previous line
        if (selectedLine != null && selectedLine != line) {
            Object[] userData = (Object[]) selectedLine.getUserData();
            Color originalColor = (userData != null && userData.length > 2) ? (Color) userData[2] : OUTGOING_DEPENDENCY_COLOR;
            selectedLine.setStroke(originalColor);
            selectedLine.setStrokeWidth(scaledWidth);
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(originalColor);
                arrow2.setStroke(originalColor);
                arrow1.setStrokeWidth(scaledWidth);
                arrow2.setStrokeWidth(scaledWidth);
            }
        }

        // Toggle selection
        if (selectedLine == line) {
            // Deselect
            Object[] userData = (Object[]) line.getUserData();
            Color originalColor = (userData != null && userData.length > 2) ? (Color) userData[2] : OUTGOING_DEPENDENCY_COLOR;
            line.setStroke(originalColor);
            line.setStrokeWidth(scaledWidth);
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(originalColor);
                arrow2.setStroke(originalColor);
                arrow1.setStrokeWidth(scaledWidth);
                arrow2.setStrokeWidth(scaledWidth);
            }
            selectedLine = null;
            statusCallback.accept("Ready");
        } else {
            // Select
            line.setStroke(Color.RED);
            line.setStrokeWidth(selectedWidth);
            Object[] userData = (Object[]) line.getUserData();
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(Color.RED);
                arrow2.setStroke(Color.RED);
                arrow1.setStrokeWidth(selectedWidth);
                arrow2.setStrokeWidth(selectedWidth);
            }
            selectedLine = line;

            // Show dependency info in status bar
            String simpleSource = sourceName.contains(".") ?
                    sourceName.substring(sourceName.lastIndexOf('.') + 1) : sourceName;
            String simpleTarget = targetName.contains(".") ?
                    targetName.substring(targetName.lastIndexOf('.') + 1) : targetName;
            statusCallback.accept("Dependency: " + simpleSource + " → " + simpleTarget);
        }
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
        return DEPENDENCY_WIDTH;
    }

    /**
     * Returns the arrow size (scales automatically with content).
     */
    private double getScaledArrowSize() {
        return 4.0;
    }
}
