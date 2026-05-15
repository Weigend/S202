package de.weigend.s202.ui.wfx.events;

import de.weigend.s202.domain.DependencyEdge;

import java.util.Collection;
import java.util.EventObject;
import java.util.Set;

/**
 * Published when several recommended tangle cut edges are applied at once.
 */
public class CutTangleEdgesEvent extends EventObject {

    private final Set<DependencyEdge> edges;

    public CutTangleEdgesEvent(Collection<DependencyEdge> edges, Object source) {
        super(source);
        this.edges = edges == null ? Set.of() : Set.copyOf(edges);
    }

    public Set<DependencyEdge> getEdges() {
        return edges;
    }
}
