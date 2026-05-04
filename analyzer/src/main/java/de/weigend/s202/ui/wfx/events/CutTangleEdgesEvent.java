package de.weigend.s202.ui.wfx.events;

import de.weigend.s202.ui.rendering.TangleEdgeRenderer;

import java.util.Collection;
import java.util.EventObject;
import java.util.Set;

/**
 * Published when several recommended tangle cut edges are applied at once.
 */
public class CutTangleEdgesEvent extends EventObject {

    private final Set<TangleEdgeRenderer.Edge> edges;

    public CutTangleEdgesEvent(Collection<TangleEdgeRenderer.Edge> edges, Object source) {
        super(source);
        this.edges = edges == null ? Set.of() : Set.copyOf(edges);
    }

    public Set<TangleEdgeRenderer.Edge> getEdges() {
        return edges;
    }
}
