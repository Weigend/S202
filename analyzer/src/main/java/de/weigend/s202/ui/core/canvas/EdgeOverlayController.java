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

import de.weigend.s202.domain.DependencyEdge;
import javafx.scene.layout.Pane;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Verwaltet das angepinnte Kanten-Overlay des Canvas: den
 * angehefteten Kanten-Satz (überlebt refreshLayout), Auswahl, Cut/Restore
 * der Preview-Kanten und die zugehörigen Sinks zur Host-Shell. Der
 * {@link de.weigend.s202.ui.core.spi.EdgeOverlayRenderer} wird bei jedem Root-Build neu erzeugt und per
 * {@link #attachRenderer} wieder angeschlossen. Aus ArchitectureCanvas
 * extrahiert.
 */
public final class EdgeOverlayController {

    private final Pane edgeOverlayPane;
    private final Consumer<String> status;

    private de.weigend.s202.ui.core.spi.EdgeOverlayRenderer renderer;

    // Pending overlay snapshot, applied once setArchitectureRoot
    // (re-)builds the renderer. Set by setEdgeOverlay before the root
    // is assigned, or restored after a refreshLayout.
    private List<DependencyEdge> pendingEdges;
    private String pendingSelFrom;
    private String pendingSelTo;
    private Set<DependencyEdge> cycleBreakEdges = Set.of();
    private final Set<DependencyEdge> appliedCutEdges = new HashSet<>();

    private BiConsumer<String, String> edgeClickedSink = (a, b) -> { /* no-op default */ };
    private BiConsumer<String, String> edgeCutSink = (a, b) -> { /* no-op default */ };
    private BiConsumer<String, String> edgeRestoreSink = (a, b) -> { /* no-op default */ };

    public EdgeOverlayController(Pane edgeOverlayPane, Consumer<String> status) {
        this.edgeOverlayPane = edgeOverlayPane;
        this.status = status;
    }

    /** Vom Root-Build gerufen: frisch erzeugten Renderer verdrahten und Overlay-Zustand reapplizieren. */
    public void attachRenderer(de.weigend.s202.ui.core.spi.EdgeOverlayRenderer newRenderer) {
        this.renderer = newRenderer;
        renderer.setOnEdgeClicked(this::handleEdgeClicked);
        renderer.setOnEdgeCut(this::handleEdgeCut);
        renderer.setOnEdgeRestore(this::handleEdgeRestore);
        renderer.setCycleBreakEdges(cycleBreakEdges);
        renderer.setAppliedCutEdges(appliedCutEdges);
    }

    /** Nach dem Root-Build: angepinnte Kanten wieder anzeigen (überlebt refreshLayout). */
    public void reapplyPendingEdges() {
        if (pendingEdges != null && renderer != null) {
            renderer.setEdges(pendingEdges);
            renderer.setSelectedEdge(pendingSelFrom, pendingSelTo);
            edgeOverlayPane.setVisible(true);
        }
    }

    public Set<DependencyEdge> appliedCutEdges() {
        return appliedCutEdges;
    }

    public void setVisualization(List<DependencyEdge> edges, String selectedFrom, String selectedTo) {
        if (edges == null || edges.isEmpty()) {
            pendingEdges = null;
            pendingSelFrom = null;
            pendingSelTo = null;
            if (renderer != null) {
                renderer.clear();
            }
            if (edgeOverlayPane != null) {
                edgeOverlayPane.setVisible(false);
            }
            return;
        }
        pendingEdges = List.copyOf(edges);
        pendingSelFrom = selectedFrom;
        pendingSelTo = selectedTo;
        if (renderer != null) {
            renderer.setEdges(pendingEdges);
            renderer.setSelectedEdge(selectedFrom, selectedTo);
        }
        if (edgeOverlayPane != null) {
            edgeOverlayPane.setVisible(true);
        }
    }

    public void setSelectedEdge(String from, String to) {
        pendingSelFrom = from;
        pendingSelTo = to;
        if (renderer != null) {
            renderer.setSelectedEdge(from, to);
        }
    }

    public Set<DependencyEdge> getCycleBreakEdges() {
        return cycleBreakEdges;
    }

    public void setCycleBreakEdges(Set<DependencyEdge> edges) {
        this.cycleBreakEdges = edges == null ? Set.of() : Set.copyOf(edges);
        if (renderer != null) {
            renderer.setCycleBreakEdges(this.cycleBreakEdges);
        }
    }

    public void setAppliedCutEdges(Set<DependencyEdge> cuts) {
        appliedCutEdges.clear();
        if (cuts != null) {
            appliedCutEdges.addAll(cuts);
        }
        if (renderer != null) {
            renderer.setAppliedCutEdges(appliedCutEdges);
        }
    }

    public void applyEdgeCut(String from, String to) {
        if (from == null || to == null) {
            return;
        }
        DependencyEdge cut = new DependencyEdge(from, to);
        if (!appliedCutEdges.add(cut)) {
            return;
        }
        if (from.equals(pendingSelFrom) && to.equals(pendingSelTo)) {
            pendingSelFrom = null;
            pendingSelTo = null;
            edgeClickedSink.accept(null, null);
        }
        if (renderer != null) {
            renderer.setAppliedCutEdges(appliedCutEdges);
            renderer.setSelectedEdge(pendingSelFrom, pendingSelTo);
        }
        status.accept("Refactoring Preview: cut " + simple(from) + " -> " + simple(to));
    }

    public void applyEdgeCuts(Collection<DependencyEdge> cuts) {
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
            if (cut.from().equals(pendingSelFrom) && cut.to().equals(pendingSelTo)) {
                pendingSelFrom = null;
                pendingSelTo = null;
                edgeClickedSink.accept(null, null);
            }
        }
        if (added == 0) {
            return;
        }
        if (renderer != null) {
            renderer.setAppliedCutEdges(appliedCutEdges);
            renderer.setSelectedEdge(pendingSelFrom, pendingSelTo);
        }
        status.accept("Refactoring Preview: cut " + added + " edge" + (added == 1 ? "" : "s"));
    }

    public void restoreEdgeCut(String from, String to) {
        if (from == null || to == null) {
            return;
        }
        DependencyEdge cut = new DependencyEdge(from, to);
        if (!appliedCutEdges.remove(cut)) {
            return;
        }
        if (renderer != null) {
            renderer.setAppliedCutEdges(appliedCutEdges);
        }
        status.accept("Restored preview cut: " + simple(from) + " -> " + simple(to));
    }

    private void handleEdgeClicked(String from, String to) {
        pendingSelFrom = from;
        pendingSelTo = to;
        edgeClickedSink.accept(from, to);
    }

    private void handleEdgeCut(String from, String to) {
        if (from == null || to == null || pendingEdges == null) {
            return;
        }
        DependencyEdge cut = new DependencyEdge(from, to);
        if (!pendingEdges.contains(cut)) {
            return;
        }
        applyEdgeCut(from, to);
        edgeCutSink.accept(from, to);
    }

    private void handleEdgeRestore(String from, String to) {
        restoreEdgeCut(from, to);
        edgeRestoreSink.accept(from, to);
    }

    public void setOnEdgeClicked(BiConsumer<String, String> sink) {
        this.edgeClickedSink = sink == null ? (a, b) -> {} : sink;
    }

    public void setOnEdgeCut(BiConsumer<String, String> sink) {
        this.edgeCutSink = sink == null ? (a, b) -> {} : sink;
    }

    public void setOnEdgeRestore(BiConsumer<String, String> sink) {
        this.edgeRestoreSink = sink == null ? (a, b) -> {} : sink;
    }

    private static String simple(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
