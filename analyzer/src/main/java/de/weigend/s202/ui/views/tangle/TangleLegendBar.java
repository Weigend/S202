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
package de.weigend.s202.ui.views.tangle;

import de.weigend.s202.ui.core.arrows.ArrowStyle;
import de.weigend.s202.ui.core.arrows.TangleEdgeScope;
import de.weigend.s202.ui.core.arrows.TangleEdgeScope.Highlight;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

/**
 * The strip above a tangle view. It explains how the arrows are drawn —
 * red and heavy for a back-edge, solid on a class cycle, dashed on a package
 * cycle only — and doubles as the filter: each entry is a toggle that pushes
 * every dependency without that property into the background. The numbers
 * count the tangle's dependencies and fall as the user cuts; once the cuts
 * have broken every cycle between the members, the strip says so. A last
 * entry says what the number on an arrow means.
 */
final class TangleLegendBar extends HBox {

    private static final String BUTTON_STYLE = "-fx-font-size: 11px; -fx-padding: 2 8 2 6;";
    private static final String TEXT_STYLE = "-fx-font-size: 11px; -fx-text-fill: #e0e0e0;";
    private static final String RESOLVED_STYLE = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #7fd68a;";

    private final ArchitectureCanvas canvas;
    private final ToggleButton backEdges;
    private final ToggleButton classCycle;
    private final ToggleButton packageCycle;
    private final Label total = new Label();
    private final Label resolved = new Label("\u2713 No cycle left between the members");
    private final CheckBox context = new CheckBox("Context");

    private TangleEdgeScope scope;
    private Runnable onContextChanged = () -> { };

    TangleLegendBar(ArchitectureCanvas canvas) {
        this.canvas = canvas;
        backEdges = toggle(Highlight.BACK_EDGE,
                sample(ArrowStyle.BACK_EDGE_COLOR, ArrowStyle.BACK_EDGE_WIDTH + 0.7, false),
                "Red and heavy: a back-edge. The layout had to cut this dependency to order the members,\n"
                        + "so it runs upward. These are the candidates to remove.");
        classCycle = toggle(Highlight.CLASS_CYCLE,
                sample(ArrowStyle.FORWARD_COLOR, 1.2, false),
                "Solid: the dependency lies on a class cycle. Only a code change resolves it.\n"
                        + "The cycle may run through classes outside this tangle.");
        packageCycle = toggle(Highlight.PACKAGE_CYCLE,
                sample(ArrowStyle.FORWARD_COLOR, 1.2, true),
                "Dashed: the dependency lies on a package cycle only. Moving a class is enough.");

        total.setStyle(TEXT_STYLE);
        resolved.setStyle(RESOLVED_STYLE);
        resolved.setTooltip(new Tooltip(
                "The cuts break every cycle between the members of this tangle.\n"
                        + "Dependencies still counted as \"Class cycle\" lie on a class cycle\n"
                        + "that runs through classes outside the tangle."));
        resolved.managedProperty().bind(resolved.visibleProperty());
        resolved.setVisible(false);
        Label weights = new Label("\u00b7  Number on an arrow: method calls");
        weights.setStyle(TEXT_STYLE);
        weights.setTooltip(new Tooltip(
                "The number on an arrow counts the method calls that carry the dependency: what cutting it costs.\n"
                        + "A dependency without calls (inheritance, a field or parameter type) counts 1.\n"
                        + "\"12 / 3\u2191\" on an arrow that bundles several dependencies: 3 of its 12 calls are back-edges."));
        context.setStyle(TEXT_STYLE);
        context.setTooltip(new Tooltip(
                "Also show the classes and dependencies of the members that play no part in the tangle."));
        context.selectedProperty().addListener((obs, was, isNow) -> {
            if (scope != null) {
                scope.setShowContext(isNow);
                onContextChanged.run();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(backEdges, classCycle, packageCycle, total, resolved, weights, spacer, context);
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(3, 8, 3, 8));
        setStyle("-fx-background-color: #3c3f41;");

        // Every arrow redraw may follow a cut or restore — keep the numbers current.
        canvas.redrawTickProperty().addListener((obs, was, isNow) -> refreshCounts());
        refreshCounts();
    }

    /**
     * Binds the strip to the scope of the tangle the view shows from now on.
     * The toggles keep their state: reopening a tab does not reset the filter.
     *
     * @param onContextChanged rebuilds the view after the context switch changed
     */
    void bind(TangleEdgeScope scope, Runnable onContextChanged) {
        this.scope = scope;
        this.onContextChanged = onContextChanged == null ? () -> { } : onContextChanged;
        scope.setShowContext(context.isSelected());
        scope.setHighlight(Highlight.BACK_EDGE, backEdges.isSelected());
        scope.setHighlight(Highlight.CLASS_CYCLE, classCycle.isSelected());
        scope.setHighlight(Highlight.PACKAGE_CYCLE, packageCycle.isSelected());
    }

    /**
     * Switches the context on before the strip is bound — for a tab that is
     * built to show the context from the start. Does not rebuild anything.
     */
    void preselectContext() {
        TangleEdgeScope bound = scope;
        scope = null;
        context.setSelected(true);
        scope = bound;
    }

    void refreshCounts() {
        TangleEdgeScope.Stats stats = canvas.getTangleEdgeStats();
        backEdges.setText("Back-edges  " + stats.backEdges());
        classCycle.setText("Class cycle  " + stats.classCycle());
        packageCycle.setText("Package cycle only  " + stats.packageCycle());
        total.setText("of " + stats.edges() + (stats.edges() == 1 ? " dependency" : " dependencies"));
        resolved.setVisible(scope != null && stats.resolved());
    }

    private ToggleButton toggle(Highlight highlight, Node sample, String tooltip) {
        ToggleButton button = new ToggleButton();
        button.setGraphic(sample);
        button.setStyle(BUTTON_STYLE);
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip + "\nClick to highlight these dependencies."));
        button.selectedProperty().addListener((obs, was, isNow) -> {
            if (scope != null) {
                scope.setHighlight(highlight, isNow);
                canvas.redrawDependencyArrows();
            }
        });
        return button;
    }

    /** A short line drawn the way the arrows are, on the canvas colour so it reads in any theme. */
    private static Node sample(Color color, double width, boolean dashed) {
        Line line = new Line(0, 0, 20, 0);
        line.setStroke(color);
        line.setStrokeWidth(width);
        if (dashed) {
            line.getStrokeDashArray().setAll(4.0, 2.5);
        }
        StackPane swatch = new StackPane(line);
        swatch.setPadding(new Insets(4, 3, 4, 3));
        swatch.setStyle("-fx-background-color: #f5f5f0; -fx-background-radius: 2;");
        return swatch;
    }
}
