/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.ui.core.canvas.WhatIfEditController;
import de.weigend.s202.ui.core.canvas.WhatIfUndoManager;
import de.weigend.s202.ui.core.canvas.EdgeOverlayController;
import de.weigend.s202.ui.core.graph.ArchitectureDragController;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.graph.PulseCoalescer;
import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureContext;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ArchitectureStyle;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.HexagonalArchitecture;
import de.weigend.s202.domain.architecture.LayeredArchitecture;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.domain.architecture.WhatIfArchitecture;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.core.model.ScopeExtensionModel;
import de.weigend.s202.ui.core.arrows.DependencyRenderer;
import de.weigend.s202.ui.core.arrows.DependencyRendererStrategy;
import de.weigend.s202.ui.core.arrows.SCCRenderer;
import de.weigend.s202.ui.core.arrows.WhatIfUpwardEdgeRenderer;
import de.weigend.s202.ui.core.canvas.ArchitectureTreeBuilder;
import de.weigend.s202.ui.views.component.ComponentArchitectureTreeBuilder;
import de.weigend.s202.ui.views.hexagonal.HexagonalArchitectureTreeBuilder;
import de.weigend.s202.ui.core.canvas.ZoomController;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
public class ArchitectureCanvas extends AbstractArchitectureCanvas {

    public record BuildProgress(int processedNodes, int totalNodes, String currentElement) {}

    private ScrollPane scrollPane;
    private Pane dependencyPane;   // Container for dependency lines
    private Pane sccPane;          // Container for SCC lines
    private Pane whatIfPane;       // Container for What-If upward-edge overlay
    private Pane edgeOverlayPane;       // Container for the dedicated pinned-edge overlay
    private StackPane overlayPane; // Contains dependency, SCC, What-If and tangle panes
    private StackPane contentPane;
    private ArchitectureNode currentRootNode;
    private final Map<String, javafx.scene.Node> elementRegistry = new HashMap<>();

    private ZoomController zoomController;


    // Coalesces redraw triggers (expand/collapse, bounds changes, future DnD
    // drops) into one flush per JavaFX pulse. See §2.2 of the
    // ADR_PULSE_COALESCING_AND_DND.
    private final PulseCoalescer arrowsCoalescer =
            new PulseCoalescer(javafx.application.Platform::runLater, this::flushArrowsRedraw);

    // Top-level VBox of the current layout — used by applyMoveToScene for root-level moves.
    private VBox whatIfRootContainer;

    // Feature-Controller (nach setupUI initialisiert, s. Konstruktor).
    private final SelectionController selection;
    private final OverlayRenderCoordinator overlay;
    private final de.weigend.s202.ui.core.spi.ViewServices viewServices;
    /** Eine zustandsbehaftete StyleView-Instanz pro Stil, lazy erzeugt und gecacht. */
    private final Map<ArchitectureKind, de.weigend.s202.ui.core.spi.StyleView> styleViews =
            new java.util.EnumMap<>(ArchitectureKind.class);
    private final StyleProjectionStateKeeper stateKeeper;
    private final EdgeOverlayController edgeOverlay;
    private final WhatIfEditController whatIfEdit;
    private final ScopeExtensionController scopeExtension;

    // Pulses every time the arrow overlay finishes a redraw. WFX side panels
    // (e.g. the Dependencies module) can subscribe to refresh themselves.
    private final javafx.beans.property.LongProperty redrawTick =
            new javafx.beans.property.SimpleLongProperty(0);

    private javafx.scene.layout.Pane zoomableContent;
    private final Consumer<String> graphSelectionSink;

    private final ArchitectureProjectionModel projection =
            new ArchitectureProjectionModel(this::getViewStyle, this::updateSccRendererTangles);
    private final ElementBoundsExporter boundsExporter =
            new ElementBoundsExporter(elementRegistry, () -> zoomableContent, this::getScene);

    public ArchitectureCanvas() {
        setupUI();
        selection = new SelectionController(elementRegistry, scrollPane, arrowsCoalescer, this::invalidateLines);
        graphSelectionSink = selection::handleGraphSelectionChanged;
        stateKeeper = new StyleProjectionStateKeeper(
                scrollPane, () -> zoomController, () -> whatIfRootContainer, zoomFactor::get, arrowsCoalescer,
                this::styleView);
        edgeOverlay = new EdgeOverlayController(edgeOverlayPane, this::setStatus);
        whatIfEdit = new WhatIfEditController(
                this,
                elementRegistry,
                arrowsCoalescer,
                this::setStatus,
                projection::getWhatIfArchitecture,
                this::getViewStyle,
                () -> whatIfRootContainer,
                new WhatIfEditController.ComponentApiAnnotator() {
                    @Override
                    public void addToApi(String fqn) {
                        setArchitectureAnnotations(getArchitectureAnnotations().withComponentApiIncluded(fqn));
                        refreshStyleProjection("Added to API: " + fqn);
                    }

                    @Override
                    public void removeFromApi(String fqn) {
                        setArchitectureAnnotations(getArchitectureAnnotations().withComponentApiExcluded(fqn));
                        refreshStyleProjection("Removed from API: " + fqn);
                    }
                },
                this::resetVisualLayout,
                showWhatIfViolations);
        overlay = new OverlayRenderCoordinator(
                dependencyPane, sccPane, whatIfPane, edgeOverlayPane,
                elementRegistry,
                showDependencies, showScc, showPackageScc, showWhatIfViolations, showOverlayDebugLines,
                arrowsCoalescer,
                edgeOverlay,
                projection,
                () -> currentRootNode,
                this::styleView,
                selection::getSelectedFullName,
                this::setStatus,
                whatIfEdit::applyVirtuallyMovedDecorations);
        viewServices = new de.weigend.s202.ui.core.spi.ViewServices(
                elementRegistry,
                graphSelectionSink,
                this::getArchitectureAnnotations,
                this::handleProjectionAnnotationsChanged,
                projection::getRawDependencyModel,
                projection::getArchitecture,
                arrowsCoalescer::markDirty,
                this::isSkipTransparentTopLevelPackages,
                () -> getScene() == null ? null : getScene().getWindow(),
                this::setStatus,
                this::refreshStyleProjection,
                () -> whatIfEdit.undoManager().effectiveMoves(),
                edgeOverlay::appliedCutEdges);
        scopeExtension = new ScopeExtensionController(
                this::setStatus,
                () -> currentRootNode,
                this::getScopeExtensionSourceRoot,
                () -> getScene() == null ? null : getScene().getWindow(),
                this::setArchitectureRoot,
                this::selectByFullName);
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
        whatIfPane.setVisible(false);
        whatIfPane.setManaged(false);

        edgeOverlayPane = new Pane();
        edgeOverlayPane.setMouseTransparent(false);
        edgeOverlayPane.setPickOnBounds(false);
        edgeOverlayPane.setVisible(false);
        edgeOverlayPane.setManaged(false);

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

    @Override
    void onViewStyleChanged() {
        projection.rebuildArchitectureProjection();
    }

    /**
     * Kontextmenü des Canvas: stil-spezifische Einträge der aktiven StyleView
     * plus „Add to Scope…“, wenn diese View eine Scope-Erweiterung hat.
     */
    private void installContextMenu(javafx.scene.Node target) {
        boolean hasStyleItems = !styleView().contextMenuItems().isEmpty();
        if (!hasStyleItems && getScopeExtensionSourceRoot() == null) {
            target.setOnContextMenuRequested(null);
            return;
        }
        target.setOnContextMenuRequested(event -> {
            ContextMenu menu = new ContextMenu();
            menu.getItems().addAll(styleView().contextMenuItems());
            if (getScopeExtensionSourceRoot() != null) {
                menu.getItems().add(scopeExtension.addToScopeMenuItem());
            }
            if (!menu.getItems().isEmpty()) {
                menu.show(target, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
    }

    /** Die StyleView des aktiven Stils — pro Stil einmal erzeugt (SPI-Lookup). */
    private de.weigend.s202.ui.core.spi.StyleView styleView() {
        return styleViews.computeIfAbsent(getViewStyle(), kind ->
                Lookup.findAll(de.weigend.s202.ui.core.spi.StyleViewFactory.class).stream()
                        .filter(factory -> factory.kind() == kind)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No style view registered for " + kind))
                        .create(viewServices));
    }

    /** Gemeinsamer Handler für Annotations-Änderungen aus den Stil-Buildern. */
    private void handleProjectionAnnotationsChanged(ArchitectureAnnotations nextAnnotations, String message) {
        setArchitectureAnnotations(nextAnnotations);
        refreshStyleProjection(message);
    }

    private void invalidateLines() {
        overlay.invalidateLines();
    }

    private void updateSccRendererTangles() {
        overlay.updateSccRendererTangles();
    }

    private void wirePropertyListeners() {
        showDependencies.addListener((obs, was, isNow) -> overlay.applyShowDependencies(isNow));
        showScc.addListener((obs, was, isNow) -> overlay.applyShowScc(isNow));
        showPackageScc.addListener((obs, was, isNow) -> overlay.applyShowPackageScc(isNow));
        showWhatIfViolations.addListener((obs, was, isNow) -> overlay.applyShowWhatIfViolations(isNow));
        showOverlayDebugLines.addListener((obs, was, isNow) -> overlay.applyShowOverlayDebugLines(isNow));
    }

    /**
     * Sets the ArchitectureNode root for level-based layout display.
     * Populates the ScrollPane with a LevelPackageBox hierarchy.
     * The synthetic "root" node is hidden - only its children are displayed.
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        setArchitectureRoot(rootNode, null);
    }

    public void setArchitectureRootAsync(ArchitectureNode rootNode,
                                         Consumer<BuildProgress> progressSink,
                                         Runnable onComplete) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        beginArchitectureRootBuild(rootNode);

        int maxDepth = packageDepth.get();
        styleView().buildTreeAsync(rootNode, maxDepth,
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

        // Node selection is now a single-click action. Keep the legacy
        // double-click callback disconnected to avoid duplicate selection
        // events when users double-click out of habit.
        GraphSelection.setOnDoubleClick(fqn -> {});
    }

    private void refreshStyleProjection(String statusMessage) {
        if (currentRootNode == null) {
            return;
        }
        StyleProjectionStateKeeper.ViewState viewState = stateKeeper.capture();
        String selected = selection.getSelectedFullName();
        boolean depsSave = showDependencies.get();
        boolean sccSave = showScc.get();
        boolean pkgSccSave = showPackageScc.get();
        boolean wifSave = showWhatIfViolations.get();
        setArchitectureRoot(currentRootNode, viewState);
        showDependencies.set(depsSave);
        showScc.set(sccSave);
        showPackageScc.set(pkgSccSave);
        showWhatIfViolations.set(wifSave);
        if (selected != null) {
            selection.restoreSelectionWithoutScrolling(selected);
        }
        if (statusMessage != null && !statusMessage.isBlank()) {
            setStatus(statusMessage);
        }
    }

    /**
     * Rebuilds a style-specific projection while preserving the user's
     * viewport and expansion state. Used when shared annotations change in a
     * different component/hexagonal view.
     */
    public void refreshStyleProjection() {
        refreshStyleProjection(null);
    }


    private void setArchitectureRoot(ArchitectureNode rootNode, StyleProjectionStateKeeper.ViewState preservedState) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        beginArchitectureRootBuild(rootNode);

        int maxDepth = packageDepth.get();
        javafx.scene.layout.VBox topLevelContainer = styleView().buildTree(rootNode, maxDepth);
        finishArchitectureRootBuild(rootNode, topLevelContainer, preservedState);
    }

    private void finishArchitectureRootBuild(ArchitectureNode rootNode,
                                             javafx.scene.layout.VBox topLevelContainer) {
        finishArchitectureRootBuild(rootNode, topLevelContainer, null);
    }

    private void finishArchitectureRootBuild(ArchitectureNode rootNode,
                                             javafx.scene.layout.VBox topLevelContainer,
                                             StyleProjectionStateKeeper.ViewState preservedState) {
        this.whatIfRootContainer = topLevelContainer;
        if (preservedState != null) {
            stateKeeper.restoreExpansionState(topLevelContainer, preservedState);
        }
        styleView().afterContentBuilt(topLevelContainer);
        dependencyPane.getChildren().clear();
        dependencyPane.setVisible(false);
        sccPane.getChildren().clear();
        sccPane.setVisible(false);
        edgeOverlayPane.getChildren().clear();
        // Don't reset edgeOverlayPane.visible / pending overlay edges here — we want
        // the per-tab edge overlay to survive a refreshLayout.

        overlayPane = new StackPane();
        overlayPane.setMouseTransparent(false);
        overlayPane.setPickOnBounds(false);
        overlayPane.getChildren().addAll(dependencyPane, sccPane, whatIfPane, edgeOverlayPane);

        StackPane contentWithOverlay = new StackPane();
        contentWithOverlay.getChildren().addAll(topLevelContainer, overlayPane);
        installContextMenu(contentWithOverlay);

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

        zoomController = new ZoomController(zoomableContent, overlay::handleZoomChanged);
        zoomController.zoomFactorProperty().addListener(
                (obs, was, isNow) -> zoomFactor.set(isNow.doubleValue()));
        if (preservedState != null) {
            zoomController.setZoom(preservedState.zoomFactor());
        } else {
            zoomController.resetZoom();
        }

        overlay.rebuildRenderers(zoomableContent, overlayPane, scrollPane, zoomController);

        // Reset overlay toggles for the new architecture so the global toolbar resyncs.
        showDependencies.set(false);
        showScc.set(false);
        showPackageScc.set(false);

        if (preservedState != null) {
            stateKeeper.restoreViewportState(preservedState);
            stateKeeper.scheduleDeferredViewportRestore(preservedState);
        }
        showWhatIfViolations.set(false);

        // Re-apply any pending edge overlay now that the renderer
        // exists and the new tree is in place.
        edgeOverlay.reapplyPendingEdges();

        setStatus("Architecture loaded: " + rootNode.getLevelCount() + " levels");

        // Notify external observers (e.g. Outline Explorer) last, so the registry
        // is fully populated before listeners run lookups.
        architectureRoot.set(rootNode);
    }

    /**
     * Read-only handle to the analyzed domain model. Pushed in by the host
     * shell after each successful analysis; needed by panels that compute
     * scoped metrics (e.g. quality view for a selected package).
     */
    public ReadOnlyObjectProperty<DomainModel> domainModelProperty() {
        return projection.domainModelProperty();
    }

    public DomainModel getDomainModel() {
        return projection.getDomainModel();
    }

    public void setDomainModel(DomainModel model) {
        whatIfEdit.undoManager().clear();
        projection.setDomainModel(model);
    }

    /**
     * Read-only handle to the architecture derived from the current
     * {@link DomainModel}. Built once per analysis (when
     * {@link #setDomainModel} fires). The canonical source for
     * structural information, violations, and tangles — UI panels
     * consume from here.
     */
    public ReadOnlyObjectProperty<Architecture> architectureProperty() {
        return projection.architectureProperty();
    }

    public Architecture getArchitecture() {
        return projection.getArchitecture();
    }

    /**
     * Read-only handle to the mutable What-If counterpart of
     * {@link #architectureProperty()}. Starts as a deep copy of the
     * original; the DnD drop handler mutates it via {@code moveElement}
     * so {@link WhatIfArchitecture#violations()} reflects the user's
     * current rearrangement. Rebuilt on each {@link #setDomainModel}.
     */
    public ReadOnlyObjectProperty<WhatIfArchitecture> whatIfArchitectureProperty() {
        return projection.whatIfArchitectureProperty();
    }

    public WhatIfArchitecture getWhatIfArchitecture() {
        return projection.getWhatIfArchitecture();
    }

    public ObjectProperty<ArchitectureAnnotations> architectureAnnotationsProperty() {
        return projection.architectureAnnotationsProperty();
    }

    public ArchitectureAnnotations getArchitectureAnnotations() {
        return projection.getArchitectureAnnotations();
    }

    public void setArchitectureAnnotations(ArchitectureAnnotations annotations) {
        projection.setArchitectureAnnotations(annotations);
    }

    /**
     * Read-only handle to the raw bytecode-analysis result. Carries per-edge
     * relationship kinds (extends / implements / calls / instantiates) that
     * the {@link #domainModel} flattens away. Pushed in by the host shell
     * after each successful analysis; needed by features that want to know
     * "what kind of dependency is this" (e.g. the Top Tangles view).
     */
    public ReadOnlyObjectProperty<DependencyModel> rawDependencyModelProperty() {
        return projection.rawDependencyModelProperty();
    }

    public DependencyModel getRawDependencyModel() {
        return projection.getRawDependencyModel();
    }

    public void setRawDependencyModel(DependencyModel model) {
        projection.setRawDependencyModelValue(model);
        whatIfEdit.clearMovedFqns();
        if ((viewStyle == ArchitectureKind.COMPONENT
                || viewStyle == ArchitectureKind.HEXAGONAL)
                && projection.getDomainModel() != null) {
            projection.rebuildArchitectureProjection();
        }
        whatIfEdit.ensureDropListenerRegistered();
        arrowsCoalescer.markDirty();
    }

    /** Renderer that paints wrong-direction edges — exposed so side panels can query violations. */
    public WhatIfUpwardEdgeRenderer getWhatIfRenderer() {
        return overlay.whatIfRenderer();
    }

    /**
     * Long-typed pulse counter that increments after every successful
     * arrow-overlay flush. Side panels that derive their content from the
     * current scene positions (e.g. the What-If Dependencies module) bind
     * to this to refresh in sync with the canvas.
     */
    public javafx.beans.value.ObservableValue<Number> redrawTickProperty() {
        return redrawTick;
    }

    /**
     * Read-only handle to the currently selected node's full name (class or
     * package), or null when nothing is selected.
     */
    public ReadOnlyStringProperty selectedFullNameProperty() {
        return selection.selectedFullNameProperty();
    }

    public String getSelectedFullName() {
        return selection.getSelectedFullName();
    }

    /**
     * Select the node (class or package) with the given full name (if present)
     * and scroll it into view. No-op if the name is unknown or the
     * architecture is not yet loaded. Idempotent — re-selecting the current
     * node does not toggle it off.
     */
    @Override
    public void selectByFullName(String fullName) {
        selection.selectByFullName(fullName);
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
        overlay.redrawVisibleArrows();
        redrawTick.set(redrawTick.get() + 1);
    }

    /**
     * Force CSS, layout, and all currently enabled overlays into a stable
     * state before a JavaFX snapshot is taken. Used by the quality report
     * exporter so report evidence images are screenshots of this real view
     * pipeline, not a separate renderer.
     */
    public void prepareForSnapshot() {
        applyCss();
        layout();
        if (zoomableContent != null) {
            zoomableContent.applyCss();
            zoomableContent.requestLayout();
            zoomableContent.layout();
        }
        if (getScene() != null && getScene().getRoot() != null) {
            getScene().getRoot().applyCss();
            getScene().getRoot().layout();
        }
        overlay.redrawVisibleArrows();
    }

    /**
     * Returns the full architecture content node for report screenshots. This
     * bypasses the ScrollPane viewport so exports capture the complete evidence
     * view, including overlays, instead of only the currently visible window.
     */
    public javafx.scene.Node snapshotContentNode() {
        return zoomableContent == null ? this : zoomableContent;
    }

    public Bounds snapshotContentBounds() {
        javafx.scene.Node node = snapshotContentNode();
        Bounds bounds = node.getBoundsInLocal();
        if (bounds == null || bounds.getWidth() <= 1.0 || bounds.getHeight() <= 1.0) {
            bounds = node.getLayoutBounds();
        }
        return bounds;
    }

    /**
     * Re-runs the layout for the currently loaded architecture (e.g. after a
     * package-depth change).
     */
    public void refreshLayout() {
        resetVisualLayout();
        whatIfEdit.undoManager().clear();
    }

    private void resetVisualLayout() {
        if (currentRootNode == null) {
            return;
        }
        // finishArchitectureRootBuild resets overlay toggles to false so that the
        // toolbar syncs correctly when a new JAR is loaded.  For a depth-change
        // refresh we want to keep whatever the user had enabled, so save and
        // restore them around the rebuild.
        boolean depsSave    = showDependencies.get();
        boolean sccSave     = showScc.get();
        boolean pkgSccSave2 = showPackageScc.get();
        boolean wifSave     = showWhatIfViolations.get();

        WhatIfArchitecture wif = projection.getWhatIfArchitecture();
        if (wif != null) {
            wif.reset();
        }
        whatIfEdit.clearMovedFqns();
        setArchitectureRoot(currentRootNode);

        showDependencies.set(depsSave);
        showScc.set(sccSave);
        showPackageScc.set(pkgSccSave2);
        showWhatIfViolations.set(wifSave);
    }

    public void undoWhatIf() {
        whatIfEdit.undo();
    }

    public void redoWhatIf() {
        whatIfEdit.redo();
    }

    public BooleanProperty canUndoWhatIfProperty() { return whatIfEdit.undoManager().canUndoProperty(); }
    public BooleanProperty canRedoWhatIfProperty() { return whatIfEdit.undoManager().canRedoProperty(); }

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

    /* ----- Settings properties --------------------------------------------- */

    /**
     * Highlight a specific SCC edge ({@code from} → {@code to}). Pass
     * {@code null} to clear. The highlight survives subsequent SCC
     * re-draws (e.g. zoom changes) until cleared. No-op if the SCC
     * renderer hasn't been initialised yet.
     */
    public void highlightSccEdge(String from, String to) {
        overlay.highlightSccEdge(from, to);
    }

    public void setEdgeOverlay(List<DependencyEdge> edges,
                                       String selectedFrom, String selectedTo) {
        edgeOverlay.setVisualization(edges, selectedFrom, selectedTo);
    }

    /** Update only the selected overlay edge without re-supplying the edge list. */
    public void setSelectedOverlayEdge(String from, String to) {
        edgeOverlay.setSelectedEdge(from, to);
    }

    public Set<DependencyEdge> getCycleBreakEdges() {
        return edgeOverlay.getCycleBreakEdges();
    }

    public void setCycleBreakEdges(Set<DependencyEdge> cycleBreakEdges) {
        edgeOverlay.setCycleBreakEdges(cycleBreakEdges);
    }

    public void setAppliedCutEdges(Set<DependencyEdge> appliedCutEdges) {
        edgeOverlay.setAppliedCutEdges(appliedCutEdges);
    }

    public void applyEdgeCut(String from, String to) {
        edgeOverlay.applyEdgeCut(from, to);
    }

    public void applyEdgeCuts(Collection<DependencyEdge> cuts) {
        edgeOverlay.applyEdgeCuts(cuts);
    }

    public void restoreEdgeCut(String from, String to) {
        edgeOverlay.restoreEdgeCut(from, to);
    }

    private static String simple(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /* ----- Bounds-Export (3D-Ansicht, Report-Screenshots) ------------------- */

    public java.util.Map<String, javafx.geometry.Bounds> getElementBoundsInScene() {
        return boundsExporter.elementBoundsInScene();
    }

    public java.util.Map<String, javafx.geometry.Bounds> getElementFootprintBoundsInScene() {
        return boundsExporter.elementFootprintBoundsInScene();
    }

    public java.util.Map<String, javafx.geometry.Bounds> getElementFootprintBoundsInLayout() {
        return boundsExporter.elementFootprintBoundsInLayout();
    }

    public java.util.Map<String, String> getVisibleElementParentFqns() {
        return boundsExporter.visibleElementParentFqns();
    }

    /* ----- Status sink ----------------------------------------------------- */

    /**
     * Set a sink that receives the full name whenever the user selects a node
     * (class or package) in the graph. Pass null to detach.
     */
    @Override
    public void setOnNodeSelected(Consumer<String> sink) {
        selection.setOnNodeSelected(sink);
    }

    /**
     * Set a sink that receives {@code (from, to)} whenever the user clicks
     * a tangle SCC edge in the {@link TangleEdgeRenderer} overlay. Pass
     * {@code null} to detach.
     */
    public void setOnOverlayEdgeClicked(BiConsumer<String, String> sink) {
        edgeOverlay.setOnEdgeClicked(sink);
    }

    /**
     * Set a sink that receives {@code (from, to)} whenever the user applies a
     * recommended cut edge in the edge overlay. Pass {@code null} to detach.
     */
    public void setOnOverlayEdgeCut(BiConsumer<String, String> sink) {
        edgeOverlay.setOnEdgeCut(sink);
    }

    /**
     * Set a sink that receives {@code (from, to)} whenever the user restores a
     * refactoring-preview cut edge in the edge overlay. Pass {@code null} to detach.
     */
    public void setOnOverlayEdgeRestore(BiConsumer<String, String> sink) {
        edgeOverlay.setOnEdgeRestore(sink);
    }
}
