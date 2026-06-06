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
package de.weigend.s202.domain.impl;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Assigns a per-parent layer position ({@code localLevel}) to every element in a
 * {@link DomainModel}.  Each parent package is treated as an independent scope:
 * only dependencies whose source <em>and</em> target both live inside the parent's
 * direct children contribute to the sibling-only weighted graph.
 *
 * <p>Class back-edges recorded by {@link LevelCalculator} (Step 4) are excluded
 * when building the sibling graph.  Because the class graph after Step 4 is a
 * DAG, the aggregated sibling graph is acyclic by construction and no
 * cycle-breaking fires in practice.  The rank-score SCC-break in
 * {@link #computeLayers} is retained as a safety net.
 */
final class LocalLevelCalculator {

    public void assign(DomainModel domain, DependencyModel rawModel) {
        Map<String, List<CalculatedElementInfo>> childrenByParent = groupChildrenByParent(domain);
        new TreeMap<>(childrenByParent).forEach((key, siblings) ->
            assignForParent(siblings, domain, rawModel));
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
            s.setLocalLevel(layers.getOrDefault(s.fullName, 0));
        }
    }

    /**
     * Build the weighted sibling-only edge map.  For every class C in any
     * sibling's subtree, every dep into another sibling's subtree contributes
     * {@code callCount(C, dep)} weight; deps that leave the parent's subtree or
     * stay inside the same sibling are skipped.
     */
    private Map<String, Map<String, Integer>> buildSiblingGraph(Set<String> siblingFqns,
                                                                DomainModel domain,
                                                                DependencyModel rawModel) {
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        for (String s : siblingFqns) {
            weights.put(s, new HashMap<>());
        }
        List<CalculatedElementInfo> sortedClasses = new ArrayList<>(domain.getAllClasses().values());
        sortedClasses.sort(Comparator.comparing(c -> c.fullName));
        for (CalculatedElementInfo cls : sortedClasses) {
            String fromSibling = containingSibling(cls.fullName, siblingFqns);
            if (fromSibling == null) {
                continue;
            }
            List<String> sortedDeps = new ArrayList<>(cls.dependencies);
            Collections.sort(sortedDeps);
            for (String dep : sortedDeps) {
                if (domain.getClass(dep) == null) {
                    continue; // external — not in any sibling
                }
                String toSibling = containingSibling(dep, siblingFqns);
                if (toSibling == null || toSibling.equals(fromSibling)) {
                    continue; // out of parent OR intra-sibling
                }
                if (domain.isClassBackEdge(cls.fullName, dep)) {
                    continue; // already cut in Step 4 — keep local ordering consistent
                }
                int w = callCount(cls.fullName, dep, rawModel);
                weights.get(fromSibling).merge(toSibling, w, Integer::sum);
            }
        }
        return weights;
    }

    /**
     * Walk up the fqn package chain until the first ancestor that is one of the
     * siblings, or {@code null} when none of the ancestors is.
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
        domain.getAllClasses().values().stream()
            .sorted(Comparator.comparing(c -> c.fullName))
            .forEach(cls -> result.computeIfAbsent(parentOf(cls.fullName), k -> new ArrayList<>()).add(cls));
        domain.getAllPackages().values().stream()
            .sorted(Comparator.comparing(p -> p.fullName))
            .forEach(pkg -> result.computeIfAbsent(parentOf(pkg.fullName), k -> new ArrayList<>()).add(pkg));
        return result;
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /**
     * Method-call count from {@code from} to {@code to}.  Falls back to 1 for
     * structural deps where no method-call info exists.
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

    // -------------------------------------------------------------------------
    // Core: two-step cycle breaking + longest-path level assignment
    // -------------------------------------------------------------------------

    /**
     * Breaks all cycles in the sibling weight graph using the deterministic two-step
     * approach, then assigns levels via longest-path on the resulting DAG.
     */
    private static Map<String, Integer> computeLayers(Map<String, Map<String, Integer>> weights,
                                                      Set<String> nodes) {
        // Build adjacency graph from weight map
        Map<String, Set<String>> graph = new HashMap<>();
        for (String n : nodes) {
            Set<String> edges = new HashSet<>(weights.getOrDefault(n, Map.of()).keySet());
            edges.retainAll(nodes);
            graph.put(n, edges);
        }

        // Two-step deterministic cycle breaking
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StronglyConnectedComponent scc : SCCFinder.defaultFinder().findSCCs(graph)) {
                if (scc.getSize() < 2) continue;
                Set<String> members = scc.getMembers();

                List<String> sortedMembers = new ArrayList<>(members);
                Collections.sort(sortedMembers);

                // Rank per member from in/out weights within the SCC.
                Map<String, Double> rank = new HashMap<>();
                for (String m : sortedMembers) {
                    int out = 0, in = 0;
                    for (String other : sortedMembers) {
                        if (other.equals(m)) continue;
                        out += weights.getOrDefault(m, Map.of()).getOrDefault(other, 0);
                        in  += weights.getOrDefault(other, Map.of()).getOrDefault(m, 0);
                    }
                    rank.put(m, (out - in) / (double) Math.max(1, out + in));
                }

                // Cut all edges running against the flow; fall back to remove-all
                // when topology gives no direction (all ranks equal).
                boolean anyCut = false;
                for (String from : sortedMembers) {
                    List<String> targets = new ArrayList<>(graph.getOrDefault(from, Set.of()));
                    Collections.sort(targets);
                    for (String to : targets) {
                        if (!members.contains(to)) continue;
                        if (rank.get(to) > rank.get(from)) {
                            graph.get(from).remove(to);
                            anyCut = true;
                        }
                    }
                }
                if (!anyCut) {
                    for (String from : sortedMembers) {
                        graph.get(from).removeIf(members::contains);
                    }
                }
                changed = true;
                break; // restart Tarjan after any graph modification
            }
        }

        // Longest-path level assignment on the resulting DAG
        Map<String, Integer> levels = new HashMap<>();
        for (String n : nodes) levels.put(n, 0);
        boolean lvlChanged = true;
        while (lvlChanged) {
            lvlChanged = false;
            List<String> sortedNodes = new ArrayList<>(nodes);
            Collections.sort(sortedNodes);
            for (String n : sortedNodes) {
                int maxDep = -1;
                List<String> deps = new ArrayList<>(graph.getOrDefault(n, Set.of()));
                Collections.sort(deps);
                for (String dep : deps) {
                    if (nodes.contains(dep)) {
                        maxDep = Math.max(maxDep, levels.getOrDefault(dep, 0));
                    }
                }
                int newLevel = maxDep >= 0 ? maxDep + 1 : 0;
                if (!levels.get(n).equals(newLevel)) {
                    levels.put(n, newLevel);
                    lvlChanged = true;
                }
            }
        }
        return levels;
    }
}
