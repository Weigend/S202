package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.input.InputAnalyzer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;

/**
 * Main application entry point for the S202 Code Analyzer.
 */
public class AnalyzerApplication extends Application {
    private ArchitectureView architectureView;
    private InputAnalyzer rawAnalyzer;
    private LevelCalculator levelCalculator;
    private ArchitectureNodeBuilder architectureNodeBuilder;
    private Stage primaryStage;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.rawAnalyzer = new InputAnalyzer();
        this.levelCalculator = new LevelCalculator();
        this.architectureNodeBuilder = new ArchitectureNodeBuilder();

        primaryStage.setTitle("S202 Code Analyzer - Architecture Viewer");

        BorderPane root = new BorderPane();
        VBox header = createHeader();
        root.setTop(header);

        // Load original layout by default
        loadOldLayout(root);

        Scene scene = new Scene(root, 1200, 800);
        primaryStage.setScene(scene);
        primaryStage.show();

        // Check for JAR file from multiple sources
        String jarFilePath = null;
        
        // 1. Check environment variable
        jarFilePath = System.getenv("APP_JAR");
        
        // 2. Check system property
        if (jarFilePath == null || jarFilePath.isEmpty()) {
            jarFilePath = System.getProperty("app.jar");
        }
        
        // 3. Check application arguments
        if ((jarFilePath == null || jarFilePath.isEmpty()) && !getParameters().getRaw().isEmpty()) {
            jarFilePath = getParameters().getRaw().get(0);
        }
        
        if (jarFilePath != null && !jarFilePath.isEmpty() && !jarFilePath.equals(".")) {
            File jarFile = new File(jarFilePath);
            if (jarFile.exists()) {
                // Load JAR immediately, using Platform.runLater to ensure UI thread
                javafx.application.Platform.runLater(() -> {
                    loadJarFile(jarFile);
                });
            } else {
                String errorMsg = "Error: JAR file not found at " + jarFilePath;
                architectureView.setStatus(errorMsg);
            }
        } else {
            // Initialize with empty view
            architectureView.setStatus("Ready to analyze bytecode. Click 'Load JAR' to begin.");
        }
    }

    /**
     * Load the original ArchitectureView layout.
     */
    private void loadOldLayout(BorderPane root) {
        architectureView = new ArchitectureView(primaryStage);
        architectureView.setOnFileSelected(this::loadJarFile);
        root.setCenter(architectureView);
    }



    private VBox createHeader() {
        VBox header = new VBox(10);
        header.setPadding(new Insets(15));
        header.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");

        // Title area
        Label titleLabel = new Label("S202 Code Analyzer");
        titleLabel.setStyle("-fx-font-size: 18; -fx-font-weight: bold;");

        Label descriptionLabel = new Label(
            "Analyze Java bytecode dependencies and visualize architecture structure with automatic cycle detection"
        );
        descriptionLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");

        header.getChildren().addAll(titleLabel, descriptionLabel);
        return header;
    }

    /**
     * Loads a JAR file and updates the architecture view using the new pipeline:
     * InputAnalyzer -> LevelCalculator -> ArchitectureNodeBuilder
     */
    public void loadJarFile(File jarFile) {
        if (jarFile == null) return;

        try {
            if (architectureView != null) {
                architectureView.setStatus("Analyzing: " + jarFile.getName() + " (this may take a moment)...");
            }

            // Step 1: Raw analysis - extract classes and dependencies from bytecode
            DependencyModel rawModel = rawAnalyzer.analyze(jarFile.getAbsolutePath());
            System.err.println("[AnalyzerApplication] rawModel packages: " + rawModel.getAllPackageNames());
            
            if (rawModel.getAllClasses().isEmpty()) {
                String errorMsg = "Error: No classes found in JAR file";
                if (architectureView != null) {
                    architectureView.setStatus(errorMsg);
                }
                showErrorDialog("No Classes Found", "The JAR file does not contain any .class files");
                return;
            }

            // Step 2: Calculate architectural levels based on dependency topology
            DomainModel calculatedModel = levelCalculator.calculate(rawModel);

            // Step 3: Build architecture node tree
            ArchitectureNode rootNode = architectureNodeBuilder.build(calculatedModel);

            // Step 4: Display in the UI
            if (architectureView != null) {
                architectureView.setArchitectureRoot(rootNode);
                
                String message = String.format(
                    "Loaded %d classes | %d levels | Max level %d",
                    rawModel.getAllClasses().size(),
                    rootNode.getLevelCount(),
                    rootNode.getMaxLevel()
                );
                architectureView.setStatus(message);
            }

        } catch (Exception e) {
            String errorMsg = "Error: " + e.getMessage();
            if (architectureView != null) {
                architectureView.setStatus(errorMsg);
            }
            showErrorDialog("Analysis Error", "Failed to analyze JAR file:\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shows an error dialog to the user.
     */
    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
