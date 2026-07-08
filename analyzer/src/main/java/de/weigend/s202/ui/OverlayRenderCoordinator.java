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

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.domain.architecture.WhatIfArchitecture;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.arrows.DependencyRenderer;
import de.weigend.s202.ui.core.arrows.DependencyRendererStrategy;
import de.weigend.s202.ui.core.arrows.SCCRenderer;
import de.weigend.s202.ui.rendering.TangleEdgeRenderer;
import de.weigend.s202.ui.core.arrows.WhatIfUpwardEdgeRenderer;
import de.weigend.s202.ui.core.graph.PulseCoalescer;
import de.weigend.s202.ui.core.canvas.ZoomController;
import javafx.beans.property.BooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Koordiniert die Overlay-Renderer (Abhängigkeitspfeile, SCC-Linien,
 * What-If-Verstöße, Tangle-Kanten): Erzeugung pro Root-Build, Sichtbarkeits-
 * Toggles und das gesammelte Neuzeichnen. Aus ArchitectureView extrahiert.
 */
final class OverlayRenderCoordinator {

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

    private final Pane dependencyPane;
    private final Pane sccPane;
    private final Pane whatIfPane;
    private final Pane tanglePane;
    private final Map<String, Node> elementRegistry;
    private final BooleanProperty showDependencies;
    private final BooleanProperty showScc;
    private final BooleanProperty showPackageScc;
    private final BooleanProperty showWhatIfViolations;
    private final BooleanProperty showTangleDebugLines;
    private final PulseCoalescer arrowsCoalescer;
    private final TangleOverlayController tangleOverlay;
    private final ArchitectureProjectionModel projection;
    private final Supplier<ArchitectureNode> currentRoot;
    private final Supplier<ArchitectureViewStyle> viewStyle;
    private final Supplier<String> selectedFullName;
    private final Consumer<String> status;
    /** Zieht die orange „moved“-Dekoration nach jedem Redraw nach. */
    private final Runnable applyMovedDecorations;

    // Renderers — rebuilt with the view content on every root build.
    private DependencyRendererStrategy dependencyRenderer;
    private DependencyRenderer classicRenderer;
    private SCCRenderer sccRenderer;
    private WhatIfUpwardEdgeRenderer whatIfRenderer;
    private TangleEdgeRenderer tangleRenderer;

    // Lines need redraw after zoom/scroll changes (perf optimization).
    private boolean linesNeedUpdate = false;

    OverlayRenderCoordinator(Pane dependencyPane,
                             Pane sccPane,
                             Pane whatIfPane,
                             Pane tanglePane,
                             Map<String, Node> elementRegistry,
                             BooleanProperty showDependencies,
                             BooleanProperty showScc,
                             BooleanProperty showPackageScc,
                             BooleanProperty showWhatIfViolations,
                             BooleanProperty showTangleDebugLines,
                             PulseCoalescer arrowsCoalescer,
                             TangleOverlayController tangleOverlay,
                             ArchitectureProjectionModel projection,
                             Supplier<ArchitectureNode> currentRoot,
                             Supplier<ArchitectureViewStyle> viewStyle,
                             Supplier<String> selectedFullName,
                             Consumer<String> status,
                             Runnable applyMovedDecorations) {
        this.dependencyPane = dependencyPane;
        this.sccPane = sccPane;
        this.whatIfPane = whatIfPane;
        this.tanglePane = tanglePane;
        this.elementRegistry = elementRegistry;
        this.showDependencies = showDependencies;
        this.showScc = showScc;
        this.showPackageScc = showPackageScc;
        this.showWhatIfViolations = showWhatIfViolations;
        this.showTangleDebugLines = showTangleDebugLines;
        this.arrowsCoalescer = arrowsCoalescer;
        this.tangleOverlay = tangleOverlay;
        this.projection = projection;
        this.currentRoot = currentRoot;
        this.viewStyle = viewStyle;
        this.selectedFullName = selectedFullName;
        this.status = status;
        this.applyMovedDecorations = applyMovedDecorations;
    }

    /** Erzeugt alle Renderer für den frisch gebauten Content neu. */
    void rebuildRenderers(Pane zoomableContent, StackPane overlayPane,
                          ScrollPane scrollPane, ZoomController zoomController) {
        classicRenderer = new DependencyRenderer(dependencyPane, elementRegistry, zoomController, status);
        classicRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        dependencyRenderer = classicRenderer;

        sccRenderer = new SCCRenderer(sccPane, elementRegistry, status);
        sccRenderer.setCoordinateContext(zoomableContent, overlayPane, scrollPane);
        updateSccRendererTangles();

        whatIfRenderer = new WhatIfUpwardEdgeRenderer(whatIfPane, elementRegistry);
        whatIfRenderer.setCoordinateContext(zoomableContent, overlayPane);

        tangleRenderer = new TangleEdgeRenderer(tanglePane, elementRegistry, status);
        tangleRenderer.setCoordinateContext(zoomableContent, overlayPane);
        tangleOverlay.attachRenderer(tangleRenderer);
        tangleRenderer.setShowDebugLines(showTangleDebugLines.get());

        dependencyRenderer.clearDependencyArrows();
        sccRenderer.clearSccLines();
        dependencyPane.setVisible(false);
        sccPane.setVisible(false);
    }

    WhatIfUpwardEdgeRenderer whatIfRenderer() {
        return whatIfRenderer;
    }

    void highlightSccEdge(String from, String to) {
        if (sccRenderer != null) {
            sccRenderer.highlightEdge(from, to);
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
                sccRenderer.drawSccLines(currentRoot.get());
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

    void applyShowTangleDebugLines(boolean visible) {
        if (tangleRenderer != null) {
            tangleRenderer.setShowDebugLines(visible);
        }
    }

    /* ----- Zeichnen ----------------------------------------------------------- */

    void handleZoomChanged() {
        invalidateLines();
        if (showDependencies.get() && dependencyPane != null && dependencyPane.isVisible()) {
            drawDependencyArrows();
        }
        if ((showScc.get() || showPackageScc.get()) && sccPane != null && sccPane.isVisible()) {
            sccRenderer.drawSccLines(currentRoot.get());
        }
    }

    void redrawVisibleArrows() {
        if (showDependencies.get()) {
            drawDependencyArrows();
            linesNeedUpdate = false;
        }
        if (showScc.get() || showPackageScc.get()) {
            updateSccRendererTangles();
            sccRenderer.drawSccLines(currentRoot.get());
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
        dependencyRenderer.setSelectedFullName(selectedFullName.get());
        dependencyRenderer.drawDependencyArrows(currentRoot.get());
    }

    private Architecture violationOverlayArchitecture() {
        ArchitectureViewStyle style = viewStyle.get();
        if (style == ArchitectureViewStyle.COMPONENT
                || style == ArchitectureViewStyle.HEXAGONAL) {
            return projection.getArchitecture();
        }
        return projection.getWhatIfArchitecture() != null
                ? projection.getWhatIfArchitecture() : projection.getArchitecture();
    }

    private Set<ViolationKind> violationOverlayKinds() {
        return switch (viewStyle.get()) {
            case COMPONENT -> COMPONENT_VIOLATION_OVERLAY_KINDS;
            case HEXAGONAL -> HEXAGONAL_VIOLATION_OVERLAY_KINDS;
            case LAYERED -> LAYERED_VIOLATION_OVERLAY_KINDS;
        };
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
