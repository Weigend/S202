package de.weigend.s202.ui.newlayout;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Demo application for the new ArchitectureViewV2.
 * Minimal version - just shows empty ScrollPane.
 */
public class ArchitectureViewV2Demo extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        // Load the controller from FXML
        ArchitectureViewV2Controller rootController = ArchitectureViewV2Controller.loadView();

        // Display the UI
        primaryStage.setTitle("S202 Architecture Viewer - Demo");
        primaryStage.setScene(new Scene(rootController.getRootNode(), 1200, 800));
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
