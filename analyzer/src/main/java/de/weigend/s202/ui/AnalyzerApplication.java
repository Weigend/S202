package de.weigend.s202.ui;

import de.weigend.s202.analysis.ArchitectureModelBuilder;
import de.weigend.s202.analysis.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.analysis.calculated.CalculatedModel;
import de.weigend.s202.analysis.calculated.LevelCalculator;
import de.weigend.s202.analysis.raw.DependencyModel;
import de.weigend.s202.analysis.raw.RawAnalyzer;
import de.weigend.s202.analysis.ui.UIModel;
import de.weigend.s202.analysis.ui.UIModelBuilder;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main application entry point for the S202 Code Analyzer.
 */
public class AnalyzerApplication extends Application {
    private ArchitectureView architectureView;
    private RawAnalyzer rawAnalyzer;
    private LevelCalculator levelCalculator;
    private UIModelBuilder uiModelBuilder;
    private ArchitectureModelBuilder architectureModelBuilder;
    private Stage primaryStage;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.rawAnalyzer = new RawAnalyzer();
        this.levelCalculator = new LevelCalculator();
        this.uiModelBuilder = new UIModelBuilder();
        this.architectureModelBuilder = new ArchitectureModelBuilder();

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
     * Loads a JAR file and updates the architecture view using the new pipeline:
     * RawAnalyzer -> LevelCalculator -> UIModelBuilder -> ArchitectureModelBuilder
     */
    public void loadJarFile(File jarFile) {
        if (jarFile == null) return;

        try {
            architectureView.setStatus("Analyzing: " + jarFile.getName() + " (this may take a moment)...");

            // Step 1: Raw analysis - extract classes and dependencies from bytecode
            DependencyModel rawModel = rawAnalyzer.analyze(jarFile.getAbsolutePath());
            
            if (rawModel.getAllClasses().isEmpty()) {
                architectureView.setStatus("Error: No classes found in JAR file");
                showErrorDialog("No Classes Found", "The JAR file does not contain any .class files");
                return;
            }

            // Step 2: Calculate architectural levels based on dependency topology
            CalculatedModel calculatedModel = levelCalculator.calculate(rawModel);

            // Step 3: Build UI model (organize by levels)
            UIModel uiModel = uiModelBuilder.build(calculatedModel);

            // Step 4: Build architecture node tree for visualization
            // We still use ArchitectureModelBuilder for hierarchical tree structure
            ArchitectureNode model = buildArchitectureNodeFromUIModel(uiModel, calculatedModel);

            // Display in UI
            architectureView.setArchitectureRoot(model);
            
            String message = String.format(
                "Loaded %d classes | %d levels | Max level %d",
                rawModel.getAllClasses().size(),
                uiModel.getLevelCount(),
                uiModel.getMaxLevel()
            );
            architectureView.setStatus(message);

        } catch (Exception e) {
            architectureView.setStatus("Error: " + e.getMessage());
            showErrorDialog("Analysis Error", "Failed to analyze JAR file:\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Converts UIModel back into ArchitectureNode tree structure for TreeView visualization.
     * This maintains compatibility with PackageTreeView while using the new analysis pipeline.
     */
    private ArchitectureNode buildArchitectureNodeFromUIModel(UIModel uiModel, CalculatedModel calculatedModel) {
        // Group elements by package hierarchy
        Map<String, List<UIModel.UIElementInfo>> elementsByPackage = new HashMap<>();
        
        // Collect all packages and classes
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            for (UIModel.UIElementInfo element : uiModel.getElementsAtLevel(level)) {
                String parentPackage = getParentPackage(element.fullName);
                if (parentPackage == null) {
                    parentPackage = "root";
                }
                elementsByPackage.computeIfAbsent(parentPackage, k -> new ArrayList<>()).add(element);
            }
        }

        // Build root node from the deepest common package
        String rootPackageName = findCommonRootPackage(elementsByPackage.keySet());
        ArchitectureNode rootNode = new ArchitectureNode(
            rootPackageName,
            rootPackageName.isEmpty() ? "root" : rootPackageName.substring(rootPackageName.lastIndexOf('.') + 1),
            ArchitectureModelBuilder.NodeType.PACKAGE,
            true
        );

        // Recursively build package hierarchy
        buildPackageHierarchy(rootNode, elementsByPackage, rootPackageName, calculatedModel);

        return rootNode;
    }

    /**
     * Extract parent package name from a fully qualified name.
     */
    private String getParentPackage(String fullName) {
        if (!fullName.contains(".")) return null;
        
        int lastDot = fullName.lastIndexOf('.');
        if (lastDot <= 0) return null;
        
        return fullName.substring(0, lastDot);
    }

    /**
     * Find the common root package from all package names.
     */
    private String findCommonRootPackage(Set<String> packageNames) {
        if (packageNames.isEmpty()) return "root";

        // Filter out the artificial "root" package to avoid duplication
        List<String> packages = new ArrayList<>();
        for (String pkg : packageNames) {
            if (!"root".equals(pkg)) {
                packages.add(pkg);
            }
        }
        
        if (packages.isEmpty()) return "root";
        String root = packages.get(0);
        
        for (String pkg : packages) {
            while (!pkg.startsWith(root) && !root.isEmpty()) {
                int lastDot = root.lastIndexOf('.');
                root = lastDot > 0 ? root.substring(0, lastDot) : "";
            }
        }
        
        return root;
    }

    /**
     * Recursively builds package hierarchy tree from UIModel elements.
     */
    private void buildPackageHierarchy(ArchitectureNode parentNode, 
                                      Map<String, List<UIModel.UIElementInfo>> elementsByPackage,
                                      String currentPackage,
                                      CalculatedModel calculatedModel) {
        // Add only CLASSES (not packages) as direct children of this package
        List<UIModel.UIElementInfo> directElements = elementsByPackage.get(currentPackage);
        if (directElements != null) {
            for (UIModel.UIElementInfo element : directElements) {
                // Only add CLASS elements; packages are handled separately as subpackages
                if ("CLASS".equals(element.type)) {
                    ArchitectureNode childNode = new ArchitectureNode(
                        element.fullName,
                        element.simpleName,
                        ArchitectureModelBuilder.NodeType.CLASS,
                        false
                    );
                    
                    // Set dependencies
                    childNode.setDependencies(element.dependencies);
                    parentNode.addChild(childNode);
                }
            }
        }

        // Find and add subpackages
        String packagePrefix = currentPackage.isEmpty() ? "" : currentPackage + ".";
        Set<String> subpackages = new HashSet<>();
        
        for (String pkg : elementsByPackage.keySet()) {
            // Skip the artificial "root" package
            if ("root".equals(pkg)) continue;
            
            if (pkg.startsWith(packagePrefix) && !pkg.equals(currentPackage)) {
                String relativePkg = pkg.substring(packagePrefix.length());
                if (!relativePkg.contains(".")) {
                    subpackages.add(pkg);
                }
            }
        }

        // Recursively process subpackages
        for (String subpkg : subpackages) {
            String subpkgSimpleName = subpkg.substring(subpkg.lastIndexOf('.') + 1);
            ArchitectureNode subpkgNode = new ArchitectureNode(
                subpkg,
                subpkgSimpleName,
                ArchitectureModelBuilder.NodeType.PACKAGE,
                true
            );
            parentNode.addChild(subpkgNode);
            buildPackageHierarchy(subpkgNode, elementsByPackage, subpkg, calculatedModel);
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
