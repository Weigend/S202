package de.weigend.s202.ui.demo;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * JavaFX Application entry point for the Architecture Visualization (Demo mode).
 * This demo shows the new LevelPackageBox hierarchy with hardcoded test data.
 * 
 * For integration with the main analyzer, use ArchitectureViewV2Controller directly.
 */
public class ArchitectureViewDemoApp extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        // Load the FXML-based view
        ArchitectureViewDemoController controller = ArchitectureViewDemoController.loadView();
        
        // Create a scene with the root node
        Scene scene = new Scene(controller.getRootNode(), 1200, 800);
        
        // Setup the stage
        primaryStage.setTitle("S202 Code Analyzer - Architecture Visualization (Demo)");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

