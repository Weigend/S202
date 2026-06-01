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
package de.weigend.s202.ui.rendering;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.zoom.ZoomController;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
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

    private static final Color BADGE_BG           = Color.rgb(0, 140, 0);    // normal direction
    private static final Color BADGE_BG_VIOLATION = Color.rgb(210, 105, 0); // wrong direction
    private static final Color BADGE_FG  = Color.WHITE;
    private static final Font  BADGE_FONT = Font.font(null, FontWeight.BOLD, 5.0);
    private static final double BADGE_PADDING    = 1.5;
    private static final double BADGE_MIN_RADIUS = 4.0;

    private final Pane dependencyPane;
    private final Map<String, Node> elementRegistry;
    private final ZoomController zoomController;
    private final Consumer<String> statusCallback;

    private final List<Shape> dependencyLines = new ArrayList<>();
    private Shape selectedLine = null;
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
     * Recursively draws arrows for visible nodes according to the four-state matrix:
     * <pre>
     *  source open  / target open       → class → class (individual)
     *  source open  / target collapsed  → class → package-top (individual, deduplicated)
     *  source closed / target open      → package-bottom → class (individual)
     *  source closed / target collapsed → package → package (aggregated + badge)
     * </pre>
     */
    private void drawDependencyArrowsRecursive(ArchitectureNode node) {
        String selectedClass = LevelClassBox.getSelectedClassName();

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
                    boolean isSourceSelected = selectedClass != null &&
                            (child.getFullName().equals(selectedClass) ||
                             child.getFullName().startsWith(selectedClass + "."));
                    boolean isTargetSelected = selectedClass != null &&
                            (depName.equals(selectedClass) ||
                             depName.startsWith(selectedClass + "."));
                    if (selectedClass != null && !isSourceSelected && !isTargetSelected) {
                        continue;
                    }
                    boolean isIncoming = isTargetSelected && !isSourceSelected;
                    String targetName = (targetElement instanceof LevelPackageBox lpb)
                            ? lpb.getFullName() : depName;
                    createDependencyLine(sourceElement, targetElement,
                            child.getFullName(), targetName, isIncoming);
                }

            } else if (child.getType() == ArchitectureNode.NodeType.PACKAGE) {
                Node uiElement = elementRegistry.get(child.getFullName());
                if (uiElement instanceof LevelPackageBox packageBox
                        && isNodeActuallyVisible(packageBox)
                        && isPackageCollapsed(child)) {
                    // Closed source: draw aggregated or class-level arrows from the package
                    drawCollapsedPackageArrows(child, packageBox, selectedClass);
                } else {
                    // Open source: recurse so class-level arrows are drawn
                    drawDependencyArrowsRecursive(child);
                }
            }
        }
    }

    /**
     * Finds the visible UI node to use as the arrow target for {@code targetFqn}.
     * <ul>
     *   <li>If the element itself is visible → return it directly.</li>
     *   <li>If it is hidden (inside a collapsed package) → walk up the JavaFX
     *       parent chain and return the nearest visible {@link LevelPackageBox}.</li>
     * </ul>
     */
    private Node findVisibleTarget(String targetFqn) {
        Node element = elementRegistry.get(targetFqn);
        if (element == null) return null;
        if (isNodeActuallyVisible(element)) return element;

        // Target is inside a collapsed package — roll up to the visible package box.
        Parent parent = element.getParent();
        while (parent != null) {
            if (parent instanceof LevelPackageBox lpb && isNodeActuallyVisible(lpb)) {
                return lpb;
            }
            parent = parent.getParent();
        }
        return null;
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
    private void drawCollapsedPackageArrows(ArchitectureNode packageNode, Node sourceElement, String selectedClass) {
        String srcFqn    = packageNode.getFullName();
        String srcPrefix = srcFqn != null ? srcFqn + "." : "";

        // target node → total number of class-level deps pointing there
        Map<Node, Integer> targetCounts = new HashMap<>();
        collectSubtreeClassDeps(packageNode, srcFqn, srcPrefix, targetCounts);

        for (Map.Entry<Node, Integer> entry : targetCounts.entrySet()) {
            Node   targetElement = entry.getKey();
            int    count         = entry.getValue();
            String targetFqn     = fqnOf(targetElement);

            boolean isSourceSelected = selectedClass != null &&
                    (srcFqn.equals(selectedClass) || srcFqn.startsWith(selectedClass + ".") ||
                     selectedClass.startsWith(srcPrefix));
            boolean isTargetSelected = selectedClass != null &&
                    (targetFqn.equals(selectedClass) || targetFqn.startsWith(selectedClass + ".") ||
                     selectedClass.startsWith(targetFqn + "."));

            if (selectedClass != null && !isSourceSelected && !isTargetSelected) continue;

            boolean isIncoming = isTargetSelected && !isSourceSelected;
            // Always show count badge; for class-level targets only when > 1.
            int badge = (targetElement instanceof LevelPackageBox || count > 1) ? count : 0;
            createCurvedDependencyLine(sourceElement, targetElement, srcFqn, targetFqn, isIncoming, badge);
        }
    }

    /**
     * Walks the subtree of {@code node} collecting every class-level dep that
     * points outside {@code srcFqn}/{@code srcPrefix}. Each dep is resolved to
     * its visible target node (rolling up collapsed packages) and aggregated.
     */
    private void collectSubtreeClassDeps(ArchitectureNode node,
                                          String srcFqn, String srcPrefix,
                                          Map<Node, Integer> targetCounts) {
        for (ArchitectureNode child : node.getChildren()) {
            if (child.getType() == ArchitectureNode.NodeType.CLASS) {
                for (String dep : child.getDependencies()) {
                    if (dep.equals(srcFqn) || dep.startsWith(srcPrefix)) continue; // internal
                    Node target = findVisibleTarget(dep);
                    if (target != null) targetCounts.merge(target, 1, Integer::sum);
                }
            } else {
                collectSubtreeClassDeps(child, srcFqn, srcPrefix, targetCounts);
            }
        }
    }

    /** Returns the FQN of a {@link LevelPackageBox} or {@link LevelClassBox}. */
    private static String fqnOf(Node node) {
        if (node instanceof LevelPackageBox lpb) return lpb.getFullName();
        if (node instanceof LevelClassBox  lcb) return lcb.getFullName();
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

    /**
     * Creates a selectable dependency line between source and target.
     */
    private void createDependencyLine(Node source, Node target, String sourceName, String targetName,
                                       boolean isIncoming) {
        try {
            double[] srcB = getBoundsInPane(source);
            double[] tgtB = getBoundsInPane(target);

            if (srcB == null || tgtB == null) {
                return;
            }

            double srcCenterY = (srcB[1] + srcB[3]) / 2.0;
            double tgtCenterY = (tgtB[1] + tgtB[3]) / 2.0;
            // Normal direction: source above target → bottom edge to top edge.
            // Violation: source below target → top edge to bottom edge.
            boolean normalDir = srcCenterY <= tgtCenterY;
            double startX = (srcB[0] + srcB[2]) / 2.0;
            double startY = normalDir ? srcB[3] : srcB[1];
            double endX   = (tgtB[0] + tgtB[2]) / 2.0;
            double endY   = normalDir ? tgtB[1] : tgtB[3];

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
                selectShape(line, sourceName, targetName);
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
     * Draws one aggregated straight arrow from a collapsed package to a target package,
     * identical in style to class-level dependency arrows. A small badge next to the
     * line midpoint shows the number of underlying class-level dependencies.
     */
    private void createCurvedDependencyLine(Node source, Node target, String sourceName, String targetName,
                                             boolean isIncoming, int count) {
        try {
            double[] srcB = getBoundsInPane(source);
            double[] tgtB = getBoundsInPane(target);
            if (srcB == null || tgtB == null) return;

            double srcCenterY = (srcB[1] + srcB[3]) / 2.0;
            double tgtCenterY = (tgtB[1] + tgtB[3]) / 2.0;
            boolean normalDir = srcCenterY <= tgtCenterY;
            double startX = (srcB[0] + srcB[2]) / 2.0;
            double startY = normalDir ? srcB[3] : srcB[1];
            double endX   = (tgtB[0] + tgtB[2]) / 2.0;
            double endY   = normalDir ? tgtB[1] : tgtB[3];

            Color lineColor  = isIncoming ? INCOMING_DEPENDENCY_COLOR : OUTGOING_DEPENDENCY_COLOR;
            double lineWidth = getScaledLineWidth();

            Line line = new Line(startX, startY, endX, endY);
            line.setStroke(lineColor);
            line.setStrokeWidth(lineWidth);

            // Arrowhead
            double arrowSize = getScaledArrowSize();
            double angle     = Math.atan2(endY - startY, endX - startX);
            double x1 = endX - arrowSize * Math.cos(angle - Math.PI / 6);
            double y1 = endY - arrowSize * Math.sin(angle - Math.PI / 6);
            double x2 = endX - arrowSize * Math.cos(angle + Math.PI / 6);
            double y2 = endY - arrowSize * Math.sin(angle + Math.PI / 6);

            Line arrow1 = new Line(endX, endY, x1, y1);
            Line arrow2 = new Line(endX, endY, x2, y2);
            for (Line a : new Line[]{arrow1, arrow2}) {
                a.setStroke(lineColor);
                a.setStrokeWidth(lineWidth);
                a.setMouseTransparent(true);
            }

            final Color originalColor = lineColor;
            line.setUserData(new Object[]{arrow1, arrow2, originalColor});

            line.setOnMouseEntered(e -> {
                if (line != selectedLine) line.setStroke(Color.GRAY);
                line.setCursor(javafx.scene.Cursor.HAND);
            });
            line.setOnMouseExited(e -> {
                if (line != selectedLine) line.setStroke(originalColor);
                line.setCursor(javafx.scene.Cursor.DEFAULT);
            });
            line.setOnMouseClicked(e -> {
                selectShape(line, sourceName, targetName);
                e.consume();
            });

            dependencyPane.getChildren().addAll(line, arrow1, arrow2);

            if (count > 0) {
                // Badge beside the line midpoint, offset perpendicular to the line
                double midX = (startX + endX) / 2.0;
                double midY = (startY + endY) / 2.0;
                double dx   = endX - startX;
                double dy   = endY - startY;
                double len  = Math.max(1.0, Math.sqrt(dx * dx + dy * dy));
                double offset = BADGE_MIN_RADIUS + 2.0;
                double badgeX = midX + (-dy / len) * offset;
                double badgeY = midY + ( dx / len) * offset;
                Color badgeBg = normalDir ? BADGE_BG : BADGE_BG_VIOLATION;
                dependencyPane.getChildren().add(buildBadge(Integer.toString(count), badgeX, badgeY, badgeBg));
            }

            dependencyLines.add(line);

        } catch (Exception e) {
            // Ignore drawing errors for elements not yet in scene
        }
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
     * Selects a dependency shape (line or curve) and highlights it.
     * Arrowhead lines are stored as userData: {arrow1, arrow2, originalColor}.
     */
    private void selectShape(Shape shape, String sourceName, String targetName) {
        double scaledWidth = getScaledLineWidth();
        double selectedWidth = scaledWidth * 2;

        // Deselect previous shape
        if (selectedLine != null && selectedLine != shape) {
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
        if (selectedLine == shape) {
            // Deselect
            Object[] userData = (Object[]) shape.getUserData();
            Color originalColor = (userData != null && userData.length > 2) ? (Color) userData[2] : OUTGOING_DEPENDENCY_COLOR;
            shape.setStroke(originalColor);
            shape.setStrokeWidth(scaledWidth);
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
            shape.setStroke(Color.RED);
            shape.setStrokeWidth(selectedWidth);
            Object[] userData = (Object[]) shape.getUserData();
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(Color.RED);
                arrow2.setStroke(Color.RED);
                arrow1.setStrokeWidth(selectedWidth);
                arrow2.setStrokeWidth(selectedWidth);
            }
            selectedLine = shape;

            // Show dependency info in status bar
            String simpleSource = sourceName.contains(".") ?
                    sourceName.substring(sourceName.lastIndexOf('.') + 1) : sourceName;
            String simpleTarget = targetName.contains(".") ?
                    targetName.substring(targetName.lastIndexOf('.') + 1) : targetName;
            statusCallback.accept("Dependency: " + simpleSource + " → " + simpleTarget);
        }
    }

    /**
     * Returns [minX, minY, maxX, maxY] of {@code node} in the zoomable-content
     * coordinate space using JavaFX's built-in scene-graph transform API.
     *
     * <p>{@code localToScene} + {@code sceneToLocal} is always consistent with
     * the committed scene graph state and avoids the stale-bounds issue that
     * arises when walking {@code getBoundsInParent()} manually across nodes
     * whose layout may not yet have fully propagated.
     */
    private double[] getBoundsInPane(Node node) {
        try {
            if (zoomableContent == null) return null;
            Bounds inScene = node.localToScene(node.getBoundsInLocal());
            Bounds inPane  = zoomableContent.sceneToLocal(inScene);
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
