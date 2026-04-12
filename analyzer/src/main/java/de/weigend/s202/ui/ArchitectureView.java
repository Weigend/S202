package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.rendering.CircuitBoardRenderer;
import de.weigend.s202.ui.rendering.DependencyRenderer;
import de.weigend.s202.ui.rendering.DependencyRendererStrategy;
import de.weigend.s202.ui.rendering.SCCRenderer;
import de.weigend.s202.ui.tree.ArchitectureTreeBuilder;
import de.weigend.s202.ui.zoom.ZoomController;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
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
    private CheckBox showSccCheckbox;
    private Pane dependencyPane;  // Container for dependency lines
    private Pane sccPane;          // Container for SCC lines
    private StackPane overlayPane; // Contains both dependency and SCC panes
    private StackPane contentPane;
    private ArchitectureNode currentRootNode;
    private Map<String, javafx.scene.Node> elementRegistry = new HashMap<>();

    // Renderers and builders
    private DependencyRendererStrategy dependencyRenderer;
    private DependencyRenderer classicRenderer;
    private CircuitBoardRenderer circuitRenderer;
    private ToggleButton circuitToggle;
    private SCCRenderer sccRenderer;
    private ArchitectureTreeBuilder treeBuilder;
    private ZoomController zoomController;

    // Flags to track if lines have been drawn (for performance optimization)
    private boolean linesNeedUpdate = false;  // Set when zoom/scroll changes

    // UI components for zoom
    private Label zoomLabel;
    private javafx.scene.layout.Pane zoomableContent;  // StackPane containing content + overlay
    private javafx.scene.layout.VBox topLevelContainer; // The actual architecture content

    public ArchitectureView(Stage parentStage) {
        this.parentStage = Objects.requireNonNull(parentStage, "parentStage cannot be null");
        setupUI();
    }

    private void setupUI() {
        // Top toolbar (will be fully initialized after renderers are created)
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
        
        // Create panes for drawing lines (initially empty, will be populated in setArchitectureRoot)
        dependencyPane = new Pane();
        dependencyPane.setMouseTransparent(false);
        dependencyPane.setPickOnBounds(false);
        dependencyPane.setVisible(false);

        sccPane = new Pane();
        sccPane.setMouseTransparent(true);
        sccPane.setPickOnBounds(false);
        sccPane.setVisible(false);
        
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

        // Status bar (label created here, placed in root by AnalyzerApplication)
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-bar");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
    }

    /**
     * Returns the status bar label for placement in the application root layout.
     */
    public Label getStatusBar() {
        return statusLabel;
    }


    private HBox createToolbar() {
        HBox toolbar = new HBox(8);
        toolbar.setPadding(new Insets(5));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("tool-bar");

        // --- File group ---
        Button loadButton = new Button("Load JAR");
        loadButton.getStyleClass().add("toolbar-button");
        loadButton.setTooltip(new Tooltip("Analyse a JAR file"));
        loadButton.setOnAction(e -> openFileChooser());

        // --- View group ---
        Label depthLabel = new Label("Depth:");
        depthLabel.getStyleClass().add("toolbar-label");
        depthLabel.setTooltip(new Tooltip("Package nesting depth to display"));

        depthSpinner = new Spinner<>(0, 10, 3);
        depthSpinner.setTooltip(new Tooltip("Package nesting depth to display"));

        Button refreshButton = new Button("\u21bb Refresh");
        refreshButton.getStyleClass().add("toolbar-button");
        refreshButton.setTooltip(new Tooltip("Rebuild architecture view (re-layout all packages)"));
        refreshButton.setOnAction(e -> {
            if (currentRootNode != null) {
                setArchitectureRoot(currentRootNode);
            }
        });

        // --- Overlay group ---
        showDependenciesCheckbox = new CheckBox("Show Dependencies");
        showDependenciesCheckbox.setTooltip(new Tooltip("Toggle dependency arrows"));
        showDependenciesCheckbox.setOnAction(e -> {
            if (showDependenciesCheckbox.isSelected()) {
                if (!dependencyRenderer.isDependencyLinesDrawn() || linesNeedUpdate) {
                    dependencyRenderer.drawDependencyArrows(currentRootNode);
                    linesNeedUpdate = false;
                }
                dependencyPane.setVisible(true);
            } else {
                dependencyPane.setVisible(false);
            }
        });

        circuitToggle = new ToggleButton("Leiterbahn");
        circuitToggle.setTooltip(new Tooltip("Dependency style: classic straight lines vs. circuit-board routing"));
        circuitToggle.setSelected(false);
        circuitToggle.setOnAction(e -> {
            dependencyRenderer = circuitToggle.isSelected() ? circuitRenderer : classicRenderer;
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected() && currentRootNode != null) {
                dependencyRenderer.drawDependencyArrows(currentRootNode);
                dependencyPane.setVisible(true);
            }
        });

        showSccCheckbox = new CheckBox("Show Cyclic Dependencies - SCCs");
        showSccCheckbox.setTooltip(new Tooltip("Toggle cycle highlighting (Strongly Connected Components)"));
        showSccCheckbox.setOnAction(e -> {
            if (showSccCheckbox.isSelected()) {
                if (!sccRenderer.isSccLinesDrawn() || linesNeedUpdate) {
                    sccRenderer.drawSccLines(currentRootNode);
                    linesNeedUpdate = false;
                }
                sccPane.setVisible(true);
            } else {
                sccPane.setVisible(false);
            }
        });

        // --- Zoom group ---
        Button zoomOutBtn = new Button("\u2212");
        zoomOutBtn.getStyleClass().add("toolbar-zoom-button");
        zoomOutBtn.setTooltip(new Tooltip("Zoom Out (Ctrl+Scroll Down)"));
        zoomOutBtn.setOnAction(e -> { if (zoomController != null) zoomController.zoomOut(); });

        zoomLabel = new Label("100%");
        zoomLabel.getStyleClass().add("toolbar-zoom-label");

        Button zoomInBtn = new Button("+");
        zoomInBtn.getStyleClass().add("toolbar-zoom-button");
        zoomInBtn.setTooltip(new Tooltip("Zoom In (Ctrl+Scroll Up)"));
        zoomInBtn.setOnAction(e -> { if (zoomController != null) zoomController.zoomIn(); });

        Button zoomResetBtn = new Button("1:1");
        zoomResetBtn.getStyleClass().add("toolbar-zoom-button");
        zoomResetBtn.setTooltip(new Tooltip("Reset Zoom to 100%"));
        zoomResetBtn.setOnAction(e -> { if (zoomController != null) zoomController.resetZoom(); });

        // Zoom controls grouped tightly
        HBox zoomGroup = new HBox(2);
        zoomGroup.setAlignment(Pos.CENTER_LEFT);
        zoomGroup.getChildren().addAll(zoomOutBtn, zoomLabel, zoomInBtn);

        toolbar.getChildren().addAll(
            loadButton, new Separator(),
            depthLabel, depthSpinner, refreshButton,
            new Separator(),
            showDependenciesCheckbox, circuitToggle, showSccCheckbox,
            new Separator(),
            zoomGroup, zoomResetBtn
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
            sccRenderer.drawSccLines(currentRootNode);
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
        
        // On expand/collapse: CSS was already applied to newly visible content in toggleExpanded().
        // Ensure the entire layout chain is marked dirty (ScrollPane can break propagation),
        // then force a full scene layout and redraw arrows with correct coordinates.
        LevelPackageBox.setOnExpandChangeCallback(() -> {
            if (getScene() != null && getScene().getRoot() != null) {
                if (zoomableContent != null) {
                    zoomableContent.requestLayout();
                }
                getScene().getRoot().layout();
                redrawVisibleArrows();
            }
        });

        // Set callback to refresh dependency arrows when a class is selected/deselected
        LevelClassBox.setOnSelectionChangeCallback(() -> {
            if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
                dependencyRenderer.drawDependencyArrows(currentRootNode);
            }
            if (showSccCheckbox != null && showSccCheckbox.isSelected()) {
                sccRenderer.drawSccLines(currentRootNode);
            }
        });

        // Initialize ArchitectureTreeBuilder and build the UI tree
        treeBuilder = new ArchitectureTreeBuilder(elementRegistry);
        int maxDepth = depthSpinner != null ? depthSpinner.getValue() : 3;
        javafx.scene.layout.VBox topLevelContainer = treeBuilder.buildTree(rootNode, maxDepth);

        // Store reference to the architecture container
        this.topLevelContainer = topLevelContainer;


        // Clear panes for new architecture (panes were created in setupUI())
        dependencyPane.getChildren().clear();
        dependencyPane.setVisible(false);
        sccPane.getChildren().clear();
        sccPane.setVisible(false);

        // Create overlay that contains both line panes, stacked on top of content
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
        
        scrollPane.setContent(centeringWrapper);

        // Apply current viewport bounds immediately (the setupUI listener only fires on change,
        // so on Refresh the new wrapper's minWidth/minHeight would remain unset)
        javafx.geometry.Bounds viewportBounds = scrollPane.getViewportBounds();
        if (viewportBounds != null && viewportBounds.getWidth() > 0) {
            centeringWrapper.setMinWidth(viewportBounds.getWidth());
            centeringWrapper.setMinHeight(viewportBounds.getHeight());
        }

        // Initialize or recreate ZoomController (now that zoomableContent is set)
        zoomController = new ZoomController(zoomLabel, zoomableContent, this::handleZoomChanged);
        zoomController.resetZoom();

        // Initialize both dependency renderer strategies with coordinate context
        classicRenderer = new DependencyRenderer(dependencyPane, elementRegistry, zoomController, this::setStatus);
        classicRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        circuitRenderer = new CircuitBoardRenderer(dependencyPane, elementRegistry, this::setStatus);
        circuitRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        dependencyRenderer = (circuitToggle != null && circuitToggle.isSelected()) ? circuitRenderer : classicRenderer;

        // Initialize SCCRenderer with coordinate context
        sccRenderer = new SCCRenderer(sccPane, elementRegistry, this::setStatus);
        sccRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);

        // Clear dependency and SCC lines (new architecture = new lines needed)
        dependencyRenderer.clearDependencyArrows();
        sccRenderer.clearSccLines();
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
     * Redraws dependency and SCC arrows if their checkboxes are enabled.
     */
    private void redrawVisibleArrows() {
        if (showDependenciesCheckbox != null && showDependenciesCheckbox.isSelected()) {
            dependencyRenderer.drawDependencyArrows(currentRootNode);
        }
        if (showSccCheckbox != null && showSccCheckbox.isSelected()) {
            sccRenderer.drawSccLines(currentRootNode);
        }
    }

    /**
     * Updates the status bar message.
     */
    public void setStatus(String message) {
        statusLabel.setText(Objects.requireNonNull(message, "message cannot be null"));
    }

}

