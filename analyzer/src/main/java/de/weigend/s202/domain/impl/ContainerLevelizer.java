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

import de.weigend.s202.domain.ContainerProjection;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Levelizes every container independently.
 *
 * <p>A <em>container</em> is a package (or the root) whose direct children,
 * classes and sub-packages alike, are its <em>siblings</em>. Every class
 * dependency {@code X → Y} is attributed to exactly one container: the deepest
 * one that contains both X and Y. There it becomes a weighted edge between the
 * two siblings whose subtrees hold X and Y (see {@link ContainerProjection#project}). Nothing is
 * propagated anywhere else; a dependency between two classes of the same
 * package therefore orders those two classes, a dependency between classes of
 * two different top-level packages orders those two packages.
 *
 * <p>Per container the sibling graph is processed in three steps:
 * <ol>
 *   <li><b>Tangles</b> — SCCs of size ≥ 2 are recorded as container tangles.</li>
 *   <li><b>Cut</b> — tangles are broken with the deterministic rank cut: inside an
 *       SCC every edge running against the weighted flow
 *       ({@code rank(to) > rank(from)}) is removed; when all ranks are equal the
 *       topology gives no direction and every internal edge is removed. SCCs are
 *       recomputed after each round until none is left. Cut edges are the
 *       <em>container back-edges</em>.</li>
 *   <li><b>Levels</b> — longest-path (ASAP) levelization on the remaining DAG
 *       yields the {@code localLevel} of every sibling.</li>
 * </ol>
 *
 * <p>Consequence: a class dependency runs upward in the picture if and only if
 * its projection is a cut sibling edge. Back-edges live only inside tangles.
 */
final class ContainerLevelizer {

    /** Everything the levelization determined besides the per-element localLevel. */
    record Result(Map<String, Map<String, Integer>> siblingWeights,
                  Map<String, Integer> classEdgeWeights,
                  Set<String> cutSiblingEdges,
                  List<Set<String>> tangles) {
    }

    Result levelize(DomainModel model, DependencyModel rawModel) {
        Map<String, CalculatedElementInfo> classes = new TreeMap<>(model.getAllClasses());
        Map<String, CalculatedElementInfo> packages = new TreeMap<>(model.getAllPackages());

        // Children per container, sorted for determinism.
        Map<String, Set<String>> childrenByContainer = new TreeMap<>();
        for (String fqn : classes.keySet()) {
            childrenByContainer.computeIfAbsent(parentOf(fqn), k -> new TreeSet<>()).add(fqn);
        }
        for (String fqn : packages.keySet()) {
            childrenByContainer.computeIfAbsent(parentOf(fqn), k -> new TreeSet<>()).add(fqn);
        }

        // Sibling weights: every class dependency lands in exactly one container.
        Map<String, Map<String, Integer>> weights = new TreeMap<>();
        Map<String, Integer> classWeights = new TreeMap<>();
        for (CalculatedElementInfo cls : classes.values()) {
            Map<String, Integer> callCounts = callCountsByTarget(rawModel.getClass(cls.fullName), cls.dependencies);
            for (String dep : new TreeSet<>(cls.dependencies)) {
                if (!classes.containsKey(dep)) {
                    continue; // external — belongs to no container
                }
                ContainerProjection.Projection p = ContainerProjection.project(cls.fullName, dep);
                if (p == null) {
                    continue;
                }
                int weight = Math.max(1, callCounts.getOrDefault(dep, 0));
                classWeights.put(cls.fullName + "\0" + dep, weight);
                weights.computeIfAbsent(p.fromSibling(), k -> new TreeMap<>()).merge(p.toSibling(), weight, Integer::sum);
            }
        }

        Set<String> cut = new LinkedHashSet<>();
        List<Set<String>> tangles = new ArrayList<>();
        for (Map.Entry<String, Set<String>> container : childrenByContainer.entrySet()) {
            Set<String> siblings = container.getValue();
            Map<String, Set<String>> graph = new LinkedHashMap<>();
            for (String s : siblings) {
                Set<String> targets = new TreeSet<>(weights.getOrDefault(s, Map.of()).keySet());
                targets.retainAll(siblings);
                graph.put(s, targets);
            }
            for (StronglyConnectedComponent scc : new TarjanSCCFinder().findSCCs(graph)) {
                if (scc.getSize() >= 2) {
                    tangles.add(new TreeSet<>(scc.getMembers()));
                }
            }
            breakCycles(graph, weights, cut);
            Map<String, Integer> levels = longestPathLevels(graph);
            for (String s : siblings) {
                CalculatedElementInfo element = classes.containsKey(s) ? classes.get(s) : packages.get(s);
                if (element != null) {
                    element.setLocalLevel(levels.getOrDefault(s, 0));
                }
            }
        }
        return new Result(weights, classWeights, cut, tangles);
    }

    /**
     * Method-call count per target class, derived from the raw model. A call key
     * is {@code owner.method}; inner-class owners count for their outer class,
     * which is how the reader records dependencies.
     */
    private static Map<String, Integer> callCountsByTarget(DependencyModel.ClassInfo raw, Set<String> deps) {
        Map<String, Integer> counts = new TreeMap<>();
        if (raw == null) {
            return counts;
        }
        for (DependencyModel.MethodInfo method : raw.methods.values()) {
            for (Map.Entry<String, Integer> call : method.methodCalls.entrySet()) {
                String key = call.getKey();
                int lastDot = key.lastIndexOf('.');
                if (lastDot <= 0) {
                    continue;
                }
                String owner = key.substring(0, lastDot);
                int dollar = owner.indexOf('$');
                if (dollar > 0) {
                    owner = owner.substring(0, dollar);
                }
                if (deps.contains(owner)) {
                    counts.merge(owner, call.getValue(), Integer::sum);
                }
            }
        }
        return counts;
    }

    /**
     * Deterministic rank cut. Mutates {@code graph} until it is acyclic and adds
     * every removed edge to {@code cut}.
     */
    static void breakCycles(Map<String, Set<String>> graph,
                            Map<String, Map<String, Integer>> weights,
                            Set<String> cut) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StronglyConnectedComponent scc : new TarjanSCCFinder().findSCCs(graph)) {
                if (scc.getSize() < 2) {
                    continue;
                }
                List<String> members = new ArrayList<>(new TreeSet<>(scc.getMembers()));

                // rank(P) = (out − in) / max(1, out + in) over the SCC-internal weights.
                // Positive: uses more than it is used → belongs up. Negative → foundation.
                Map<String, Double> rank = new LinkedHashMap<>();
                for (String m : members) {
                    int out = 0;
                    int in = 0;
                    for (String other : members) {
                        if (other.equals(m)) {
                            continue;
                        }
                        out += weights.getOrDefault(m, Map.of()).getOrDefault(other, 0);
                        in += weights.getOrDefault(other, Map.of()).getOrDefault(m, 0);
                    }
                    rank.put(m, (out - in) / (double) Math.max(1, out + in));
                }

                boolean anyCut = false;
                for (String from : members) {
                    for (String to : new TreeSet<>(graph.get(from))) {
                        if (scc.getMembers().contains(to) && rank.get(to) > rank.get(from)) {
                            graph.get(from).remove(to);
                            cut.add(from + "\0" + to);
                            anyCut = true;
                        }
                    }
                }
                if (!anyCut) {
                    // Symmetric tangle: no direction can be justified — dissolve it.
                    for (String from : members) {
                        for (String to : new TreeSet<>(graph.get(from))) {
                            if (scc.getMembers().contains(to)) {
                                graph.get(from).remove(to);
                                cut.add(from + "\0" + to);
                            }
                        }
                    }
                }
                changed = true;
                break; // SCCs changed — recompute
            }
        }
    }

    /**
     * Longest-path (ASAP) levels: a node without dependencies is level 0, every
     * other node sits one above its highest dependency. Residual SCCs (none are
     * expected after {@link #breakCycles}) are collapsed and share a level.
     */
    static Map<String, Integer> longestPathLevels(Map<String, Set<String>> graph) {
        List<StronglyConnectedComponent> sccs = new TarjanSCCFinder().findSCCs(graph);
        Map<String, Integer> nodeToScc = new LinkedHashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String m : scc.getMembers()) {
                nodeToScc.put(m, scc.getId());
            }
        }
        Map<Integer, Set<Integer>> sccDeps = new TreeMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            Set<Integer> deps = new TreeSet<>();
            for (String m : scc.getMembers()) {
                for (String to : graph.getOrDefault(m, Set.of())) {
                    Integer toScc = nodeToScc.get(to);
                    if (toScc != null && toScc != scc.getId()) {
                        deps.add(toScc);
                    }
                }
            }
            sccDeps.put(scc.getId(), deps);
        }
        Map<Integer, Integer> sccLevel = new TreeMap<>();
        for (Integer id : sccDeps.keySet()) {
            sccLevel.put(id, 0);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Integer, Set<Integer>> e : sccDeps.entrySet()) {
                int level = 0;
                for (Integer dep : e.getValue()) {
                    level = Math.max(level, sccLevel.get(dep) + 1);
                }
                if (sccLevel.get(e.getKey()) != level) {
                    sccLevel.put(e.getKey(), level);
                    changed = true;
                }
            }
        }
        Map<String, Integer> levels = new TreeMap<>();
        for (Map.Entry<String, Integer> e : nodeToScc.entrySet()) {
            levels.put(e.getKey(), sccLevel.get(e.getValue()));
        }
        return levels;
    }

    static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }
}
