package de.weigend.s202.ui.newlayout;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;

/**
 * Main controller for the new architecture visualization layout.
 * Dynamically creates HBox rows with test elements.
 */
public class ArchitectureViewV2Controller {

    @FXML
    private BorderPane rootPane;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private VBox mainContent;

    @FXML
    private void initialize() {
        // CSS will be loaded if needed
        if (mainContent != null) {
            mainContent.getStylesheets().add(
                getClass().getResource("css/architecture-style.css").toExternalForm()
            );
            
            // VBox sollte volle Breite nutzen
            mainContent.setMaxWidth(Double.MAX_VALUE);
            
            // Dynamically add test content using ArchitectureContainer
            addTestContent();
        }
    }

    /**
     * Add test content using LevelPackageBox (hierarchical nesting).
     */
    private void addTestContent() {
        mainContent = new VBox(10);
        mainContent.setStyle("-fx-background-color: #ffffff; -fx-padding: 20;");
        mainContent.setMaxWidth(Double.MAX_VALUE);
        mainContent.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(mainContent, Priority.ALWAYS);
        
        // Create first level with name
        LevelPackageBox level1 = new LevelPackageBox("Level 1");
        VBox.setVgrow(level1, Priority.ALWAYS);
        
        // Populate with test elements (9 elements in 4 levels)
        level1.addToLevel(1, new LevelClassBox("Element 1.1"));
        level1.addToLevel(2, new LevelClassBox("Element 2.1"));
        level1.addToLevel(3, new LevelClassBox("Element 3.1"));
        level1.addToLevel(3, new LevelClassBox("Element 3.2"));
        level1.addToLevel(3, new LevelClassBox("Element 3.3"));
        level1.addToLevel(4, new LevelClassBox("Element 4.1"));
        level1.addToLevel(4, new LevelClassBox("Element 4.2"));
        level1.addToLevel(4, new LevelClassBox("Element 4.3"));
        level1.addToLevel(4, new LevelClassBox("Element 4.4"));
        
        // Create a nested level at position 2.2
        LevelPackageBox nestedLevel = new LevelPackageBox("Nested Level");
        nestedLevel.addToLevel(1, new LevelClassBox("Nested 1.1"));
        nestedLevel.addToLevel(1, new LevelClassBox("Nested 1.2"));
        nestedLevel.addToLevel(2, new LevelClassBox("Nested 2.1"));
        
        // Add nested structure to level 2 (as Element 2.2, alongside Element 2.1)
        level1.addToLevel(2, nestedLevel);
        
        mainContent.getChildren().add(level1);
        scrollPane.setContent(mainContent);
    }

    /**
     * Load the FXML and return the controller.
     */
    public static ArchitectureViewV2Controller loadView() throws IOException {
        FXMLLoader loader = new FXMLLoader(
            ArchitectureViewV2Controller.class.getResource("fxml/architecture-view-v2.fxml")
        );
        loader.load();
        return loader.getController();
    }

    /**
     * Get the root node (BorderPane rootPane).
     */
    public BorderPane getRootNode() {
        return rootPane;
    }
}
