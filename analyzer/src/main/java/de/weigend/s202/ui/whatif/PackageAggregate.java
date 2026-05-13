package de.weigend.s202.ui.whatif;

import java.util.List;

/**
 * Aggregation of all class-to-class edges that, given the current
 * {@link VirtualIdentity}, connect virtual package {@code source} to
 * virtual package {@code target}. The original class edges remain
 * available for the Dependencies-View drilldown (Phase 5).
 */
public record PackageAggregate(String source, String target, List<ClassEdge> classEdges) {

    public PackageAggregate {
        classEdges = List.copyOf(classEdges);
    }

    /** Number of distinct class-to-class edges behind this aggregate. */
    public int classEdgeCount() {
        return classEdges.size();
    }

    /** Sum of method-call counts across all class edges in the aggregate. */
    public int totalWeight() {
        int sum = 0;
        for (ClassEdge e : classEdges) {
            sum += e.weight();
        }
        return sum;
    }
}
