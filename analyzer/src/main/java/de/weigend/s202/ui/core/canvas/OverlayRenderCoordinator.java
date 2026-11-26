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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.domain.architecture.WhatIfArchitecture;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.PlanState;
import de.weigend.s202.ui.core.arrows.DependencyRenderer;
import de.weigend.s202.ui.core.arrows.DependencyRendererStrategy;
import de.weigend.s202.ui.core.arrows.LineSelection;
import de.weigend.s202.ui.core.arrows.SCCRenderer;
import de.weigend.s202.ui.core.arrows.TangleEdgeScope;
import de.weigend.s202.ui.core.arrows.WhatIfUpwardEdgeRenderer;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.PulseCoalescer;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.beans.property.BooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Koordiniert die Overlay-Renderer (Abhängigkeitspfeile, SCC-Linien,
 * What-If-Verstöße, angepinnte Kanten): Erzeugung pro Root-Build, Sichtbarkeits-
 * Toggles und das gesammelte Neuzeichnen. Aus ArchitectureCanvas extrahiert.
 */
final class OverlayRenderCoordinator {

    private final Pane dependencyPane;
    private final Pane sccPane;
    private final Pane whatIfPane;
    private final Map<String, Node> elementRegistry;
    private final BooleanProperty showDependencies;
    private final BooleanProperty showScc;
    private final BooleanProperty showPackageScc;
    private final BooleanProperty showWhatIfViolations;
    private final PulseCoalescer arrowsCoalescer;
    private BiPredicate<String, String> edgeFilter = (from, to) -> true;
    private final de.weigend.s202.ui.core.arrows.DependencyEdgeActions edgeActions;
    /** What is planned and shown; a cycle the cuts break is no longer one. */
    private final Supplier<PlanState> plan;
    private final ArchitectureProjectionModel projection;
    private final Supplier<ArchitectureNode> currentRoot;
    private final Supplier<de.weigend.s202.ui.core.spi.StyleView> styleView;
    private final Supplier<String> selectedFullName;
    private final Consumer<String> status;
    /** Zieht die orange „moved“-Dekoration nach jedem Redraw nach. */
    private final Runnable applyMovedDecorations;

    // Renderers — rebuilt with the view content on every root build.
    private DependencyRendererStrategy dependencyRenderer;
    private DependencyRenderer classicRenderer;
    private SCCRenderer sccRenderer;
    private WhatIfUpwardEdgeRenderer whatIfRenderer;

    /** The boxes at the other ends of the arrows of the box under the mouse. */
    private List<Node> connected = List.of();

    // Lines need redraw after zoom/scroll changes (perf optimization).
    private boolean linesNeedUpdate = false;

    // What the model knows about each class dependency — rebuilt when the
    // model or the preview cuts change.
    private EdgeCycleClassifier edgeCycles;
    private DomainModel edgeCyclesModel;
    private PlanState edgeCyclesPlan;

    // Non-null in a tangle view.
    private TangleEdgeScope tangleScope;
    private TangleEdgeScope.Stats tangleStats;
    private EdgeCycleClassifier tangleStatsCycles;
    private ArchitectureNode tangleStatsRoot;

    OverlayRenderCoordinator(Pane dependencyPane,
                             Pane sccPane,
                             Pane whatIfPane,
                             Map<String, Node> elementRegistry,
                             BooleanProperty showDependencies,
                             BooleanProperty showScc,
                             BooleanProperty showPackageScc,
                             BooleanProperty showWhatIfViolations,
                             PulseCoalescer arrowsCoalescer,
                             de.weigend.s202.ui.core.arrows.DependencyEdgeActions edgeActions,
                             Supplier<PlanState> plan,
                             ArchitectureProjectionModel projection,
                             Supplier<ArchitectureNode> currentRoot,
                             Supplier<de.weigend.s202.ui.core.spi.StyleView> styleView,
                             Supplier<String> selectedFullName,
                             Consumer<String> status,
                             Runnable applyMovedDecorations) {
        this.dependencyPane = dependencyPane;
        this.sccPane = sccPane;
        this.whatIfPane = whatIfPane;
        this.elementRegistry = elementRegistry;
        this.showDependencies = showDependencies;
        this.showScc = showScc;
        this.showPackageScc = showPackageScc;
        this.showWhatIfViolations = showWhatIfViolations;
        this.arrowsCoalescer = arrowsCoalescer;
        this.edgeActions = edgeActions;
        this.plan = plan;
        this.projection = projection;
        this.currentRoot = currentRoot;
        this.styleView = styleView;
        this.selectedFullName = selectedFullName;
        this.status = status;
        this.applyMovedDecorations = applyMovedDecorations;
    }

    /** Erzeugt alle Renderer für den frisch gebauten Content neu. */
    void rebuildRenderers(Pane zoomableContent, StackPane overlayPane, ScrollPane scrollPane) {
        LineSelection lineSelection = new LineSelection(status);
        connected = List.of();
        classicRenderer = new DependencyRenderer(dependencyPane, elementRegistry, status);
        classicRenderer.setLineSelection(lineSelection);
        classicRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        classicRenderer.setEdgeActions(edgeActions);
        dependencyRenderer = classicRenderer;

        sccRenderer = new SCCRenderer(sccPane, elementRegistry, status);
        sccRenderer.setLineSelection(lineSelection);
        sccRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        updateSccRendererTangles();

        whatIfRenderer = new WhatIfUpwardEdgeRenderer(whatIfPane, elementRegistry);
        whatIfRenderer.setLineSelection(lineSelection);
        whatIfRenderer.setCoordinateContext(zoomableContent, overlayPane);
        applyEdgeFilter();

        dependencyRenderer.clearDependencyArrows();
        sccRenderer.clearSccLines();
        dependencyPane.setVisible(false);
        sccPane.setVisible(false);
    }

    /** Draws only the class dependencies that pass; all overlays follow. */
    void setEdgeFilter(BiPredicate<String, String> filter) {
        edgeFilter = filter;
        linesNeedUpdate = true;
        if (classicRenderer != null) {
            applyEdgeFilter();
            arrowsCoalescer.markDirty();
        }
    }

    private void applyEdgeFilter() {
        classicRenderer.setEdgeFilter(edgeFilter);
        sccRenderer.setEdgeFilter(edgeFilter);
        whatIfRenderer.setEdgeFilter(edgeFilter);
    }

    WhatIfUpwardEdgeRenderer whatIfRenderer() {
        return whatIfRenderer;
    }

    void highlightSccEdge(String from, String to) {
        if (sccRenderer != null) {
            sccRenderer.highlightEdge(from, to);
        }
    }

    /**
     * Lets the arrows of the box stand out, and the boxes they connect it
     * with, as long as the arrows are shown; {@code null} for none.
     */
    void emphasise(Node box) {
        connected.forEach(node -> setConnected(node, false));
        connected = classicRenderer == null ? List.of()
                : classicRenderer.emphasise(showDependencies.get() ? box : null);
        connected.forEach(node -> setConnected(node, true));
    }

    private static void setConnected(Node node, boolean isConnected) {
        if (node instanceof GraphSelection.Selectable selectable) {
            selectable.setConnected(isConnected);
        }
    }

    void invalidateLines() {
        linesNeedUpdate = true;
    }

    /* ----- Sichtbarkeits-Toggles -------------------------------------------- */

    void applyShowDependencies(boolean visible) {
        if (dependencyRenderer == null || dependencyPane == null) {
            return;
        }
        if (visible) {
            dependencyPane.setVisible(true);
            if (currentRoot.get() != null && (!dependencyRenderer.isDependencyLinesDrawn() || linesNeedUpdate)) {
                arrowsCoalescer.markDirty();
            }
        } else {
            dependencyPane.setVisible(false);
        }
    }

    void applyShowScc(boolean visible) {
        if (sccRenderer == null || sccPane == null) return;
        sccRenderer.setShowClassScc(visible);
        refreshSccPane();
    }

    void applyShowPackageScc(boolean visible) {
        if (sccRenderer == null || sccPane == null) return;
        sccRenderer.setShowPackageCycles(visible);
        refreshSccPane();
    }

    private void refreshSccPane() {
        boolean anyEnabled = showScc.get() || showPackageScc.get();
        if (anyEnabled) {
            updateSccRendererTangles();
            sccRenderer.clearSccLines();
            if (currentRoot.get() != null) {
                drawSccLines();
                linesNeedUpdate = false;
            }
            sccPane.setVisible(true);
        } else {
            sccPane.setVisible(false);
        }
    }

    void applyShowWhatIfViolations(boolean visible) {
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

    /* ----- Zeichnen ----------------------------------------------------------- */

    void handleZoomChanged() {
        invalidateLines();
        if (showDependencies.get() && dependencyPane != null && dependencyPane.isVisible()) {
            drawDependencyArrows();
        }
        if ((showScc.get() || showPackageScc.get()) && sccPane != null && sccPane.isVisible()) {
            drawSccLines();
        }
    }

    void redrawVisibleArrows() {
        if (showDependencies.get()) {
            drawDependencyArrows();
            linesNeedUpdate = false;
        }
        if (showScc.get() || showPackageScc.get()) {
            updateSccRendererTangles();
            drawSccLines();
        }
        if (whatIfRenderer != null) {
            Architecture source = violationOverlayArchitecture();
            if (showWhatIfViolations.get() && source != null) {
                whatIfRenderer.redraw(source, violationOverlayKinds());
            } else {
                whatIfRenderer.clear();
            }
        }
        applyMovedDecorations.run();
    }

    private void drawDependencyArrows() {
        // Back-edges are the layered layout's own violations; the radial and
        // component layouts keep judging direction by architecture level.
        classicRenderer.setEdgeCycles(edgeCycles(), projection.viewStyle() == ArchitectureKind.LAYERED);
        classicRenderer.setTangleScope(tangleScope);
        dependencyRenderer.setSelectedFullName(selectedFullName.get());
        dependencyRenderer.drawDependencyArrows(currentRoot.get());
    }

    private void drawSccLines() {
        sccRenderer.setEdgeCycles(edgeCycles());
        sccRenderer.drawSccLines(currentRoot.get());
    }

    /* ----- Zyklen-Wissen des Modells und Tangle-Sicht ------------------------- */

    /** What the model, minus the preview cuts, knows about each class dependency; null without a model. */
    EdgeCycleClassifier edgeCycles() {
        DomainModel model = projection.getDomainModel();
        if (model == null) {
            edgeCycles = null;
            edgeCyclesModel = null;
            return null;
        }
        PlanState state = plan.get();
        if (edgeCycles == null || model != edgeCyclesModel || !state.equals(edgeCyclesPlan)) {
            edgeCycles = EdgeCycleClassifier.of(model, state.cuts(), Lookup.lookup(SCCFinder.class), state.placement());
            edgeCyclesModel = model;
            edgeCyclesPlan = state;
        }
        return edgeCycles;
    }

    void setTangleScope(TangleEdgeScope scope) {
        tangleScope = scope;
        tangleStats = null;
    }

    TangleEdgeScope tangleScope() {
        return tangleScope;
    }

    /** Counts of the tangle's dependencies by property; empty outside a tangle view. */
    TangleEdgeScope.Stats tangleStats() {
        if (tangleScope == null) {
            return TangleEdgeScope.Stats.EMPTY;
        }
        EdgeCycleClassifier cycles = edgeCycles();
        ArchitectureNode root = currentRoot.get();
        if (tangleStats == null || cycles != tangleStatsCycles || root != tangleStatsRoot) {
            tangleStats = tangleScope.stats(root, cycles, edgeActions::isCut);
            tangleStatsCycles = cycles;
            tangleStatsRoot = root;
        }
        return tangleStats;
    }

    private Architecture violationOverlayArchitecture() {
        if (styleView.get().usesWhatIfViolationSource() && projection.getWhatIfArchitecture() != null) {
            return projection.getWhatIfArchitecture();
        }
        return projection.getArchitecture();
    }

    private Set<ViolationKind> violationOverlayKinds() {
        return styleView.get().violationOverlayKinds();
    }

    void updateSccRendererTangles() {
        if (sccRenderer == null) return;
        Architecture arch = projection.packageCycleArchitecture();
        if (arch instanceof WhatIfArchitecture wif) {
            Map<String, String> classPackages = wif.classPackages();
            sccRenderer.setPackageResolver(fqn -> classPackages.getOrDefault(fqn, staticPackageOf(fqn)));
        } else {
            sccRenderer.setPackageResolver(OverlayRenderCoordinator::staticPackageOf);
        }
        if (arch != null) {
            sccRenderer.setPackageTangles(
                    arch.tangles().stream()
                            .map(t -> (Set<String>) t.members())
                            .collect(java.util.stream.Collectors.toList()));
        } else {
            sccRenderer.setPackageTangles(List.of());
        }
    }

    private static String staticPackageOf(String fqn) {
        int dot = fqn == null ? -1 : fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }
}
