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
package de.weigend.s202.ui.views.hexagonal;

import de.weigend.s202.ui.core.graph.GraphSelection;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.function.Consumer;

/**
 * Collapsible group box in the hexagonal projection: one (package x theme)
 * group or an API/SPI socket inside a sector.
 */
final class HexGroupBox extends HBox implements GraphSelection.Selectable {
    private final String fullName;
    private final Label toggle = new Label();
    private final boolean socket;
    private Consumer<String> selectionChangeSink;
    private Runnable toggleAction = () -> {};

    HexGroupBox(String label,
                String fullName,
                int classCount,
                boolean expanded,
                boolean socket,
                Consumer<String> selectionChangeSink) {
        super(5);
        this.fullName = fullName;
        this.socket = socket;
        this.selectionChangeSink = selectionChangeSink;
        setAlignment(Pos.CENTER_LEFT);
        setCursor(Cursor.HAND);
        setMaxWidth(150);
        getStyleClass().add(socket ? "hexagonal-socket-box" : "hexagonal-package-box");
        getProperties().put("s202.aggregateEndpoint", Boolean.TRUE);

        toggle.setMinWidth(12);
        toggle.setAlignment(Pos.CENTER);
        toggle.setStyle("-fx-font-weight: bold; -fx-text-fill: #172033;");
        toggle.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                toggleAction.run();
                event.consume();
            }
        });

        Label nameLabel = new Label(label);
        nameLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #172033;");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Label count = new Label("(" + classCount + ")");
        count.setStyle("-fx-font-size: 9px; -fx-text-fill: #475569;");

        if (socket) {
            // API/SPI contracts ARE the ports of the hexagon — say so.
            FontIcon portIcon = new FontIcon(MaterialDesignP.POWER_PLUG);
            portIcon.setIconColor(Color.web("#7c2d12"));
            portIcon.setIconSize(11);
            getChildren().addAll(toggle, portIcon, nameLabel, count);
        } else {
            getChildren().addAll(toggle, nameLabel, count);
        }
        setExpandedVisual(expanded);
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

    void setExpandedVisual(boolean expanded) {
        toggle.setText(expanded ? "−" : "+");
        getProperties().put("s202.collapsed", !expanded);
    }

    @Override
    public String getFullName() {
        return fullName;
    }

    @Override
    public void applySelectedStyle() {
        setStyle("-fx-background-color: #fff3a0; -fx-border-color: #ff6600;"
                + " -fx-border-width: 2; -fx-padding: 3 6;"
                + " -fx-background-radius: 4; -fx-border-radius: 4;");
    }

    @Override
    public void applyUnselectedStyle() {
        if (socket) {
            setStyle("-fx-background-color: #ffd28a; -fx-border-color: #b45309;"
                    + " -fx-border-width: 1.6; -fx-padding: 3 6;"
                    + " -fx-background-radius: 4; -fx-border-radius: 4;");
        } else {
            setStyle("-fx-background-color: rgba(255,255,255,0.94); -fx-border-color: #3b5371;"
                    + " -fx-border-width: 1.2; -fx-padding: 3 6;"
                    + " -fx-background-radius: 4; -fx-border-radius: 4;");
        }
    }

    @Override
    public Consumer<String> selectionChangeSink() {
        return selectionChangeSink;
    }
}
