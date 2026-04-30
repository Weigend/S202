package de.weigend.s202.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;

/**
 * Reusable box representing a single class element in the architecture
 * hierarchy. Selection is delegated to {@link GraphSelection} so packages and
 * classes share the same single-selection slot (mutually exclusive).
 */
public class LevelClassBox extends Label implements GraphSelection.Selectable {

    private final String fullClassName;

    public LevelClassBox(String name) {
        this(name, -1, null);
    }

    public LevelClassBox(String name, int level) {
        this(name, level, null);
    }

    public LevelClassBox(String name, int level, String fullClassName) {
        super(level >= 0 ? name + " (L:" + level + ")" : name);
        this.fullClassName = fullClassName;

        this.getStyleClass().add("class-box");
        this.setAlignment(Pos.CENTER);
        this.setCursor(javafx.scene.Cursor.HAND);
        this.setWrapText(true);

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
}
