package de.weigend.s202.ui;

import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.rendering.CircuitBoardRenderer;
import de.weigend.s202.ui.rendering.DependencyRenderer;
import de.weigend.s202.ui.rendering.DependencyRendererStrategy;
import de.weigend.s202.ui.rendering.SCCRenderer;
import de.weigend.s202.ui.tree.ArchitectureTreeBuilder;
import de.weigend.s202.ui.zoom.ZoomController;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Main UI component for displaying the architecture graph.
 * <p>
 * The view exposes its settings (package depth, dependency/SCC overlays,
 * circuit-board mode, zoom) via JavaFX properties so the host shell can
 * provide a single shared toolbar that operates on the focused view.
 */
public class ArchitectureView extends BorderPane {

    private ScrollPane scrollPane;
    private Pane dependencyPane;   // Container for dependency lines
    private Pane sccPane;          // Container for SCC lines
    private StackPane overlayPane; // Contains both dependency and SCC panes
    private StackPane contentPane;
    private ArchitectureNode currentRootNode;
    private final Map<String, javafx.scene.Node> elementRegistry = new HashMap<>();

    // Renderers and builders
    private DependencyRendererStrategy dependencyRenderer;
    private DependencyRenderer classicRenderer;
    private CircuitBoardRenderer circuitRenderer;
    private SCCRenderer sccRenderer;
    private ArchitectureTreeBuilder treeBuilder;
    private ZoomController zoomController;

    // Lines need redraw after zoom/scroll changes (perf optimization).
    private boolean linesNeedUpdate = false;

    private javafx.scene.layout.Pane zoomableContent;
    private Consumer<String> statusSink = msg -> { /* no-op default */ };
    private Consumer<String> nodeDoubleClickSink = fqn -> { /* no-op default */ };

    // Externally bindable settings.
    private final IntegerProperty packageDepth = new SimpleIntegerProperty(3);
    private final BooleanProperty showDependencies = new SimpleBooleanProperty(false);
    private final BooleanProperty circuitMode = new SimpleBooleanProperty(false);
    private final BooleanProperty showScc = new SimpleBooleanProperty(false);
    // Icon visibility is shared across all open architecture views — boxes bind
    // their FontIcon visibility to this property so toggling refreshes every
    // open tab without rebuilding the tree.
    private static final BooleanProperty SHOW_ICONS = new SimpleBooleanProperty(false);
    private final ReadOnlyObjectWrapper<ArchitectureNode> architectureRoot = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<QualityMetrics> qualityMetrics = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<DomainModel> domainModel = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyStringWrapper selectedFullName = new ReadOnlyStringWrapper(null);

    public ArchitectureView() {
        setupUI();
        wirePropertyListeners();
    }

    private void setupUI() {
        // Center: StackPane containing ScrollPane and Pane overlay for dependency arrows
        scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(false);  // Disabled for zoom
        scrollPane.setFitToHeight(false);
        scrollPane.setPrefHeight(600);
        scrollPane.setPannable(true);  // Enable panning when zoomed out

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

        contentPane = new StackPane();
        contentPane.getChildren().add(scrollPane);

        // Update centering wrapper when viewport changes
        scrollPane.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            if (scrollPane.getContent() instanceof StackPane wrapper) {
                wrapper.setMinWidth(newVal.getWidth());
                wrapper.setMinHeight(newVal.getHeight());
            }
        });

        setCenter(contentPane);
    }

    private void wirePropertyListeners() {
        showDependencies.addListener((obs, was, isNow) -> applyShowDependencies(isNow));
        showScc.addListener((obs, was, isNow) -> applyShowScc(isNow));
        circuitMode.addListener((obs, was, isNow) -> applyCircuitMode());
    }

    private void applyShowDependencies(boolean visible) {
        if (dependencyRenderer == null || dependencyPane == null) {
            return;
        }
        if (visible) {
            if (currentRootNode != null && (!dependencyRenderer.isDependencyLinesDrawn() || linesNeedUpdate)) {
                dependencyRenderer.drawDependencyArrows(currentRootNode);
                linesNeedUpdate = false;
            }
            dependencyPane.setVisible(true);
        } else {
            dependencyPane.setVisible(false);
        }
    }

    private void applyShowScc(boolean visible) {
        if (sccRenderer == null || sccPane == null) {
            return;
        }
        if (visible) {
            if (currentRootNode != null && (!sccRenderer.isSccLinesDrawn() || linesNeedUpdate)) {
                sccRenderer.drawSccLines(currentRootNode);
                linesNeedUpdate = false;
            }
            sccPane.setVisible(true);
        } else {
            sccPane.setVisible(false);
        }
    }

    private void applyCircuitMode() {
        if (classicRenderer == null || circuitRenderer == null) {
            return;
        }
        dependencyRenderer = circuitMode.get() ? circuitRenderer : classicRenderer;
        if (showDependencies.get() && currentRootNode != null) {
            dependencyRenderer.drawDependencyArrows(currentRootNode);
            dependencyPane.setVisible(true);
        }
    }

    private void handleZoomChanged() {
        invalidateLines();
        if (showDependencies.get() && dependencyPane != null && dependencyPane.isVisible()) {
            dependencyRenderer.drawDependencyArrows(currentRootNode);
        }
        if (showScc.get() && sccPane != null && sccPane.isVisible()) {
            sccRenderer.drawSccLines(currentRootNode);
        }
    }

    private void invalidateLines() {
        linesNeedUpdate = true;
    }

    /**
     * Sets the ArchitectureNode root for level-based layout display.
     * Populates the ScrollPane with a LevelPackageBox hierarchy.
     * The synthetic "root" node is hidden - only its children are displayed.
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");

        this.currentRootNode = rootNode;
        this.elementRegistry.clear();

        LevelPackageBox.setOnExpandChangeCallback(() -> {
            if (getScene() != null && getScene().getRoot() != null) {
                if (zoomableContent != null) {
                    zoomableContent.requestLayout();
                }
                getScene().getRoot().layout();
                redrawVisibleArrows();
            }
        });

        // Selection (class OR package) is owned by GraphSelection. Mirror it
        // onto our selectedFullName property and trigger overlay redraws.
        GraphSelection.setOnSelectionChange(fqn -> {
            selectedFullName.set(fqn);
            if (showDependencies.get()) {
                dependencyRenderer.drawDependencyArrows(currentRootNode);
            }
            if (showScc.get()) {
                sccRenderer.drawSccLines(currentRootNode);
            }
        });

        // Re-route double-clicks on graph boxes (class or package) through this
        // view's sink so external panels (e.g. outline explorer) can reveal it.
        GraphSelection.setOnDoubleClick(fqn -> nodeDoubleClickSink.accept(fqn));

        treeBuilder = new ArchitectureTreeBuilder(elementRegistry);
        int maxDepth = packageDepth.get();
        javafx.scene.layout.VBox topLevelContainer = treeBuilder.buildTree(rootNode, maxDepth);

        dependencyPane.getChildren().clear();
        dependencyPane.setVisible(false);
        sccPane.getChildren().clear();
        sccPane.setVisible(false);

        overlayPane = new StackPane();
        overlayPane.setMouseTransparent(false);
        overlayPane.setPickOnBounds(false);
        overlayPane.getChildren().addAll(dependencyPane, sccPane);

        StackPane contentWithOverlay = new StackPane();
        contentWithOverlay.getChildren().addAll(topLevelContainer, overlayPane);

        this.zoomableContent = contentWithOverlay;

        javafx.scene.Group scaledGroup = new javafx.scene.Group(contentWithOverlay);
        StackPane centeringWrapper = new StackPane(scaledGroup);
        centeringWrapper.setAlignment(javafx.geometry.Pos.CENTER);

        scrollPane.setContent(centeringWrapper);

        javafx.geometry.Bounds viewportBounds = scrollPane.getViewportBounds();
        if (viewportBounds != null && viewportBounds.getWidth() > 0) {
            centeringWrapper.setMinWidth(viewportBounds.getWidth());
            centeringWrapper.setMinHeight(viewportBounds.getHeight());
        }

        zoomController = new ZoomController(zoomableContent, this::handleZoomChanged);
        zoomController.resetZoom();

        classicRenderer = new DependencyRenderer(dependencyPane, elementRegistry, zoomController, this::setStatus);
        classicRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        circuitRenderer = new CircuitBoardRenderer(dependencyPane, elementRegistry, this::setStatus);
        circuitRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        dependencyRenderer = circuitMode.get() ? circuitRenderer : classicRenderer;

        sccRenderer = new SCCRenderer(sccPane, elementRegistry, this::setStatus);
        sccRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);

        dependencyRenderer.clearDependencyArrows();
        sccRenderer.clearSccLines();
        dependencyPane.setVisible(false);
        sccPane.setVisible(false);

        // Reset overlay toggles for the new architecture so the global toolbar resyncs.
        showDependencies.set(false);
        showScc.set(false);

        setStatus("Architecture loaded: " + rootNode.getLevelCount() + " levels");

        // Notify external observers (e.g. Outline Explorer) last, so the registry
        // is fully populated before listeners run lookups.
        architectureRoot.set(rootNode);
    }

    /**
     * Read-only handle to the currently displayed architecture root. Fires when
     * a new JAR is loaded or {@link #refreshLayout()} runs.
     */
    public ReadOnlyObjectProperty<ArchitectureNode> architectureRootProperty() {
        return architectureRoot.getReadOnlyProperty();
    }

    public ArchitectureNode getArchitectureRoot() {
        return architectureRoot.get();
    }

    /**
     * Read-only handle to the current quality metrics for this view, or null
     * if the analysis hasn't computed them yet. Pushed in by the host shell
     * after each successful analysis.
     */
    public ReadOnlyObjectProperty<QualityMetrics> qualityMetricsProperty() {
        return qualityMetrics.getReadOnlyProperty();
    }

    public QualityMetrics getQualityMetrics() {
        return qualityMetrics.get();
    }

    public void setQualityMetrics(QualityMetrics metrics) {
        qualityMetrics.set(metrics);
    }

    /**
     * Read-only handle to the analyzed domain model. Pushed in by the host
     * shell after each successful analysis; needed by panels that compute
     * scoped metrics (e.g. quality view for a selected package).
     */
    public ReadOnlyObjectProperty<DomainModel> domainModelProperty() {
        return domainModel.getReadOnlyProperty();
    }

    public DomainModel getDomainModel() {
        return domainModel.get();
    }

    public void setDomainModel(DomainModel model) {
        domainModel.set(model);
    }

    /**
     * Read-only handle to the currently selected node's full name (class or
     * package), or null when nothing is selected.
     */
    public ReadOnlyStringProperty selectedFullNameProperty() {
        return selectedFullName.getReadOnlyProperty();
    }

    public String getSelectedFullName() {
        return selectedFullName.get();
    }

    /**
     * Select the node (class or package) with the given full name (if present)
     * and scroll it into view. No-op if the name is unknown or the
     * architecture is not yet loaded. Idempotent — re-selecting the current
     * node does not toggle it off.
     */
    public void selectByFullName(String fullName) {
        if (fullName == null) {
            return;
        }
        javafx.scene.Node node = elementRegistry.get(fullName);
        if (node instanceof GraphSelection.Selectable target) {
            GraphSelection.ensureSelected(target);
            // Defer scrolling until layout has settled; the box may have just
            // been created during a refresh and have unresolved bounds.
            javafx.application.Platform.runLater(() -> scrollToNode(node));
            return;
        }
        // Tree-only node (e.g. a package skipped as a transparent passthrough
        // — "com", "de", … — that has no visible box in the chart). No visual
        // highlight to apply, but external observers (quality view, etc.) still
        // need the announcement. Clear any visible chart selection and update
        // the property directly.
        GraphSelection.clear();
        selectedFullName.set(fullName);
    }

    /** @deprecated use {@link #selectByFullName(String)}. */
    @Deprecated
    public void selectClass(String fullClassName) {
        selectByFullName(fullClassName);
    }

    private void scrollToNode(javafx.scene.Node target) {
        if (scrollPane == null || scrollPane.getContent() == null) {
            return;
        }
        javafx.scene.Node content = scrollPane.getContent();
        javafx.geometry.Bounds contentBounds = content.getBoundsInLocal();
        javafx.geometry.Bounds targetInContent = content.sceneToLocal(target.localToScene(target.getBoundsInLocal()));

        double contentWidth = contentBounds.getWidth();
        double contentHeight = contentBounds.getHeight();
        javafx.geometry.Bounds viewport = scrollPane.getViewportBounds();
        if (viewport == null || contentWidth <= 0 || contentHeight <= 0) {
            return;
        }

        double overflowX = Math.max(0, contentWidth - viewport.getWidth());
        double overflowY = Math.max(0, contentHeight - viewport.getHeight());
        if (overflowX > 0) {
            double centerX = targetInContent.getMinX() + targetInContent.getWidth() / 2.0;
            double hValue = (centerX - viewport.getWidth() / 2.0) / overflowX;
            scrollPane.setHvalue(Math.max(0, Math.min(1, hValue)));
        }
        if (overflowY > 0) {
            double centerY = targetInContent.getMinY() + targetInContent.getHeight() / 2.0;
            double vValue = (centerY - viewport.getHeight() / 2.0) / overflowY;
            scrollPane.setVvalue(Math.max(0, Math.min(1, vValue)));
        }
    }

    private void redrawVisibleArrows() {
        if (showDependencies.get()) {
            dependencyRenderer.drawDependencyArrows(currentRootNode);
        }
        if (showScc.get()) {
            sccRenderer.drawSccLines(currentRootNode);
        }
    }

    /**
     * Re-runs the layout for the currently loaded architecture (e.g. after a
     * package-depth change).
     */
    public void refreshLayout() {
        if (currentRootNode != null) {
            setArchitectureRoot(currentRootNode);
        }
    }

    public boolean hasRoot() {
        return currentRootNode != null;
    }

    /* ----- Zoom passthrough ------------------------------------------------ */

    public void zoomIn() {
        if (zoomController != null) {
            zoomController.zoomIn();
        }
    }

    public void zoomOut() {
        if (zoomController != null) {
            zoomController.zoomOut();
        }
    }

    public void zoomReset() {
        if (zoomController != null) {
            zoomController.resetZoom();
        }
    }

    /**
     * Read-only zoom factor (1.0 = 100%). Returns null when no architecture is
     * loaded yet — bind via {@link #zoomFactorProperty()} for live updates.
     */
    public ReadOnlyDoubleProperty zoomFactorProperty() {
        return zoomController != null ? zoomController.zoomFactorProperty() : null;
    }

    /* ----- Settings properties --------------------------------------------- */

    public IntegerProperty packageDepthProperty() {
        return packageDepth;
    }

    public int getPackageDepth() {
        return packageDepth.get();
    }

    public void setPackageDepth(int depth) {
        packageDepth.set(depth);
    }

    public BooleanProperty showDependenciesProperty() {
        return showDependencies;
    }

    public boolean isShowDependencies() {
        return showDependencies.get();
    }

    public void setShowDependencies(boolean show) {
        showDependencies.set(show);
    }

    public BooleanProperty circuitModeProperty() {
        return circuitMode;
    }

    public boolean isCircuitMode() {
        return circuitMode.get();
    }

    public void setCircuitMode(boolean circuit) {
        circuitMode.set(circuit);
    }

    public BooleanProperty showSccProperty() {
        return showScc;
    }

    public boolean isShowScc() {
        return showScc.get();
    }

    public void setShowScc(boolean show) {
        showScc.set(show);
    }

    /**
     * Global icon visibility for all package/class boxes. Backed by a static
     * property so all open architecture tabs and freshly created boxes react
     * to the same toggle.
     */
    public static BooleanProperty showIconsProperty() {
        return SHOW_ICONS;
    }

    public boolean isShowIcons() {
        return SHOW_ICONS.get();
    }

    public void setShowIcons(boolean show) {
        SHOW_ICONS.set(show);
    }

    /* ----- Status sink ----------------------------------------------------- */

    /**
     * Updates the status bar message. Routes through the configured sink so the
     * host shell (e.g. the WFX statusbar) can pick up status changes.
     */
    public void setStatus(String message) {
        Objects.requireNonNull(message, "message cannot be null");
        statusSink.accept(message);
    }

    /**
     * Set a sink that receives every status message produced by this view.
     * Pass null to detach.
     */
    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink != null ? sink : (m -> {});
    }

    /**
     * Set a sink that receives the full name whenever the user double-clicks
     * a node (class or package) in the graph. Pass null to detach.
     */
    public void setOnNodeDoubleClicked(Consumer<String> sink) {
        this.nodeDoubleClickSink = sink != null ? sink : (fqn -> {});
    }

    /** @deprecated use {@link #setOnNodeDoubleClicked(Consumer)}. */
    @Deprecated
    public void setOnClassDoubleClicked(Consumer<String> sink) {
        setOnNodeDoubleClicked(sink);
    }
}
