package de.weigend.s202.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;

/**
 * Reusable box representing a single class element in the architecture hierarchy.
 * Can be selected/deselected with visual feedback.
 * Extends Label for simplicity and reusability.
 */
public class LevelClassBox extends Label {

    private static Label globalSelectedLabel;  // Global selection across all instances

    /**
     * Create a new LevelClassBox with the given name.
     * @param name The class name to display
     */
    public LevelClassBox(String name) {
        this(name, -1);
    }

    /**
     * Create a new LevelClassBox with the given name and level.
     * @param name The class name to display
     * @param level The architectural level (-1 means not specified)
     */
    public LevelClassBox(String name, int level) {
        super(level >= 0 ? name + " (L:" + level + ")" : name);
        
        // Styling via CSS class
        this.getStyleClass().add("class-box");
        this.setMaxWidth(Double.MAX_VALUE);
        this.setAlignment(Pos.CENTER);
        this.setCursor(javafx.scene.Cursor.HAND);
        this.setWrapText(true);
        
        // Click handler for selection
        this.setOnMouseClicked(event -> select());
    }

    /**
     * Select this box and deselect the previously selected one.
     * Clicking again on the same box will deselect it (toggle behavior).
     */
    public void select() {
        // If clicking on already selected element, deselect it
        if (globalSelectedLabel == this) {
            deselect();
            globalSelectedLabel = null;
            return;
        }
        
        // Deselect previous label globally
        if (globalSelectedLabel != null) {
            if (globalSelectedLabel instanceof LevelClassBox) {
                ((LevelClassBox) globalSelectedLabel).deselect();
            }
        }
        
        // Select this label
        globalSelectedLabel = this;
        this.getStyleClass().remove("class-box");
        if (!this.getStyleClass().contains("class-box-selected")) {
            this.getStyleClass().add("class-box-selected");
        }
    }

    /**
     * Deselect this box.
     */
    public void deselect() {
        this.getStyleClass().remove("class-box-selected");
        if (!this.getStyleClass().contains("class-box")) {
            this.getStyleClass().add("class-box");
        }
    }
}
