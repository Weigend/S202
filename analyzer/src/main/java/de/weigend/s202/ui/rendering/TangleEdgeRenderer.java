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
package de.weigend.s202.ui.rendering;

import de.weigend.s202.domain.DependencyEdge;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tangle-edge overlay renderer.
 *
 * <p>Renders the intra-SCC (tangle) dependency edges as orthogonal routes
 * along a lane grid, with straight lines as fallback. Source and target are
 * looked up via the element registry. Selection highlights a single edge.
 *
 * <p>This class orchestrates one render pass and owns the redraw/retry
 * life-cycle; the work is delegated to {@link TangleLaneLayout} (lane grid),
 * {@link TangleRouter} (routing), {@link TangleEdgePainter} (painting and
 * interaction) and {@link TangleCycleModel} (cycle bookkeeping).
 */
public class TangleEdgeRenderer {

    private final Pane pane;
    private final Map<String, Node> elementRegistry;
    private final TangleCycleModel cycleModel = new TangleCycleModel();
    private final TangleEdgePainter painter;

    private List<DependencyEdge> edges = List.of();
    private Pane zoomableContent;
    private Pane overlayPane;
    private boolean showDebugLines = true;

    private int retriesLeft = 0;
    private static final int INITIAL_RETRIES = 8;
    private int settleRedrawsLeft = 0;
    private static final int INITIAL_SETTLE_REDRAWS = 3;
    private boolean layoutPending;

    // Coalesces rapid layout-bounds changes (e.g. CSS border-width flips on
    // selection) into a single deferred redraw so edges are drawn after the
    // layout pass has settled, not synchronously mid-CSS-change.
    private final de.weigend.s202.ui.core.graph.PulseCoalescer layoutCoalescer =
            new de.weigend.s202.ui.core.graph.PulseCoalescer(javafx.application.Platform::runLater, this::redraw);

    private final javafx.beans.value.ChangeListener<Bounds> layoutListener =
            (obs, was, isNow) -> layoutCoalescer.markDirty();

    public TangleEdgeRenderer(Pane pane, Map<String, Node> elementRegistry,
                               Consumer<String> statusCallback) {
        this.pane = Objects.requireNonNull(pane, "pane");
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry");
        Objects.requireNonNull(statusCallback, "statusCallback");
        // The painter reports back via callbacks so it never depends on this class.
        this.painter = new TangleEdgePainter(this.pane, this.elementRegistry, statusCallback,
                cycleModel, this::setSelectedEdge, () -> layoutPending = true);
    }

    public void setCoordinateContext(Pane zoomableContent, Pane overlayPane) {
        if (this.zoomableContent != null) {
            this.zoomableContent.layoutBoundsProperty().removeListener(layoutListener);
        }
        this.zoomableContent = zoomableContent;
        this.overlayPane = overlayPane;
        if (zoomableContent != null) {
            zoomableContent.layoutBoundsProperty().addListener(layoutListener);
        }
    }

    public void setOnEdgeClicked(java.util.function.BiConsumer<String, String> handler) {
        painter.setOnEdgeClicked(handler);
    }

    public void setOnEdgeCut(java.util.function.BiConsumer<String, String> handler) {
        painter.setOnEdgeCut(handler);
    }

    public void setOnEdgeRestore(java.util.function.BiConsumer<String, String> handler) {
        painter.setOnEdgeRestore(handler);
    }

    public void setEdges(List<DependencyEdge> edges) {
        this.edges = edges == null ? List.of() : List.copyOf(edges);
        cycleModel.recomputeActiveTangleEdges(this.edges);
        retriesLeft = INITIAL_RETRIES;
        settleRedrawsLeft = INITIAL_SETTLE_REDRAWS;
        redraw();
    }

    public void setSelectedEdge(String from, String to) {
        painter.setSelectedEdge(from, to);
        redraw();
    }

    public void setCycleBreakEdges(Set<DependencyEdge> cycleBreakEdges) {
        cycleModel.setCycleBreakEdges(cycleBreakEdges);
        redraw();
    }

    public void setAppliedCutEdges(Set<DependencyEdge> appliedCutEdges) {
        cycleModel.setAppliedCutEdges(appliedCutEdges);
        cycleModel.recomputeActiveTangleEdges(edges);
        redraw();
    }

    public void setShowDebugLines(boolean showDebugLines) {
        if (this.showDebugLines == showDebugLines) {
            return;
        }
        this.showDebugLines = showDebugLines;
        redraw();
    }

    public void clear() {
        pane.getChildren().clear();
        edges = List.of();
        settleRedrawsLeft = 0;
    }

    public void requestRedraw() {
        retriesLeft = INITIAL_RETRIES;
        settleRedrawsLeft = INITIAL_SETTLE_REDRAWS;
        redraw();
    }

    // -------------------------------------------------------------------------

    private void redraw() {
        pane.getChildren().clear();
        layoutPending = false;
        if (zoomableContent == null || overlayPane == null || edges.isEmpty()) {
            return;
        }
        zoomableContent.applyCss();
        zoomableContent.layout();

        // Routing lanes are built first; visible debug lines, when enabled,
        // are drawn before edges so the dependencies stay on top.
        TangleLaneLayout laneBuilder = new TangleLaneLayout(pane, elementRegistry, edges, showDebugLines);
        TangleGeometry.LaneLayout lanes = laneBuilder.build(zoomableContent, overlayPane);
        if (laneBuilder.isLayoutPending()) {
            layoutPending = true;
        }

        boolean anyRendered = false;
        if (lanes != null) {
            TangleGeometry.RoutingResult routing = TangleRouter.routeEdges(edges, lanes);
            TangleGeometry.BypassAllocator bypass = TangleGeometry.bypassAllocator(lanes);
            for (TangleGeometry.UnroutedEdge unrouted : routing.unrouted) {
                if (unrouted.source == null || unrouted.target == null) {
                    if (hasVisibleEndpointWaitingForLayout(unrouted.edge)) {
                        layoutPending = true;
                    } else if (painter.renderEdge(unrouted.edge, overlayPane)) {
                        anyRendered = true;
                    }
                } else if (painter.renderFallbackEdge(unrouted.edge, unrouted.fallback,
                        unrouted.source, unrouted.target, bypass)) {
                    anyRendered = true;
                }
            }
            List<TangleGeometry.VerticalSegment> verticalSegments =
                    TangleGeometry.collectVerticalSegments(routing.routed);
            for (TangleGeometry.RoutedTangleEdge edge : routing.routed) {
                painter.paintRoutedEdge(edge, verticalSegments);
            }
            if (!routing.routed.isEmpty()) {
                anyRendered = true;
            }
        } else {
            for (DependencyEdge edge : edges) {
                if (painter.renderEdge(edge, overlayPane)) anyRendered = true;
            }
        }

        boolean needsSettleRedraw = settleRedrawsLeft > 0;
        if ((!anyRendered || layoutPending || needsSettleRedraw) && retriesLeft > 0) {
            if (needsSettleRedraw) {
                settleRedrawsLeft--;
            }
            scheduleRetry();
        }
    }

    private boolean hasVisibleEndpointWaitingForLayout(DependencyEdge edge) {
        Node source = elementRegistry.get(edge.from());
        Node target = elementRegistry.get(TangleLaneLayout.targetClassOf(edge.to()));
        if (source == null || target == null) {
            return false;
        }
        return (TangleGeometry.isVisible(source) && TangleGeometry.overlayBounds(source, overlayPane) == null)
                || (TangleGeometry.isVisible(target) && TangleGeometry.overlayBounds(target, overlayPane) == null);
    }

    private void scheduleRetry() {
        if (retriesLeft > 0) {
            retriesLeft--;
            javafx.application.Platform.runLater(this::redraw);
        }
    }

    List<Node> getRenderedShapes() {
        return new ArrayList<>(pane.getChildren());
    }
}
