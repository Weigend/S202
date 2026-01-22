package de.weigend.s202.ui.demo;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;

import java.io.IOException;

/**
 * Main controller for the new architecture visualization layout.
 * Demo mode only - shows hardcoded test data for UI testing.
 */
public class ArchitectureViewDemoController {

    @FXML
    private BorderPane rootPane;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private void initialize() {
        // Demo mode - show hardcoded test content
        addTestContent();
    }

    /**
     * Add demo/test content (hardcoded hierarchical structure).
     */
    private void addTestContent() {
        // Create first level with name
        LevelPackageBox level1 = new LevelPackageBox("Root Package");
        
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
        
        scrollPane.setContent(level1);
    }

    /**
     * Load the FXML and return the controller.
     */
    public static ArchitectureViewDemoController loadView() throws IOException {
        FXMLLoader loader = new FXMLLoader(
            ArchitectureViewDemoController.class.getResource("fxml/architecture-view-v2.fxml")
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

