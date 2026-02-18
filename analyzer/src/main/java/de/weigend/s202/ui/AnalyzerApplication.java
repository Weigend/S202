package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.model.DistrictRowLevelCalculator;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

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
        HBox header = createHeader();
        root.setTop(header);

        // Load original layout by default
        loadOldLayout(root);

        Scene scene = new Scene(root, 1200, 800);
        
        // Load global CSS styles
        String css = getClass().getResource("/de/weigend/s202/ui/styles.css").toExternalForm();
        scene.getStylesheets().add(css);
        
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
                    loadJarFiles(List.of(jarFile));
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
        architectureView.setOnFilesSelected(this::loadJarFiles);
        root.setCenter(architectureView);
        root.setBottom(architectureView.getStatusBar());
    }



    private HBox createHeader() {
        HBox header = new HBox(12);
        header.setPadding(new Insets(8, 15, 8, 15));
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #2b3e50;");

        Label titleLabel = new Label("S202 Code Analyzer");
        titleLabel.setStyle("-fx-font-size: 16; -fx-font-weight: bold; -fx-text-fill: white;");

        Label separator = new Label("\u2014");
        separator.setStyle("-fx-text-fill: #7a9bb5;");

        Label descriptionLabel = new Label(
            "Analyze Java bytecode dependencies and visualize architecture structure with automatic cycle detection"
        );
        descriptionLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #c0d0dd;");

        header.getChildren().addAll(titleLabel, separator, descriptionLabel);
        return header;
    }

    /**
     * Loads one or more JAR files and updates the architecture view using the new pipeline:
     * InputAnalyzer -> LevelCalculator -> ArchitectureNodeBuilder
     */
    public void loadJarFiles(List<File> jarFiles) {
        if (jarFiles == null || jarFiles.isEmpty()) return;

        try {
            String fileNames = jarFiles.stream()
                .map(File::getName)
                .collect(Collectors.joining(", "));
            
            if (architectureView != null) {
                architectureView.setStatus("Analyzing: " + fileNames + " (this may take a moment)...");
            }

            // Step 1: Raw analysis - extract classes and dependencies from bytecode
            List<String> jarPaths = jarFiles.stream()
                .map(File::getAbsolutePath)
                .collect(Collectors.toList());
            DependencyModel rawModel = rawAnalyzer.analyzeMultiple(jarPaths);
            
            if (rawModel.getAllClasses().isEmpty()) {
                String errorMsg = "Error: No classes found in JAR file(s)";
                if (architectureView != null) {
                    architectureView.setStatus(errorMsg);
                }
                showErrorDialog("No Classes Found", "The JAR file(s) do not contain any .class files");
                return;
            }

            // Step 2: Calculate architectural levels based on dependency topology
            DomainModel calculatedModel = levelCalculator.calculate(rawModel);

            // Step 3: Build architecture node tree
            ArchitectureNode rootNode = architectureNodeBuilder.build(calculatedModel);

            // Step 3b: Assign district row-levels based on inter-package dependency direction
            new DistrictRowLevelCalculator().assignDistrictRowLevels(rootNode);

            // Step 4: Display in the UI
            if (architectureView != null) {
                architectureView.setArchitectureRoot(rootNode);
                
                String message = String.format(
                    "Loaded %d JAR(s) | %d classes | %d levels | Max level %d",
                    jarFiles.size(),
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
            showErrorDialog("Analysis Error", "Failed to analyze JAR file(s):\n" + e.getMessage());
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
