package de.weigend.s202.ui.newlayout;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
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
     * Add test content using ArchitectureLevel (hierarchical nesting).
     */
    private void addTestContent() {
        mainContent = new VBox(10);
        mainContent.setStyle("-fx-background-color: #ffffff; -fx-padding: 20;");
        mainContent.setMaxWidth(Double.MAX_VALUE);
        
        // Create first level with name
        ArchitectureLevel level1 = new ArchitectureLevel("Level 1");
        
        // Insert nested level at position 2.2 (in row 2, element 2)
        level1.addNestedLevelAtPosition2();
        
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
