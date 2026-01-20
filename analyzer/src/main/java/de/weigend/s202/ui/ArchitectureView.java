package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.ui.model.UIModel;
import de.weigend.s202.ui.newlayout.LevelPackageBox;
import de.weigend.s202.ui.newlayout.LevelClassBox;
import de.weigend.s202.analysis.scc.EdgeClassification.ClassifiedEdge;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.Objects;

/**
 * Main UI component for displaying the architecture graph.
 */
public class ArchitectureView extends BorderPane {
    private ScrollPane scrollPane;
    private Label statusLabel;
    private Spinner<Integer> depthSpinner;
    private Stage parentStage;
    private java.util.function.Consumer<File> onFileSelected;

    public ArchitectureView(Stage parentStage) {
        this.parentStage = Objects.requireNonNull(parentStage, "parentStage cannot be null");
        setupUI();
    }

    private void setupUI() {
        // Top toolbar
        HBox toolbar = createToolbar();
        setTop(toolbar);

        // Center: ScrollPane with LevelPackageBox (hierarchical with levels)
        scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setPrefHeight(600);
        
        // Load demo content
        addTestContent();
        
        setCenter(scrollPane);

        // Bottom: Status bar
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-padding: 5; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        setBottom(statusLabel);
    }
    
    /**
     * Populate the view with demo content (test LevelPackageBox hierarchy).
     */
    private void addTestContent() {
        // Create root package container
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

    private HBox createToolbar() {
        HBox toolbar = new HBox(10);
        toolbar.setPadding(new Insets(10));
        toolbar.setStyle("-fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");

        Button loadButton = new Button("📂 Load JAR");
        loadButton.setStyle("-fx-font-size: 11;");
        loadButton.setOnAction(e -> openFileChooser());

        Label depthLabel = new Label("Auto-Expand Depth:");
        depthSpinner = new Spinner<>(1, 10, 3);
        depthSpinner.setPrefWidth(80);

        Button refreshButton = new Button("🔄 Refresh");
        refreshButton.setStyle("-fx-font-size: 11;");
        refreshButton.setOnAction(e -> {
            String depth = String.valueOf(depthSpinner.getValue());
            setStatus("Analyzing with depth: " + depth);
        });

        Separator separator = new Separator();
        separator.setStyle("-fx-padding: 0 5; -fx-opacity: 0.3;");

        toolbar.getChildren().addAll(
            loadButton, new Separator(), depthLabel, depthSpinner, refreshButton
        );

        return toolbar;
    }

    /**
     * Opens a file chooser for selecting JAR files.
     */
    private void openFileChooser() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select JAR File to Analyze");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File selectedFile = fileChooser.showOpenDialog(parentStage);
        if (selectedFile != null && onFileSelected != null) {
            onFileSelected.accept(selectedFile);
        }
    }

    /**
     * Sets callback when a JAR file is selected.
     */
    public void setOnFileSelected(java.util.function.Consumer<File> callback) {
        this.onFileSelected = Objects.requireNonNull(callback, "callback cannot be null");
    }

    /**
     * Sets the UIModel for level-based layout display.
     * Populates the ScrollPane with a LevelPackageBox hierarchy based on UIModel data.
     * This is the modern way to display the architecture analysis.
     */
    public void setUIModel(UIModel uiModel) {
        Objects.requireNonNull(uiModel, "uiModel cannot be null");
        
        // Create root package container
        LevelPackageBox rootLevel = new LevelPackageBox("Root Package");
        
        // Map to store package containers by full name for hierarchical organization
        java.util.Map<String, LevelPackageBox> packageContainers = new java.util.HashMap<>();
        packageContainers.put("", rootLevel);
        
        // Set to track which elements have been added to their parents
        // This prevents duplicates when an element appears in multiple levels
        java.util.Set<String> elementsAddedToParent = new java.util.HashSet<>();
        
        // Iterate through levels and add elements
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            for (UIModel.UIElementInfo element : uiModel.getElementsAtLevel(level)) {
                // Determine parent package
                String parentPackage = getParentPackage(element.fullName);
                if (parentPackage == null) {
                    parentPackage = "";
                }
                
                // Ensure all parent packages exist in the hierarchy
                ensurePackageHierarchy(parentPackage, packageContainers, rootLevel, element.level);
                
                // Get parent container (should now exist)
                LevelPackageBox parentContainer = packageContainers.get(parentPackage);
                if (parentContainer == null) {
                    parentContainer = rootLevel;
                }
                
                // Only add element if not already added (prevents duplicates)
                if (elementsAddedToParent.contains(element.fullName)) {
                    continue;
                }
                
                // Create appropriate element based on type
                if ("PACKAGE".equals(element.type)) {
                    // Create package container only if not already created
                    if (!packageContainers.containsKey(element.fullName)) {
                        LevelPackageBox packageBox = new LevelPackageBox(element.simpleName, element.level);
                        packageContainers.put(element.fullName, packageBox);
                        parentContainer.addToLevel(level + 1, packageBox);
                    }
                } else if ("CLASS".equals(element.type)) {
                    // Create class element
                    LevelClassBox classBox = new LevelClassBox(element.simpleName, element.level);
                    parentContainer.addToLevel(level + 1, classBox);
                }
                
                // Mark element as added
                elementsAddedToParent.add(element.fullName);
            }
        }
        
        // Replace content with populated hierarchy
        scrollPane.setContent(rootLevel);
        
        setStatus("Architecture loaded: " + uiModel.getLevelCount() + " levels");
    }
    
    /**
     * Ensures that all parent packages in a hierarchy exist.
     * Creates missing package containers as needed.
     * @param packageName The package name to ensure exists
     * @param packageContainers Map of existing containers
     * @param rootLevel The root package box
     * @param elementLevel The level of the element that owns this package
     */
    private void ensurePackageHierarchy(String packageName, 
                                       java.util.Map<String, LevelPackageBox> packageContainers,
                                       LevelPackageBox rootLevel,
                                       int elementLevel) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        
        if (packageContainers.containsKey(packageName)) {
            return; // Already exists
        }
        
        // Split the package into parts
        String[] parts = packageName.split("\\.");
        String currentPkg = "";
        
        for (String part : parts) {
            String previousPkg = currentPkg;
            currentPkg = currentPkg.isEmpty() ? part : currentPkg + "." + part;
            
            if (!packageContainers.containsKey(currentPkg)) {
                // Create missing package container with the same level as the element
                LevelPackageBox packageBox = new LevelPackageBox(part, elementLevel);
                packageContainers.put(currentPkg, packageBox);
                
                // Add to parent
                LevelPackageBox parentContainer = packageContainers.get(previousPkg);
                if (parentContainer != null) {
                    // Use level 1 for created packages (they should appear at top)
                    parentContainer.addToLevel(1, packageBox);
                }
            }
        }
    }
    
    /**
     * Extract parent package name from a fully qualified name.
     */
    private String getParentPackage(String fullName) {
        if (!fullName.contains(".")) return "";
        
        int lastDot = fullName.lastIndexOf('.');
        return fullName.substring(0, lastDot);
    }

    /**
     * Sets the root node of the architecture graph.
     * Uses the modern analysis pipeline (levels already calculated by LevelCalculator).
     * For now, still shows demo content - future integration with UIModel.
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        // TODO: Convert ArchitectureNode tree to LevelPackageBox hierarchy
        // For now, keep showing demo content
        setStatus("Architecture loaded: " + rootNode.getSimpleName());
    }

    /**
     * Updates the status bar message.
     */
    public void setStatus(String message) {
        statusLabel.setText(Objects.requireNonNull(message, "message cannot be null"));
    }

    /**
     * Returns the selected architecture node.
     */
    public ArchitectureNode getSelectedNode() {
        return null; // Graph view doesn't have selection yet
    }

    /**
     * Returns the current auto-expand depth setting.
     */
    public int getAutoExpandDepth() {
        return depthSpinner.getValue();
    }

    /**
     * Sets the auto-expand depth.
     */
    public void setAutoExpandDepth(int depth) {
        if (depth < 1 || depth > 10) {
            throw new IllegalArgumentException("Depth must be between 1 and 10");
        }
        depthSpinner.getValueFactory().setValue(depth);
    }
}

