package de.weigend.s202.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reusable architecture level containing 4 rows with 1, 2, 3, 4 elements.
 * Row 2 can contain a nested ArchitectureLevel at position 2 (Element 2.2).
 * Can be nested inside other ArchitectureLevel instances.
 */
public class LevelPackageBox extends VBox {

    private Label toggleIcon;
    private VBox contentContainer;
    private boolean isExpanded = true;
    private String levelName;
    private Map<Integer, HBox> levelRows;
    private boolean transparent;  // Map to manage HBox containers by level number
    
    // Static callback for notifying when expand/collapse changes
    private static Runnable onExpandChangeCallback = null;
    
    /**
     * Sets a global callback that is called whenever any LevelPackageBox is expanded/collapsed.
     * Used to refresh dependency arrows in ArchitectureView.
     */
    public static void setOnExpandChangeCallback(Runnable callback) {
        onExpandChangeCallback = callback;
    }

    /**
     * Create a new LevelPackageBox with empty content area and a header.
     * @param levelName The name to display in the header
     */
    public LevelPackageBox(String levelName) {
        this(levelName, -1);
    }

    /**
     * Create a new LevelPackageBox with empty content area and a header.
     * @param levelName The name to display in the header
     * @param level The architectural level (-1 means not specified)
     */
    public LevelPackageBox(String levelName, int level) {
        this(levelName, level, false);
    }
    
    /**
     * Create a new LevelPackageBox with empty content area and a header.
     * @param levelName The name to display in the header
     * @param level The architectural level (-1 means not specified)
     * @param transparent If true, shows with no background and no border (for pass-through packages)
     */
    public LevelPackageBox(String levelName, int level, boolean transparent) {
        super(transparent ? 0 : 3);
        this.levelName = level >= 0 ? levelName + " (L:" + level + ")" : levelName;
        this.transparent = transparent;

        // Apply styles directly - CSS doesn't override VBox defaults reliably
        if (transparent) {
            this.setStyle("-fx-background-color: transparent; -fx-border-width: 0;");
        } else {
            this.setStyle("-fx-background-color: #fffacd; -fx-border-color: #999999; -fx-border-width: 1;");
        }
        this.setPadding(new Insets(0));
        this.setMaxWidth(Double.MAX_VALUE);

        // Initialize level rows map (TreeMap for ordered levels)
        this.levelRows = new TreeMap<>();

        // Create and add header
        createHeader();

        // Create content container for all rows
        contentContainer = new VBox(6);
        contentContainer.setPadding(transparent ? new Insets(0, 0, 0, 10) : new Insets(6, 6, 6, 20));
        contentContainer.setMaxWidth(Double.MAX_VALUE);
        
        // Add content to this VBox
        this.getChildren().add(contentContainer);
    }

    /**
     * Create the header with toggle icon and level name.
     */
    private void createHeader() {
        HBox header = new HBox(6);
        header.setPadding(transparent ? new Insets(0) : new Insets(4));
        header.setMaxWidth(Double.MAX_VALUE);
        header.setAlignment(Pos.CENTER_LEFT);
        
        // Toggle icon (- for expanded, + for collapsed)
        toggleIcon = new Label("−");
        toggleIcon.getStyleClass().add("toggle-icon");
        toggleIcon.setCursor(javafx.scene.Cursor.HAND);
        
        // Level name label
        Label nameLabel = new Label(levelName);
        nameLabel.getStyleClass().add("package-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        
        header.getChildren().addAll(toggleIcon, nameLabel);
        
        // Add click handler to toggle expansion
        header.setOnMouseClicked(event -> toggleExpanded());
        
        this.getChildren().add(0, header);
    }

    /**
     * Sets the expanded/collapsed state programmatically.
     */
    public void setExpanded(boolean expanded) {
        if (this.isExpanded != expanded) {
            toggleExpanded();
        }
    }

    /**
     * Toggle between expanded and collapsed state.
     */
    private void toggleExpanded() {
        isExpanded = !isExpanded;
        if (isExpanded) {
            toggleIcon.setText("−");
            contentContainer.setVisible(true);
            contentContainer.setManaged(true);
        } else {
            toggleIcon.setText("+");
            contentContainer.setVisible(false);
            contentContainer.setManaged(false);
        }
        
        // Notify optional callback
        if (onExpandChangeCallback != null) {
            onExpandChangeCallback.run();
        }
    }

    /**
     * Add a node (LevelClassBox, LevelPackageBox, etc.) to a specific level.
     * Creates the HBox for this level if it doesn't exist yet.
     * Levels are displayed in DESCENDING order (higher levels at top).
     * @param levelNumber The level number (1-based)
     * @param node The node to add (typically LevelClassBox or LevelPackageBox)
     */
    public void addToLevel(int levelNumber, Node node) {
        Objects.requireNonNull(node, "node cannot be null");
        
        // Get or create HBox for this level
        HBox levelRow = levelRows.computeIfAbsent(levelNumber, level -> {
            HBox hbox = new HBox(10);
            hbox.setStyle(transparent ? "-fx-background-color: transparent;" : "-fx-background-color: #fffacd;");
            hbox.setMaxWidth(Double.MAX_VALUE);
            hbox.setMaxHeight(Double.MAX_VALUE);
            hbox.setAlignment(Pos.CENTER);
            VBox.setVgrow(hbox, Priority.ALWAYS);  // Make HBox grow vertically in parent VBox
            
            // Insert new HBox at correct position (descending order = highest level first)
            insertLevelRowAtCorrectPosition(levelNumber, hbox);
            
            return hbox;
        });
        
        // Add node to the level row
        // Only packages should grow, classes keep their natural size
        if (node instanceof LevelPackageBox) {
            ((Region) node).setMaxWidth(Double.MAX_VALUE);
            ((Region) node).setMaxHeight(Double.MAX_VALUE);
            HBox.setHgrow(node, Priority.ALWAYS);
        }
        // LevelClassBox keeps natural size (no setMaxWidth, no HBox.setHgrow)
        levelRow.getChildren().add(node);
    }
    
    /**
     * Insert a level row at the correct position to maintain descending order.
     * Higher level numbers should appear first (at top).
     */
    private void insertLevelRowAtCorrectPosition(int newLevelNumber, HBox hbox) {
        // Find insertion index based on descending order
        int insertIndex = 0;
        for (int i = 0; i < contentContainer.getChildren().size(); i++) {
            Node child = contentContainer.getChildren().get(i);
            // Try to find the level number for this child
            for (Integer levelNum : levelRows.keySet()) {
                if (levelRows.get(levelNum) == child && levelNum > newLevelNumber) {
                    insertIndex = i + 1;
                    break;
                }
            }
        }
        contentContainer.getChildren().add(insertIndex, hbox);
    }
}

