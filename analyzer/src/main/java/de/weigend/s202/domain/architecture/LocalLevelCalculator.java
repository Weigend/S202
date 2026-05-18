package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.graph.StronglyConnectedComponent;
import de.weigend.s202.graph.TarjanSCCFinder;
import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns a per-parent layer position ({@code localLevel}) to every
 * element in a {@link DomainModel}. Each parent package is treated as a
 * self-contained scope: only class dependencies whose source <em>and</em>
 * target both live in the parent's subtree contribute to the sibling-only
 * weighted graph. Refs that leave the parent are ignored — they belong to
 * the global {@code architectureLevel} via the package hierarchy.
 *
 * <p>Within each parent's sibling graph:
 * <ol>
 *   <li>Tarjan finds SCCs.</li>
 *   <li>SCCs of size &gt; 1 are broken one edge at a time. The default
 *       strategy evaluates every candidate edge in the whole SCC and cuts
 *       the edge whose removal decomposes the cyclic component the most.
 *       Package-architecture contradictions, edge weight, and rank are used
 *       only as tie-breakers.</li>
 *   <li>Longest-path on the SCC-collapsed DAG assigns a {@code layer}
 *       (= local layer index) per sibling. Layer 0 = bottom of the box,
 *       higher values = visually higher.</li>
 * </ol>
 *
 * <p>Single-child containers stay at layer 0 — no graph, no work.
 *
 * <p>The algorithm intentionally mirrors the structure of
 * {@link LevelCalculator}'s package-level computation, but applied
 * locally and using a different graph. Unlike package-level computation,
 * local SCC breaking is layout-oriented: it aims to stabilize the visible
 * architecture by reducing cyclic local structure, not to minimize the
 * number of visible upward edges. Local cycles are not surfaced as tangles;
 * the user-visible tangle list remains the global one.
 */
public class LocalLevelCalculator {

    private static final double RANK_THRESHOLD = 0.1;
    private final BreakMode breakMode;

    public LocalLevelCalculator() {
        this(BreakMode.MAX_CYCLE_BREAK);
    }

    LocalLevelCalculator(BreakMode breakMode) {
        this.breakMode = breakMode;
    }

    enum BreakMode {
        /**
         * Legacy local heuristic: remove every edge whose source has lower
         * weighted rank than its target by more than {@link #RANK_THRESHOLD}.
         */
        RANK,
        /**
         * Default local heuristic: inspect the whole SCC and remove one edge
         * whose cut maximally decomposes the cyclic component.
         */
        MAX_CYCLE_BREAK
    }

    public void assign(DomainModel domain, DependencyModel rawModel) {
        Map<String, List<CalculatedElementInfo>> childrenByParent = groupChildrenByParent(domain);
        for (Map.Entry<String, List<CalculatedElementInfo>> entry : childrenByParent.entrySet()) {
            assignForParent(entry.getValue(), domain, rawModel);
        }
    }

    private void assignForParent(List<CalculatedElementInfo> siblings,
                                 DomainModel domain, DependencyModel rawModel) {
        if (siblings.size() <= 1) {
            return; // single child stays at layer 0
        }

        Set<String> siblingFqns = new LinkedHashSet<>();
        for (CalculatedElementInfo c : siblings) {
            siblingFqns.add(c.fullName);
        }

        Map<String, Map<String, Integer>> weights = buildSiblingGraph(siblingFqns, domain, rawModel);
        Map<String, Integer> layers = computeLayers(weights, siblings, siblingFqns);
        for (CalculatedElementInfo s : siblings) {
            s.setLocalLayerIndex(layers.getOrDefault(s.fullName, 0));
        }
    }

    /**
     * Build the weighted sibling-only edge map. For every class C in any
     * sibling's subtree, every dep into another sibling's subtree
     * contributes {@code callCount(C, dep)} weight; deps that leave the
     * parent's subtree or stay inside the same sibling are skipped.
     */
    private Map<String, Map<String, Integer>> buildSiblingGraph(Set<String> siblingFqns,
                                                                DomainModel domain,
                                                                DependencyModel rawModel) {
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        for (String s : siblingFqns) {
            weights.put(s, new HashMap<>());
        }
        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            String fromSibling = containingSibling(cls.fullName, siblingFqns);
            if (fromSibling == null) {
                continue;
            }
            for (String dep : cls.dependencies) {
                if (domain.getClass(dep) == null) {
                    continue; // external — not in any sibling
                }
                String toSibling = containingSibling(dep, siblingFqns);
                if (toSibling == null || toSibling.equals(fromSibling)) {
                    continue; // out of parent OR intra-sibling
                }
                int w = callCount(cls.fullName, dep, rawModel);
                weights.get(fromSibling).merge(toSibling, w, Integer::sum);
            }
        }
        return weights;
    }

    /**
     * Walk up the fqn package chain until the first ancestor that is one
     * of the siblings, or return {@code null} when none of the ancestors
     * is — the class lives outside the parent's subtree.
     */
    private static String containingSibling(String fqn, Set<String> siblingFqns) {
        String current = fqn;
        while (current != null && !current.isEmpty()) {
            if (siblingFqns.contains(current)) {
                return current;
            }
            int dot = current.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            current = current.substring(0, dot);
        }
        return null;
    }

    private static Map<String, List<CalculatedElementInfo>> groupChildrenByParent(DomainModel domain) {
        Map<String, List<CalculatedElementInfo>> result = new HashMap<>();
        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            result.computeIfAbsent(parentOf(cls.fullName), k -> new ArrayList<>()).add(cls);
        }
        for (CalculatedElementInfo pkg : domain.getAllPackages().values()) {
            result.computeIfAbsent(parentOf(pkg.fullName), k -> new ArrayList<>()).add(pkg);
        }
        return result;
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /**
     * Method-call count from class {@code from} to class {@code to}.
     * Falls back to 1 for structural deps (extends/implements/field type)
     * where no method-call info exists — same convention the global
     * weighted package graph uses.
     */
    private static int callCount(String from, String to, DependencyModel rawModel) {
        DependencyModel.ClassInfo cls = rawModel.getClass(from);
        if (cls == null) {
            return 1;
        }
        int count = 0;
        String prefix = to + ".";
        for (DependencyModel.MethodInfo method : cls.methods.values()) {
            for (Map.Entry<String, Integer> call : method.methodCalls.entrySet()) {
                if (call.getKey().startsWith(prefix)) {
                    count += call.getValue();
                }
            }
        }
        return count > 0 ? count : 1;
    }

    // ----------- SCC-collapsed longest path with configurable SCC break -----------

    private Map<String, Integer> computeLayers(Map<String, Map<String, Integer>> weights,
                                                List<CalculatedElementInfo> siblings,
                                                Set<String> nodes) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (String n : nodes) {
            graph.put(n, new HashSet<>(weights.getOrDefault(n, Map.of()).keySet()));
        }
        Map<String, CalculatedElementInfo> siblingByName = new HashMap<>();
        for (CalculatedElementInfo sibling : siblings) {
            siblingByName.put(sibling.fullName, sibling);
        }

        // Iteratively break local SCCs, then assign layers on the remaining DAG.
        // MAX_CYCLE_BREAK is the default; RANK remains available as a legacy
        // comparison mode for tests and experiments.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StronglyConnectedComponent scc : new TarjanSCCFinder(graph).findSCCs()) {
                if (scc.getSize() < 2) {
                    continue;
                }
                Set<String> members = scc.getMembers();
                Map<String, Double> rank = new HashMap<>();
                for (String m : members) {
                    int out = 0;
                    int in = 0;
                    for (String other : members) {
                        if (other.equals(m)) {
                            continue;
                        }
                        out += weights.getOrDefault(m, Map.of()).getOrDefault(other, 0);
                        in  += weights.getOrDefault(other, Map.of()).getOrDefault(m, 0);
                    }
                    rank.put(m, (out - in) / (double) Math.max(1, out + in));
                }
                if (breakMode == BreakMode.MAX_CYCLE_BREAK) {
                    EdgeCutCandidate best = bestCycleBreakingEdge(graph, weights, members, rank, siblingByName);
                    if (best != null) {
                        graph.get(best.from()).remove(best.to());
                        changed = true;
                    }
                    continue;
                }
                // Legacy path: remove all rank-based back-edges found in this SCC.
                for (String from : new ArrayList<>(members)) {
                    for (String to : new ArrayList<>(graph.getOrDefault(from, Set.of()))) {
                        if (!members.contains(to)) {
                            continue;
                        }
                        if (rank.get(from) < rank.get(to) - RANK_THRESHOLD) {
                            graph.get(from).remove(to);
                            changed = true;
                        }
                    }
                }
            }
        }

        // SCC-collapsed longest-path level assignment.
        List<StronglyConnectedComponent> sccs = new TarjanSCCFinder(graph).findSCCs();
        Map<String, StronglyConnectedComponent> nodeToScc = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String m : scc.getMembers()) {
                nodeToScc.put(m, scc);
            }
        }
        Map<Integer, Set<Integer>> sccDeps = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            sccDeps.put(scc.getId(), new HashSet<>());
            for (String m : scc.getMembers()) {
                for (String to : graph.getOrDefault(m, Set.of())) {
                    StronglyConnectedComponent toScc = nodeToScc.get(to);
                    if (toScc != null && toScc.getId() != scc.getId()) {
                        sccDeps.get(scc.getId()).add(toScc.getId());
                    }
                }
            }
        }
        Map<Integer, Integer> sccLayers = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            sccLayers.put(scc.getId(), 0);
        }
        boolean lvlChanged = true;
        while (lvlChanged) {
            lvlChanged = false;
            for (StronglyConnectedComponent scc : sccs) {
                int maxDep = -1;
                for (int depId : sccDeps.get(scc.getId())) {
                    maxDep = Math.max(maxDep, sccLayers.get(depId));
                }
                int newLayer = maxDep >= 0 ? maxDep + 1 : 0;
                if (sccLayers.get(scc.getId()) != newLayer) {
                    sccLayers.put(scc.getId(), newLayer);
                    lvlChanged = true;
                }
            }
        }

        Map<String, Integer> result = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            int layer = sccLayers.get(scc.getId());
            for (String m : scc.getMembers()) {
                result.put(m, layer);
            }
        }
        return result;
    }

    private static EdgeCutCandidate bestCycleBreakingEdge(Map<String, Set<String>> graph,
                                                          Map<String, Map<String, Integer>> weights,
                                                          Set<String> members,
                                                          Map<String, Double> rank,
                                                          Map<String, CalculatedElementInfo> siblingByName) {
        EdgeCutCandidate best = null;
        for (String from : members) {
            for (String to : graph.getOrDefault(from, Set.of())) {
                if (!members.contains(to)) {
                    continue;
                }
                EdgeCutCandidate candidate = new EdgeCutCandidate(
                        from,
                        to,
                        cycleBreakScore(graph, members, from, to),
                        architectureContradiction(from, to, siblingByName),
                        weights.getOrDefault(from, Map.of()).getOrDefault(to, 0),
                        rank.getOrDefault(to, 0.0) - rank.getOrDefault(from, 0.0));
                if (best == null || candidate.compareTo(best) > 0) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Score how much one candidate cut decomposes the current SCC. The score
     * compares the original SCC size squared with the sum of squared SCC sizes
     * after removing the candidate edge. A cut that splits one large cycle into
     * several smaller components therefore wins over a cut that leaves the SCC
     * almost intact.
     */
    private static int cycleBreakScore(Map<String, Set<String>> graph,
                                       Set<String> members,
                                       String cutFrom,
                                       String cutTo) {
        Map<String, Set<String>> copy = new HashMap<>();
        for (String member : members) {
            copy.put(member, new HashSet<>());
            for (String to : graph.getOrDefault(member, Set.of())) {
                if (members.contains(to) && !(member.equals(cutFrom) && to.equals(cutTo))) {
                    copy.get(member).add(to);
                }
            }
        }

        int after = 0;
        for (StronglyConnectedComponent scc : new TarjanSCCFinder(copy).findSCCs()) {
            after += scc.getSize() * scc.getSize();
        }
        return members.size() * members.size() - after;
    }

    /**
     * Tie-breaker for package siblings: if an edge runs against the already
     * computed package architectureLevel, prefer cutting it over an otherwise
     * equivalent package edge. Class edges return 0 because classes are placed
     * inside the package frame, not used to redefine that frame.
     */
    private static int architectureContradiction(String from,
                                                 String to,
                                                 Map<String, CalculatedElementInfo> siblingByName) {
        CalculatedElementInfo fromInfo = siblingByName.get(from);
        CalculatedElementInfo toInfo = siblingByName.get(to);
        if (fromInfo == null || toInfo == null) {
            return 0;
        }
        if (!"PACKAGE".equals(fromInfo.type) || !"PACKAGE".equals(toInfo.type)) {
            return 0;
        }
        return fromInfo.architectureLevel < toInfo.architectureLevel ? 1 : 0;
    }

    private record EdgeCutCandidate(String from,
                                    String to,
                                    int cycleBreakScore,
                                    int architectureContradiction,
                                    int weight,
                                    double rankBackEdgeScore)
            implements Comparable<EdgeCutCandidate> {
        /**
         * Higher is better. Order of preference:
         * 1. always cut an edge that contradicts the package architecture
         *    hypothesis first — Phase 3 must never reorder packages relative
         *    to what Phase 2 computed,
         * 2. among architecture-equivalent candidates, decompose the local SCC
         *    as much as possible,
         * 3. cut the weaker edge,
         * 4. fall back to the legacy rank signal.
         */
        @Override
        public int compareTo(EdgeCutCandidate other) {
            int cmp = Integer.compare(architectureContradiction, other.architectureContradiction);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(cycleBreakScore, other.cycleBreakScore);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(other.weight, weight);
            if (cmp != 0) {
                return cmp;
            }
            return Double.compare(rankBackEdgeScore, other.rankBackEdgeScore);
        }
    }
}
