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
package de.weigend.s202.ui.tree;

import de.weigend.s202.ui.core.graph.GraphSelection;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.function.Consumer;

/**
 * Header of one BUSINESS THEME segment in the hexagonal projection. Toggling
 * the header expands/collapses all group boxes of the sector.
 */
final class HexagonalSegmentHeader extends HBox implements GraphSelection.Selectable {
    private final String fullName;
    private final Label toggle = new Label("−");
    private final Label nameLabel;
    private Consumer<String> selectionChangeSink;
    private Runnable toggleAction = () -> {};
    private boolean expanded = true;

    HexagonalSegmentHeader(String label, String fullName, Consumer<String> selectionChangeSink) {
        super(6);
        this.fullName = fullName;
        this.selectionChangeSink = selectionChangeSink;
        setAlignment(Pos.CENTER_LEFT);
        setCursor(Cursor.HAND);
        setMaxWidth(180);
        setMinWidth(150);
        getStyleClass().add("hexagonal-segment-header");
        getProperties().put("s202.aggregateEndpoint", Boolean.TRUE);
        getProperties().put("s202.collapsed", Boolean.FALSE);

        toggle.setMinWidth(12);
        toggle.setAlignment(Pos.CENTER);
        toggle.setStyle("-fx-font-weight: bold; -fx-text-fill: #172033;");
        toggle.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                toggleAction.run();
                event.consume();
            }
        });

        nameLabel = new Label(label);
        nameLabel.setWrapText(true);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #172033;");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        getChildren().addAll(toggle, nameLabel);
        applyUnselectedStyle();
        setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            GraphSelection.select(this);
            event.consume();
        });
    }

    void setToggleAction(Runnable action) {
        toggleAction = action == null ? () -> {} : action;
    }

    boolean toggleExpanded() {
        expanded = !expanded;
        toggle.setText(expanded ? "−" : "+");
        getProperties().put("s202.collapsed", !expanded);
        return expanded;
    }

    boolean isExpanded() {
        return expanded;
    }

    @Override
    public String getFullName() {
        return fullName;
    }

    @Override
    public void applySelectedStyle() {
        setStyle("-fx-background-color: #fff3a0; -fx-border-color: #ff6600;"
                + " -fx-border-width: 2; -fx-padding: 4 7;"
                + " -fx-background-radius: 4; -fx-border-radius: 4;");
    }

    @Override
    public void applyUnselectedStyle() {
        setStyle("-fx-background-color: rgba(255,255,255,0.9); -fx-border-color: #607086;"
                + " -fx-border-width: 1; -fx-padding: 4 7;"
                + " -fx-background-radius: 4; -fx-border-radius: 4;");
    }

    @Override
    public Consumer<String> selectionChangeSink() {
        return selectionChangeSink;
    }
}
