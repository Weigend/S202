package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Main UI component for displaying the architecture graph.
 */
public class ArchitectureView extends BorderPane {
    private ScrollPane scrollPane;
    private Label statusLabel;
    private Spinner<Integer> depthSpinner;
    private Stage parentStage;
    private java.util.function.Consumer<List<File>> onFilesSelected;
    private CheckBox showDependenciesCheckbox;
    private Pane dependencyPane;
    private StackPane contentPane;
    private ArchitectureNode currentRootNode;
    private Map<String, javafx.scene.Node> elementRegistry = new HashMap<>();
    private List<Line> dependencyLines = new ArrayList<>();
    private Line selectedLine = null;
    private static final Color OUTGOING_DEPENDENCY_COLOR = Color.rgb(64, 64, 64); // Anthrazit - ausgehende Abhängigkeiten
    private static final Color INCOMING_DEPENDENCY_COLOR = Color.rgb(0, 128, 0); // Grün - eingehende Abhängigkeiten (dependents)
    private static final double DEPENDENCY_WIDTH = 1.0;

    public ArchitectureView(Stage parentStage) {
        this.parentStage = Objects.requireNonNull(parentStage, "parentStage cannot be null");
        setupUI();
    }

    private void setupUI() {
        // Top toolbar
        HBox toolbar = createToolbar();
        setTop(toolbar);

        // Center: StackPane containing ScrollPane and Pane overlay for dependency arrows
        scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setPrefHeight(600);
        
        // Pane for drawing dependency arrows (on top of scroll content)
        dependencyPane = new Pane();
        dependencyPane.setMouseTransparent(false); // Allow mouse events on lines
        dependencyPane.setPickOnBounds(false); // Only pick on actual shapes
        
        // Wrap in StackPane to layer pane on top
        contentPane = new StackPane();
        contentPane.getChildren().addAll(scrollPane, dependencyPane);
        
        // Resize pane when content changes
        scrollPane.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            dependencyPane.setPrefWidth(newVal.getWidth());
            dependencyPane.setPrefHeight(newVal.getHeight());
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
                drawDependencyArrows();
            }
        });
        
        scrollPane.hvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
                drawDependencyArrows();
            }
        });
        
        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
                drawDependencyArrows();
            }
        });

        setCenter(contentPane);

        // Bottom: Status bar
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-bar");
        setBottom(statusLabel);
    }


    private HBox createToolbar() {
        HBox toolbar = new HBox(8);
        toolbar.setPadding(new Insets(5));
        toolbar.getStyleClass().add("tool-bar");

        Button loadButton = new Button("📂 Load JAR");
        loadButton.setOnAction(e -> openFileChooser());

        Label depthLabel = new Label("Depth:");
        depthSpinner = new Spinner<>(1, 10, 3);
        depthSpinner.setPrefWidth(55);

        Button refreshButton = new Button("🔄 Refresh");
        refreshButton.setOnAction(e -> {
            String depth = String.valueOf(depthSpinner.getValue());
            setStatus("Analyzing with depth: " + depth);
        });

        // Checkbox to show/hide dependency arrows
        showDependenciesCheckbox = new CheckBox("Show Dependencies");
        showDependenciesCheckbox.setOnAction(e -> {
            if (showDependenciesCheckbox.isSelected()) {
                drawDependencyArrows();
            } else {
                clearDependencyArrows();
            }
        });

        toolbar.getChildren().addAll(
            loadButton, new Separator(), depthLabel, depthSpinner, refreshButton,
            new Separator(), showDependenciesCheckbox
        );

        return toolbar;
    }

    /**
     * Opens a file chooser for selecting one or more JAR files.
     */
    private void openFileChooser() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select JAR File(s) to Analyze");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(parentStage);
        if (selectedFiles != null && !selectedFiles.isEmpty() && onFilesSelected != null) {
            onFilesSelected.accept(selectedFiles);
        }
    }

    /**
     * Sets callback when one or more JAR files are selected.
     */
    public void setOnFilesSelected(java.util.function.Consumer<List<File>> callback) {
        this.onFilesSelected = Objects.requireNonNull(callback, "callback cannot be null");
    }

    /**
     * Sets the ArchitectureNode root for level-based layout display.
     * Populates the ScrollPane with a LevelPackageBox hierarchy.
     * The synthetic "root" node is hidden - only its children are displayed.
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        
        // Store for dependency drawing
        this.currentRootNode = rootNode;
        this.elementRegistry.clear();
        
        // Set callback to refresh dependency arrows when packages are expanded/collapsed
        LevelPackageBox.setOnExpandChangeCallback(() -> {
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
                drawDependencyArrows();
            }
        });
        
        // Set callback to refresh dependency arrows when a class is selected/deselected
        LevelClassBox.setOnSelectionChangeCallback(() -> {
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
                drawDependencyArrows();
            }
        });
        
        // Map to store package containers by full name for hierarchical organization
        java.util.Map<String, LevelPackageBox> packageContainers = new java.util.HashMap<>();
        
        // Set to track which elements have been added to their parents
        java.util.Set<String> elementsAddedToParent = new java.util.HashSet<>();
        
        // Container for top-level packages (children of root)
        javafx.scene.layout.VBox topLevelContainer = new javafx.scene.layout.VBox(8);
        topLevelContainer.setPadding(new Insets(10));
        
        // Check if top-level packages should be transparent (only one top-level package)
        boolean topLevelTransparent = shouldChildrenBeTransparent(rootNode);
        
        // Process children of root directly (skip the root node itself)
        for (ArchitectureNode child : rootNode.getChildren()) {
            if (child.getType() == NodeType.PACKAGE) {
                // Create top-level package box
                LevelPackageBox packageBox = new LevelPackageBox(child.getSimpleName(), child.getLevel(), topLevelTransparent);
                packageContainers.put(child.getFullName(), packageBox);
                elementRegistry.put(child.getFullName(), packageBox);
                topLevelContainer.getChildren().add(packageBox);
                
                // Recursively process children
                processArchitectureNode(child, packageContainers, packageBox, elementsAddedToParent, rootNode);
            } else if (child.getType() == NodeType.CLASS) {
                // Top-level class (rare but possible)
                LevelClassBox classBox = new LevelClassBox(child.getSimpleName(), child.getLevel(), child.getFullName());
                elementRegistry.put(child.getFullName(), classBox);
                topLevelContainer.getChildren().add(classBox);
            }
            elementsAddedToParent.add(child.getFullName());
        }
        
        // Replace content with populated hierarchy
        scrollPane.setContent(topLevelContainer);
        
        // Clear dependency arrows
        clearDependencyArrows();
        
        setStatus("Architecture loaded: " + rootNode.getLevelCount() + " levels");
    }
    
    /**
     * Checks if a package should be displayed as transparent.
     * A package is transparent if it is the ONLY sub-package of its parent.
     * This visually de-emphasizes "pass-through" packages like de.weigend.s202.
     */
    private boolean shouldChildrenBeTransparent(ArchitectureNode parentNode) {
        // Count how many sub-packages the parent has
        long packageCount = parentNode.getChildren().stream()
            .filter(c -> c.getType() == NodeType.PACKAGE)
            .count();
        // Children are transparent only if there's exactly one sub-package
        return packageCount == 1;
    }
    
    /**
     * Recursively processes an ArchitectureNode and its children to build the UI hierarchy.
     */
    private void processArchitectureNode(ArchitectureNode node,
                                        java.util.Map<String, LevelPackageBox> packageContainers,
                                        LevelPackageBox rootLevel,
                                        java.util.Set<String> elementsAddedToParent,
                                        ArchitectureNode archRoot) {
        // Check if child packages should be transparent (node has only one sub-package)
        boolean childrenTransparent = shouldChildrenBeTransparent(node);
        
        for (ArchitectureNode child : node.getChildren()) {
            // Skip if already processed
            if (elementsAddedToParent.contains(child.getFullName())) {
                continue;
            }
            
            // Determine parent package
            String parentPackage = getParentPackage(child.getFullName());
            if (parentPackage == null) {
                parentPackage = "";
            }
            
            // Ensure parent hierarchy exists
            ensurePackageHierarchy(parentPackage, packageContainers, rootLevel, archRoot);
            
            // Get parent container
            LevelPackageBox parentContainer = packageContainers.get(parentPackage);
            if (parentContainer == null) {
                parentContainer = rootLevel;
            }
            
            if (child.getType() == NodeType.PACKAGE) {
                // Create package container if not already created
                if (!packageContainers.containsKey(child.getFullName())) {
                    LevelPackageBox packageBox = new LevelPackageBox(child.getSimpleName(), child.getLevel(), childrenTransparent);
                    packageContainers.put(child.getFullName(), packageBox);
                    elementRegistry.put(child.getFullName(), packageBox);
                    parentContainer.addToLevel(child.getLevel(), packageBox);
                }
                // Recursively process children
                processArchitectureNode(child, packageContainers, rootLevel, elementsAddedToParent, archRoot);
            } else if (child.getType() == NodeType.CLASS) {
                // Create class element
                LevelClassBox classBox = new LevelClassBox(child.getSimpleName(), child.getLevel(), child.getFullName());
                elementRegistry.put(child.getFullName(), classBox);
                parentContainer.addToLevel(child.getLevel(), classBox);
            }
            
            elementsAddedToParent.add(child.getFullName());
        }
    }
    
    /**
     * Ensures that all parent packages in a hierarchy exist.
     */
    private void ensurePackageHierarchy(String packageName, 
                                       java.util.Map<String, LevelPackageBox> packageContainers,
                                       LevelPackageBox rootLevel,
                                       ArchitectureNode rootNode) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        
        if (packageContainers.containsKey(packageName)) {
            return;
        }
        
        // Split the package into parts
        String[] parts = packageName.split("\\.");
        String currentPkg = "";
        
        for (String part : parts) {
            String previousPkg = currentPkg;
            currentPkg = currentPkg.isEmpty() ? part : currentPkg + "." + part;
            
            if (!packageContainers.containsKey(currentPkg)) {
                // Look up package level and check if transparent from architecture tree
                int packageLevel = findPackageLevelInTree(currentPkg, rootNode);
                
                // Find the parent node to determine if this package should be transparent
                ArchitectureNode parentNode = previousPkg.isEmpty() ? rootNode : findNodeInTree(previousPkg, rootNode);
                boolean isTransparent = parentNode != null && shouldChildrenBeTransparent(parentNode);
                
                LevelPackageBox packageBox = new LevelPackageBox(part, packageLevel, isTransparent);
                packageContainers.put(currentPkg, packageBox);
                
                // Add to parent at the correct architectural level
                LevelPackageBox parentContainer = packageContainers.get(previousPkg);
                if (parentContainer != null) {
                    parentContainer.addToLevel(packageLevel, packageBox);
                } else {
                    rootLevel.addToLevel(packageLevel, packageBox);
                }
            }
        }
    }
    
    /**
     * Find a node by full name in the architecture tree.
     */
    private ArchitectureNode findNodeInTree(String fullName, ArchitectureNode node) {
        if (node.getFullName().equals(fullName)) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findNodeInTree(fullName, child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
    
    /**
     * Look up a package's level in the architecture tree.
     */
    private int findPackageLevelInTree(String packageName, ArchitectureNode node) {
        if (node.getFullName().equals(packageName) && node.getType() == NodeType.PACKAGE) {
            return node.getLevel();
        }
        for (ArchitectureNode child : node.getChildren()) {
            int level = findPackageLevelInTree(packageName, child);
            if (level >= 0) {
                return level;
            }
        }
        return 0; // Default if not found
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
    
    /**
     * Clears all dependency arrows.
     */
    private void clearDependencyArrows() {
        if (dependencyPane != null) {
            dependencyPane.getChildren().clear();
            dependencyLines.clear();
            selectedLine = null;
        }
    }
    
    /**
     * Draws dependency arrows between visible elements.
     */
    private void drawDependencyArrows() {
        if (dependencyPane == null || currentRootNode == null) {
            return;
        }
        
        // Clear existing lines
        dependencyPane.getChildren().clear();
        dependencyLines.clear();
        selectedLine = null;
        
        // Iterate through all registered elements and draw arrows for their dependencies
        drawDependencyArrowsRecursive(currentRootNode);
    }
    
    /**
     * Recursively draws arrows for all visible CLASS nodes (not packages).
     * If a class is selected, only draws arrows to/from that class.
     */
    private void drawDependencyArrowsRecursive(ArchitectureNode node) {
        String selectedClass = LevelClassBox.getSelectedClassName();
        
        for (ArchitectureNode child : node.getChildren()) {
            // Only draw arrows for CLASS nodes, not packages
            if (child.getType() == ArchitectureNode.NodeType.CLASS) {
                // Get the UI element for this node
                javafx.scene.Node sourceElement = elementRegistry.get(child.getFullName());
                
                if (sourceElement != null && isNodeActuallyVisible(sourceElement)) {
                    // Draw arrows for each dependency
                    for (String depName : child.getDependencies()) {
                        // If a class is selected, only show dependencies involving that class
                        boolean isSourceSelected = selectedClass != null && child.getFullName().equals(selectedClass);
                        boolean isTargetSelected = selectedClass != null && depName.equals(selectedClass);
                        
                        if (selectedClass != null && !isSourceSelected && !isTargetSelected) {
                            continue; // Skip this dependency
                        }
                        
                        javafx.scene.Node targetElement = findBestTargetElement(depName);
                        
                        if (targetElement != null && isNodeActuallyVisible(targetElement)) {
                            // Determine arrow direction relative to selected class:
                            // - isSourceSelected = outgoing (selected class depends on target)
                            // - isTargetSelected = incoming (source depends on selected class)
                            boolean isIncoming = isTargetSelected && !isSourceSelected;
                            createDependencyLine(sourceElement, targetElement, child.getFullName(), depName, isIncoming);
                        }
                    }
                }
            }
            
            // Recurse into children (packages contain classes)
            drawDependencyArrowsRecursive(child);
        }
    }
    
    /**
     * Finds the target element for a dependency.
     * Only returns exact matches - no fallback to parent packages.
     */
    private javafx.scene.Node findBestTargetElement(String targetName) {
        // Only exact match - no parent package fallback
        javafx.scene.Node element = elementRegistry.get(targetName);
        if (element != null && isNodeActuallyVisible(element)) {
            return element;
        }
        return null;
    }
    
    /**
     * Checks if a node is actually visible (itself and all parents are visible).
     * A node is hidden if any parent container is collapsed (visible=false).
     */
    private boolean isNodeActuallyVisible(javafx.scene.Node node) {
        if (node == null) {
            return false;
        }
        
        // Check this node's visibility
        if (!node.isVisible()) {
            return false;
        }
        
        // Check all parents up to the scene root - any invisible parent means this node is hidden
        javafx.scene.Parent parent = node.getParent();
        while (parent != null) {
            if (!parent.isVisible()) {
                return false;
            }
            parent = parent.getParent();
        }
        
        return true;
    }
    
    /**
     * Checks if a node is visible within the current viewport.
     */
    private boolean isNodeVisibleInViewport(javafx.scene.Node node) {
        try {
            // First check if actually visible in hierarchy
            if (!isNodeActuallyVisible(node)) {
                return false;
            }
            
            Bounds boundsInScene = node.localToScene(node.getBoundsInLocal());
            Bounds viewportBounds = scrollPane.localToScene(scrollPane.getBoundsInLocal());
            return boundsInScene != null && viewportBounds != null && 
                   boundsInScene.intersects(viewportBounds);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Creates a selectable dependency line between source and target.
     * @param isIncoming true if this is an incoming dependency (someone depends on selected class),
     *                   false if outgoing (selected class depends on someone)
     */
    private void createDependencyLine(javafx.scene.Node source, javafx.scene.Node target, 
                                       String sourceName, String targetName, boolean isIncoming) {
        try {
            // Get bounds relative to the pane/viewport
            Bounds sourceBounds = source.localToScene(source.getBoundsInLocal());
            Bounds targetBounds = target.localToScene(target.getBoundsInLocal());
            Bounds paneBounds = dependencyPane.localToScene(dependencyPane.getBoundsInLocal());
            
            if (sourceBounds == null || targetBounds == null || paneBounds == null) {
                return;
            }
            
            // Calculate start point (center of source)
            double startX = sourceBounds.getMinX() + sourceBounds.getWidth() / 2 - paneBounds.getMinX();
            double startY = sourceBounds.getMinY() + sourceBounds.getHeight() / 2 - paneBounds.getMinY();
            
            // Calculate end point (center of target)
            double endX = targetBounds.getMinX() + targetBounds.getWidth() / 2 - paneBounds.getMinX();
            double endY = targetBounds.getMinY() + targetBounds.getHeight() / 2 - paneBounds.getMinY();
            
            // Choose color based on direction
            Color lineColor = isIncoming ? INCOMING_DEPENDENCY_COLOR : OUTGOING_DEPENDENCY_COLOR;
            
            // Create the line
            Line line = new Line(startX, startY, endX, endY);
            line.setStroke(lineColor);
            line.setStrokeWidth(DEPENDENCY_WIDTH);
            
            // Store original color for hover restore
            final Color originalColor = lineColor;
            
            // Make line easier to click by adding invisible thick stroke
            line.setOnMouseEntered(e -> {
                if (line != selectedLine) {
                    line.setStroke(Color.GRAY);
                }
                line.setCursor(javafx.scene.Cursor.HAND);
            });
            
            line.setOnMouseExited(e -> {
                if (line != selectedLine) {
                    line.setStroke(originalColor);
                }
                line.setCursor(javafx.scene.Cursor.DEFAULT);
            });
            
            // Click handler for selection
            line.setOnMouseClicked(e -> {
                selectLine(line, sourceName, targetName);
                e.consume();
            });
            
            // Create arrowhead lines
            double arrowSize = 4;
            double angle = Math.atan2(endY - startY, endX - startX);
            
            double x1 = endX - arrowSize * Math.cos(angle - Math.PI / 6);
            double y1 = endY - arrowSize * Math.sin(angle - Math.PI / 6);
            double x2 = endX - arrowSize * Math.cos(angle + Math.PI / 6);
            double y2 = endY - arrowSize * Math.sin(angle + Math.PI / 6);
            
            Line arrow1 = new Line(endX, endY, x1, y1);
            Line arrow2 = new Line(endX, endY, x2, y2);
            arrow1.setStroke(lineColor);
            arrow2.setStroke(lineColor);
            arrow1.setStrokeWidth(DEPENDENCY_WIDTH);
            arrow2.setStrokeWidth(DEPENDENCY_WIDTH);
            arrow1.setMouseTransparent(true);
            arrow2.setMouseTransparent(true);
            
            // Store reference to arrow lines and original color in main line's user data
            line.setUserData(new Object[]{arrow1, arrow2, lineColor});
            
            dependencyPane.getChildren().addAll(line, arrow1, arrow2);
            dependencyLines.add(line);
            
        } catch (Exception e) {
            // Ignore drawing errors for elements not in scene
        }
    }
    
    /**
     * Selects a dependency line and highlights it.
     */
    private void selectLine(Line line, String sourceName, String targetName) {
        // Deselect previous line
        if (selectedLine != null && selectedLine != line) {
            Object[] userData = (Object[]) selectedLine.getUserData();
            Color originalColor = (userData != null && userData.length > 2) ? (Color) userData[2] : OUTGOING_DEPENDENCY_COLOR;
            selectedLine.setStroke(originalColor);
            selectedLine.setStrokeWidth(DEPENDENCY_WIDTH);
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(originalColor);
                arrow2.setStroke(originalColor);
                arrow1.setStrokeWidth(DEPENDENCY_WIDTH);
                arrow2.setStrokeWidth(DEPENDENCY_WIDTH);
            }
        }
        
        // Toggle selection
        if (selectedLine == line) {
            // Deselect
            Object[] userData = (Object[]) line.getUserData();
            Color originalColor = (userData != null && userData.length > 2) ? (Color) userData[2] : OUTGOING_DEPENDENCY_COLOR;
            line.setStroke(originalColor);
            line.setStrokeWidth(DEPENDENCY_WIDTH);
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(originalColor);
                arrow2.setStroke(originalColor);
                arrow1.setStrokeWidth(DEPENDENCY_WIDTH);
                arrow2.setStrokeWidth(DEPENDENCY_WIDTH);
            }
            selectedLine = null;
            setStatus("Ready");
        } else {
            // Select
            line.setStroke(Color.RED);
            line.setStrokeWidth(2.0);
            Object[] userData = (Object[]) line.getUserData();
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(Color.RED);
                arrow2.setStroke(Color.RED);
                arrow1.setStrokeWidth(2.0);
                arrow2.setStrokeWidth(2.0);
            }
            selectedLine = line;
            
            // Show dependency info in status bar
            String simpleSource = sourceName.contains(".") ? 
                sourceName.substring(sourceName.lastIndexOf('.') + 1) : sourceName;
            String simpleTarget = targetName.contains(".") ? 
                targetName.substring(targetName.lastIndexOf('.') + 1) : targetName;
            setStatus("Dependency: " + simpleSource + " → " + simpleTarget);
        }
    }
}

