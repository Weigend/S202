package de.weigend.s202.ui.whatif;

import de.weigend.s202.graph.StronglyConnectedComponent;
import de.weigend.s202.graph.TarjanSCCFinder;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The virtual package graph derived from a set of {@link PackageAggregate}s:
 * SCCs (via Tarjan on the package adjacency) and longest-path levels on the
 * SCC condensation DAG. Computed fresh on each {@link #compute(Collection)}
 * call; the package graph is small enough that a full recompute is sub-pulse
 * (ADR §4.6).
 *
 * <p>Level convention matches the existing analyzer: leaf packages (no
 * outgoing edges to any other package) sit at level 0; each ancestor SCC's
 * level is {@code max(successor levels) + 1}. Members of an SCC share a
 * single level.
 */
public final class VirtualPackageGraph {

    private final Map<String, Integer> packageLevels;
    private final List<StronglyConnectedComponent> sccs;
    private final Map<String, Integer> packageToSccId;

    private VirtualPackageGraph(Map<String, Integer> packageLevels,
                                List<StronglyConnectedComponent> sccs,
                                Map<String, Integer> packageToSccId) {
        this.packageLevels = Collections.unmodifiableMap(packageLevels);
        this.sccs = List.copyOf(sccs);
        this.packageToSccId = Collections.unmodifiableMap(packageToSccId);
    }

    public static VirtualPackageGraph compute(Collection<PackageAggregate> aggregates) {
        if (aggregates == null) {
            throw new IllegalArgumentException("aggregates must not be null");
        }

        Map<String, Set<String>> adjacency = buildAdjacency(aggregates);
        TarjanSCCFinder finder = new TarjanSCCFinder(adjacency);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();

        Map<String, Integer> packageToSccId = mapPackagesToSccIds(sccs);
        Map<Integer, Set<Integer>> sccDag = buildSccDag(aggregates, packageToSccId);
        Map<Integer, Integer> sccLevels = computeSccLevels(sccs, sccDag);

        Map<String, Integer> packageLevels = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            int level = sccLevels.getOrDefault(scc.getId(), 0);
            scc.setLevel(level);
            for (String member : scc.getMembers()) {
                packageLevels.put(member, level);
            }
        }
        return new VirtualPackageGraph(packageLevels, sccs, packageToSccId);
    }

    private static Map<String, Set<String>> buildAdjacency(Collection<PackageAggregate> aggregates) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (PackageAggregate a : aggregates) {
            adjacency.computeIfAbsent(a.source(), k -> new HashSet<>()).add(a.target());
            // Ensure every referenced package is a vertex even if it has no outgoing edges.
            adjacency.computeIfAbsent(a.target(), k -> new HashSet<>());
        }
        return adjacency;
    }

    private static Map<String, Integer> mapPackagesToSccIds(List<StronglyConnectedComponent> sccs) {
        Map<String, Integer> result = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                result.put(member, scc.getId());
            }
        }
        return result;
    }

    private static Map<Integer, Set<Integer>> buildSccDag(Collection<PackageAggregate> aggregates,
                                                          Map<String, Integer> packageToSccId) {
        Map<Integer, Set<Integer>> dag = new HashMap<>();
        for (Integer sccId : packageToSccId.values()) {
            dag.computeIfAbsent(sccId, k -> new HashSet<>());
        }
        for (PackageAggregate a : aggregates) {
            Integer srcScc = packageToSccId.get(a.source());
            Integer tgtScc = packageToSccId.get(a.target());
            if (srcScc != null && tgtScc != null && !srcScc.equals(tgtScc)) {
                dag.get(srcScc).add(tgtScc);
            }
        }
        return dag;
    }

    private static Map<Integer, Integer> computeSccLevels(List<StronglyConnectedComponent> sccs,
                                                          Map<Integer, Set<Integer>> sccDag) {
        Map<Integer, Integer> memo = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            longestPathLevel(scc.getId(), sccDag, memo);
        }
        return memo;
    }

    private static int longestPathLevel(int sccId, Map<Integer, Set<Integer>> dag, Map<Integer, Integer> memo) {
        Integer cached = memo.get(sccId);
        if (cached != null) {
            return cached;
        }
        // Mark as in-progress to avoid infinite recursion on a malformed DAG —
        // tarjan-produced SCC graphs are acyclic by construction, so this is
        // belt-and-braces.
        memo.put(sccId, 0);
        int max = -1;
        for (int succ : dag.getOrDefault(sccId, Set.of())) {
            int sl = longestPathLevel(succ, dag, memo);
            if (sl > max) {
                max = sl;
            }
        }
        int level = max + 1;
        memo.put(sccId, level);
        return level;
    }

    /** Level of the given package, or {@code -1} if it isn't in the graph. */
    public int levelOf(String packageName) {
        return packageLevels.getOrDefault(packageName, -1);
    }

    /** SCC id of the given package, or {@code -1} if not in the graph. */
    public int sccIdOf(String packageName) {
        return packageToSccId.getOrDefault(packageName, -1);
    }

    /** True if the package sits in a tangle (SCC of size {@literal >} 1). */
    public boolean isInTangle(String packageName) {
        Integer sccId = packageToSccId.get(packageName);
        if (sccId == null) {
            return false;
        }
        for (StronglyConnectedComponent scc : sccs) {
            if (scc.getId() == sccId) {
                return scc.isTangle();
            }
        }
        return false;
    }

    public List<StronglyConnectedComponent> sccs() {
        return sccs;
    }

    public Set<String> packages() {
        return packageLevels.keySet();
    }
}
