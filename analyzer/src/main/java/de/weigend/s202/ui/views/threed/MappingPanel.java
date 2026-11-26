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
package de.weigend.s202.ui.views.threed;

import de.weigend.s202.analysis.metrics.Metric;
import de.weigend.s202.analysis.metrics.MetricMapping;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Opens over the picture and sets what heights and colours say. Every change
 * is drawn at once, so the picture itself shows what a setting does.
 */
final class MappingPanel extends VBox {

    /** A setting for a question of a review. */
    record Preset(String name, MetricMapping mapping) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Preset> PRESETS = List.of(
            new Preset("Architecture levels", MetricMapping.DEFAULT),
            new Preset("Risk: complexity × fan-in", new MetricMapping(Metric.COMPLEXITY, MetricMapping.Scale.SQRT, 30,
                    Metric.FAN_IN, null, null, MetricMapping.Palette.GREEN_RED, false)),
            new Preset("Stability: fan-in × instability", new MetricMapping(Metric.FAN_IN, MetricMapping.Scale.SQRT, 30,
                    Metric.INSTABILITY, 0.0, 1.0, MetricMapping.Palette.BLUE_RED, false)),
            new Preset("Impact of a change", new MetricMapping(Metric.IMPACT, MetricMapping.Scale.SQRT, 30,
                    Metric.CYCLE, null, null, MetricMapping.Palette.GREEN_RED, false)),
            new Preset("Size: lines × max complexity", new MetricMapping(Metric.LINES, MetricMapping.Scale.SQRT, 30,
                    Metric.MAX_COMPLEXITY, 1.0, 20.0, MetricMapping.Palette.GREEN_RED, false)),
            new Preset("Main sequence: abstractness × distance", new MetricMapping(Metric.ABSTRACTNESS,
                    MetricMapping.Scale.LINEAR, 15, Metric.DISTANCE, 0.0, 1.0, MetricMapping.Palette.GREEN_RED, false)));

    private final Consumer<MetricMapping> onChange;
    private MetricMapping mapping = MetricMapping.DEFAULT;
    private Predicate<Metric> available = metric -> true;
    private boolean updating;

    private final VBox body = new VBox(6);
    private final Button header = new Button("▸ Metrics");
    private final ComboBox<Preset> preset = new ComboBox<>();
    private final ComboBox<Metric> height = new ComboBox<>();
    private final ToggleGroup scale = new ToggleGroup();
    private final Slider heightPercent = new Slider(MetricMapping.MIN_HEIGHT_PERCENT, MetricMapping.MAX_HEIGHT_PERCENT, 25);
    private final Label heightPercentLabel = label("");
    private final ComboBox<Metric> color = new ComboBox<>();
    private final ComboBox<MetricMapping.Palette> palette = new ComboBox<>();
    private final CheckBox reversed = new CheckBox("reverse");
    private final TextField min = new TextField();
    private final TextField max = new TextField();
    private final Canvas bar = new Canvas(220, 12);
    private final Label heightRange = label("");
    private final Label classRange = label("");
    private final Label packageRange = label("");
    private View25D.Legend legend;

    MappingPanel(Consumer<MetricMapping> onChange) {
        this.onChange = onChange;
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setPadding(new Insets(4, 8, 6, 8));
        setSpacing(4);
        setStyle("-fx-background-color: rgba(30, 36, 48, 0.92); -fx-background-radius: 4;");

        header.setStyle("-fx-background-color: transparent; -fx-text-fill: #e0e0e0; -fx-font-size: 11px; "
                + "-fx-font-weight: bold; -fx-padding: 2 0 2 0;");
        header.setTooltip(new Tooltip("What the heights and the colors of the sides show"));
        header.setOnAction(event -> setOpen(!body.isVisible()));

        preset.getItems().setAll(PRESETS);
        preset.setPromptText("Preset…");
        preset.setOnAction(event -> {
            if (!updating && preset.getValue() != null) {
                change(preset.getValue().mapping());
            }
        });

        List<Metric> metrics = List.of(Metric.values());
        height.getItems().setAll(metrics);
        height.setCellFactory(list -> metricCell());
        height.setButtonCell(metricCell());
        height.setOnAction(event -> {
            if (!updating && height.getValue() != null) {
                change(mapping.withHeight(height.getValue()));
            }
        });

        HBox scales = new HBox(0);
        for (MetricMapping.Scale value : MetricMapping.Scale.values()) {
            ToggleButton button = new ToggleButton(value.label());
            button.setUserData(value);
            button.setToggleGroup(scale);
            button.setStyle("-fx-font-size: 10px; -fx-padding: 1 6;");
            button.setTooltip(new Tooltip(switch (value) {
                case LINEAR -> "Height grows with the value";
                case SQRT -> "Height grows with the square root: a few large values flatten the rest less";
                case LOG -> "Height grows with the logarithm: for values far apart";
            }));
            scales.getChildren().add(button);
        }
        scale.selectedToggleProperty().addListener((obs, was, isNow) -> {
            if (isNow == null) {
                // One scale is always chosen.
                if (was != null) {
                    was.setSelected(true);
                }
                return;
            }
            if (!updating) {
                change(mapping.withScale((MetricMapping.Scale) isNow.getUserData()));
            }
        });

        heightPercent.setPrefWidth(110);
        heightPercent.setTooltip(new Tooltip("Height of the largest value, in percent of the size of the picture"));
        heightPercent.valueProperty().addListener((obs, was, isNow) -> {
            heightPercentLabel.setText(Math.round(isNow.doubleValue()) + " %");
            if (!updating) {
                change(mapping.withHeightPercent(isNow.doubleValue()));
            }
        });

        List<Metric> colors = new ArrayList<>();
        colors.add(null);
        colors.addAll(metrics);
        color.getItems().setAll(colors);
        color.setCellFactory(list -> metricCell());
        color.setButtonCell(metricCell());
        color.setOnAction(event -> {
            if (!updating) {
                change(mapping.withColor(color.getValue()));
            }
        });

        palette.getItems().setAll(MetricMapping.Palette.values());
        palette.setOnAction(event -> {
            if (!updating && palette.getValue() != null) {
                change(mapping.withPalette(palette.getValue()));
            }
        });
        reversed.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 11px;");
        reversed.setTooltip(new Tooltip("The palette the other way round: for values where small is bad"));
        reversed.setOnAction(event -> {
            if (!updating) {
                change(mapping.withReversed(reversed.isSelected()));
            }
        });

        for (TextField field : new TextField[]{min, max}) {
            field.setPromptText("auto");
            field.setPrefColumnCount(5);
            field.setStyle("-fx-font-size: 11px;");
            field.setOnAction(event -> rangeEdited());
            field.focusedProperty().addListener((obs, was, isNow) -> {
                if (!isNow) {
                    rangeEdited();
                }
            });
        }
        min.setTooltip(new Tooltip("Value at the start of the palette; empty for the smallest shown"));
        max.setTooltip(new Tooltip("Value at the end of the palette; empty for the largest shown"));

        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(5);
        grid.addRow(0, label("Preset"), preset);
        grid.addRow(1, label("Height"), height, scales, new HBox(4, heightPercent, heightPercentLabel));
        grid.addRow(2, label("Color"), color, palette, reversed);
        HBox range = new HBox(4, label("from"), min, label("to"), max);
        range.setAlignment(Pos.CENTER_LEFT);
        grid.add(range, 1, 3, 3, 1);
        VBox legendBox = new VBox(2, heightRange, bar, classRange, packageRange);
        grid.add(legendBox, 1, 4, 3, 1);
        body.getChildren().add(grid);

        getChildren().addAll(header, body);
        setOpen(false);
        show(mapping);
    }

    /** Shows the mapping without telling anyone. */
    void setMapping(MetricMapping newMapping) {
        mapping = newMapping == null ? MetricMapping.DEFAULT : newMapping;
        show(mapping);
    }

    /** Which metrics have values; the others are offered greyed. */
    void setAvailable(Predicate<Metric> isAvailable) {
        available = isAvailable == null ? metric -> true : isAvailable;
        height.setButtonCell(metricCell());
        color.setButtonCell(metricCell());
    }

    /** The ranges of the values shown, under the bar of the palette. */
    void setLegend(View25D.Legend newLegend) {
        legend = newLegend;
        drawBar();
    }

    private void setOpen(boolean open) {
        body.setVisible(open);
        body.setManaged(open);
        header.setText(open ? "▾ Metrics" : "▸ Metrics");
    }

    private void change(MetricMapping newMapping) {
        mapping = newMapping;
        show(mapping);
        onChange.accept(mapping);
    }

    private void rangeEdited() {
        if (updating) {
            return;
        }
        Double from = parse(min.getText());
        Double to = parse(max.getText());
        if (!java.util.Objects.equals(from, mapping.colorMin()) || !java.util.Objects.equals(to, mapping.colorMax())) {
            change(mapping.withColorRange(from, to));
        }
    }

    private static Double parse(String text) {
        try {
            return text == null || text.isBlank() ? null : Double.valueOf(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void show(MetricMapping shown) {
        updating = true;
        try {
            preset.setValue(PRESETS.stream().filter(p -> p.mapping().equals(shown)).findFirst().orElse(null));
            height.setValue(shown.height());
            scale.getToggles().forEach(toggle -> toggle.setSelected(toggle.getUserData() == shown.scale()));
            heightPercent.setValue(shown.heightPercent());
            heightPercentLabel.setText(Math.round(shown.heightPercent()) + " %");
            color.setValue(shown.color());
            palette.setValue(shown.palette());
            reversed.setSelected(shown.reversed());
            min.setText(shown.colorMin() == null ? "" : View25D.format(shown.colorMin()));
            max.setText(shown.colorMax() == null ? "" : View25D.format(shown.colorMax()));
            boolean colored = shown.color() != null;
            palette.setDisable(!colored);
            reversed.setDisable(!colored);
            min.setDisable(!colored);
            max.setDisable(!colored);
        } finally {
            updating = false;
        }
        drawBar();
    }

    private void drawBar() {
        GraphicsContext g = bar.getGraphicsContext2D();
        g.clearRect(0, 0, bar.getWidth(), bar.getHeight());
        boolean colored = mapping.color() != null;
        if (colored) {
            for (int x = 0; x < bar.getWidth(); x++) {
                g.setFill(Palettes.color(mapping.palette(), x / (bar.getWidth() - 1), mapping.reversed()));
                g.fillRect(x, 0, 1, bar.getHeight());
            }
        }
        bar.setVisible(colored);
        bar.setManaged(colored);
        boolean heightKnown = legend != null && legend.height() == mapping.height() && !Double.isNaN(legend.heightMax());
        heightRange.setText(heightKnown ? String.format(Locale.ROOT, "height: %s … %s",
                View25D.format(legend.heightMin()), View25D.format(legend.heightMax())) : "");
        heightRange.setVisible(heightKnown);
        heightRange.setManaged(heightKnown);
        boolean known = colored && legend != null && legend.metric() == mapping.color();
        classRange.setText(known ? rangeText("classes", legend.classMin(), legend.classMax()) : "");
        packageRange.setText(known ? rangeText("packages", legend.packageMin(), legend.packageMax()) : "");
        classRange.setVisible(!classRange.getText().isEmpty());
        classRange.setManaged(classRange.isVisible());
        packageRange.setVisible(!packageRange.getText().isEmpty());
        packageRange.setManaged(packageRange.isVisible());
    }

    /** The values of a kind that the palette spans, the limits set by hand taken into account. */
    private String rangeText(String kind, double low, double high) {
        if (Double.isNaN(low)) {
            return "";
        }
        double from = mapping.colorMin() != null ? mapping.colorMin() : low;
        double to = mapping.colorMax() != null ? mapping.colorMax() : high;
        return String.format(Locale.ROOT, "%s: %s … %s  (shown %s – %s)", kind,
                View25D.format(from), View25D.format(to), View25D.format(low), View25D.format(high));
    }

    private ListCell<Metric> metricCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Metric metric, boolean empty) {
                super.updateItem(metric, empty);
                if (empty) {
                    setText(null);
                    setTooltip(null);
                    setDisable(false);
                    return;
                }
                if (metric == null) {
                    setText("None (colors of 2D)");
                    setTooltip(null);
                    setDisable(false);
                    return;
                }
                boolean has = available.test(metric);
                setText(has ? metric.label() : metric.label() + " (Java bytecode only)");
                setTooltip(new Tooltip(metric.description()));
                setDisable(!has);
                setOpacity(has ? 1 : 0.5);
            }
        };
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 11px;");
        return label;
    }
}
