package de.weigend.s202.ui;

import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.whatif.ClassEdges;
import de.weigend.s202.ui.whatif.WhatIfModel;
import de.weigend.s202.ui.whatif.view.WhatIfDependenciesView;
import de.weigend.s202.ui.rendering.CircuitBoardRenderer;
import de.weigend.s202.ui.rendering.DependencyRenderer;
import de.weigend.s202.ui.rendering.DependencyRendererStrategy;
import de.weigend.s202.ui.rendering.SCCRenderer;
import de.weigend.s202.ui.rendering.TangleEdgeRenderer;
import de.weigend.s202.ui.rendering.WhatIfUpwardEdgeRenderer;
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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Main UI component for displaying the architecture graph.
 * <p>
 * The view exposes its settings (package depth, dependency/SCC overlays,
 * circuit-board mode, zoom) via JavaFX properties so the host shell can
 * provide a single shared toolbar that operates on the focused view.
 */
public class ArchitectureView extends BorderPane {

    public record BuildProgress(int processedNodes, int totalNodes, String currentElement) {}

    private ScrollPane scrollPane;
    private Pane dependencyPane;   // Container for dependency lines
    private Pane sccPane;          // Container for SCC lines
    private Pane whatIfPane;       // Container for What-If upward-edge overlay
    private Pane tanglePane;       // Container for the dedicated tangle-edge overlay
    private StackPane overlayPane; // Contains dependency, SCC, What-If and tangle panes
    private StackPane contentPane;
    private ArchitectureNode currentRootNode;
    private final Map<String, javafx.scene.Node> elementRegistry = new HashMap<>();

    // Renderers and builders
    private DependencyRendererStrategy dependencyRenderer;
    private DependencyRenderer classicRenderer;
    private CircuitBoardRenderer circuitRenderer;
    private SCCRenderer sccRenderer;
    private WhatIfUpwardEdgeRenderer whatIfRenderer;
    private TangleEdgeRenderer tangleRenderer;
    private ArchitectureTreeBuilder treeBuilder;
    private ZoomController zoomController;

    // Pending tangle visualisation snapshot, applied once setArchitectureRoot
    // (re-)builds the renderer. Set by setTangleVisualization before the root
    // is assigned, or restored after a refreshLayout.
    private List<TangleEdgeRenderer.Edge> pendingTangleEdges;
    private String pendingTangleSelFrom;
    private String pendingTangleSelTo;
    private Set<TangleEdgeRenderer.Edge> cycleBreakEdges = Set.of();
    private final Set<TangleEdgeRenderer.Edge> appliedCutEdges = new HashSet<>();

    // Lines need redraw after zoom/scroll changes (perf optimization).
    private boolean linesNeedUpdate = false;

    // Coalesces redraw triggers (expand/collapse, bounds changes, future DnD
    // drops) into one flush per JavaFX pulse. See §2.2 of the
    // ADR_PULSE_COALESCING_AND_DND.
    private final PulseCoalescer arrowsCoalescer =
            new PulseCoalescer(javafx.application.Platform::runLater, this::flushArrowsRedraw);

    // What-If layer: virtual identity + aggregated package graph. Built when
    // a DependencyModel is pushed in; nulled on no-analysis state.
    private WhatIfModel whatIfModel;
    private ArchitectureDragController.DropListener whatIfDropListener;
    private WhatIfDependenciesView whatIfDependenciesView;

    private javafx.scene.layout.Pane zoomableContent;
    private Consumer<String> statusSink = msg -> { /* no-op default */ };
    private Consumer<String> nodeDoubleClickSink = fqn -> { /* no-op default */ };
    private BiConsumer<String, String> tangleEdgeClickedSink = (a, b) -> { /* no-op default */ };
    private BiConsumer<String, String> tangleEdgeCutSink = (a, b) -> { /* no-op default */ };
    private BiConsumer<String, String> tangleEdgeRestoreSink = (a, b) -> { /* no-op default */ };

    // Externally bindable settings.
    private final IntegerProperty packageDepth = new SimpleIntegerProperty(3);
    private final BooleanProperty showDependencies = new SimpleBooleanProperty(false);
    private final BooleanProperty circuitMode = new SimpleBooleanProperty(false);
    private final BooleanProperty showScc = new SimpleBooleanProperty(false);
    private final BooleanProperty showTangleDebugLines = new SimpleBooleanProperty(false);
    // Icon visibility is shared across all open architecture views — boxes bind
    // their FontIcon visibility to this property so toggling refreshes every
    // open tab without rebuilding the tree.
    private static final BooleanProperty SHOW_ICONS = new SimpleBooleanProperty(true);
    private final ReadOnlyObjectWrapper<ArchitectureNode> architectureRoot = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<QualityMetrics> qualityMetrics = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<DomainModel> domainModel = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<DependencyModel> rawDependencyModel = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyStringWrapper selectedFullName = new ReadOnlyStringWrapper(null);
    private String preferredTopTanglesScope;
    private boolean topTanglesScopeOwner = true;

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
        dependencyPane.setManaged(false);

        sccPane = new Pane();
        sccPane.setMouseTransparent(true);
        sccPane.setPickOnBounds(false);
        sccPane.setVisible(false);
        sccPane.setManaged(false);

        whatIfPane = new Pane();
        whatIfPane.setMouseTransparent(true);
        whatIfPane.setPickOnBounds(false);
        whatIfPane.setVisible(true);
        whatIfPane.setManaged(false);

        tanglePane = new Pane();
        tanglePane.setMouseTransparent(false);
        tanglePane.setPickOnBounds(false);
        tanglePane.setVisible(false);
        tanglePane.setManaged(false);

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

        whatIfDependenciesView = new WhatIfDependenciesView();
        setLeft(whatIfDependenciesView);
    }

    private void wirePropertyListeners() {
        showDependencies.addListener((obs, was, isNow) -> applyShowDependencies(isNow));
        showScc.addListener((obs, was, isNow) -> applyShowScc(isNow));
        circuitMode.addListener((obs, was, isNow) -> applyCircuitMode());
        showTangleDebugLines.addListener((obs, was, isNow) -> applyShowTangleDebugLines(isNow));
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

    private void applyShowTangleDebugLines(boolean visible) {
        if (tangleRenderer != null) {
            tangleRenderer.setShowDebugLines(visible);
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
        beginArchitectureRootBuild(rootNode);

        treeBuilder = new ArchitectureTreeBuilder(elementRegistry);
        int maxDepth = packageDepth.get();
        javafx.scene.layout.VBox topLevelContainer = treeBuilder.buildTree(rootNode, maxDepth);
        finishArchitectureRootBuild(rootNode, topLevelContainer);
    }

    public void setArchitectureRootAsync(ArchitectureNode rootNode,
                                         Consumer<BuildProgress> progressSink,
                                         Runnable onComplete) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        beginArchitectureRootBuild(rootNode);

        treeBuilder = new ArchitectureTreeBuilder(elementRegistry);
        int maxDepth = packageDepth.get();
        treeBuilder.buildTreeAsync(rootNode, maxDepth,
                (processed, total, current) -> {
                    if (progressSink != null) {
                        progressSink.accept(new BuildProgress(processed, total, current));
                    }
                },
                topLevelContainer -> {
                    finishArchitectureRootBuild(rootNode, topLevelContainer);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
    }

    private void beginArchitectureRootBuild(ArchitectureNode rootNode) {
        this.currentRootNode = rootNode;
        this.elementRegistry.clear();

        LevelPackageBox.setOnExpandChangeCallback(arrowsCoalescer::markDirty);

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
    }

    private void finishArchitectureRootBuild(ArchitectureNode rootNode,
                                             javafx.scene.layout.VBox topLevelContainer) {
        dependencyPane.getChildren().clear();
        dependencyPane.setVisible(false);
        sccPane.getChildren().clear();
        sccPane.setVisible(false);
        tanglePane.getChildren().clear();
        // Don't reset tanglePane.visible / pendingTangleEdges here — we want
        // the per-tab tangle visualisation to survive a refreshLayout.

        overlayPane = new StackPane();
        overlayPane.setMouseTransparent(false);
        overlayPane.setPickOnBounds(false);
        overlayPane.getChildren().addAll(dependencyPane, sccPane, whatIfPane, tanglePane);

        StackPane contentWithOverlay = new StackPane();
        contentWithOverlay.getChildren().addAll(topLevelContainer, overlayPane);

        this.zoomableContent = contentWithOverlay;

        // Any layout change inside the architecture tree (expand/collapse,
        // resize, future DnD drop) shows up as a bounds-in-parent change of
        // the wrapping content node. Route every such change through the
        // coalescer so we get exactly one arrow redraw per pulse — the
        // listener on the old zoomableContent dies with the node on the next
        // refreshLayout, since nothing else holds a reference.
        contentWithOverlay.boundsInParentProperty()
                .addListener((obs, oldBounds, newBounds) -> arrowsCoalescer.markDirty());

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

        whatIfRenderer = new WhatIfUpwardEdgeRenderer(whatIfPane, elementRegistry);
        whatIfRenderer.setCoordinateContext(zoomableContent, overlayPane);
        if (whatIfDependenciesView != null) {
            whatIfDependenciesView.setModel(whatIfModel, rawDependencyModel.get(), whatIfRenderer);
        }

        tangleRenderer = new TangleEdgeRenderer(tanglePane, elementRegistry, this::setStatus);
        tangleRenderer.setCoordinateContext(zoomableContent, overlayPane);
        tangleRenderer.setOnEdgeClicked(this::handleTangleEdgeClicked);
        tangleRenderer.setOnEdgeCut(this::handleTangleEdgeCut);
        tangleRenderer.setOnEdgeRestore(this::handleTangleEdgeRestore);
        tangleRenderer.setCycleBreakEdges(cycleBreakEdges);
        tangleRenderer.setAppliedCutEdges(appliedCutEdges);
        tangleRenderer.setShowDebugLines(showTangleDebugLines.get());

        dependencyRenderer.clearDependencyArrows();
        sccRenderer.clearSccLines();
        dependencyPane.setVisible(false);
        sccPane.setVisible(false);

        // Reset overlay toggles for the new architecture so the global toolbar resyncs.
        showDependencies.set(false);
        showScc.set(false);

        // Re-apply any pending tangle visualisation now that the renderer
        // exists and the new tree is in place.
        if (pendingTangleEdges != null) {
            tangleRenderer.setEdges(pendingTangleEdges);
            tangleRenderer.setSelectedEdge(pendingTangleSelFrom, pendingTangleSelTo);
            tanglePane.setVisible(true);
        }

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
     * Read-only handle to the raw bytecode-analysis result. Carries per-edge
     * relationship kinds (extends / implements / calls / instantiates) that
     * the {@link #domainModel} flattens away. Pushed in by the host shell
     * after each successful analysis; needed by features that want to know
     * "what kind of dependency is this" (e.g. the Top Tangles view).
     */
    public ReadOnlyObjectProperty<DependencyModel> rawDependencyModelProperty() {
        return rawDependencyModel.getReadOnlyProperty();
    }

    public DependencyModel getRawDependencyModel() {
        return rawDependencyModel.get();
    }

    public void setRawDependencyModel(DependencyModel model) {
        rawDependencyModel.set(model);
        rebuildWhatIfModel(model);
    }

    private void rebuildWhatIfModel(DependencyModel model) {
        whatIfModel = model == null ? null : new WhatIfModel(ClassEdges.fromDependencyModel(model));
        if (whatIfModel != null) {
            whatIfModel.addChangeListener(arrowsCoalescer::markDirty);
        }
        if (whatIfDependenciesView != null) {
            whatIfDependenciesView.setModel(whatIfModel, model, whatIfRenderer);
        }
        ensureWhatIfDropListenerRegistered();
        arrowsCoalescer.markDirty();
    }

    private void ensureWhatIfDropListenerRegistered() {
        if (whatIfDropListener != null) {
            return;
        }
        whatIfDropListener = this::handleWhatIfDrop;
        ArchitectureDragController.addDropListener(whatIfDropListener);
    }

    private void handleWhatIfDrop(javafx.scene.Node movedSource, javafx.scene.layout.HBox destinationRow) {
        if (whatIfModel == null) {
            return;
        }
        if (!isInsideThisView(movedSource)) {
            return;
        }
        if (!(movedSource instanceof GraphSelection.Selectable selectable)) {
            return;
        }
        String movedFqcn = selectable.getFullName();
        if (movedFqcn == null || movedFqcn.isEmpty()) {
            return;
        }
        String destinationContainerFqcn = resolveDestinationContainer(destinationRow);
        if (destinationContainerFqcn == null) {
            return;
        }
        String newVirtualParent = destinationContainerFqcn.isEmpty()
                ? ""
                : whatIfModel.identity().virtualFullName(destinationContainerFqcn);
        String currentVirtualParent = whatIfModel.identity().virtualParent(movedFqcn);
        if (currentVirtualParent.equals(newVirtualParent)) {
            setStatus("What-If: " + simple(movedFqcn) + " — visual reorder only (same virtual parent)");
            return;
        }
        whatIfModel.applyMove(movedFqcn, newVirtualParent);
        setStatus(buildWhatIfStatusMessage(movedFqcn, newVirtualParent));
    }

    private boolean isInsideThisView(javafx.scene.Node node) {
        javafx.scene.Node n = node;
        while (n != null) {
            if (n == this) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    /**
     * Resolve the static fqcn of the package container the drop landed in.
     * Walks up the scene graph from the destination row: the first enclosing
     * {@link LevelPackageBox} wins. If the drop lands at top level (no
     * enclosing package box), the row stack is tagged with the effective
     * root's fqcn by the tree builder and that value is returned — an empty
     * string then means "drop at the architecture's effective root".
     */
    private static String resolveDestinationContainer(javafx.scene.Node row) {
        javafx.scene.Node n = row == null ? null : row.getParent();
        while (n != null) {
            if (n instanceof LevelPackageBox lpb) {
                String fqcn = lpb.getFullName();
                return fqcn == null ? "" : fqcn;
            }
            Object rootTag = n.getProperties().get("s202.whatif.rootFqcn");
            if (rootTag instanceof String s) {
                return s;
            }
            n = n.getParent();
        }
        return null;
    }

    private String buildWhatIfStatusMessage(String movedFqcn, String newVirtualParent) {
        int wrongDirectionClassEdges = 0;
        if (whatIfRenderer != null && whatIfModel != null) {
            for (var v : whatIfRenderer.findVisibleViolations(whatIfModel.staticEdges())) {
                wrongDirectionClassEdges += v.classEdges().size();
            }
        }
        return String.format("What-If: %s → %s — %d wrong-direction class edge%s",
                simple(movedFqcn), newVirtualParent,
                wrongDirectionClassEdges, wrongDirectionClassEdges == 1 ? "" : "s");
    }

    /** Read-only handle to the current What-If model, or {@code null} when no analysis is loaded. */
    public WhatIfModel getWhatIfModel() {
        return whatIfModel;
    }

    public String getPreferredTopTanglesScope() {
        return preferredTopTanglesScope;
    }

    public void setPreferredTopTanglesScope(String scope) {
        preferredTopTanglesScope = scope == null || scope.isBlank() ? null : scope;
    }

    /**
     * Whether this view drives TopTangles scope tracking. True for all regular
     * architecture and scope views; false for tangle satellite tabs, which
     * inherit their scope from the source view and must not reset it on focus.
     */
    public boolean isTopTanglesScopeOwner() {
        return topTanglesScopeOwner;
    }

    public void setTopTanglesScopeOwner(boolean owner) {
        topTanglesScopeOwner = owner;
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
        if (whatIfRenderer != null) {
            whatIfRenderer.redraw(whatIfModel);
        }
        applyVirtuallyMovedDecorations();
    }

    private void applyVirtuallyMovedDecorations() {
        if (whatIfModel == null) {
            return;
        }
        for (Map.Entry<String, javafx.scene.Node> entry : elementRegistry.entrySet()) {
            String fqcn = entry.getKey();
            boolean moved = whatIfModel.identity().hasOverride(fqcn);
            javafx.scene.Node node = entry.getValue();
            if (node instanceof LevelClassBox cls) {
                cls.setVirtuallyMoved(moved);
            } else if (node instanceof LevelPackageBox pkg) {
                pkg.setVirtuallyMoved(moved);
            }
        }
    }

    /**
     * Flush handler for {@link #arrowsCoalescer}: runs at most once per pulse
     * once any source (expand/collapse, bounds change, future DnD drop) has
     * marked the arrows dirty. Forcing layout before reading bounds is a cheap
     * defensive guard — by the time the coalescer fires, the FX queue has
     * already advanced one pulse, so layout is normally settled, but a node
     * whose ancestor was hidden may still have invalid bounds.
     */
    private void flushArrowsRedraw() {
        if (getScene() == null || getScene().getRoot() == null) {
            return;
        }
        if (zoomableContent != null) {
            zoomableContent.requestLayout();
        }
        getScene().getRoot().layout();
        redrawVisibleArrows();
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
     * Set an explicit zoom factor (clamped to the controller's range).
     * No-op if the zoom controller hasn't been initialised yet.
     */
    public void setZoom(double factor) {
        if (zoomController != null) {
            zoomController.setZoom(factor);
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

    public BooleanProperty showTangleDebugLinesProperty() {
        return showTangleDebugLines;
    }

    public boolean isShowTangleDebugLines() {
        return showTangleDebugLines.get();
    }

    public void setShowTangleDebugLines(boolean show) {
        showTangleDebugLines.set(show);
    }

    /**
     * Highlight a specific SCC edge ({@code from} → {@code to}). Pass
     * {@code null} to clear. The highlight survives subsequent SCC
     * re-draws (e.g. zoom changes) until cleared. No-op if the SCC
     * renderer hasn't been initialised yet.
     */
    public void highlightSccEdge(String from, String to) {
        if (sccRenderer != null) {
            sccRenderer.highlightEdge(from, to);
        }
    }

    /**
     * Install a tangle-specific edge overlay on top of the architecture
     * tree. Replaces the legacy SCC visualisation for this view with
     * properly clipped arrows that dock to the box perimeters and a single
     * highlighted "selected" edge.
     * <p>
     * Pass {@code null} or an empty list to remove the overlay. Pinning is
     * the caller's job — calling this method again with new data updates
     * the overlay in place. Survives {@link #refreshLayout()}.
     */
    public void setTangleVisualization(List<TangleEdgeRenderer.Edge> edges,
                                       String selectedFrom, String selectedTo) {
        if (edges == null || edges.isEmpty()) {
            pendingTangleEdges = null;
            pendingTangleSelFrom = null;
            pendingTangleSelTo = null;
            if (tangleRenderer != null) {
                tangleRenderer.clear();
            }
            if (tanglePane != null) {
                tanglePane.setVisible(false);
            }
            return;
        }
        pendingTangleEdges = List.copyOf(edges);
        pendingTangleSelFrom = selectedFrom;
        pendingTangleSelTo = selectedTo;
        if (tangleRenderer != null) {
            tangleRenderer.setEdges(pendingTangleEdges);
            tangleRenderer.setSelectedEdge(selectedFrom, selectedTo);
        }
        if (tanglePane != null) {
            tanglePane.setVisible(true);
        }
    }

    /** Update only the selected tangle edge without re-supplying the edge list. */
    public void setSelectedTangleEdge(String from, String to) {
        pendingTangleSelFrom = from;
        pendingTangleSelTo = to;
        if (tangleRenderer != null) {
            tangleRenderer.setSelectedEdge(from, to);
        }
    }

    public Set<TangleEdgeRenderer.Edge> getCycleBreakEdges() {
        return cycleBreakEdges;
    }

    public void setCycleBreakEdges(Set<TangleEdgeRenderer.Edge> cycleBreakEdges) {
        this.cycleBreakEdges = cycleBreakEdges == null ? Set.of() : Set.copyOf(cycleBreakEdges);
        if (tangleRenderer != null) {
            tangleRenderer.setCycleBreakEdges(this.cycleBreakEdges);
        }
    }

    public void setAppliedTangleCutEdges(Set<TangleEdgeRenderer.Edge> appliedCutEdges) {
        this.appliedCutEdges.clear();
        if (appliedCutEdges != null) {
            this.appliedCutEdges.addAll(appliedCutEdges);
        }
        if (tangleRenderer != null) {
            tangleRenderer.setAppliedCutEdges(this.appliedCutEdges);
        }
    }

    public void applyTangleEdgeCut(String from, String to) {
        if (from == null || to == null) {
            return;
        }
        TangleEdgeRenderer.Edge cut = new TangleEdgeRenderer.Edge(from, to);
        if (!appliedCutEdges.add(cut)) {
            return;
        }
        if (from.equals(pendingTangleSelFrom) && to.equals(pendingTangleSelTo)) {
            pendingTangleSelFrom = null;
            pendingTangleSelTo = null;
            tangleEdgeClickedSink.accept(null, null);
        }
        if (tangleRenderer != null) {
            tangleRenderer.setAppliedCutEdges(appliedCutEdges);
            tangleRenderer.setSelectedEdge(pendingTangleSelFrom, pendingTangleSelTo);
        }
        setStatus("Refactoring Preview: cut " + simple(from) + " -> " + simple(to));
    }

    public void applyTangleEdgeCuts(Collection<TangleEdgeRenderer.Edge> cuts) {
        if (cuts == null || cuts.isEmpty()) {
            return;
        }
        int added = 0;
        for (TangleEdgeRenderer.Edge cut : cuts) {
            if (cut == null || cut.from() == null || cut.to() == null) {
                continue;
            }
            if (appliedCutEdges.add(cut)) {
                added++;
            }
            if (cut.from().equals(pendingTangleSelFrom) && cut.to().equals(pendingTangleSelTo)) {
                pendingTangleSelFrom = null;
                pendingTangleSelTo = null;
                tangleEdgeClickedSink.accept(null, null);
            }
        }
        if (added == 0) {
            return;
        }
        if (tangleRenderer != null) {
            tangleRenderer.setAppliedCutEdges(appliedCutEdges);
            tangleRenderer.setSelectedEdge(pendingTangleSelFrom, pendingTangleSelTo);
        }
        setStatus("Refactoring Preview: cut " + added + " tangle edge" + (added == 1 ? "" : "s"));
    }

    public void restoreTangleEdgeCut(String from, String to) {
        if (from == null || to == null) {
            return;
        }
        TangleEdgeRenderer.Edge cut = new TangleEdgeRenderer.Edge(from, to);
        if (!appliedCutEdges.remove(cut)) {
            return;
        }
        if (tangleRenderer != null) {
            tangleRenderer.setAppliedCutEdges(appliedCutEdges);
        }
        setStatus("Restored preview cut: " + simple(from) + " -> " + simple(to));
    }

    private void handleTangleEdgeClicked(String from, String to) {
        pendingTangleSelFrom = from;
        pendingTangleSelTo = to;
        tangleEdgeClickedSink.accept(from, to);
    }

    private void handleTangleEdgeCut(String from, String to) {
        if (from == null || to == null || pendingTangleEdges == null) {
            return;
        }
        TangleEdgeRenderer.Edge cut = new TangleEdgeRenderer.Edge(from, to);
        if (!pendingTangleEdges.contains(cut)) {
            return;
        }
        applyTangleEdgeCut(from, to);
        tangleEdgeCutSink.accept(from, to);
    }

    private void handleTangleEdgeRestore(String from, String to) {
        restoreTangleEdgeCut(from, to);
        tangleEdgeRestoreSink.accept(from, to);
    }

    private static String simple(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
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

    /**
     * Set a sink that receives {@code (from, to)} whenever the user clicks
     * a tangle SCC edge in the {@link TangleEdgeRenderer} overlay. Pass
     * {@code null} to detach.
     */
    public void setOnTangleEdgeClicked(BiConsumer<String, String> sink) {
        this.tangleEdgeClickedSink = sink == null ? (a, b) -> {} : sink;
        if (tangleRenderer != null) {
            tangleRenderer.setOnEdgeClicked(this::handleTangleEdgeClicked);
        }
    }

    /**
     * Set a sink that receives {@code (from, to)} whenever the user applies a
     * recommended cut edge in the tangle overlay. Pass {@code null} to detach.
     */
    public void setOnTangleEdgeCut(BiConsumer<String, String> sink) {
        this.tangleEdgeCutSink = sink == null ? (a, b) -> {} : sink;
        if (tangleRenderer != null) {
            tangleRenderer.setOnEdgeCut(this::handleTangleEdgeCut);
        }
    }

    /**
     * Set a sink that receives {@code (from, to)} whenever the user restores a
     * refactoring-preview cut edge in the tangle overlay. Pass {@code null} to detach.
     */
    public void setOnTangleEdgeRestore(BiConsumer<String, String> sink) {
        this.tangleEdgeRestoreSink = sink == null ? (a, b) -> {} : sink;
        if (tangleRenderer != null) {
            tangleRenderer.setOnEdgeRestore(this::handleTangleEdgeRestore);
        }
    }
}
