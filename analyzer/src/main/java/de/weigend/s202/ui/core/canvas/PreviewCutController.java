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
import de.weigend.s202.ui.core.arrows.DependencyEdgeActions;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Refactoring-preview cuts of one canvas: which dependencies are cut, which
 * ones the level calculation recommends as cuts, and the sinks that tell the
 * host shell when the user cuts, restores or selects a dependency arrow.
 * Every change asks the canvas to redraw its arrows.
 */
public final class PreviewCutController implements DependencyEdgeActions {

    private final Consumer<String> status;
    private final Runnable redrawArrows;

    private Set<DependencyEdge> cycleBreakEdges = Set.of();
    private final Set<DependencyEdge> appliedCutEdges = new HashSet<>();

    private BiConsumer<String, String> edgeClickedSink = (a, b) -> { /* no-op default */ };
    private BiConsumer<String, String> edgeCutSink = (a, b) -> { /* no-op default */ };
    private BiConsumer<String, String> edgeRestoreSink = (a, b) -> { /* no-op default */ };

    public PreviewCutController(Consumer<String> status, Runnable redrawArrows) {
        this.status = status;
        this.redrawArrows = redrawArrows;
    }

    public Set<DependencyEdge> appliedCutEdges() {
        return appliedCutEdges;
    }

    public Set<DependencyEdge> getCycleBreakEdges() {
        return cycleBreakEdges;
    }

    public void setCycleBreakEdges(Set<DependencyEdge> edges) {
        this.cycleBreakEdges = edges == null ? Set.of() : Set.copyOf(edges);
    }

    public void setAppliedCutEdges(Set<DependencyEdge> cuts) {
        appliedCutEdges.clear();
        if (cuts != null) {
            appliedCutEdges.addAll(cuts);
        }
        redrawArrows.run();
    }

    public void applyEdgeCut(String from, String to) {
        if (from == null || to == null) {
            return;
        }
        if (!appliedCutEdges.add(new DependencyEdge(from, to))) {
            return;
        }
        redrawArrows.run();
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
        }
        if (added == 0) {
            return;
        }
        redrawArrows.run();
        status.accept("Refactoring Preview: cut " + added + " edge" + (added == 1 ? "" : "s"));
    }

    public void restoreEdgeCut(String from, String to) {
        if (from == null || to == null) {
            return;
        }
        if (!appliedCutEdges.remove(new DependencyEdge(from, to))) {
            return;
        }
        redrawArrows.run();
        status.accept("Restored preview cut: " + simple(from) + " -> " + simple(to));
    }

    /* ----- DependencyEdgeActions: what the arrows of this canvas report ------ */

    @Override
    public boolean isCut(String from, String to) {
        return appliedCutEdges.contains(new DependencyEdge(from, to));
    }

    @Override
    public void cut(String from, String to) {
        applyEdgeCut(from, to);
        edgeCutSink.accept(from, to);
    }

    @Override
    public void restore(String from, String to) {
        restoreEdgeCut(from, to);
        edgeRestoreSink.accept(from, to);
    }

    @Override
    public void clicked(String from, String to) {
        edgeClickedSink.accept(from, to);
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
