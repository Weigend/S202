package de.weigend.s202.ui;

import de.weigend.s202.analysis.ArchitectureModelBuilder;
import de.weigend.s202.analysis.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.analysis.DependencyGraphBuilder;
import de.weigend.s202.io.JarLoader;
import de.weigend.s202.model.JavaClass;
import de.weigend.s202.model.JavaPackage;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.Map;

/**
 * Main application entry point for the S202 Code Analyzer.
 */
public class AnalyzerApplication extends Application {
    private ArchitectureView architectureView;
    private JarLoader jarLoader;
    private Stage primaryStage;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.jarLoader = new JarLoader();

        primaryStage.setTitle("S202 Code Analyzer - Architecture Viewer");

        // Create main container
        BorderPane root = new BorderPane();

        // Create header
        VBox header = createHeader();
        root.setTop(header);

        // Create architecture view
        architectureView = new ArchitectureView(primaryStage);
        
        // Set callback for when user selects a JAR file
        architectureView.setOnFileSelected(this::loadJarFile);
        
        root.setCenter(architectureView);

        // Create scene
        Scene scene = new Scene(root, 1200, 800);
        primaryStage.setScene(scene);

        // Show stage
        primaryStage.show();

        // Initialize with empty view
        architectureView.setStatus("Ready to analyze bytecode. Click 'Load JAR' to begin.");
    }

    private VBox createHeader() {
        VBox header = new VBox(10);
        header.setPadding(new Insets(15));
        header.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");

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
     * Loads a JAR file and updates the architecture view.
     */
    public void loadJarFile(File jarFile) {
        if (jarFile == null) return;

        try {
            architectureView.setStatus("Analyzing: " + jarFile.getName() + " (this may take a moment)...");

            // Load classes from JAR
            Map<String, JavaClass> classes = jarLoader.loadJar(jarFile);
            
            if (classes.isEmpty()) {
                architectureView.setStatus("Error: No classes found in JAR file");
                showErrorDialog("No Classes Found", "The JAR file does not contain any .class files");
                return;
            }

            // Find root package
            String rootPackage = findRootPackage(classes.keySet());
            System.out.println("DEBUG: Found root package: '" + rootPackage + "'");
            if (rootPackage.isEmpty()) {
                System.out.println("ERROR: Root package is empty!");
                return;
            }

            // Build dependency graph
            DependencyGraphBuilder graphBuilder = new DependencyGraphBuilder();
            for (JavaClass javaClass : classes.values()) {
                graphBuilder.addClass(javaClass);
            }

            JavaPackage rootPkg = graphBuilder.buildPackageHierarchy(rootPackage);

            // Detect cycles
            var cycles = graphBuilder.detectCycles(rootPkg);

            // Build architecture model
            ArchitectureModelBuilder modelBuilder = new ArchitectureModelBuilder();
            ArchitectureNode model = modelBuilder.buildModel(rootPkg, architectureView.getAutoExpandDepth());

            // Display in UI
            architectureView.setArchitectureRoot(model);
            
            String message = String.format(
                "Loaded %d classes | %d packages | %d cycles detected",
                classes.size(),
                countPackages(model),
                cycles.size()
            );
            architectureView.setStatus(message);

        } catch (Exception e) {
            architectureView.setStatus("Error: " + e.getMessage());
            showErrorDialog("Analysis Error", "Failed to analyze JAR file:\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Finds the root package from a set of class names.
     */
    private String findRootPackage(java.util.Set<String> classNames) {
        if (classNames.isEmpty()) return "";

        // Extract direct package names from class names
        Set<String> directPackages = new java.util.HashSet<>();
        for (String className : classNames) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                directPackages.add(className.substring(0, lastDot));
            }
        }
        
        if (directPackages.isEmpty()) return "";
        
        // Convert to sorted list for consistent processing
        List<String> packages = new ArrayList<>(directPackages);
        java.util.Collections.sort(packages);
        
        // Start with the first package
        String candidate = packages.get(0);
        
        // Find the longest prefix that all packages start with
        while (!candidate.isEmpty()) {
            String finalCandidate = candidate;
            if (packages.stream().allMatch(p -> p.startsWith(finalCandidate))) {
                return candidate;
            }
            // Remove the last component and try again
            int lastDot = candidate.lastIndexOf('.');
            candidate = lastDot > 0 ? candidate.substring(0, lastDot) : "";
        }
        
        return "";
    }

    /**
     * Counts total packages in the architecture tree.
     */
    private int countPackages(ArchitectureNode node) {
        int count = node.getType().toString().equals("PACKAGE") ? 1 : 0;
        for (ArchitectureNode child : node.getChildren()) {
            count += countPackages(child);
        }
        return count;
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
