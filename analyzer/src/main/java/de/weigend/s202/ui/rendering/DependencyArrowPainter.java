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

import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
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
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Draws and selects the actual dependency arrow shapes on the dependency pane.
 * {@link DependencyRenderer} decides WHICH arrows to draw (traversal,
 * aggregation, rollup); this painter owns the geometry, colors, hover/click
 * selection and the optional aggregate count badge.
 */
final class DependencyArrowPainter {

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
    private final Consumer<String> statusCallback;

    private final List<Shape> dependencyLines = new ArrayList<>();
    private Shape selectedLine = null;

    DependencyArrowPainter(Pane dependencyPane, Consumer<String> statusCallback) {
        this.dependencyPane = Objects.requireNonNull(dependencyPane, "dependencyPane cannot be null");
        this.statusCallback = Objects.requireNonNull(statusCallback, "statusCallback cannot be null");
    }

    /**
     * Removes all drawn arrows and resets the selection.
     */
    void clear() {
        dependencyPane.getChildren().clear();
        dependencyLines.clear();
        selectedLine = null;
    }

    /**
     * Draws a selectable dependency line without a count badge.
     */
    void drawArrow(Node source, Node target, String sourceName, String targetName, boolean isIncoming) {
        drawArrow(source, target, sourceName, targetName, isIncoming, 0, false);
    }

    /**
     * Draws a selectable straight dependency arrow between source and target.
     * When {@code count > 0} a small badge next to the line midpoint shows the
     * number of underlying class-level dependencies; it turns to the violation
     * colour only when the aggregate contains a semantic wrong-direction edge
     * — never based on pixel direction.
     */
    void drawArrow(Node source, Node target, String sourceName, String targetName,
                   boolean isIncoming, int count, boolean containsWrongDirection) {
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
                Color badgeBg = containsWrongDirection ? BADGE_BG_VIOLATION : BADGE_BG;
                dependencyPane.getChildren().add(buildBadge(Integer.toString(count), badgeX, badgeY, badgeBg));
            }

            dependencyLines.add(line);

        } catch (Exception e) {
            // Ignore drawing errors for elements not in scene
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
     * Returns [minX, minY, maxX, maxY] of {@code node} in the dependency pane's
     * coordinate space using JavaFX's built-in scene-graph transform API.
     *
     * <p>{@code localToScene} + {@code sceneToLocal} is always consistent with
     * the committed scene graph state and avoids the stale-bounds issue that
     * arises when walking {@code getBoundsInParent()} manually across nodes
     * whose layout may not yet have fully propagated.
     */
    private double[] getBoundsInPane(Node node) {
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
