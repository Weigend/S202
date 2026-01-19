package de.weigend.s202.ui.newlayout;

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
        super(name);
        
        // Styling
        this.setStyle("-fx-font-size: 12; -fx-border-color: #0066cc; -fx-border-width: 2; -fx-padding: 8; -fx-background-color: #e6f0ff;");
        this.setMaxWidth(Double.MAX_VALUE);
        this.setAlignment(Pos.CENTER);
        this.setCursor(javafx.scene.Cursor.HAND);
        this.setWrapText(true);
        
        // Click handler for selection
        this.setOnMouseClicked(event -> select());
    }

    /**
     * Select this box and deselect the previously selected one.
     */
    public void select() {
        // Deselect previous label globally
        if (globalSelectedLabel != null && globalSelectedLabel != this) {
            if (globalSelectedLabel instanceof LevelClassBox) {
                ((LevelClassBox) globalSelectedLabel).deselect();
            }
        }
        
        // Select this label
        globalSelectedLabel = this;
        this.setStyle("-fx-font-size: 12; -fx-border-color: #ff6600; -fx-border-width: 3; -fx-padding: 8; -fx-background-color: #ffe6cc;");
    }

    /**
     * Deselect this box.
     */
    public void deselect() {
        this.setStyle("-fx-font-size: 12; -fx-border-color: #0066cc; -fx-border-width: 2; -fx-padding: 8; -fx-background-color: #e6f0ff;");
    }
}
