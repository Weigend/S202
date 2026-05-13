package de.weigend.s202.ui.whatif;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups static {@link ClassEdge}s by their virtual source/target package
 * (resolved through {@link VirtualIdentity}). Intra-package edges drop
 * out — only cross-package edges produce aggregates.
 *
 * <p>{@link #recompute(VirtualIdentity, Collection)} is the only mutator.
 * Phase 3 MVP uses a full re-bucketing per recompute call; the package
 * graph stays small enough for this to be well under a pulse-budget
 * (ADR §4.6). Per-class-edge incremental tracking can be layered on later
 * if profiling shows it matters.
 */
public final class PackageAggregator {

    private Map<EdgeKey, PackageAggregate> aggregates = Map.of();

    public void recompute(VirtualIdentity identity, Collection<ClassEdge> classEdges) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (classEdges == null) {
            throw new IllegalArgumentException("classEdges must not be null");
        }
        Map<EdgeKey, List<ClassEdge>> buckets = new HashMap<>();
        for (ClassEdge edge : classEdges) {
            String srcPkg = identity.virtualParent(edge.source());
            String tgtPkg = identity.virtualParent(edge.target());
            if (srcPkg.equals(tgtPkg)) {
                continue;
            }
            buckets.computeIfAbsent(new EdgeKey(srcPkg, tgtPkg), k -> new ArrayList<>()).add(edge);
        }
        Map<EdgeKey, PackageAggregate> next = new HashMap<>(buckets.size());
        for (Map.Entry<EdgeKey, List<ClassEdge>> entry : buckets.entrySet()) {
            EdgeKey key = entry.getKey();
            next.put(key, new PackageAggregate(key.source(), key.target(), entry.getValue()));
        }
        this.aggregates = Collections.unmodifiableMap(next);
    }

    /** All current aggregates, keyed by (source, target). */
    public Map<EdgeKey, PackageAggregate> aggregates() {
        return aggregates;
    }

    /** Aggregate for a specific package pair, or {@code null} if no edges connect them. */
    public PackageAggregate get(String sourcePackage, String targetPackage) {
        return aggregates.get(new EdgeKey(sourcePackage, targetPackage));
    }

    /** Number of distinct aggregate edges in the package graph. */
    public int size() {
        return aggregates.size();
    }

    /** Key for an aggregated package-to-package edge. */
    public record EdgeKey(String source, String target) {}
}
