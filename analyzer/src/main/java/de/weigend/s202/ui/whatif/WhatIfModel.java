package de.weigend.s202.ui.whatif;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Coordinator for the What-If layer. Holds the static class-edge list
 * (built once per analysis), owns the {@link VirtualIdentity} the user
 * mutates via DnD, and exposes the latest {@link VirtualPackageGraph}
 * after every override change. Recompute runs synchronously inside
 * {@link #applyMove(String, String)} — see ADR §2.4: the aggregator
 * re-buckets edges (O(|edges|)) and the package graph runs Tarjan +
 * longest-path on the small condensation DAG.
 *
 * <p>Listeners registered via {@link #addChangeListener(Runnable)} fire
 * after each recompute. Renderers (Phase 4) and the Dependencies-View
 * (Phase 5) hook in there.
 */
public final class WhatIfModel {

    private final List<ClassEdge> staticEdges;
    private final VirtualIdentity identity = new VirtualIdentity();
    private final PackageAggregator aggregator = new PackageAggregator();
    private final List<Runnable> changeListeners = new ArrayList<>();

    private VirtualPackageGraph graph;
    private final VirtualPackageGraph staticGraph;

    public WhatIfModel(Collection<ClassEdge> staticEdges) {
        if (staticEdges == null) {
            throw new IllegalArgumentException("staticEdges must not be null");
        }
        this.staticEdges = List.copyOf(staticEdges);
        recompute();
        // Snapshot the override-free graph as the baseline for SCC diffs.
        this.staticGraph = this.graph;
    }

    /**
     * Apply a What-If move: anchor {@code fqcn}'s virtual parent at
     * {@code newVirtualParent}. Passing {@code null} for the parent removes
     * the override and reverts the node to its static identity.
     */
    public void applyMove(String fqcn, String newVirtualParent) {
        identity.setOverride(fqcn, newVirtualParent);
        recompute();
        fireChange();
    }

    /** Drop all overrides (e.g. after re-analysis, per §4.4). */
    public void reset() {
        if (identity.isEmpty()) {
            return;
        }
        identity.clear();
        recompute();
        fireChange();
    }

    public VirtualIdentity identity() {
        return identity;
    }

    public PackageAggregator aggregator() {
        return aggregator;
    }

    public VirtualPackageGraph graph() {
        return graph;
    }

    /**
     * The override-free baseline graph, computed once at construction. Useful
     * for diffing the current state against the static analysis (e.g. "which
     * tangles did my moves create or dissolve?").
     */
    public VirtualPackageGraph staticGraph() {
        return staticGraph;
    }

    public List<ClassEdge> staticEdges() {
        return staticEdges;
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void recompute() {
        aggregator.recompute(identity, staticEdges);
        graph = VirtualPackageGraph.compute(aggregator.aggregates().values());
    }

    private void fireChange() {
        for (Runnable l : changeListeners) {
            l.run();
        }
    }
}
