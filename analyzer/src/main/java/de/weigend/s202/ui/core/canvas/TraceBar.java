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
package de.weigend.s202.ui.core.canvas;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The strip below a picture while a trace is shown: whose users or
 * dependencies are marked, how many there are, how many steps are shown, and
 * a way to end it.
 */
final class TraceBar extends HBox {

    private static final String TEXT_STYLE = "-fx-font-size: 11px; -fx-text-fill: #e0e0e0;";

    private final Label swatch = new Label();
    private final Label text = new Label();
    private final ComboBox<Integer> steps = new ComboBox<>();
    private boolean updating;

    TraceBar(IntConsumer onSteps, Runnable onClear) {
        swatch.setMinSize(14, 12);
        swatch.setStyle("-fx-background-color: #80deea; -fx-border-color: #00838f; -fx-border-width: 2;");
        text.setStyle(TEXT_STYLE);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.SOMETIMES);

        Label stepsLabel = new Label("Steps:");
        stepsLabel.setStyle(TEXT_STYLE);
        steps.setTooltip(new Tooltip(
                "1: only what is directly connected.\n"
                        + "More steps follow the dependencies further, through the classes reached before."));
        steps.setButtonCell(new StepsCell());
        steps.setCellFactory(list -> new StepsCell());
        steps.valueProperty().addListener((obs, was, isNow) -> {
            if (!updating && isNow != null) {
                // The last choice is all of them, also when the model grows.
                onSteps.accept(isNow >= steps.getItems().size() ? Trace.ALL : isNow);
            }
        });

        Button clear = new Button("Clear");
        clear.setStyle("-fx-font-size: 11px; -fx-padding: 1 8;");
        clear.setTooltip(new Tooltip("Remove the marks"));
        clear.setOnAction(event -> onClear.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(swatch, text, spacer, stepsLabel, steps, clear);
        getStyleClass().add("trace-bar");
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(3, 8, 3, 8));
        setStyle("-fx-background-color: #3c3f41;");
    }

    /** Shows a trace; {@code null} for none. */
    void show(TraceController.State state) {
        if (state == null) {
            return;
        }
        Trace trace = state.trace();
        int shownSteps = Math.min(state.maxSteps(), Math.max(1, trace.depth()));
        text.setText(de.weigend.s202.domain.ShownNames.shown(describe(trace, state.maxSteps())));
        text.setTooltip(new Tooltip(text.getText()));
        updating = true;
        try {
            List<Integer> choices = new ArrayList<>();
            for (int i = 1; i <= Math.max(1, trace.depth()); i++) {
                choices.add(i);
            }
            steps.getItems().setAll(choices);
            steps.setValue(shownSteps);
            steps.setDisable(trace.depth() <= 1);
        } finally {
            updating = false;
        }
    }

    static String describe(Trace trace, int maxSteps) {
        String name = simple(trace.start());
        int classes = trace.classes(maxSteps).size();
        boolean users = trace.direction() == Trace.Direction.USERS;
        if (classes == 0) {
            return users ? "Nothing uses " + name : name + " uses nothing in the analysis";
        }
        int packages = trace.packages(maxSteps);
        int outside = trace.outsideView(maxSteps);
        String what = (users ? "Users of " : "Dependencies of ") + name
                + (maxSteps >= trace.depth() ? ", transitive" : maxSteps == 1 ? ", direct" : ", up to " + maxSteps + " steps");
        return what + ": " + count(classes, "class", "classes") + " in " + count(packages, "package", "packages")
                + (outside == 0 ? "" : ", " + outside + " of them outside this view");
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static String simple(String fullName) {
        return fullName.substring(fullName.lastIndexOf('.') + 1);
    }

    /** The last choice is "all", the others say what they mean. */
    private final class StepsCell extends javafx.scene.control.ListCell<Integer> {
        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
            } else if (item == 1) {
                setText(steps.getItems().size() <= 1 ? "1 (all)" : "1 (direct)");
            } else {
                setText(item == steps.getItems().size() ? item + " (all)" : String.valueOf(item));
            }
        }
    }
}
