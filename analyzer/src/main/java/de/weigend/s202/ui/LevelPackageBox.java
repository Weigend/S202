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
package de.weigend.s202.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reusable architecture level containing 4 rows with 1, 2, 3, 4 elements.
 * Row 2 can contain a nested ArchitectureLevel at position 2 (Element 2.2).
 * Can be nested inside other ArchitectureLevel instances.
 */
public class LevelPackageBox extends VBox implements GraphSelection.Selectable {

    private static final String STYLE_NORMAL =
            "-fx-background-color: #fffacd; -fx-border-color: #999999; -fx-border-width: 1;";
    private static final String STYLE_SELECTED =
            "-fx-background-color: #fff3a0; -fx-border-color: #ff6600; -fx-border-width: 2;";
    private static final String STYLE_MOVED =
            "-fx-background-color: #fffacd; -fx-border-color: #1d4ed8; -fx-border-width: 2;";
    private static final String STYLE_MOVED_SELECTED =
            "-fx-background-color: #fff3a0; -fx-border-color: #1d4ed8; -fx-border-width: 3;";
    private static final String STYLE_TRANSPARENT =
            "-fx-background-color: transparent; -fx-border-width: 0;";
    private static final Color PACKAGE_COLOR = Color.web("#e6c46a");

    private Label toggleIcon;
    private Label nameLabel;
    private VBox contentContainer;
    private boolean isExpanded = true;
    private final String simpleName;
    private final int staticLevel;
    private int currentLocalLevel;
    private final int architectureLevel;
    private String levelName;
    private final String fullName;
    private final Map<Integer, HBox> levelRows;
    private final boolean transparent;
    private boolean selected;
    private boolean virtuallyMoved;

    // Static callback for notifying when expand/collapse changes
    private static Runnable onExpandChangeCallback = null;

    /**
     * Sets a global callback that is called whenever any LevelPackageBox is expanded/collapsed.
     * Used to refresh dependency arrows in ArchitectureView.
     */
    public static void setOnExpandChangeCallback(Runnable callback) {
        onExpandChangeCallback = callback;
    }

    public LevelPackageBox(String levelName) {
        this(levelName, -1);
    }

    public LevelPackageBox(String levelName, int level) {
        this(levelName, level, false);
    }

    public LevelPackageBox(String levelName, int level, boolean transparent) {
        this(levelName, level, transparent, null);
    }

    public LevelPackageBox(String levelName, int level, boolean transparent, String fullName) {
        this(levelName, level, transparent, fullName, -1);
    }

    /**
     * Create a new LevelPackageBox.
     *
     * @param levelName          Display name (simple name + optional level marker).
     * @param level              Local layer index, -1 if unspecified.
     * @param transparent        Pass-through visual (no border / no background).
     * @param fullName           Fully qualified package name; required for selection.
     * @param architectureLevel  Global architecture level (longest dependency-chain
     *                           depth), -1 if unknown. Shown next to the local level.
     */
    public LevelPackageBox(String levelName, int level, boolean transparent, String fullName, int architectureLevel) {
        super(transparent ? 0 : 3);
        this.simpleName = levelName;
        this.staticLevel = level;
        this.currentLocalLevel = level;
        this.architectureLevel = architectureLevel;
        this.levelName = BoxLabelFormatter.format(levelName, level, architectureLevel,
                ArchitectureView.showArchitectureLevelProperty().get());
        this.transparent = transparent;
        this.fullName = fullName;

        getStyleClass().add(transparent ? "package-box-transparent" : "package-box");

        // Apply styles directly - CSS doesn't override VBox defaults reliably
        applyBaseStyle();
        this.setPadding(new Insets(0));
        this.setMaxWidth(Double.MAX_VALUE);

        this.levelRows = new TreeMap<>();

        createHeader();

        contentContainer = new VBox(6);
        contentContainer.setPadding(transparent ? new Insets(0, 0, 0, 10) : new Insets(6, 6, 6, 20));
        contentContainer.setMaxWidth(Double.MAX_VALUE);
        ArchitectureDragController.markAsRowStack(contentContainer);

        this.getChildren().add(contentContainer);

        ArchitectureDragController.makeDraggable(this);

        // Live toggle: re-render the label whenever the global architecture-level
        // visibility flips, without rebuilding the tree.
        ArchitectureView.showArchitectureLevelProperty().addListener((obs, oldVal, newVal) -> applyLabelText());

        // Selectable click target on the package frame itself (free area outside
        // the header). The toggle icon and inner boxes consume their own clicks
        // so they do not bubble up here.
        if (!transparent && fullName != null) {
            this.setCursor(Cursor.HAND);
            this.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                handleSelectionClick(event.getClickCount());
                event.consume();
            });
        }
    }

    private void applyBaseStyle() {
        if (transparent) {
            this.setStyle(STYLE_TRANSPARENT);
            return;
        }
        if (virtuallyMoved) {
            this.setStyle(selected ? STYLE_MOVED_SELECTED : STYLE_MOVED);
        } else {
            this.setStyle(selected ? STYLE_SELECTED : STYLE_NORMAL);
        }
    }

    private void handleSelectionClick(int clickCount) {
        if (transparent || fullName == null) {
            return;
        }
        if (clickCount == 2) {
            GraphSelection.ensureSelected(this);
            GraphSelection.fireDoubleClick(fullName);
        } else {
            GraphSelection.select(this);
        }
    }

    /**
     * Create the header. The toggle icon owns its own click handler (with
     * {@code consume()}) so collapse/expand never falls through to selection.
     * The rest of the header bubbles up to the package's mouse handler and is
     * therefore part of the "free area" that selects the package.
     */
    private void createHeader() {
        HBox header = new HBox(6);
        header.getStyleClass().add("header");
        header.setPadding(transparent ? new Insets(0) : new Insets(4));
        header.setMaxWidth(Double.MAX_VALUE);
        header.setAlignment(Pos.CENTER_LEFT);

        // Toggle icon (- for expanded, + for collapsed)
        toggleIcon = new Label("−");
        toggleIcon.getStyleClass().add("toggle-icon");
        toggleIcon.setStyle("-fx-text-fill: #000000; -fx-font-size: 10px; -fx-font-weight: bold;");
        toggleIcon.setMinWidth(12);
        toggleIcon.setPrefWidth(12);
        toggleIcon.setAlignment(Pos.CENTER);
        toggleIcon.setCursor(Cursor.HAND);
        toggleIcon.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                toggleExpanded();
                event.consume();
            }
        });

        FontIcon packageIcon = new FontIcon(MaterialDesignP.PACKAGE_VARIANT_CLOSED);
        packageIcon.getStyleClass().add("architecture-icon");
        packageIcon.setIconColor(PACKAGE_COLOR);
        packageIcon.visibleProperty().bind(ArchitectureView.showIconsProperty());
        packageIcon.managedProperty().bind(ArchitectureView.showIconsProperty());

        nameLabel = new Label(levelName);
        nameLabel.getStyleClass().add("package-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        header.getChildren().addAll(toggleIcon, packageIcon, nameLabel);

        this.getChildren().add(0, header);
    }

    public void setExpanded(boolean expanded) {
        if (this.isExpanded != expanded) {
            toggleExpanded();
        }
    }

    private void toggleExpanded() {
        isExpanded = !isExpanded;
        if (isExpanded) {
            toggleIcon.setText("−");
            contentContainer.setVisible(true);
            contentContainer.setManaged(true);
            contentContainer.applyCss();
        } else {
            toggleIcon.setText("+");
            contentContainer.setVisible(false);
            contentContainer.setManaged(false);
        }

        if (onExpandChangeCallback != null) {
            // Defer until after the current pulse so listeners observe stable
            // bounds — running synchronously inside the pulse that just queued
            // the layout invalidation makes bounds-readers see stale values.
            Runnable callback = onExpandChangeCallback;
            javafx.application.Platform.runLater(callback);
        }
    }

    public void addToLevel(int levelNumber, Node node) {
        Objects.requireNonNull(node, "node cannot be null");

        HBox levelRow = levelRows.computeIfAbsent(levelNumber, level -> {
            HBox hbox = new HBox(10);
            // Always transparent — the row's bg used to mirror the package
            // bg (#fffacd), which becomes visible as a stripe once the package
            // switches to its selected bg. Letting the row inherit avoids that.
            hbox.setStyle("-fx-background-color: transparent;");
            hbox.setMaxWidth(Double.MAX_VALUE);
            hbox.setMaxHeight(Double.MAX_VALUE);
            hbox.setAlignment(Pos.CENTER);
            VBox.setVgrow(hbox, Priority.ALWAYS);
            ArchitectureDragController.markAsRow(hbox);

            insertLevelRowAtCorrectPosition(levelNumber, hbox);

            return hbox;
        });

        if (node instanceof LevelPackageBox) {
            ((Region) node).setMaxWidth(Double.MAX_VALUE);
            ((Region) node).setMaxHeight(Double.MAX_VALUE);
            HBox.setHgrow(node, Priority.ALWAYS);
        }
        levelRow.getChildren().add(node);
    }

    private void insertLevelRowAtCorrectPosition(int newLevelNumber, HBox hbox) {
        int insertIndex = 0;
        for (int i = 0; i < contentContainer.getChildren().size(); i++) {
            Node child = contentContainer.getChildren().get(i);
            for (Integer levelNum : levelRows.keySet()) {
                if (levelRows.get(levelNum) == child && levelNum > newLevelNumber) {
                    insertIndex = i + 1;
                    break;
                }
            }
        }
        contentContainer.getChildren().add(insertIndex, hbox);
    }

    // ===== GraphSelection.Selectable =====

    @Override
    public String getFullName() {
        return fullName;
    }

    @Override
    public void applySelectedStyle() {
        selected = true;
        applyBaseStyle();
    }

    @Override
    public void applyUnselectedStyle() {
        selected = false;
        applyBaseStyle();
    }

    /**
     * Toggle the "this box has been virtually moved" decoration. Switches to
     * a blue 2px border (3px when also selected). Blue rather than red
     * keeps the colour space reserved for actual architecture violations.
     * A previous DropShadow version of this indicator visually obscured the
     * 6px VBox-spacing gap between rows, making the drop-target gap
     * unaimable; a border stays inside the box bounds.
     */
    public void setVirtuallyMoved(boolean moved) {
        if (this.virtuallyMoved != moved) {
            this.virtuallyMoved = moved;
            applyBaseStyle();
        }
    }

    /**
     * Update the displayed architectural level. Pass {@code -1} to revert to
     * the static level baked in at construction. Idempotent.
     */
    public void setVirtualLevel(int level) {
        int shown = level >= 0 ? level : staticLevel;
        this.currentLocalLevel = shown;
        applyLabelText();
    }

    private void applyLabelText() {
        String text = BoxLabelFormatter.format(simpleName, currentLocalLevel, architectureLevel,
                ArchitectureView.showArchitectureLevelProperty().get());
        if (!text.equals(this.levelName)) {
            this.levelName = text;
            if (nameLabel != null) {
                nameLabel.setText(text);
            }
        }
    }
}
