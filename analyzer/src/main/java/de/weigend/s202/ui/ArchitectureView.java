package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private CheckBox showSccCheckbox;
    private Pane dependencyPane;  // Container for dependency lines
    private Pane sccPane;          // Container for SCC lines
    private StackPane overlayPane; // Contains both dependency and SCC panes
    private StackPane contentPane;
    private ArchitectureNode currentRootNode;
    private Map<String, javafx.scene.Node> elementRegistry = new HashMap<>();
    private List<Line> dependencyLines = new ArrayList<>();
    private List<Line> sccLines = new ArrayList<>();
    private Line selectedLine = null;
    
    // Flags to track if lines have been drawn (for performance optimization)
    private boolean dependencyLinesDrawn = false;
    private boolean sccLinesDrawn = false;
    private boolean linesNeedUpdate = false;  // Set when zoom/scroll changes
    private static final Color OUTGOING_DEPENDENCY_COLOR = Color.rgb(64, 64, 64); // Anthrazit - ausgehende Abhängigkeiten
    private static final Color INCOMING_DEPENDENCY_COLOR = Color.rgb(0, 128, 0); // Grün - eingehende Abhängigkeiten (dependents)
    private static final Color SCC_COLOR = Color.RED; // Rot - zyklische Abhängigkeiten (SCCs)
    private static final double DEPENDENCY_WIDTH = 1.0;
    
    // Zoom-Funktionalität
    private double zoomFactor = 1.0;
    private static final double ZOOM_MIN = 0.02;  // 2% - für sehr große Architekturen wie Minecraft
    private static final double ZOOM_MAX = 3.0;
    private static final double ZOOM_STEP = 0.1;
    private Label zoomLabel;
    private javafx.scene.layout.Pane zoomableContent;  // StackPane containing content + overlay
    private javafx.scene.layout.VBox topLevelContainer; // The actual architecture content

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
        scrollPane.setFitToWidth(false);  // Disabled for zoom
        scrollPane.setFitToHeight(false);
        scrollPane.setPrefHeight(600);
        scrollPane.setPannable(true);  // Enable panning when zoomed out
        
        // Center content when smaller than viewport
        scrollPane.setStyle("-fx-background-color: transparent;");
        scrollPane.getStyleClass().add("centered-scroll-pane");
        
        // Zoom mit Mausrad (Ctrl+Scroll)
        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (event.isControlDown()) {
                event.consume();
                double delta = event.getDeltaY();
                if (delta > 0) {
                    zoomIn();
                } else if (delta < 0) {
                    zoomOut();
                }
            }
        });
        
        // Panes for drawing lines will be created in setArchitectureRoot
        // They are placed inside the scrollable content so they scroll with it
        dependencyPane = null;
        sccPane = null;
        overlayPane = null;
        
        // Content pane just contains the scroll pane
        contentPane = new StackPane();
        contentPane.getChildren().add(scrollPane);
        
        // Update centering wrapper when viewport changes
        scrollPane.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            // Update centering wrapper to fill viewport (for centering when zoomed out)
            if (scrollPane.getContent() instanceof StackPane wrapper) {
                wrapper.setMinWidth(newVal.getWidth());
                wrapper.setMinHeight(newVal.getHeight());
            }
        });
        
        // Note: Scrolling no longer invalidates lines since they are part of the content

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
                // Draw lines only if not already drawn or if invalidated
                if (!dependencyLinesDrawn || linesNeedUpdate) {
                    drawDependencyArrows();
                }
                dependencyPane.setVisible(true);
            } else {
                dependencyPane.setVisible(false);
            }
        });

        // Checkbox to show/hide SCC (cycle) lines
        showSccCheckbox = new CheckBox("Show SCC");
        showSccCheckbox.setOnAction(e -> {
            if (showSccCheckbox.isSelected()) {
                // Draw lines only if not already drawn or if invalidated
                if (!sccLinesDrawn || linesNeedUpdate) {
                    drawSccLines();
                }
                sccPane.setVisible(true);
            } else {
                sccPane.setVisible(false);
            }
        });

        // Zoom controls
        Button zoomInBtn = new Button("🔍+");
        zoomInBtn.setTooltip(new Tooltip("Zoom In (Ctrl+Scroll Up)"));
        zoomInBtn.setOnAction(e -> zoomIn());
        
        Button zoomOutBtn = new Button("🔍-");
        zoomOutBtn.setTooltip(new Tooltip("Zoom Out (Ctrl+Scroll Down)"));
        zoomOutBtn.setOnAction(e -> zoomOut());
        
        Button zoomResetBtn = new Button("1:1");
        zoomResetBtn.setTooltip(new Tooltip("Reset Zoom"));
        zoomResetBtn.setOnAction(e -> resetZoom());
        
        zoomLabel = new Label("100%");
        zoomLabel.setPrefWidth(45);
        zoomLabel.setStyle("-fx-font-family: monospace;");

        toolbar.getChildren().addAll(
            loadButton, new Separator(), depthLabel, depthSpinner, refreshButton,
            new Separator(), showDependenciesCheckbox, showSccCheckbox,
            new Separator(), zoomOutBtn, zoomLabel, zoomInBtn, zoomResetBtn
        );

        return toolbar;
    }
    
    // ==================== Zoom Methods ====================
    
    /**
     * Zooms in by dynamic step (larger steps at higher zoom levels).
     */
    private void zoomIn() {
        setZoom(zoomFactor + getDynamicZoomStep());
    }
    
    /**
     * Zooms out by dynamic step (smaller steps at lower zoom levels).
     */
    private void zoomOut() {
        setZoom(zoomFactor - getDynamicZoomStep());
    }
    
    /**
     * Resets zoom to 100%.
     */
    private void resetZoom() {
        setZoom(1.0);
    }
    
    /**
     * Calculates dynamic zoom step based on current zoom level.
     * Smaller steps at lower zoom levels for finer control.
     */
    private double getDynamicZoomStep() {
        if (zoomFactor <= 0.1) {
            return 0.02;  // 2% steps when very zoomed out
        } else if (zoomFactor <= 0.3) {
            return 0.05;  // 5% steps
        } else {
            return ZOOM_STEP;  // 10% steps at normal zoom
        }
    }
    
    /**
     * Sets the zoom factor and updates the display.
     */
    private void setZoom(double newZoom) {
        // Clamp to valid range
        zoomFactor = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, newZoom));
        
        // Apply scale via CSS transform on the content
        if (zoomableContent != null) {
            zoomableContent.setScaleX(zoomFactor);
            zoomableContent.setScaleY(zoomFactor);
            
            // Adjust translation to keep top-left corner anchored
            double width = zoomableContent.getBoundsInLocal().getWidth();
            double height = zoomableContent.getBoundsInLocal().getHeight();
            zoomableContent.setTranslateX((zoomFactor - 1) * width / 2);
            zoomableContent.setTranslateY((zoomFactor - 1) * height / 2);
        }
        
        // Update label
        if (zoomLabel != null) {
            zoomLabel.setText(String.format("%d%%", Math.round(zoomFactor * 100)));
        }
        
        // Invalidate lines - they will be redrawn on next visibility toggle
        // For visible lines, redraw them now (delayed to allow layout)
        javafx.application.Platform.runLater(() -> {
            invalidateLines();
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected() && dependencyPane.isVisible()) {
                drawDependencyArrows();
            }
            if (showSccCheckbox != null && showSccCheckbox.isSelected() && sccPane.isVisible()) {
                drawSccLines();
            }
        });
    }
    
    /**
     * Marks lines as needing update. Lines will be redrawn on next visibility toggle.
     */
    private void invalidateLines() {
        linesNeedUpdate = true;
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
            if (showSccCheckbox != null && showSccCheckbox.isSelected()) {
                drawSccLines();
            }
        });
        
        // Set callback to refresh dependency arrows when a class is selected/deselected
        LevelClassBox.setOnSelectionChangeCallback(() -> {
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
                drawDependencyArrows();
            }
            if (showSccCheckbox != null && showSccCheckbox.isSelected()) {
                drawSccLines();
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
        
        // Store reference to the architecture container
        this.topLevelContainer = topLevelContainer;
        
        // Create panes for drawing dependency and SCC lines
        // These are placed inside the scrollable content so they scroll with it
        dependencyPane = new Pane();
        dependencyPane.setMouseTransparent(false);
        dependencyPane.setPickOnBounds(false);
        dependencyPane.setVisible(false);
        
        sccPane = new Pane();
        sccPane.setMouseTransparent(true);
        sccPane.setPickOnBounds(false);
        sccPane.setVisible(false);
        
        // Overlay contains both line panes, stacked on top of content
        overlayPane = new StackPane();
        overlayPane.setMouseTransparent(false);
        overlayPane.setPickOnBounds(false);
        overlayPane.getChildren().addAll(dependencyPane, sccPane);
        
        // Stack architecture content and overlay together
        StackPane contentWithOverlay = new StackPane();
        contentWithOverlay.getChildren().addAll(topLevelContainer, overlayPane);
        
        // Store reference for zoom - we scale the entire content including overlay
        this.zoomableContent = contentWithOverlay;
        
        // Wrap in a Group so that layout bounds reflect the scaled size
        // (Group reports transformed bounds of its children)
        javafx.scene.Group scaledGroup = new javafx.scene.Group(contentWithOverlay);
        
        // Wrap in a StackPane to center the content when zoomed out
        StackPane centeringWrapper = new StackPane(scaledGroup);
        centeringWrapper.setAlignment(javafx.geometry.Pos.CENTER);
        
        // Bind wrapper size to viewport for centering
        scrollPane.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            // Make wrapper at least as large as viewport so content centers
            centeringWrapper.setMinWidth(newVal.getWidth());
            centeringWrapper.setMinHeight(newVal.getHeight());
        });
        
        scrollPane.setContent(centeringWrapper);
        
        // Reset zoom to 100%
        zoomFactor = 1.0;
        if (zoomLabel != null) {
            zoomLabel.setText("100%");
        }
        
        // Clear dependency and SCC lines (new architecture = new lines needed)
        clearDependencyArrows();
        clearSccLines();
        dependencyPane.setVisible(false);
        sccPane.setVisible(false);
        if (showDependenciesCheckbox != null) {
            showDependenciesCheckbox.setSelected(false);
        }
        if (showSccCheckbox != null) {
            showSccCheckbox.setSelected(false);
        }
        
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
     * Clears all dependency arrows and resets the drawn flag.
     */
    private void clearDependencyArrows() {
        if (dependencyPane != null) {
            dependencyPane.getChildren().clear();
            dependencyLines.clear();
            selectedLine = null;
            dependencyLinesDrawn = false;
        }
    }
    
    /**
     * Draws dependency arrows between visible elements.
     * Lines are cached and only redrawn when invalidated.
     */
    private void drawDependencyArrows() {
        if (dependencyPane == null || currentRootNode == null) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        // Clear existing lines
        dependencyPane.getChildren().clear();
        dependencyLines.clear();
        selectedLine = null;
        
        // Iterate through all registered elements and draw arrows for their dependencies
        drawDependencyArrowsRecursive(currentRootNode);
        
        // Mark as drawn and reset invalidation flag
        dependencyLinesDrawn = true;
        linesNeedUpdate = false;
    }
    
    /**
     * Calculates the center point of a node relative to the overlay pane.
     * Since the overlay and content are siblings in zoomableContent,
     * we need to calculate coordinates in that common parent's coordinate space.
     * @return double array [x, y] or null if not visible
     */
    private double[] getNodeCenterInPane(javafx.scene.Node node) {
        try {
            if (zoomableContent == null || overlayPane == null) {
                return null;
            }
            
            // Get bounds in local coordinates
            Bounds localBounds = node.getBoundsInLocal();
            
            // Transform to zoomableContent's coordinate space
            // node -> ... -> topLevelContainer -> zoomableContent
            javafx.scene.Node current = node;
            double centerX = localBounds.getMinX() + localBounds.getWidth() / 2;
            double centerY = localBounds.getMinY() + localBounds.getHeight() / 2;
            
            // Walk up the parent chain to zoomableContent, accumulating transforms
            while (current != null && current != zoomableContent) {
                Bounds boundsInParent = current.getBoundsInParent();
                Bounds localB = current.getBoundsInLocal();
                
                // Adjust for the offset from local to parent bounds
                centerX = centerX - localB.getMinX() + boundsInParent.getMinX();
                centerY = centerY - localB.getMinY() + boundsInParent.getMinY();
                
                current = current.getParent();
            }
            
            return new double[] { centerX, centerY };
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Returns the line width.
     * Since lines are now inside the scaled content, they scale automatically.
     */
    private double getScaledLineWidth() {
        return DEPENDENCY_WIDTH;
    }
    
    /**
     * Returns the arrow size.
     * Since lines are now inside the scaled content, they scale automatically.
     */
    private double getScaledArrowSize() {
        return 4.0;
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
            // Get coordinates using helper method
            double[] sourceCenter = getNodeCenterInPane(source);
            double[] targetCenter = getNodeCenterInPane(target);
            
            if (sourceCenter == null || targetCenter == null) {
                return;
            }
            
            double startX = sourceCenter[0];
            double startY = sourceCenter[1];
            double endX = targetCenter[0];
            double endY = targetCenter[1];
            
            // Choose color based on direction
            Color lineColor = isIncoming ? INCOMING_DEPENDENCY_COLOR : OUTGOING_DEPENDENCY_COLOR;
            
            // Create the line with scaled width
            double scaledWidth = getScaledLineWidth();
            Line line = new Line(startX, startY, endX, endY);
            line.setStroke(lineColor);
            line.setStrokeWidth(scaledWidth);
            
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
            
            // Create arrowhead lines with scaled size
            double arrowSize = getScaledArrowSize();
            double angle = Math.atan2(endY - startY, endX - startX);
            
            double x1 = endX - arrowSize * Math.cos(angle - Math.PI / 6);
            double y1 = endY - arrowSize * Math.sin(angle - Math.PI / 6);
            double x2 = endX - arrowSize * Math.cos(angle + Math.PI / 6);
            double y2 = endY - arrowSize * Math.sin(angle + Math.PI / 6);
            
            Line arrow1 = new Line(endX, endY, x1, y1);
            Line arrow2 = new Line(endX, endY, x2, y2);
            arrow1.setStroke(lineColor);
            arrow2.setStroke(lineColor);
            arrow1.setStrokeWidth(scaledWidth);
            arrow2.setStrokeWidth(scaledWidth);
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
        double scaledWidth = getScaledLineWidth();
        double selectedWidth = scaledWidth * 2;
        
        // Deselect previous line
        if (selectedLine != null && selectedLine != line) {
            Object[] userData = (Object[]) selectedLine.getUserData();
            Color originalColor = (userData != null && userData.length > 2) ? (Color) userData[2] : OUTGOING_DEPENDENCY_COLOR;
            selectedLine.setStroke(originalColor);
            selectedLine.setStrokeWidth(scaledWidth);
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(originalColor);
                arrow2.setStroke(originalColor);
                arrow1.setStrokeWidth(scaledWidth);
                arrow2.setStrokeWidth(scaledWidth);
            }
        }
        
        // Toggle selection
        if (selectedLine == line) {
            // Deselect
            Object[] userData = (Object[]) line.getUserData();
            Color originalColor = (userData != null && userData.length > 2) ? (Color) userData[2] : OUTGOING_DEPENDENCY_COLOR;
            line.setStroke(originalColor);
            line.setStrokeWidth(scaledWidth);
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(originalColor);
                arrow2.setStroke(originalColor);
                arrow1.setStrokeWidth(scaledWidth);
                arrow2.setStrokeWidth(scaledWidth);
            }
            selectedLine = null;
            setStatus("Ready");
        } else {
            // Select
            line.setStroke(Color.RED);
            line.setStrokeWidth(selectedWidth);
            Object[] userData = (Object[]) line.getUserData();
            if (userData != null && userData.length >= 2) {
                Line arrow1 = (Line) userData[0];
                Line arrow2 = (Line) userData[1];
                arrow1.setStroke(Color.RED);
                arrow2.setStroke(Color.RED);
                arrow1.setStrokeWidth(selectedWidth);
                arrow2.setStrokeWidth(selectedWidth);
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

    // ===== SCC (Strongly Connected Components) Visualization =====

    /**
     * Clears all SCC lines from the view and resets the drawn flag.
     */
    private void clearSccLines() {
        if (sccPane != null) {
            sccPane.getChildren().clear();
        }
        sccLines.clear();
        sccLinesDrawn = false;
    }

    /**
     * Draws SCC lines connecting all visible classes that are part of the same SCC (cycle).
     * Lines are cached and only redrawn when invalidated.
     */
    private void drawSccLines() {
        // Clear existing SCC lines
        if (sccPane != null) {
            sccPane.getChildren().clear();
        }
        sccLines.clear();
        
        if (currentRootNode == null) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        // Step 1: Collect all visible classes and their dependencies
        Map<String, Set<String>> classDependencies = new HashMap<>();
        collectVisibleClassDependencies(currentRootNode, classDependencies);
        
        if (classDependencies.isEmpty()) {
            return;
        }
        
        // Step 2: Find SCCs using Tarjan algorithm
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(classDependencies);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();
        
        // Step 3: Draw lines for each SCC with more than 1 member (cycles only)
        int sccCount = 0;
        for (StronglyConnectedComponent scc : sccs) {
            if (scc.isTangle()) { // Only draw for cycles (size > 1)
                drawSccComponentLines(scc, classDependencies);
                sccCount++;
            }
        }
        
        // Mark as drawn and reset invalidation flag
        sccLinesDrawn = true;
        linesNeedUpdate = false;
        
        if (sccCount > 0) {
            setStatus("Showing " + sccCount + " SCC cycle(s) in red");
        } else {
            setStatus("No cycles found among visible classes");
        }
    }

    /**
     * Recursively collects class dependencies from visible (expanded) nodes.
     */
    private void collectVisibleClassDependencies(ArchitectureNode node, Map<String, Set<String>> result) {
        if (node.getType() == NodeType.CLASS) {
            // Check if this class is actually visible in the UI
            javafx.scene.Node uiNode = elementRegistry.get(node.getFullName());
            if (uiNode != null && isNodeActuallyVisible(uiNode)) {
                // Filter dependencies to only include classes we know about
                Set<String> visibleDeps = new HashSet<>();
                for (String dep : node.getDependencies()) {
                    if (elementRegistry.containsKey(dep)) {
                        visibleDeps.add(dep);
                    }
                }
                result.put(node.getFullName(), visibleDeps);
            }
        }
        
        // Recurse into children
        for (ArchitectureNode child : node.getChildren()) {
            collectVisibleClassDependencies(child, result);
        }
    }

    /**
     * Draws lines connecting all members of a single SCC that are visible.
     * Only draws the actual dependency edges within the SCC, not all pairs.
     */
    private void drawSccComponentLines(StronglyConnectedComponent scc, Map<String, Set<String>> classDependencies) {
        Set<String> members = scc.getMembers();
        
        // Draw dependency edges between members of this SCC
        for (String member : members) {
            javafx.scene.Node sourceNode = elementRegistry.get(member);
            if (sourceNode == null || !isNodeActuallyVisible(sourceNode)) {
                continue;
            }
            
            Set<String> deps = classDependencies.getOrDefault(member, Set.of());
            for (String dep : deps) {
                if (members.contains(dep)) {
                    javafx.scene.Node targetNode = elementRegistry.get(dep);
                    if (targetNode != null && isNodeActuallyVisible(targetNode)) {
                        createSccLine(sourceNode, targetNode, member, dep);
                    }
                }
            }
        }
    }

    /**
     * Creates a single SCC line between two elements.
     */
    private void createSccLine(javafx.scene.Node source, javafx.scene.Node target, String sourceName, String targetName) {
        try {
            // Get coordinates using helper method
            double[] sourceCenter = getNodeCenterInPane(source);
            double[] targetCenter = getNodeCenterInPane(target);
            
            if (sourceCenter == null || targetCenter == null) {
                return;
            }
            
            double startX = sourceCenter[0];
            double startY = sourceCenter[1];
            double endX = targetCenter[0];
            double endY = targetCenter[1];
            
            // Create the line with scaled width
            double scaledWidth = getScaledLineWidth();
            Line line = new Line(startX, startY, endX, endY);
            line.setStroke(SCC_COLOR);
            line.setStrokeWidth(scaledWidth);
            
            // Create arrowhead lines with scaled size
            double arrowSize = getScaledArrowSize();
            double angle = Math.atan2(endY - startY, endX - startX);
            
            double x1 = endX - arrowSize * Math.cos(angle - Math.PI / 6);
            double y1 = endY - arrowSize * Math.sin(angle - Math.PI / 6);
            double x2 = endX - arrowSize * Math.cos(angle + Math.PI / 6);
            double y2 = endY - arrowSize * Math.sin(angle + Math.PI / 6);
            
            Line arrow1 = new Line(endX, endY, x1, y1);
            Line arrow2 = new Line(endX, endY, x2, y2);
            arrow1.setStroke(SCC_COLOR);
            arrow2.setStroke(SCC_COLOR);
            arrow1.setStrokeWidth(scaledWidth);
            arrow2.setStrokeWidth(scaledWidth);
            arrow1.setMouseTransparent(true);
            arrow2.setMouseTransparent(true);
            
            // Make line clickable
            line.setUserData(new Object[]{arrow1, arrow2, SCC_COLOR, sourceName, targetName});
            line.setOnMouseEntered(e -> {
                line.setStroke(Color.DARKRED);
                line.setCursor(javafx.scene.Cursor.HAND);
            });
            line.setOnMouseExited(e -> {
                line.setStroke(SCC_COLOR);
                line.setCursor(javafx.scene.Cursor.DEFAULT);
            });
            line.setOnMouseClicked(e -> {
                String simpleSource = sourceName.contains(".") ? 
                    sourceName.substring(sourceName.lastIndexOf('.') + 1) : sourceName;
                String simpleTarget = targetName.contains(".") ? 
                    targetName.substring(targetName.lastIndexOf('.') + 1) : targetName;
                setStatus("SCC Edge: " + simpleSource + " ↔ " + simpleTarget);
                e.consume();
            });
            
            sccPane.getChildren().addAll(line, arrow1, arrow2);
            sccLines.add(line);
            sccLines.add(arrow1);
            sccLines.add(arrow2);
            
        } catch (Exception e) {
            // Ignore drawing errors for elements not in scene
        }
    }
}

