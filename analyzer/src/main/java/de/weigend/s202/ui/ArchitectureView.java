package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.rendering.DependencyRenderer;
import de.weigend.s202.ui.zoom.ZoomController;
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
    private List<Line> sccLines = new ArrayList<>();

    // Renderers
    private DependencyRenderer dependencyRenderer;
    private ZoomController zoomController;

    // Flags to track if lines have been drawn (for performance optimization)
    private boolean sccLinesDrawn = false;
    private boolean linesNeedUpdate = false;  // Set when zoom/scroll changes
    private static final Color OUTGOING_DEPENDENCY_COLOR = Color.rgb(64, 64, 64); // Anthrazit - ausgehende Abhängigkeiten
    private static final Color INCOMING_DEPENDENCY_COLOR = Color.rgb(0, 128, 0); // Grün - eingehende Abhängigkeiten (dependents)
    private static final Color SCC_COLOR = Color.RED; // Rot - zyklische Abhängigkeiten (SCCs)
    private static final double DEPENDENCY_WIDTH = 1.0; // Line width for dependency and SCC arrows

    // UI components for zoom
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
        
        // Zoom mit Mausrad (Ctrl+Scroll) - will be initialized after zoomController is created
        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (event.isControlDown() && zoomController != null) {
                event.consume();
                double delta = event.getDeltaY();
                if (delta > 0) {
                    zoomController.zoomIn();
                } else if (delta < 0) {
                    zoomController.zoomOut();
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
                if (!dependencyRenderer.isDependencyLinesDrawn() || linesNeedUpdate) {
                    dependencyRenderer.drawDependencyArrows(currentRootNode);
                    linesNeedUpdate = false;
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
        zoomInBtn.setOnAction(e -> { if (zoomController != null) zoomController.zoomIn(); });

        Button zoomOutBtn = new Button("🔍-");
        zoomOutBtn.setTooltip(new Tooltip("Zoom Out (Ctrl+Scroll Down)"));
        zoomOutBtn.setOnAction(e -> { if (zoomController != null) zoomController.zoomOut(); });

        Button zoomResetBtn = new Button("1:1");
        zoomResetBtn.setTooltip(new Tooltip("Reset Zoom"));
        zoomResetBtn.setOnAction(e -> { if (zoomController != null) zoomController.resetZoom(); });
        
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

    /**
     * Callback invoked when zoom changes. Invalidates and redraws visible lines.
     */
    private void handleZoomChanged() {
        invalidateLines();
        if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected() && dependencyPane != null && dependencyPane.isVisible()) {
            dependencyRenderer.drawDependencyArrows(currentRootNode);
        }
        if (showSccCheckbox != null && showSccCheckbox.isSelected() && sccPane != null && sccPane.isVisible()) {
            drawSccLines();
        }
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
                dependencyRenderer.drawDependencyArrows(currentRootNode);
            }
            if (showSccCheckbox != null && showSccCheckbox.isSelected()) {
                drawSccLines();
            }
        });
        
        // Set callback to refresh dependency arrows when a class is selected/deselected
        LevelClassBox.setOnSelectionChangeCallback(() -> {
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
                dependencyRenderer.drawDependencyArrows(currentRootNode);
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

        // Initialize or recreate ZoomController (now that zoomableContent is set)
        zoomController = new ZoomController(zoomLabel, zoomableContent, this::handleZoomChanged);
        zoomController.resetZoom();

        // Initialize DependencyRenderer with coordinate context
        dependencyRenderer = new DependencyRenderer(dependencyPane, elementRegistry, zoomController, this::setStatus);
        dependencyRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);

        // Clear dependency and SCC lines (new architecture = new lines needed)
        dependencyRenderer.clearDependencyArrows();
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
     * Checks if a node is actually visible (itself and all parents are visible).
     * Helper method for SCC visualization (will be moved to SCCRenderer in Phase 2.3).
     */
    private boolean isNodeActuallyVisible(javafx.scene.Node node) {
        if (node == null || !node.isVisible()) {
            return false;
        }

        // Check all parents up to the scene root
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
     * Calculates the center point of a node relative to the overlay pane.
     * Helper method for SCC visualization (will be moved to SCCRenderer in Phase 2.3).
     */
    private double[] getNodeCenterInPane(javafx.scene.Node node) {
        try {
            if (zoomableContent == null || overlayPane == null) {
                return null;
            }

            // Get bounds in local coordinates
            Bounds localBounds = node.getBoundsInLocal();

            // Transform to zoomableContent's coordinate space
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

            return new double[]{centerX, centerY};
        } catch (Exception e) {
            return null;
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

