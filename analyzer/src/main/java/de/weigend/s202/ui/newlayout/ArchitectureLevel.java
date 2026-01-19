package de.weigend.s202.ui.newlayout;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Reusable architecture level containing 4 rows with 1, 2, 3, 4 elements.
 * Row 2 can contain a nested ArchitectureLevel at position 2 (Element 2.2).
 * Can be nested inside other ArchitectureLevel instances.
 */
public class ArchitectureLevel extends VBox {

    private static Label globalSelectedLabel;  // Global selection across all instances
    
    private Label toggleIcon;
    private VBox contentContainer;
    private boolean isExpanded = true;
    private String levelName;

    /**
     * Create a new ArchitectureLevel with default structure (4 rows) and a header.
     * @param levelName The name to display in the header
     */
    public ArchitectureLevel(String levelName) {
        super(5);
        this.levelName = levelName;
        this.setStyle("-fx-background-color: #fffacd; -fx-border-color: #999999; -fx-border-width: 2; -fx-padding: 0;");
        this.setPadding(new Insets(0));
        this.setMaxWidth(Double.MAX_VALUE);
        
        // Create and add header
        createHeader();
        
        // Create content container for all rows
        contentContainer = new VBox(10);
        contentContainer.setStyle("-fx-background-color: #fffacd;");
        contentContainer.setPadding(new Insets(10, 10, 10, 30));
        contentContainer.setMaxWidth(Double.MAX_VALUE);
        
        // Add the 4 standard rows to content container
        addRowToContent(1);
        addRowToContent(2);
        addRowToContent(3);
        addRowToContent(4);
        
        // Add content to this VBox
        this.getChildren().add(contentContainer);
    }

    /**
     * Create the header with toggle icon and level name.
     */
    private void createHeader() {
        HBox header = new HBox(10);
        header.setStyle("-fx-background-color: #fffacd; -fx-padding: 8;");
        header.setMaxWidth(Double.MAX_VALUE);
        header.setAlignment(Pos.CENTER_LEFT);
        
        // Toggle icon
        toggleIcon = new Label("▼");
        toggleIcon.setStyle("-fx-font-size: 10; -fx-font-weight: bold; -fx-text-fill: #333333; -fx-min-width: 15;");
        toggleIcon.setCursor(javafx.scene.Cursor.HAND);
        
        // Level name label
        Label nameLabel = new Label(levelName);
        nameLabel.setStyle("-fx-font-size: 13; -fx-font-weight: bold; -fx-text-fill: #000000;");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        
        header.getChildren().addAll(toggleIcon, nameLabel);
        
        // Add click handler to toggle expansion
        header.setOnMouseClicked(event -> toggleExpanded());
        
        this.getChildren().add(0, header);
    }

    /**
     * Toggle between expanded and collapsed state.
     */
    private void toggleExpanded() {
        isExpanded = !isExpanded;
        if (isExpanded) {
            toggleIcon.setText("▼");
            contentContainer.setVisible(true);
            contentContainer.setManaged(true);
        } else {
            toggleIcon.setText("▶");
            contentContainer.setVisible(false);
            contentContainer.setManaged(false);
        }
    }

    /**
     * Add a row (HBox) with the specified number of elements to content container.
     */
    private void addRowToContent(int elementCount) {
        HBox hbox = new HBox(10);
        hbox.setStyle("-fx-background-color: #fffacd;");
        hbox.setMaxWidth(Double.MAX_VALUE);
        hbox.setAlignment(Pos.CENTER);
        
        for (int i = 1; i <= elementCount; i++) {
            Label label = new Label("Element " + elementCount + "." + i);
            label.setStyle("-fx-font-size: 12; -fx-border-color: #0066cc; -fx-border-width: 2; -fx-padding: 8; -fx-background-color: #e6f0ff;");
            label.setMaxWidth(Double.MAX_VALUE);
            label.setAlignment(Pos.CENTER);
            label.setCursor(javafx.scene.Cursor.HAND);
            HBox.setHgrow(label, Priority.ALWAYS);
            
            // Add click handler for selection
            label.setOnMouseClicked(event -> selectLabel(label));
            
            hbox.getChildren().add(label);
        }
        
        contentContainer.getChildren().add(hbox);
    }

    /**
     * Add a nested ArchitectureLevel at position 2 (Element 2.2) in row 2.
     * Row 2 will contain: [Label 2.1] [Nested Level]
     */
    public void addNestedLevelAtPosition2() {
        // Remove the original row 2 (index 1 in contentContainer)
        contentContainer.getChildren().remove(1);
        
        // Create new row 2 with Label 2.1 and nested level
        HBox row2 = new HBox(10);
        row2.setStyle("-fx-background-color: #fffacd;");
        row2.setMaxWidth(Double.MAX_VALUE);
        row2.setMaxHeight(Double.MAX_VALUE);
        row2.setAlignment(Pos.TOP_LEFT);
        
        // Add Label 2.1
        Label label2_1 = new Label("Element 2.1");
        label2_1.setStyle("-fx-font-size: 12; -fx-border-color: #0066cc; -fx-border-width: 2; -fx-padding: 8; -fx-background-color: #e6f0ff;");
        label2_1.setMaxWidth(Double.MAX_VALUE);
        label2_1.setMaxHeight(Double.MAX_VALUE);
        label2_1.setWrapText(true);
        label2_1.setAlignment(Pos.CENTER);
        label2_1.setCursor(javafx.scene.Cursor.HAND);
        HBox.setHgrow(label2_1, Priority.ALWAYS);
        
        // Add click handler for selection
        label2_1.setOnMouseClicked(event -> selectLabel(label2_1));
        
        row2.getChildren().add(label2_1);
        
        // Add nested level
        ArchitectureLevel nestedLevel = new ArchitectureLevel("Nested Level");
        nestedLevel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nestedLevel, Priority.ALWAYS);
        row2.getChildren().add(nestedLevel);
        
        // Insert modified row 2 back at position 1 in contentContainer
        contentContainer.getChildren().add(1, row2);
    }

    /**
     * Handle label selection and update styling.
     */
    private void selectLabel(Label clickedLabel) {
        // Deselect previous label globally
        if (globalSelectedLabel != null && globalSelectedLabel != clickedLabel) {
            globalSelectedLabel.setStyle("-fx-font-size: 12; -fx-border-color: #0066cc; -fx-border-width: 2; -fx-padding: 8; -fx-background-color: #e6f0ff;");
        }
        
        // Select new label
        globalSelectedLabel = clickedLabel;
        clickedLabel.setStyle("-fx-font-size: 12; -fx-border-color: #ff6600; -fx-border-width: 3; -fx-padding: 8; -fx-background-color: #ffe6cc;");
    }
}

