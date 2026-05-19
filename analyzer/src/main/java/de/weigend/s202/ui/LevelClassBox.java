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

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

/**
 * Reusable box representing a single class element in the architecture
 * hierarchy. Selection is delegated to {@link GraphSelection} so packages and
 * classes share the same single-selection slot (mutually exclusive).
 */
public class LevelClassBox extends HBox implements GraphSelection.Selectable {

    private static final Color CLASS_COLOR = Color.web("#7fb3ff");
    private static final Color INTERFACE_COLOR = Color.web("#4caf50");

    private final String fullClassName;
    private final String simpleName;
    private final int level;
    private final int architectureLevel;
    private final Label nameLabel;

    public LevelClassBox(String name) {
        this(name, -1, null, false, -1);
    }

    public LevelClassBox(String name, int level) {
        this(name, level, null, false, -1);
    }

    public LevelClassBox(String name, int level, String fullClassName) {
        this(name, level, fullClassName, false, -1);
    }

    public LevelClassBox(String name, int level, String fullClassName, boolean isInterface) {
        this(name, level, fullClassName, isInterface, -1);
    }

    public LevelClassBox(String name, int level, String fullClassName, boolean isInterface, int architectureLevel) {
        super(4);
        this.fullClassName = fullClassName;
        this.simpleName = name;
        this.level = level;
        this.architectureLevel = architectureLevel;

        this.getStyleClass().add("class-box");
        this.setAlignment(Pos.CENTER);
        this.setCursor(Cursor.HAND);
        // .class-box's font-size and HBox's default maxHeight=MAX_VALUE would
        // otherwise let the levelRow's vgrow stretch us vertically.
        this.setMaxHeight(Region.USE_PREF_SIZE);

        FontIcon icon = isInterface
                ? new FontIcon(MaterialDesignA.ALPHA_I_CIRCLE)
                : new FontIcon(MaterialDesignA.ALPHA_C_CIRCLE);
        icon.getStyleClass().add("architecture-icon");
        icon.setIconColor(isInterface ? INTERFACE_COLOR : CLASS_COLOR);
        icon.visibleProperty().bind(ArchitectureView.showIconsProperty());
        icon.managedProperty().bind(ArchitectureView.showIconsProperty());

        nameLabel = new Label(BoxLabelFormatter.format(name, level, architectureLevel,
                ArchitectureView.showArchitectureLevelProperty().get()));
        nameLabel.setWrapText(true);
        // text-fill doesn't cascade through HBox to descendant Label, so the
        // dark theme's derived text color (light on -fx-base #3c3f41) would win.
        nameLabel.setStyle("-fx-text-fill: #000000;");

        // Live toggle: re-render the label whenever the global architecture-level
        // visibility flips, without rebuilding the tree.
        ArchitectureView.showArchitectureLevelProperty().addListener((obs, oldVal, newVal) ->
                nameLabel.setText(BoxLabelFormatter.format(simpleName, this.level,
                        this.architectureLevel, newVal)));

        this.getChildren().addAll(icon, nameLabel);

        ArchitectureDragController.makeDraggable(this);

        // Single click toggles selection. Double click ensures the class stays
        // selected (no toggle off) and notifies the double-click listener so
        // external panels — e.g. the outline explorer — can reveal the class.
        this.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (event.getClickCount() == 2) {
                GraphSelection.ensureSelected(this);
                GraphSelection.fireDoubleClick(fullClassName);
            } else {
                GraphSelection.select(this);
            }
            // Prevent the click from bubbling up to the enclosing package's
            // selection handler.
            event.consume();
        });
    }

    /** Displayed label text (incl. "(L:n)" suffix when set). */
    public String getText() {
        return nameLabel.getText();
    }

    /** @deprecated use {@link GraphSelection#getCurrentFullName()} */
    @Deprecated
    public static String getSelectedClassName() {
        return GraphSelection.getCurrentFullName();
    }

    // ===== GraphSelection.Selectable =====

    @Override
    public String getFullName() {
        return fullClassName;
    }

    @Override
    public void applySelectedStyle() {
        this.getStyleClass().remove("class-box");
        if (!this.getStyleClass().contains("class-box-selected")) {
            this.getStyleClass().add("class-box-selected");
        }
    }

    @Override
    public void applyUnselectedStyle() {
        this.getStyleClass().remove("class-box-selected");
        if (!this.getStyleClass().contains("class-box")) {
            this.getStyleClass().add("class-box");
        }
    }

    private static final String STYLE_MOVED_BORDER =
            "-fx-border-color: #1d4ed8; -fx-border-width: 2;";

    /**
     * Toggle the "this box has been virtually moved" decoration. A blue
     * 2px border (distinct from the default blue class border by being
     * deeper / wider) replaces whatever border the CSS class set. Blue
     * because red is reserved for architecture violations. The previous
     * DropShadow variant visually leaked ~10px into the surrounding 6px
     * VBox-spacing gap, hiding the drop target. The inline style override
     * beats the CSS class colour at runtime; clearing it lets the CSS
     * class take over again.
     */
    public void setVirtuallyMoved(boolean moved) {
        if (moved) {
            setStyle(STYLE_MOVED_BORDER);
        } else {
            setStyle("");
        }
    }
}
