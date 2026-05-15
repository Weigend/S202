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
 * Assigns a per-parent layer position ({@code localLayerIndex}) to every
 * element in a {@link DomainModel}. Each parent package is treated as a
 * self-contained scope: only class dependencies whose source <em>and</em>
 * target both live in the parent's subtree contribute to the sibling-only
 * weighted graph. Refs that leave the parent are ignored — they belong to
 * the global {@code architectureLevel} via the package hierarchy.
 *
 * <p>Within each parent's sibling graph:
 * <ol>
 *   <li>Tarjan finds SCCs.</li>
 *   <li>SCCs of size &gt; 1 are broken by weight-based rank: a back-edge
 *       leaves the smaller-rank member, the dominant direction stays.</li>
 *   <li>Longest-path on the SCC-collapsed DAG assigns a {@code layer}
 *       (= local layer index) per sibling. Layer 0 = bottom of the box,
 *       higher values = visually higher.</li>
 * </ol>
 *
 * <p>Single-child containers stay at layer 0 — no graph, no work.
 *
 * <p>The algorithm intentionally mirrors the structure of
 * {@link LevelCalculator}'s package-level computation, but applied
 * locally and using a different graph. Local cycles are not surfaced
 * as tangles; the user-visible tangle list remains the global one.
 */
public class LocalLayerCalculator {

    private static final double RANK_THRESHOLD = 0.1;

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
        Map<String, Integer> layers = computeLayers(weights, siblingFqns);
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

    // ----------- SCC-collapsed longest path with rank-based break -----------

    private Map<String, Integer> computeLayers(Map<String, Map<String, Integer>> weights,
                                                Set<String> nodes) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (String n : nodes) {
            graph.put(n, new HashSet<>(weights.getOrDefault(n, Map.of()).keySet()));
        }

        // Identify and remove back-edges within SCCs based on weight rank.
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
}
