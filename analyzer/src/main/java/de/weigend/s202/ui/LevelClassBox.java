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
    private static String selectedClassName;   // Full name of selected class
    private static Runnable onSelectionChangeCallback;  // Callback when selection changes
    private String fullClassName;  // Full class name for this box

    /**
     * Create a new LevelClassBox with the given name.
     * @param name The class name to display
     */
    public LevelClassBox(String name) {
        this(name, -1, null);
    }

    /**
     * Create a new LevelClassBox with the given name and level.
     * @param name The class name to display
     * @param level The architectural level (-1 means not specified)
     */
    public LevelClassBox(String name, int level) {
        this(name, level, null);
    }
    
    /**
     * Create a new LevelClassBox with the given name, level and full class name.
     * @param name The class name to display
     * @param level The architectural level (-1 means not specified)
     * @param fullClassName The full class name for dependency matching
     */
    public LevelClassBox(String name, int level, String fullClassName) {
        super(level >= 0 ? name + " (L:" + level + ")" : name);
        this.fullClassName = fullClassName;
        
        // Styling via CSS class
        this.getStyleClass().add("class-box");
        this.setAlignment(Pos.CENTER);
        this.setCursor(javafx.scene.Cursor.HAND);
        this.setWrapText(true);
        
        // Click handler for selection
        this.setOnMouseClicked(event -> select());
    }

    /**
     * Set callback to be notified when selection changes.
     * @param callback The callback to invoke (may be null)
     */
    public static void setOnSelectionChangeCallback(Runnable callback) {
        onSelectionChangeCallback = callback;
    }
    
    /**
     * Get the full class name of the currently selected class.
     * @return The full class name or null if nothing is selected
     */
    public static String getSelectedClassName() {
        return selectedClassName;
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
            selectedClassName = null;
            notifySelectionChanged();
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
        selectedClassName = this.fullClassName;
        this.getStyleClass().remove("class-box");
        if (!this.getStyleClass().contains("class-box-selected")) {
            this.getStyleClass().add("class-box-selected");
        }
        notifySelectionChanged();
    }
    
    /**
     * Notify the callback that selection has changed.
     */
    private static void notifySelectionChanged() {
        if (onSelectionChangeCallback != null) {
            onSelectionChangeCallback.run();
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
