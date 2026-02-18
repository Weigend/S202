package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.rendering.DependencyRenderer;
import de.weigend.s202.ui.rendering.SCCRenderer;
import de.weigend.s202.ui.tree.ArchitectureTreeBuilder;
import de.weigend.s202.ui.zoom.ZoomController;
import javafx.geometry.Insets;
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
    private DependencyRenderer dependencyRenderer;
    private SCCRenderer sccRenderer;
    private ArchitectureTreeBuilder treeBuilder;
    private ZoomController zoomController;

    // Flags to track if lines have been drawn (for performance optimization)
    private boolean linesNeedUpdate = false;  // Set when zoom/scroll changes
    private boolean arrowRedrawPending = false;  // Debounce flag for layout-triggered redraws

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
            if (currentRootNode != null) {
                setArchitectureRoot(currentRootNode);
            }
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
                if (!sccRenderer.isSccLinesDrawn() || linesNeedUpdate) {
                    sccRenderer.drawSccLines(currentRootNode);
                    linesNeedUpdate = false;
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
        
        // Expand/collapse callback no longer needed — layoutBoundsProperty listener handles arrow redraw
        LevelPackageBox.setOnExpandChangeCallback(null);

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

        // Redraw arrows when layout changes (expand/collapse causes layout bounds to change).
        // layoutBoundsProperty fires AFTER the layout pass has computed correct bounds.
        // Platform.runLater defers drawing to after ALL nested layouts are complete.
        topLevelContainer.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> {
            if (!arrowRedrawPending) {
                arrowRedrawPending = true;
                javafx.application.Platform.runLater(() -> {
                    arrowRedrawPending = false;
                    redrawVisibleArrows();
                });
            }
        });

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

        // Initialize DependencyRenderer with coordinate context
        dependencyRenderer = new DependencyRenderer(dependencyPane, elementRegistry, zoomController, this::setStatus);
        dependencyRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);

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

