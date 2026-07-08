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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

/**
 * Static, non-interactive backdrop of the hexagonal projection: the legend,
 * the three concentric rings and the dashed segment boundary spokes. Uses the
 * shared {@link HexLayoutGeometry} so the backdrop and the placed boxes never
 * disagree about radii or angles.
 */
final class HexBackdropRenderer {

    private HexBackdropRenderer() {
    }

    static HBox buildLegend() {
        HBox legend = new HBox(12);
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.setPadding(new Insets(0, 0, 8, 0));
        legend.getChildren().addAll(
                legendItem("#f8f1cf", "Core"),
                legendItem("#d9eee5", "Application"),
                legendItem("#e4e9f0", "Adapters"),
                legendItem("#ffd28a", "API / SPI sockets"),
                legendItem("#ffffff", "Group (click + to expand)"));
        return legend;
    }

    private static HBox legendItem(String color, String text) {
        HBox item = new HBox(5);
        item.setAlignment(Pos.CENTER_LEFT);
        Region swatch = new Region();
        swatch.setMinSize(14, 9);
        swatch.setPrefSize(14, 9);
        swatch.setStyle("-fx-background-color: " + color + "; -fx-border-color: #6b7280; -fx-border-width: 1;");
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #253040;");
        item.getChildren().addAll(swatch, label);
        return item;
    }

    static void drawRings(Pane pane) {
        Circle adapter = circle(HexLayoutGeometry.ADAPTER_RADIUS, "#e4e9f0", "#8a98a8", 1.4);
        Circle application = circle(HexLayoutGeometry.APPLICATION_RADIUS, "#d9eee5", "#4a9b82", 1.6);
        Circle core = circle(HexLayoutGeometry.CORE_RADIUS, "#f8f1cf", "#b7932e", 1.8);
        pane.getChildren().addAll(adapter, application, core);
    }

    private static Circle circle(double radius, String fill, String stroke, double width) {
        Circle circle = new Circle(HexLayoutGeometry.CENTER_X, HexLayoutGeometry.CENTER_Y, radius);
        circle.setFill(Color.web(fill));
        circle.setStroke(Color.web(stroke));
        circle.setStrokeWidth(width);
        circle.setMouseTransparent(true);
        return circle;
    }

    static void drawSegmentBoundaries(Pane pane, int segmentCount) {
        for (int i = 0; i < segmentCount; i++) {
            double angle = HexLayoutGeometry.angleForSegmentBoundary(i, segmentCount);
            double radians = Math.toRadians(angle);
            Line line = new Line(
                    HexLayoutGeometry.CENTER_X,
                    HexLayoutGeometry.CENTER_Y,
                    HexLayoutGeometry.CENTER_X + Math.cos(radians) * HexLayoutGeometry.ADAPTER_RADIUS,
                    HexLayoutGeometry.CENTER_Y + Math.sin(radians) * HexLayoutGeometry.ADAPTER_RADIUS);
            line.setStroke(Color.web("#b0bac8"));
            line.setStrokeWidth(1);
            line.getStrokeDashArray().setAll(5.0, 7.0);
            line.setMouseTransparent(true);
            pane.getChildren().add(line);
        }
    }
}
