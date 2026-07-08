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
package de.weigend.s202.ui.features.quality;

import de.weigend.s202.analysis.quality.QualityMetrics;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;

import java.net.URL;

/**
 * 2D quality plot — fat (X) vs. tangled (Y) — with a green→red gradient
 * background and a single highlighted point for the focused JAR. Lower-left
 * corner is healthy (green), upper-right is unhealthy (red).
 */
public class QualityView implements View {

    public static final String VIEW_ID = "s202-quality";

    /** Inset around the gradient area to leave room for axis labels. */
    private static final double PADDING_LEFT = 28;
    private static final double PADDING_RIGHT = 8;
    private static final double PADDING_TOP = 8;
    private static final double PADDING_BOTTOM = 22;

    private static final double POINT_RADIUS = 6;

    private final BorderPane root = new BorderPane();
    private final StackPane plotContainer = new StackPane();
    private final Canvas gradientCanvas = new Canvas();
    private final Pane overlay = new Pane();
    private final Circle marker = new Circle(POINT_RADIUS);
    private final Label valueLabel = new Label();
    private final Label xAxisLabel = new Label("Fat");
    private final Label yAxisLabel = new Label("Tangled");

    private QualityMetrics metrics;
    private final Label scopeLabel = new Label();

    public QualityView() {
        root.getStyleClass().add("quality-view");

        marker.setFill(Color.WHITE);
        marker.setStroke(Color.web("#1a1a1a"));
        marker.setStrokeWidth(2);
        marker.setVisible(false);

        valueLabel.getStyleClass().add("quality-value");
        valueLabel.setMouseTransparent(true);

        scopeLabel.getStyleClass().add("quality-scope");
        scopeLabel.setMouseTransparent(true);

        xAxisLabel.getStyleClass().add("quality-axis-label");
        yAxisLabel.getStyleClass().add("quality-axis-label");
        yAxisLabel.setRotate(-90);

        overlay.setMouseTransparent(true);
        overlay.getChildren().addAll(marker, valueLabel, scopeLabel);

        plotContainer.setAlignment(Pos.TOP_LEFT);
        plotContainer.getChildren().addAll(gradientCanvas, overlay);

        // Keep canvas sized to its container; redraw on resize.
        plotContainer.widthProperty().addListener((obs, was, isNow) -> redraw());
        plotContainer.heightProperty().addListener((obs, was, isNow) -> redraw());

        // Constrain so the panel does not collapse to nothing inside a split.
        plotContainer.setMinSize(120, 120);

        StackPane bottomBar = new StackPane(xAxisLabel);
        bottomBar.setPadding(new javafx.geometry.Insets(2, 0, 2, 0));

        // Wrap the rotated label in a Group so its layout bounds reflect the
        // post-rotation dimensions; otherwise the StackPane sizes the Label by
        // its un-rotated width and the text gets truncated to "T..." before
        // rotation.
        StackPane leftBar = new StackPane(new Group(yAxisLabel));
        leftBar.setPadding(new javafx.geometry.Insets(0, 4, 0, 4));

        root.setCenter(plotContainer);
        root.setBottom(bottomBar);
        root.setLeft(leftBar);
    }

    public void setMetrics(QualityMetrics metrics) {
        setMetrics(metrics, null);
    }

    /**
     * Update the displayed metrics and the scope label.
     *
     * @param metrics      metrics to plot, or null to hide the marker.
     * @param scopeName    full name of the scoped element (e.g. a package),
     *                     or null for the JAR-wide scope.
     */
    public void setMetrics(QualityMetrics metrics, String scopeName) {
        this.metrics = metrics;
        scopeLabel.setText(scopeName == null || scopeName.isEmpty()
                ? "Scope: All classes"
                : "Scope: " + scopeName);
        redraw();
    }

    private void redraw() {
        double w = plotContainer.getWidth();
        double h = plotContainer.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        gradientCanvas.setWidth(w);
        gradientCanvas.setHeight(h);

        double plotW = Math.max(1, w - PADDING_LEFT - PADDING_RIGHT);
        double plotH = Math.max(1, h - PADDING_TOP - PADDING_BOTTOM);

        drawGradient(gradientCanvas.getGraphicsContext2D(), plotW, plotH);
        drawAxes(gradientCanvas.getGraphicsContext2D(), w, h, plotW, plotH);
        positionMarker(plotW, plotH);
    }

    private void drawGradient(GraphicsContext gc, double plotW, double plotH) {
        gc.clearRect(0, 0, gradientCanvas.getWidth(), gradientCanvas.getHeight());

        int iw = (int) Math.ceil(plotW);
        int ih = (int) Math.ceil(plotH);
        WritableImage img = new WritableImage(iw, ih);
        PixelWriter pw = img.getPixelWriter();

        Color green = Color.web("#2ecc71");
        Color red = Color.web("#e74c3c");

        for (int py = 0; py < ih; py++) {
            // Canvas y grows downward; chart Y (tangled) grows upward.
            double tangled = 1.0 - (double) py / Math.max(1, ih - 1);
            for (int px = 0; px < iw; px++) {
                double fat = (double) px / Math.max(1, iw - 1);
                // Severity: distance from healthy (0,0), normalised to 0..1.
                double t = Math.min(1.0, Math.sqrt((fat * fat + tangled * tangled) / 2.0));
                pw.setColor(px, py, green.interpolate(red, t));
            }
        }
        gc.drawImage(img, PADDING_LEFT, PADDING_TOP);

        // Frame around the plot area.
        gc.setStroke(Color.web("#14202b"));
        gc.setLineWidth(1);
        gc.strokeRect(PADDING_LEFT - 0.5, PADDING_TOP - 0.5, plotW + 1, plotH + 1);
    }

    private void drawAxes(GraphicsContext gc, double w, double h, double plotW, double plotH) {
        gc.setFill(Color.web("#cccccc"));
        gc.setFont(Font.font(10));

        // Tick numerals on the left (Y axis): 0 (bottom), 1 (top).
        gc.fillText("0", 4, PADDING_TOP + plotH);
        gc.fillText("1", 4, PADDING_TOP + 8);

        // Tick numerals on the bottom (X axis): 0 (left), 1 (right).
        gc.fillText("0", PADDING_LEFT, h - 6);
        gc.fillText("1", PADDING_LEFT + plotW - 6, h - 6);
    }

    private void positionMarker(double plotW, double plotH) {
        if (metrics == null) {
            marker.setVisible(false);
            valueLabel.setText("");
            return;
        }
        double fat = clamp01(metrics.getFat());
        double tangled = clamp01(metrics.getTangled());
        double x = PADDING_LEFT + fat * plotW;
        double y = PADDING_TOP + (1.0 - tangled) * plotH;
        marker.setCenterX(x);
        marker.setCenterY(y);
        marker.setVisible(true);

        valueLabel.setText(String.format("fat=%.2f  tangled=%.2f", fat, tangled));
        // Stick value label to the top-left of the plot area, not on top of the dot.
        valueLabel.setLayoutX(PADDING_LEFT + 4);
        valueLabel.setLayoutY(PADDING_TOP + 2);

        // Scope label sits a row below the value label.
        scopeLabel.setLayoutX(PADDING_LEFT + 4);
        scopeLabel.setLayoutY(PADDING_TOP + 18);
        scopeLabel.setMaxWidth(plotW - 8);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    @Override
    public String getViewId() {
        return VIEW_ID;
    }

    @Override
    public String getTitle() {
        return "Quality";
    }

    @Override
    public String getToolTipInfo() {
        return "Fat (avg deps/class) vs. Tangled (share of cyclic deps)";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.BOTTOM;
    }

    @Override
    public Parent getRootNode() {
        return root;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 0.4;
    }

    /** Exposed for tests; production code should call {@link #setMetrics(QualityMetrics)}. */
    Region getPlotContainer() {
        return plotContainer;
    }
}
