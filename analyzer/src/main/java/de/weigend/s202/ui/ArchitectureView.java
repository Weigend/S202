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
package de.weigend.s202.ui;

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
import de.weigend.s202.domain.architecture.HierarchicalLayeredArchitecture;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.domain.architecture.WhatIfArchitecture;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.component.ComponentBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.model.ScopeExtensionModel;
import de.weigend.s202.ui.rendering.DependencyRenderer;
import de.weigend.s202.ui.rendering.DependencyRendererStrategy;
import de.weigend.s202.ui.rendering.SCCRenderer;
import de.weigend.s202.ui.rendering.TangleEdgeRenderer;
import de.weigend.s202.ui.rendering.WhatIfUpwardEdgeRenderer;
import de.weigend.s202.ui.tree.ArchitectureTreeBuilder;
import de.weigend.s202.ui.tree.ComponentArchitectureTreeBuilder;
import de.weigend.s202.ui.tree.HexagonalArchitectureTreeBuilder;
import de.weigend.s202.ui.zoom.ZoomController;
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
    private SCCRenderer sccRenderer;
    private WhatIfUpwardEdgeRenderer whatIfRenderer;
    private TangleEdgeRenderer tangleRenderer;
    private ArchitectureTreeBuilder treeBuilder;
    private ZoomController zoomController;

    // Pending tangle visualisation snapshot, applied once setArchitectureRoot
    // (re-)builds the renderer. Set by setTangleVisualization before the root
    // is assigned, or restored after a refreshLayout.
    private List<DependencyEdge> pendingTangleEdges;
    private String pendingTangleSelFrom;
    private String pendingTangleSelTo;
    private Set<DependencyEdge> cycleBreakEdges = Set.of();
    private final Set<DependencyEdge> appliedCutEdges = new HashSet<>();

    // Lines need redraw after zoom/scroll changes (perf optimization).
    private boolean linesNeedUpdate = false;

    // Coalesces redraw triggers (expand/collapse, bounds changes, future DnD
    // drops) into one flush per JavaFX pulse. See §2.2 of the
    // ADR_PULSE_COALESCING_AND_DND.
    private final PulseCoalescer arrowsCoalescer =
            new PulseCoalescer(javafx.application.Platform::runLater, this::flushArrowsRedraw);

    // What-If layer: drives the orange "moved" glow on the affected boxes.
    // Cleared on every fresh analysis (setRawDependencyModel with a non-null
    // model). The structural truth of where each box currently sits lives in
    // {@link #whatIfArchitecture}; this set only tracks "user touched it" for
    // the cosmetic decoration.
    private final Set<String> movedFqns = new HashSet<>();
    private ArchitectureDragController.DropListener whatIfDropListener;

    // Undo/redo for What-If moves.
    private final WhatIfUndoManager undoManager = new WhatIfUndoManager();
    // Top-level VBox of the current layout — used by applyMoveToScene for root-level moves.
    private VBox whatIfRootContainer;

    // Pulses every time the arrow overlay finishes a redraw. WFX side panels
    // (e.g. the Dependencies module) can subscribe to refresh themselves.
    private final javafx.beans.property.LongProperty redrawTick =
            new javafx.beans.property.SimpleLongProperty(0);

    private javafx.scene.layout.Pane zoomableContent;
    private Consumer<String> statusSink = msg -> { /* no-op default */ };
    private Consumer<String> nodeSelectionSink = fqn -> { /* no-op default */ };
    private final Consumer<String> graphSelectionSink = this::handleGraphSelectionChanged;
    private boolean suppressSelectionSink = false;
    private BiConsumer<String, String> tangleEdgeClickedSink = (a, b) -> { /* no-op default */ };
    private BiConsumer<String, String> tangleEdgeCutSink = (a, b) -> { /* no-op default */ };
    private BiConsumer<String, String> tangleEdgeRestoreSink = (a, b) -> { /* no-op default */ };

    // Externally bindable settings.
    private final IntegerProperty packageDepth = new SimpleIntegerProperty(3);
    private final BooleanProperty showDependencies = new SimpleBooleanProperty(false);
    private final BooleanProperty showScc = new SimpleBooleanProperty(false);
    private final BooleanProperty showPackageScc = new SimpleBooleanProperty(false);
    private final BooleanProperty showWhatIfViolations = new SimpleBooleanProperty(false);
    private final BooleanProperty showTangleDebugLines = new SimpleBooleanProperty(false);
    private final ReadOnlyDoubleWrapper zoomFactor = new ReadOnlyDoubleWrapper(1.0);
    // Icon visibility is shared across all open architecture views — boxes bind
    // their FontIcon visibility to this property so toggling refreshes every
    // open tab without rebuilding the tree.
    private static final BooleanProperty SHOW_ICONS = new SimpleBooleanProperty(true);
    // Global architecture-level label visibility. Boxes append "G:n" to their
    // header label when true. Local level "L:n" stays as the placement indicator;
    // the global level reveals depth the package-aligned 2D layout otherwise hides.
    private static final BooleanProperty SHOW_ARCHITECTURE_LEVEL = new SimpleBooleanProperty(false);
    private final ReadOnlyObjectWrapper<ArchitectureNode> architectureRoot = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<QualityMetrics> qualityMetrics = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<DomainModel> domainModel = new ReadOnlyObjectWrapper<>(null);
    private final SimpleObjectProperty<ArchitectureAnnotations> architectureAnnotations =
            new SimpleObjectProperty<>(ArchitectureAnnotations.empty());
    private final ReadOnlyObjectWrapper<Architecture> architecture = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<WhatIfArchitecture> whatIfArchitecture = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<DependencyModel> rawDependencyModel = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyStringWrapper selectedFullName = new ReadOnlyStringWrapper(null);
    private String preferredTopTanglesScope;
    private boolean topTanglesScopeOwner = true;
    private boolean skipTransparentTopLevelPackages = true;
    private ArchitectureNode scopeExtensionSourceRoot;
    private ArchitectureViewStyle viewStyle = ArchitectureViewStyle.LAYERED;
    private final java.util.LinkedHashMap<String, String> plannedPackageNames = new java.util.LinkedHashMap<>();
    private static final Set<ViolationKind> LAYERED_VIOLATION_OVERLAY_KINDS =
            Set.of(ViolationKind.UPWARD);
    private static final Set<ViolationKind> COMPONENT_VIOLATION_OVERLAY_KINDS =
            Set.of(
                    ViolationKind.COMPONENT_API_BYPASS,
                    ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION,
                    ViolationKind.COMPONENT_INTERNAL_LAYER_BREAK);
    private static final Set<ViolationKind> HEXAGONAL_VIOLATION_OVERLAY_KINDS =
            Set.of(
                    ViolationKind.HEXAGON_OUTWARD_DEPENDENCY,
                    ViolationKind.HEXAGON_PORT_BYPASS);

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
        whatIfPane.setVisible(false);
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
    }

    private void wirePropertyListeners() {
        showDependencies.addListener((obs, was, isNow) -> applyShowDependencies(isNow));
        showScc.addListener((obs, was, isNow) -> applyShowScc(isNow));
        showPackageScc.addListener((obs, was, isNow) -> applyShowPackageScc(isNow));
        showWhatIfViolations.addListener((obs, was, isNow) -> applyShowWhatIfViolations(isNow));
        showTangleDebugLines.addListener((obs, was, isNow) -> applyShowTangleDebugLines(isNow));
    }

    private void applyShowDependencies(boolean visible) {
        if (dependencyRenderer == null || dependencyPane == null) {
            return;
        }
        if (visible) {
            dependencyPane.setVisible(true);
            if (currentRootNode != null && (!dependencyRenderer.isDependencyLinesDrawn() || linesNeedUpdate)) {
                arrowsCoalescer.markDirty();
            }
        } else {
            dependencyPane.setVisible(false);
        }
    }

    private void applyShowScc(boolean visible) {
        if (sccRenderer == null || sccPane == null) return;
        sccRenderer.setShowClassScc(visible);
        refreshSccPane();
    }

    private void applyShowPackageScc(boolean visible) {
        if (sccRenderer == null || sccPane == null) return;
        sccRenderer.setShowPackageCycles(visible);
        refreshSccPane();
    }

    private void refreshSccPane() {
        boolean anyEnabled = showScc.get() || showPackageScc.get();
        if (anyEnabled) {
            updateSccRendererTangles();
            sccRenderer.clearSccLines();
            if (currentRootNode != null) {
                sccRenderer.drawSccLines(currentRootNode);
                linesNeedUpdate = false;
            }
            sccPane.setVisible(true);
        } else {
            sccPane.setVisible(false);
        }
    }

    private void applyShowWhatIfViolations(boolean visible) {
        if (whatIfPane == null) {
            return;
        }
        whatIfPane.setVisible(visible);
        if (visible) {
            arrowsCoalescer.markDirty();
        } else if (whatIfRenderer != null) {
            whatIfRenderer.clear();
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
            drawDependencyArrows();
        }
        if ((showScc.get() || showPackageScc.get()) && sccPane != null && sccPane.isVisible()) {
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
        setArchitectureRoot(rootNode, null);
    }

    public void setArchitectureRootAsync(ArchitectureNode rootNode,
                                         Consumer<BuildProgress> progressSink,
                                         Runnable onComplete) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        beginArchitectureRootBuild(rootNode);

        int maxDepth = packageDepth.get();
        buildTreeAsync(rootNode, maxDepth,
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

    private javafx.scene.layout.VBox buildTree(ArchitectureNode rootNode, int maxDepth) {
        if (viewStyle == ArchitectureViewStyle.COMPONENT) {
            ComponentArchitectureTreeBuilder componentBuilder =
                    new ComponentArchitectureTreeBuilder(
                            elementRegistry,
                            graphSelectionSink,
                            getArchitectureAnnotations(),
                            rawDependencyModel.get(),
                            architecture.get() instanceof ComponentArchitecture component ? component : null,
                            this::handleComponentApiChanged);
            return componentBuilder.buildTree(rootNode, maxDepth);
        }
        if (viewStyle == ArchitectureViewStyle.HEXAGONAL) {
            HexagonalArchitectureTreeBuilder hexagonalBuilder =
                    new HexagonalArchitectureTreeBuilder(
                            elementRegistry,
                            graphSelectionSink,
                            getArchitectureAnnotations(),
                            architecture.get() instanceof HexagonalArchitecture hex ? hex : null,
                            this::handleHexagonalAnnotationsChanged,
                            arrowsCoalescer::markDirty);
            return hexagonalBuilder.buildTree(rootNode, maxDepth);
        }

        treeBuilder = new ArchitectureTreeBuilder(elementRegistry, graphSelectionSink);
        return treeBuilder.buildTree(rootNode, maxDepth, skipTransparentTopLevelPackages);
    }

    private void buildTreeAsync(ArchitectureNode rootNode,
                                int maxDepth,
                                ArchitectureTreeBuilder.ProgressSink progressSink,
                                Consumer<javafx.scene.layout.VBox> onComplete) {
        if (viewStyle == ArchitectureViewStyle.COMPONENT) {
            ComponentArchitectureTreeBuilder componentBuilder =
                    new ComponentArchitectureTreeBuilder(
                            elementRegistry,
                            graphSelectionSink,
                            getArchitectureAnnotations(),
                            rawDependencyModel.get(),
                            architecture.get() instanceof ComponentArchitecture component ? component : null,
                            this::handleComponentApiChanged);
            componentBuilder.buildTreeAsync(rootNode, maxDepth, progressSink, onComplete);
            return;
        }
        if (viewStyle == ArchitectureViewStyle.HEXAGONAL) {
            HexagonalArchitectureTreeBuilder hexagonalBuilder =
                    new HexagonalArchitectureTreeBuilder(
                            elementRegistry,
                            graphSelectionSink,
                            getArchitectureAnnotations(),
                            architecture.get() instanceof HexagonalArchitecture hex ? hex : null,
                            this::handleHexagonalAnnotationsChanged,
                            arrowsCoalescer::markDirty);
            hexagonalBuilder.buildTreeAsync(rootNode, maxDepth, progressSink, onComplete);
            return;
        }

        treeBuilder = new ArchitectureTreeBuilder(elementRegistry, graphSelectionSink);
        treeBuilder.buildTreeAsync(rootNode, maxDepth,
                skipTransparentTopLevelPackages,
                progressSink,
                onComplete);
    }

    private void beginArchitectureRootBuild(ArchitectureNode rootNode) {
        this.currentRootNode = rootNode;
        this.elementRegistry.clear();

        LevelPackageBox.setOnExpandChangeCallback(arrowsCoalescer::markDirty);
        ComponentBox.setOnExpandChangeCallback(arrowsCoalescer::markDirty);

        // Node selection is now a single-click action. Keep the legacy
        // double-click callback disconnected to avoid duplicate selection
        // events when users double-click out of habit.
        GraphSelection.setOnDoubleClick(fqn -> {});
    }

    private void handleGraphSelectionChanged(String fqn) {
        selectedFullName.set(fqn);
        invalidateLines();
        // Defer arrow redraw via the coalescer so the CSS layout pass
        // (border-width change on the selected box) finishes first —
        // direct drawDependencyArrows() here uses stale bounds.
        arrowsCoalescer.markDirty();
        if (!suppressSelectionSink && fqn != null) {
            nodeSelectionSink.accept(fqn);
        }
    }

    private void handleComponentApiChanged(ArchitectureAnnotations nextAnnotations, String message) {
        setArchitectureAnnotations(nextAnnotations);
        refreshStyleProjection(message);
    }

    private void handleHexagonalAnnotationsChanged(ArchitectureAnnotations nextAnnotations, String message) {
        setArchitectureAnnotations(nextAnnotations);
        refreshStyleProjection(message);
    }

    private void refreshStyleProjection(String statusMessage) {
        if (currentRootNode == null) {
            return;
        }
        StyleProjectionViewState viewState = captureStyleProjectionViewState();
        String selected = selectedFullName.get();
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
            restoreSelectionWithoutScrolling(selected);
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

    private StyleProjectionViewState captureStyleProjectionViewState() {
        Map<String, Boolean> packageExpansion = new HashMap<>();
        Map<String, Boolean> packageExpansionByFqn = new HashMap<>();
        Map<String, ComponentExpansionState> componentExpansion = new HashMap<>();
        collectExpansionState(
                whatIfRootContainer,
                packageExpansion,
                packageExpansionByFqn,
                componentExpansion);
        return new StyleProjectionViewState(
                zoomFactor.get(),
                scrollPane == null ? 0.0 : scrollPane.getHvalue(),
                scrollPane == null ? 0.0 : scrollPane.getVvalue(),
                packageExpansion,
                packageExpansionByFqn,
                componentExpansion);
    }

    private static void collectExpansionState(javafx.scene.Node node,
                                              Map<String, Boolean> packageExpansion,
                                              Map<String, Boolean> packageExpansionByFqn,
                                              Map<String, ComponentExpansionState> componentExpansion) {
        if (node == null) {
            return;
        }
        if (node instanceof ComponentBox component) {
            componentExpansion.put(
                    component.getFullName(),
                    new ComponentExpansionState(component.isExpanded(), component.isApiExpanded()));
            packageExpansionByFqn.putIfAbsent(component.getFullName(), component.isExpanded());
        } else if (node instanceof LevelPackageBox pkg && pkg.getFullName() != null) {
            packageExpansion.put(packageExpansionKey(pkg), pkg.isExpanded());
            packageExpansionByFqn.putIfAbsent(pkg.getFullName(), pkg.isExpanded());
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectExpansionState(child, packageExpansion, packageExpansionByFqn, componentExpansion);
            }
        }
    }

    private void scheduleDeferredViewportRestore(StyleProjectionViewState state) {
        ZoomController restoredZoomController = zoomController;
        javafx.scene.Node restoredScrollContent = scrollPane == null ? null : scrollPane.getContent();
        scheduleDeferredViewportRestore(state, restoredZoomController, restoredScrollContent, 2);
    }

    private void scheduleDeferredViewportRestore(StyleProjectionViewState state,
                                                 ZoomController restoredZoomController,
                                                 javafx.scene.Node restoredScrollContent,
                                                 int remainingPulses) {
        javafx.application.Platform.runLater(() -> {
            if (zoomController != restoredZoomController
                    || scrollPane == null
                    || scrollPane.getContent() != restoredScrollContent) {
                return;
            }
            restoreViewportState(state);
            if (remainingPulses > 1) {
                scheduleDeferredViewportRestore(
                        state,
                        restoredZoomController,
                        restoredScrollContent,
                        remainingPulses - 1);
                return;
            }
            arrowsCoalescer.markDirty();
        });
    }

    private static void restoreExpansionState(javafx.scene.Node node, StyleProjectionViewState state) {
        if (node == null) {
            return;
        }
        if (node instanceof ComponentBox component) {
            ComponentExpansionState expansion = state.componentExpansion().get(component.getFullName());
            if (expansion != null) {
                component.setExpanded(expansion.expanded());
                component.setApiExpanded(expansion.apiExpanded());
            } else {
                Boolean expanded = state.packageExpansionByFqn().get(component.getFullName());
                if (expanded != null) {
                    component.setExpanded(expanded);
                }
            }
        } else if (node instanceof LevelPackageBox pkg && pkg.getFullName() != null) {
            Boolean expanded = state.packageExpansion().get(packageExpansionKey(pkg));
            if (expanded == null) {
                // A package moved between implementation and API during this
                // refresh. Its FQN fallback keeps the state across that role change.
                expanded = state.packageExpansionByFqn().get(pkg.getFullName());
            }
            if (expanded != null) {
                pkg.setExpanded(expanded);
            }
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                restoreExpansionState(child, state);
            }
        }
    }

    private void restoreViewportState(StyleProjectionViewState state) {
        if (zoomController != null) {
            zoomController.setZoom(state.zoomFactor());
        }
        if (scrollPane != null) {
            scrollPane.setHvalue(state.horizontalScroll());
            scrollPane.setVvalue(state.verticalScroll());
        }
    }

    private void restoreSelectionWithoutScrolling(String fullName) {
        javafx.scene.Node node = elementRegistry.get(fullName);
        if (node instanceof GraphSelection.Selectable target) {
            runWithoutSelectionSink(() -> GraphSelection.ensureSelected(target));
            return;
        }
        if (node != null) {
            runWithoutSelectionSink(GraphSelection::clear);
            selectedFullName.set(fullName);
            invalidateLines();
            arrowsCoalescer.markDirty();
        }
    }

    private static String packageExpansionKey(LevelPackageBox pkg) {
        String role = ComponentBox.isApiElement(pkg) ? "api:" : "regular:";
        return role + pkg.getFullName();
    }

    private record ComponentExpansionState(boolean expanded, boolean apiExpanded) {}

    private record StyleProjectionViewState(
            double zoomFactor,
            double horizontalScroll,
            double verticalScroll,
            Map<String, Boolean> packageExpansion,
            Map<String, Boolean> packageExpansionByFqn,
            Map<String, ComponentExpansionState> componentExpansion) {

        private StyleProjectionViewState {
            packageExpansion = Map.copyOf(packageExpansion);
            packageExpansionByFqn = Map.copyOf(packageExpansionByFqn);
            componentExpansion = Map.copyOf(componentExpansion);
        }
    }

    private void setArchitectureRoot(ArchitectureNode rootNode, StyleProjectionViewState preservedState) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        beginArchitectureRootBuild(rootNode);

        int maxDepth = packageDepth.get();
        javafx.scene.layout.VBox topLevelContainer = buildTree(rootNode, maxDepth);
        finishArchitectureRootBuild(rootNode, topLevelContainer, preservedState);
    }

    private void finishArchitectureRootBuild(ArchitectureNode rootNode,
                                             javafx.scene.layout.VBox topLevelContainer) {
        finishArchitectureRootBuild(rootNode, topLevelContainer, null);
    }

    private void finishArchitectureRootBuild(ArchitectureNode rootNode,
                                             javafx.scene.layout.VBox topLevelContainer,
                                             StyleProjectionViewState preservedState) {
        this.whatIfRootContainer = topLevelContainer;
        if (preservedState != null) {
            restoreExpansionState(topLevelContainer, preservedState);
        }
        if (viewStyle == ArchitectureViewStyle.COMPONENT && !plannedPackageNames.isEmpty()) {
            injectPlannedPackagesIntoScene(topLevelContainer);
        }
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
        installScopeExtensionContextMenu(contentWithOverlay);

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
        zoomController.zoomFactorProperty().addListener(
                (obs, was, isNow) -> zoomFactor.set(isNow.doubleValue()));
        if (preservedState != null) {
            zoomController.setZoom(preservedState.zoomFactor());
        } else {
            zoomController.resetZoom();
        }

        classicRenderer = new DependencyRenderer(dependencyPane, elementRegistry, zoomController, this::setStatus);
        classicRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        dependencyRenderer = classicRenderer;

        sccRenderer = new SCCRenderer(sccPane, elementRegistry, this::setStatus);
        sccRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        updateSccRendererTangles();

        whatIfRenderer = new WhatIfUpwardEdgeRenderer(whatIfPane, elementRegistry);
        whatIfRenderer.setCoordinateContext(zoomableContent, overlayPane);

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
        showPackageScc.set(false);

        if (preservedState != null) {
            restoreViewportState(preservedState);
            scheduleDeferredViewportRestore(preservedState);
        }
        showWhatIfViolations.set(false);

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

    private void installScopeExtensionContextMenu(javafx.scene.Node target) {
        boolean componentMode = viewStyle == ArchitectureViewStyle.COMPONENT;
        if (scopeExtensionSourceRoot == null && !componentMode) {
            target.setOnContextMenuRequested(null);
            return;
        }

        target.setOnContextMenuRequested(event -> {
            ContextMenu menu = new ContextMenu();
            if (componentMode) {
                MenuItem addPkg = new MenuItem("Add Planned Package...");
                addPkg.setOnAction(action -> showAddPlannedPackageDialog());
                menu.getItems().add(addPkg);
                MenuItem reportItem = new MenuItem("Refactoring Report...");
                reportItem.setOnAction(action -> showRefactoringReport());
                menu.getItems().add(reportItem);
            }
            if (scopeExtensionSourceRoot != null) {
                MenuItem addToScope = new MenuItem("Add to Scope...");
                addToScope.setOnAction(action -> showScopeExtensionDialog());
                menu.getItems().add(addToScope);
            }
            if (!menu.getItems().isEmpty()) {
                menu.show(target, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
    }

    private void showScopeExtensionDialog() {
        if (scopeExtensionSourceRoot == null || currentRootNode == null) {
            return;
        }

        List<ScopeExtensionModel.Candidate> candidates =
                ScopeExtensionModel.candidates(scopeExtensionSourceRoot, currentRootNode);
        if (candidates.isEmpty()) {
            setStatus("Scope already contains all packages and classes");
            return;
        }

        Dialog<ScopeExtensionModel.Candidate> dialog = new Dialog<>();
        dialog.setTitle("Add to Scope");
        dialog.setHeaderText("Select a package or class");
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }

        ButtonType addButtonType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        TextField filterField = new TextField();
        filterField.setPromptText("Filter packages/classes");

        ObservableList<ScopeExtensionModel.Candidate> items = FXCollections.observableArrayList(candidates);
        FilteredList<ScopeExtensionModel.Candidate> filteredItems = new FilteredList<>(items, item -> true);
        filterField.textProperty().addListener((obs, oldValue, newValue) -> {
            String query = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            filteredItems.setPredicate(item -> query.isEmpty()
                    || item.fullName().toLowerCase(Locale.ROOT).contains(query)
                    || item.kind().toLowerCase(Locale.ROOT).contains(query));
        });

        ListView<ScopeExtensionModel.Candidate> candidateList = new ListView<>(filteredItems);
        candidateList.setPrefWidth(620);
        candidateList.setPrefHeight(420);
        candidateList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ScopeExtensionModel.Candidate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        candidateList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY
                    && event.getClickCount() == 2
                    && candidateList.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(candidateList.getSelectionModel().getSelectedItem());
                dialog.close();
            }
        });

        javafx.scene.Node addButton = dialogPane.lookupButton(addButtonType);
        addButton.disableProperty().bind(candidateList.getSelectionModel().selectedItemProperty().isNull());

        VBox content = new VBox(8, filterField, candidateList);
        dialogPane.setContent(content);
        dialog.setResultConverter(button -> button == addButtonType
                ? candidateList.getSelectionModel().getSelectedItem()
                : null);

        Optional<ScopeExtensionModel.Candidate> selected = dialog.showAndWait();
        selected.ifPresent(this::addScopeCandidate);
    }

    private void addScopeCandidate(ScopeExtensionModel.Candidate candidate) {
        if (candidate == null || currentRootNode == null || scopeExtensionSourceRoot == null) {
            return;
        }

        ArchitectureNode extendedRoot = ArchitectureNodeCloner.cloneTree(currentRootNode);
        boolean added = ScopeExtensionModel.addToScope(
                extendedRoot,
                scopeExtensionSourceRoot,
                candidate.fullName());
        if (!added) {
            setStatus("Scope already contains " + candidate.fullName());
            return;
        }

        setArchitectureRoot(extendedRoot);
        selectByFullName(candidate.fullName());
        setStatus("Scope extended: " + candidate.fullName());
    }

    private void showAddPlannedPackageDialog() {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("Add Planned Package");
        dialog.setHeaderText("Enter a name for the new planned package");
        dialog.setContentText("Package name:");
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.showAndWait()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .ifPresent(name -> {
                    String fqn = "virtual-pkg-" + java.util.UUID.randomUUID().toString().replace("-", "");
                    plannedPackageNames.put(fqn, name);
                    refreshStyleProjection("Added planned package: " + name);
                });
    }

    private void showRenamePlannedPackageDialog(String fqn) {
        String currentName = plannedPackageNames.getOrDefault(fqn, "");
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(currentName);
        dialog.setTitle("Rename Package");
        dialog.setHeaderText("Enter a new name for the planned package");
        dialog.setContentText("Package name:");
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.showAndWait()
                .map(String::trim)
                .filter(name -> !name.isEmpty() && !name.equals(currentName))
                .ifPresent(name -> {
                    plannedPackageNames.put(fqn, name);
                    refreshStyleProjection("Renamed planned package to: " + name);
                });
    }

    private void deletePlannedPackage(String fqn) {
        String name = plannedPackageNames.remove(fqn);
        if (name != null) {
            refreshStyleProjection("Deleted planned package: " + name);
        }
    }

    public void showRefactoringReport() {
        String json = buildRefactoringReportJson();

        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(json);
        area.setEditable(false);
        area.setWrapText(false);
        area.setFont(javafx.scene.text.Font.font("Monospaced", 12));
        area.setPrefRowCount(28);
        area.setPrefColumnCount(80);

        javafx.scene.control.ButtonType copyButton = new javafx.scene.control.ButtonType(
                "Copy to Clipboard", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        javafx.scene.control.ButtonType closeButton = javafx.scene.control.ButtonType.CLOSE;

        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Refactoring Report");
        dialog.setHeaderText("All architecture changes made in this session — paste into an AI prompt.");
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.getDialogPane().getButtonTypes().addAll(copyButton, closeButton);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().setPrefSize(820, 560);

        javafx.scene.Node copyNode = dialog.getDialogPane().lookupButton(copyButton);
        copyNode.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(json);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            e.consume();
        });

        dialog.showAndWait();
    }

    private String buildRefactoringReportJson() {
        // Deduplicate moves: keep only the last move per FQN (latest destination wins).
        java.util.LinkedHashMap<String, WhatIfUndoManager.Move> lastMove = new java.util.LinkedHashMap<>();
        for (WhatIfUndoManager.Move m : undoManager.effectiveMoves()) {
            lastMove.put(m.fqn(), m);
        }

        ArchitectureAnnotations ann = getArchitectureAnnotations();
        java.util.List<String> apiAdded = new java.util.ArrayList<>(ann.componentApiIncludes());
        java.util.List<String> apiRemoved = new java.util.ArrayList<>(ann.componentApiExcludes());

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"tool\": \"Structure202\",\n");
        sb.append("  \"view\": \"Component View\",\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"plannedPackages\": ").append(plannedPackageNames.size()).append(",\n");
        sb.append("    \"movedElements\": ").append(lastMove.size()).append(",\n");
        sb.append("    \"cutDependencies\": ").append(appliedCutEdges.size()).append(",\n");
        sb.append("    \"apiAdded\": ").append(apiAdded.size()).append(",\n");
        sb.append("    \"apiRemoved\": ").append(apiRemoved.size()).append("\n");
        sb.append("  },\n");
        sb.append("  \"changes\": [\n");

        java.util.List<String> entries = new java.util.ArrayList<>();

        for (String displayName : plannedPackageNames.values()) {
            entries.add("    {\n"
                    + "      \"action\": \"CREATE_PACKAGE\",\n"
                    + "      \"name\": " + jsonStr(displayName) + ",\n"
                    + "      \"note\": \"Planned package for cycle-free refactoring\"\n"
                    + "    }");
        }

        for (WhatIfUndoManager.Move m : lastMove.values()) {
            String dest = m.containerFqn() == null || m.containerFqn().isEmpty()
                    ? "(top-level)" : m.containerFqn();
            entries.add("    {\n"
                    + "      \"action\": \"MOVE\",\n"
                    + "      \"element\": " + jsonStr(m.fqn()) + ",\n"
                    + "      \"toPackage\": " + jsonStr(dest) + ",\n"
                    + "      \"toRow\": " + m.rowIndex() + ",\n"
                    + "      \"note\": \"Move class/package to new position to eliminate layer violation\"\n"
                    + "    }");
        }

        for (de.weigend.s202.domain.DependencyEdge e : appliedCutEdges) {
            entries.add("    {\n"
                    + "      \"action\": \"CUT_DEPENDENCY\",\n"
                    + "      \"from\": " + jsonStr(e.from()) + ",\n"
                    + "      \"to\": " + jsonStr(e.to()) + ",\n"
                    + "      \"note\": \"Remove this direct dependency to break the tangle\"\n"
                    + "    }");
        }

        for (String fqn : apiAdded) {
            entries.add("    {\n"
                    + "      \"action\": \"ADD_TO_API\",\n"
                    + "      \"element\": " + jsonStr(fqn) + ",\n"
                    + "      \"note\": \"Expose this element as part of the component's public API\"\n"
                    + "    }");
        }

        for (String fqn : apiRemoved) {
            entries.add("    {\n"
                    + "      \"action\": \"REMOVE_FROM_API\",\n"
                    + "      \"element\": " + jsonStr(fqn) + ",\n"
                    + "      \"note\": \"Remove this element from the public API surface\"\n"
                    + "    }");
        }

        sb.append(String.join(",\n", entries));
        if (!entries.isEmpty()) {
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void injectPlannedPackagesIntoScene(VBox topLevelContainer) {
        HBox plannedRow = new HBox(10);
        plannedRow.setMaxWidth(Double.MAX_VALUE);
        plannedRow.setAlignment(Pos.CENTER);
        VBox.setVgrow(plannedRow, Priority.ALWAYS);
        ArchitectureDragController.markAsRow(plannedRow);

        for (Map.Entry<String, String> entry : plannedPackageNames.entrySet()) {
            String fqn = entry.getKey();
            String displayName = entry.getValue();
            ComponentBox box = new ComponentBox(displayName, fqn, 0);
            box.setSelectionChangeSink(graphSelectionSink);
            box.setCustomStyles(
                    "-fx-background-color: #f0f7e8; -fx-border-color: #4a8a3a; -fx-border-width: 2;"
                  + " -fx-border-style: dashed; -fx-background-radius: 7; -fx-border-radius: 7;",
                    "-fx-background-color: #f0f7e8; -fx-border-color: #ff8a00; -fx-border-width: 3;"
                  + " -fx-border-style: dashed; -fx-background-radius: 7; -fx-border-radius: 7;");
            attachPlannedPackageContextMenu(box, fqn);
            elementRegistry.put(fqn, box);
            HBox.setHgrow(box, Priority.ALWAYS);
            plannedRow.getChildren().add(box);
        }

        topLevelContainer.getChildren().add(plannedRow);
    }

    private void attachPlannedPackageContextMenu(ComponentBox box, String fqn) {
        box.setOnContextMenuRequested(event -> {
            MenuItem rename = new MenuItem("Rename Package...");
            rename.setOnAction(a -> showRenamePlannedPackageDialog(fqn));
            MenuItem delete = new MenuItem("Delete Package");
            delete.setOnAction(a -> deletePlannedPackage(fqn));
            new ContextMenu(rename, delete).show(box, event.getScreenX(), event.getScreenY());
            event.consume();
        });
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
        undoManager.clear();
        rebuildArchitectureProjection();
    }

    /**
     * Read-only handle to the architecture derived from the current
     * {@link DomainModel}. Built once per analysis (when
     * {@link #setDomainModel} fires). The canonical source for
     * structural information, violations, and tangles — UI panels
     * consume from here.
     */
    public ReadOnlyObjectProperty<Architecture> architectureProperty() {
        return architecture.getReadOnlyProperty();
    }

    public Architecture getArchitecture() {
        return architecture.get();
    }

    /**
     * Read-only handle to the mutable What-If counterpart of
     * {@link #architectureProperty()}. Starts as a deep copy of the
     * original; the DnD drop handler mutates it via {@code moveElement}
     * so {@link WhatIfArchitecture#violations()} reflects the user's
     * current rearrangement. Rebuilt on each {@link #setDomainModel}.
     */
    public ReadOnlyObjectProperty<WhatIfArchitecture> whatIfArchitectureProperty() {
        return whatIfArchitecture.getReadOnlyProperty();
    }

    public WhatIfArchitecture getWhatIfArchitecture() {
        return whatIfArchitecture.get();
    }

    public ObjectProperty<ArchitectureAnnotations> architectureAnnotationsProperty() {
        return architectureAnnotations;
    }

    public ArchitectureAnnotations getArchitectureAnnotations() {
        ArchitectureAnnotations annotations = architectureAnnotations.get();
        return annotations == null ? ArchitectureAnnotations.empty() : annotations;
    }

    public void setArchitectureAnnotations(ArchitectureAnnotations annotations) {
        architectureAnnotations.set(annotations == null ? ArchitectureAnnotations.empty() : annotations);
        rebuildArchitectureProjection();
    }

    private void rebuildArchitectureProjection() {
        DomainModel model = domainModel.get();
        if (model == null) {
            architecture.set(null);
            whatIfArchitecture.set(null);
            return;
        }
        Architecture original;
        ArchitectureContext context = new ArchitectureContext(
                rawDependencyModel.get(),
                model,
                getArchitectureAnnotations());
        if (viewStyle == ArchitectureViewStyle.COMPONENT) {
            original = requireArchitectureStyle(ArchitectureKind.COMPONENT).build(context);
        } else if (viewStyle == ArchitectureViewStyle.HEXAGONAL) {
            original = requireArchitectureStyle(ArchitectureKind.HEXAGONAL).build(context);
        } else {
            original = requireArchitectureStyle(ArchitectureKind.LAYERED).build(context);
        }
        architecture.set(original);
        whatIfArchitecture.set(original instanceof HierarchicalLayeredArchitecture hla
                ? new WhatIfArchitecture(hla, model)
                : null);
        updateSccRendererTangles();
    }

    private static ArchitectureStyle requireArchitectureStyle(ArchitectureKind kind) {
        List<ArchitectureStyle> styles = Lookup.findAll(ArchitectureStyle.class);
        return styles.stream()
                .filter(style -> style.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No architecture style registered for " + kind));
    }

    private void updateSccRendererTangles() {
        if (sccRenderer == null) return;
        Architecture arch = packageCycleArchitecture();
        if (arch instanceof WhatIfArchitecture wif) {
            Map<String, String> classPackages = wif.classPackages();
            sccRenderer.setPackageResolver(fqn -> classPackages.getOrDefault(fqn, staticPackageOf(fqn)));
        } else {
            sccRenderer.setPackageResolver(ArchitectureView::staticPackageOf);
        }
        if (arch != null) {
            sccRenderer.setPackageTangles(
                    arch.tangles().stream()
                            .map(t -> (java.util.Set<String>) t.members())
                            .collect(java.util.stream.Collectors.toList()));
        } else {
            sccRenderer.setPackageTangles(java.util.List.of());
        }
    }

    private Architecture packageCycleArchitecture() {
        WhatIfArchitecture wif = whatIfArchitecture.get();
        return wif != null ? wif : architecture.get();
    }

    private static String staticPackageOf(String fqn) {
        int dot = fqn == null ? -1 : fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
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
        movedFqns.clear();
        if ((viewStyle == ArchitectureViewStyle.COMPONENT
                || viewStyle == ArchitectureViewStyle.HEXAGONAL)
                && domainModel.get() != null) {
            rebuildArchitectureProjection();
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

    private void handleWhatIfDrop(javafx.scene.Node movedSource,
                                  javafx.scene.layout.HBox destinationRow,
                                  boolean wasNewRow) {
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
        if (viewStyle == ArchitectureViewStyle.COMPONENT
                && handleComponentApiDrop(movedSource, movedFqcn, destinationRow)) {
            return;
        }
        String destinationContainerFqcn = resolveDestinationContainer(destinationRow);
        if (destinationContainerFqcn == null) {
            return;
        }
        WhatIfArchitecture wif = whatIfArchitecture.get();
        if (wif == null) {
            return;
        }
        if (!(destinationRow.getParent() instanceof javafx.scene.layout.VBox stack)) {
            return;
        }
        int rowIndex = stack.getChildren().indexOf(destinationRow);
        if (rowIndex < 0) {
            return;
        }
        if (wasNewRow) {
            undoManager.record(new WhatIfUndoManager.Move.AsNewRow(movedFqcn, destinationContainerFqcn, rowIndex));
            wif.moveElementAsNewRow(movedFqcn, destinationContainerFqcn, rowIndex);
        } else {
            int colIndex = destinationRow.getChildren().indexOf(movedSource);
            if (colIndex < 0) {
                return;
            }
            undoManager.record(new WhatIfUndoManager.Move.InRow(movedFqcn, destinationContainerFqcn, rowIndex, colIndex));
            wif.moveElement(movedFqcn, destinationContainerFqcn, rowIndex, colIndex);
        }
        movedFqns.add(movedFqcn);
        arrowsCoalescer.markDirty();
        setStatus(buildWhatIfStatusMessage(movedFqcn, destinationContainerFqcn));
    }

    private boolean handleComponentApiDrop(javafx.scene.Node movedSource,
                                           String movedFqcn,
                                           javafx.scene.layout.HBox destinationRow) {
        boolean apiDestination = ComponentBox.isApiDropTarget(destinationRow);
        boolean apiSource = ComponentBox.isApiElement(movedSource);
        if (!apiDestination && !apiSource) {
            return false;
        }
        if (apiDestination && apiSource) {
            arrowsCoalescer.markDirty();
            return true;
        }
        if (apiDestination) {
            setArchitectureAnnotations(getArchitectureAnnotations().withComponentApiIncluded(movedFqcn));
            refreshStyleProjection("Added to API: " + movedFqcn);
        } else {
            setArchitectureAnnotations(getArchitectureAnnotations().withComponentApiExcluded(movedFqcn));
            refreshStyleProjection("Removed from API: " + movedFqcn);
        }
        return true;
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
     * root's fqcn by the tree builder, but the {@link WhatIfArchitecture}
     * uses {@code ""} for its root regardless of any transparent passthroughs
     * the builder skipped, so the empty string is what we return here.
     */
    private static String resolveDestinationContainer(javafx.scene.Node row) {
        javafx.scene.Node n = row == null ? null : row.getParent();
        while (n != null) {
            if (n instanceof LevelPackageBox lpb) {
                String fqcn = lpb.getFullName();
                return fqcn == null ? "" : fqcn;
            }
            if (n.getProperties().get("s202.whatif.rootFqcn") instanceof String) {
                return "";
            }
            n = n.getParent();
        }
        return null;
    }

    private String buildWhatIfStatusMessage(String movedFqcn, String destinationContainerFqcn) {
        String parentLabel = destinationContainerFqcn.isEmpty() ? "<root>" : destinationContainerFqcn;
        return String.format("What-If: %s → %s — marked as moved", simple(movedFqcn), parentLabel);
    }

    /** Renderer that paints wrong-direction edges — exposed so side panels can query violations. */
    public WhatIfUpwardEdgeRenderer getWhatIfRenderer() {
        return whatIfRenderer;
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

    public String getPreferredTopTanglesScope() {
        return preferredTopTanglesScope;
    }

    public void setPreferredTopTanglesScope(String scope) {
        preferredTopTanglesScope = scope == null || scope.isBlank() ? null : scope;
    }

    /**
     * Regular full-project views hide top-level namespace wrapper packages
     * such as {@code de -> weigend -> s202}. Scoped views disable that skip so
     * the package the user opened from the outline remains visible.
     */
    public void setSkipTransparentTopLevelPackages(boolean skip) {
        skipTransparentTopLevelPackages = skip;
    }

    public ArchitectureViewStyle getViewStyle() {
        return viewStyle;
    }

    public void setViewStyle(ArchitectureViewStyle style) {
        viewStyle = style == null ? ArchitectureViewStyle.LAYERED : style;
        rebuildArchitectureProjection();
    }

    /**
     * Enables right-click scope extension for this view. The current root stays
     * filtered, while {@code sourceRoot} remains the complete candidate source.
     */
    public void enableScopeExtensionFrom(ArchitectureNode sourceRoot) {
        scopeExtensionSourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot cannot be null");
        setSkipTransparentTopLevelPackages(false);
    }

    public ArchitectureNode getScopeExtensionSourceRoot() {
        return scopeExtensionSourceRoot;
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
            runWithoutSelectionSink(() -> GraphSelection.ensureSelected(target));
            // Defer scrolling until layout has settled; the box may have just
            // been created during a refresh and have unresolved bounds.
            javafx.application.Platform.runLater(() -> scrollToNodeIfNeeded(node));
            return;
        }
        // Tree-only node (e.g. a package skipped as a transparent passthrough
        // — "com", "de", … — that has no visible box in the chart). No visual
        // highlight to apply, but external observers (quality view, etc.) still
        // need the announcement. Clear any visible chart selection and update
        // the property directly.
        runWithoutSelectionSink(GraphSelection::clear);
        selectedFullName.set(fullName);
    }

    private void runWithoutSelectionSink(Runnable action) {
        boolean wasSuppressing = suppressSelectionSink;
        suppressSelectionSink = true;
        try {
            action.run();
        } finally {
            suppressSelectionSink = wasSuppressing;
        }
    }

    /** @deprecated use {@link #selectByFullName(String)}. */
    @Deprecated
    public void selectClass(String fullClassName) {
        selectByFullName(fullClassName);
    }

    private void scrollToNodeIfNeeded(javafx.scene.Node target) {
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
        double viewMinX = contentBounds.getMinX() + scrollPane.getHvalue() * overflowX;
        double viewMinY = contentBounds.getMinY() + scrollPane.getVvalue() * overflowY;
        double viewMaxX = viewMinX + viewport.getWidth();
        double viewMaxY = viewMinY + viewport.getHeight();
        double margin = 24.0;

        if (overflowX > 0) {
            double targetMinX = targetInContent.getMinX();
            double targetMaxX = targetInContent.getMaxX();
            if (targetMinX < viewMinX + margin || targetMaxX > viewMaxX - margin) {
                double newViewMinX = nextVisibleStart(
                        targetMinX, targetMaxX, viewport.getWidth(), margin, viewMinX);
                scrollPane.setHvalue(clamp01((newViewMinX - contentBounds.getMinX()) / overflowX));
            }
        }
        if (overflowY > 0) {
            double targetMinY = targetInContent.getMinY();
            double targetMaxY = targetInContent.getMaxY();
            if (targetMinY < viewMinY + margin || targetMaxY > viewMaxY - margin) {
                double newViewMinY = nextVisibleStart(
                        targetMinY, targetMaxY, viewport.getHeight(), margin, viewMinY);
                scrollPane.setVvalue(clamp01((newViewMinY - contentBounds.getMinY()) / overflowY));
            }
        }
    }

    private static double nextVisibleStart(double targetMin,
                                           double targetMax,
                                           double viewportSize,
                                           double margin,
                                           double currentStart) {
        double targetSize = targetMax - targetMin;
        if (targetSize + margin * 2.0 >= viewportSize) {
            return targetMin - margin;
        }
        if (targetMin < currentStart + margin) {
            return targetMin - margin;
        }
        return targetMax - viewportSize + margin;
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void redrawVisibleArrows() {
        if (showDependencies.get()) {
            drawDependencyArrows();
            linesNeedUpdate = false;
        }
        if (showScc.get() || showPackageScc.get()) {
            updateSccRendererTangles();
            sccRenderer.drawSccLines(currentRootNode);
        }
        if (whatIfRenderer != null) {
            Architecture source = violationOverlayArchitecture();
            if (showWhatIfViolations.get() && source != null) {
                whatIfRenderer.redraw(source, violationOverlayKinds());
            } else {
                whatIfRenderer.clear();
            }
        }
        applyVirtuallyMovedDecorations();
    }

    private Architecture violationOverlayArchitecture() {
        if (viewStyle == ArchitectureViewStyle.COMPONENT
                || viewStyle == ArchitectureViewStyle.HEXAGONAL) {
            return architecture.get();
        }
        return whatIfArchitecture.get() != null ? whatIfArchitecture.get() : architecture.get();
    }

    private Set<ViolationKind> violationOverlayKinds() {
        return switch (viewStyle) {
            case COMPONENT -> COMPONENT_VIOLATION_OVERLAY_KINDS;
            case HEXAGONAL -> HEXAGONAL_VIOLATION_OVERLAY_KINDS;
            case LAYERED -> LAYERED_VIOLATION_OVERLAY_KINDS;
        };
    }

    private void applyVirtuallyMovedDecorations() {
        for (Map.Entry<String, javafx.scene.Node> entry : elementRegistry.entrySet()) {
            String fqcn = entry.getKey();
            boolean moved = movedFqns.contains(fqcn);
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
        redrawTick.set(redrawTick.get() + 1);
    }

    private void drawDependencyArrows() {
        dependencyRenderer.setSelectedFullName(selectedFullName.get());
        dependencyRenderer.drawDependencyArrows(currentRootNode);
    }

    /**
     * Re-runs the layout for the currently loaded architecture (e.g. after a
     * package-depth change).
     */
    public void refreshLayout() {
        resetVisualLayout();
        undoManager.clear();
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

        WhatIfArchitecture wif = whatIfArchitecture.get();
        if (wif != null) {
            wif.reset();
        }
        movedFqns.clear();
        setArchitectureRoot(currentRootNode);

        showDependencies.set(depsSave);
        showScc.set(sccSave);
        showPackageScc.set(pkgSccSave2);
        showWhatIfViolations.set(wifSave);
    }

    public void undoWhatIf() {
        if (whatIfArchitecture.get() == null) return;
        List<WhatIfUndoManager.Move> remaining = undoManager.decrement();
        if (remaining == null) return;
        boolean violations = showWhatIfViolations.get();
        resetVisualLayout();
        remaining.forEach(this::applyMoveToScene);
        showWhatIfViolations.set(violations);
        arrowsCoalescer.markDirty();
    }

    public void redoWhatIf() {
        if (whatIfArchitecture.get() == null) return;
        WhatIfUndoManager.Move m = undoManager.increment();
        if (m == null) return;
        applyMoveToScene(m);
        arrowsCoalescer.markDirty();
    }

    public BooleanProperty canUndoWhatIfProperty() { return undoManager.canUndoProperty(); }
    public BooleanProperty canRedoWhatIfProperty() { return undoManager.canRedoProperty(); }

    private VBox findRowStack(String containerFqn) {
        if (containerFqn == null || containerFqn.isEmpty()) {
            return whatIfRootContainer;
        }
        javafx.scene.Node container = elementRegistry.get(containerFqn);
        return container instanceof LevelPackageBox lpb ? lpb.getContentContainer() : null;
    }

    private void applyMoveToScene(WhatIfUndoManager.Move move) {
        javafx.scene.Node node = elementRegistry.get(move.fqn());
        if (node == null) return;
        VBox stack = findRowStack(move.containerFqn());
        if (stack == null) return;
        WhatIfArchitecture wif = whatIfArchitecture.get();
        if (wif == null) return;

        if (node.getParent() instanceof HBox srcRow) {
            srcRow.getChildren().remove(node);
            if (srcRow.getChildren().isEmpty() && srcRow.getParent() instanceof VBox v) {
                v.getChildren().remove(srcRow);
            }
        }

        if (move instanceof WhatIfUndoManager.Move.AsNewRow asNewRow) {
            HBox newRow = new HBox(8);
            newRow.setMaxWidth(Double.MAX_VALUE);
            newRow.setMaxHeight(Double.MAX_VALUE);
            newRow.setAlignment(Pos.CENTER);
            newRow.setStyle("-fx-background-color: transparent;");
            VBox.setVgrow(newRow, Priority.ALWAYS);
            ArchitectureDragController.markAsRow(newRow);
            int idx = Math.max(0, Math.min(asNewRow.rowIndex(), stack.getChildren().size()));
            stack.getChildren().add(idx, newRow);
            newRow.getChildren().add(node);
            wif.moveElementAsNewRow(move.fqn(), move.containerFqn(), asNewRow.rowIndex());
        } else if (move instanceof WhatIfUndoManager.Move.InRow inRow) {
            while (stack.getChildren().size() <= inRow.rowIndex()) {
                HBox gap = new HBox(8);
                ArchitectureDragController.markAsRow(gap);
                stack.getChildren().add(gap);
            }
            HBox targetRow = (HBox) stack.getChildren().get(inRow.rowIndex());
            int col = Math.max(0, Math.min(inRow.colIndex(), targetRow.getChildren().size()));
            targetRow.getChildren().add(col, node);
            wif.moveElement(move.fqn(), move.containerFqn(), inRow.rowIndex(), inRow.colIndex());
        }

        movedFqns.add(move.fqn());
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
     * Stable read-only zoom factor (1.0 = 100%). The underlying
     * {@link ZoomController} is rebuilt with the view content, but callers keep
     * observing this ArchitectureView-level property.
     */
    public ReadOnlyDoubleProperty zoomFactorProperty() {
        return zoomFactor.getReadOnlyProperty();
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

    public BooleanProperty showSccProperty() {
        return showScc;
    }

    public boolean isShowScc() {
        return showScc.get();
    }

    public void setShowScc(boolean show) {
        showScc.set(show);
    }

    public BooleanProperty showPackageSccProperty() {
        return showPackageScc;
    }

    public boolean isShowPackageScc() {
        return showPackageScc.get();
    }

    public void setShowPackageScc(boolean show) {
        showPackageScc.set(show);
    }

    public BooleanProperty showWhatIfViolationsProperty() {
        return showWhatIfViolations;
    }

    public boolean isShowWhatIfViolations() {
        return showWhatIfViolations.get();
    }

    public void setShowWhatIfViolations(boolean show) {
        showWhatIfViolations.set(show);
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
    public void setTangleVisualization(List<DependencyEdge> edges,
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

    public Set<DependencyEdge> getCycleBreakEdges() {
        return cycleBreakEdges;
    }

    public void setCycleBreakEdges(Set<DependencyEdge> cycleBreakEdges) {
        this.cycleBreakEdges = cycleBreakEdges == null ? Set.of() : Set.copyOf(cycleBreakEdges);
        if (tangleRenderer != null) {
            tangleRenderer.setCycleBreakEdges(this.cycleBreakEdges);
        }
    }

    public void setAppliedTangleCutEdges(Set<DependencyEdge> appliedCutEdges) {
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
        DependencyEdge cut = new DependencyEdge(from, to);
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

    public void applyTangleEdgeCuts(Collection<DependencyEdge> cuts) {
        if (cuts == null || cuts.isEmpty()) {
            return;
        }
        int added = 0;
        for (DependencyEdge cut : cuts) {
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
        DependencyEdge cut = new DependencyEdge(from, to);
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
        DependencyEdge cut = new DependencyEdge(from, to);
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

    /**
     * Global toggle for the "G:n" architecture-level suffix shown alongside
     * "L:n" in each package and class box header. Boxes react live without a
     * tree rebuild, mirroring {@link #showIconsProperty()}.
     */
    public static BooleanProperty showArchitectureLevelProperty() {
        return SHOW_ARCHITECTURE_LEVEL;
    }

    public boolean isShowArchitectureLevel() {
        return SHOW_ARCHITECTURE_LEVEL.get();
    }

    public void setShowArchitectureLevel(boolean show) {
        SHOW_ARCHITECTURE_LEVEL.set(show);
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
     * Set a sink that receives the full name whenever the user selects a node
     * (class or package) in the graph. Pass null to detach.
     */
    public void setOnNodeSelected(Consumer<String> sink) {
        this.nodeSelectionSink = sink != null ? sink : (fqn -> {});
    }

    /**
     * @deprecated use {@link #setOnNodeSelected(Consumer)}.
     */
    @Deprecated
    public void setOnNodeDoubleClicked(Consumer<String> sink) {
        setOnNodeSelected(sink);
    }

    /** @deprecated use {@link #setOnNodeSelected(Consumer)}. */
    @Deprecated
    public void setOnClassDoubleClicked(Consumer<String> sink) {
        setOnNodeSelected(sink);
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

    /**
     * Returns the layout bounds of every registered element in the JavaFX
     * scene coordinate space. Forces a layout pass so bounds are valid.
     * Must be called on the JavaFX Application Thread after the scene is shown.
     */
    public java.util.Map<String, javafx.geometry.Bounds> getElementBoundsInScene() {
        if (getScene() != null && getScene().getRoot() != null) {
            getScene().getRoot().layout();
        }
        var result = new java.util.LinkedHashMap<String, javafx.geometry.Bounds>();
        for (var entry : elementRegistry.entrySet()) {
            var node = entry.getValue();
            if (node.getScene() == null || !node.isVisible()) continue;
            result.put(entry.getKey(), node.localToScene(node.getBoundsInLocal()));
        }
        return result;
    }

    /**
     * Returns 3D footprint bounds for registered package/class boxes. Helper
     * registry entries for transparent parent containers are filtered out.
     */
    public java.util.Map<String, javafx.geometry.Bounds> getElementFootprintBoundsInScene() {
        if (getScene() != null && getScene().getRoot() != null) {
            getScene().getRoot().layout();
        }
        var result = new java.util.LinkedHashMap<String, javafx.geometry.Bounds>();
        for (var entry : elementRegistry.entrySet()) {
            var node = entry.getValue();
            if (node.getScene() == null || !node.isVisible()) continue;
            javafx.geometry.Bounds bounds = footprintBoundsInScene(node);
            if (bounds != null) {
                result.put(entry.getKey(), bounds);
            }
        }
        return result;
    }

    /**
     * Returns 3D footprint bounds in this view's unscaled layout coordinate
     * space. Unlike scene coordinates, these stay stable when the 2D
     * ScrollPane moves or the application window changes focus.
     */
    public java.util.Map<String, javafx.geometry.Bounds> getElementFootprintBoundsInLayout() {
        if (getScene() != null && getScene().getRoot() != null) {
            getScene().getRoot().layout();
        }
        var result = new java.util.LinkedHashMap<String, javafx.geometry.Bounds>();
        for (var entry : elementRegistry.entrySet()) {
            var node = entry.getValue();
            if (node.getScene() == null || !node.isVisible()) continue;
            javafx.geometry.Bounds bounds = footprintBoundsInLayout(node);
            if (bounds != null) {
                result.put(entry.getKey(), bounds);
            }
        }
        return result;
    }

    /**
     * Returns the closest visible package parent per currently registered
     * package/class box. Used by projections such as the 3D view that need to
     * roll hidden class-level edges up to the same visible endpoint as the 2D
     * scene, including after What-If drag-and-drop moves.
     */
    public java.util.Map<String, String> getVisibleElementParentFqns() {
        if (getScene() != null && getScene().getRoot() != null) {
            getScene().getRoot().layout();
        }
        var result = new java.util.LinkedHashMap<String, String>();
        for (var entry : elementRegistry.entrySet()) {
            javafx.scene.Node node = entry.getValue();
            if (!(node instanceof LevelPackageBox || node instanceof LevelClassBox)) continue;
            if (node.getScene() == null) continue;
            String parent = nearestVisiblePackageParent(node.getParent());
            if (parent != null) {
                result.put(entry.getKey(), parent);
            }
        }
        return result;
    }

    private static javafx.geometry.Bounds footprintBoundsInScene(javafx.scene.Node node) {
        if (node instanceof LevelPackageBox || node instanceof LevelClassBox) {
            return node.localToScene(node.getBoundsInLocal());
        }
        return null;
    }

    private javafx.geometry.Bounds footprintBoundsInLayout(javafx.scene.Node node) {
        if (!(node instanceof LevelPackageBox || node instanceof LevelClassBox)) {
            return null;
        }
        if (zoomableContent == null || zoomableContent.getScene() == null) {
            return footprintBoundsInScene(node);
        }
        return zoomableContent.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
    }

    private static String nearestVisiblePackageParent(javafx.scene.Node node) {
        javafx.scene.Node current = node;
        while (current != null) {
            if (current instanceof LevelPackageBox pkg && isActuallyVisible(current)) {
                return pkg.getFullName();
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isActuallyVisible(javafx.scene.Node node) {
        if (node == null || !node.isVisible()) {
            return false;
        }
        javafx.scene.Parent parent = node.getParent();
        while (parent != null) {
            if (!parent.isVisible()) {
                return false;
            }
            parent = parent.getParent();
        }
        return true;
    }
}
