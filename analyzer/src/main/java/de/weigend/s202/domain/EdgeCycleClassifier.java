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
package de.weigend.s202.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Answers, for one class dependency, the questions every cycle view asks:
 * <ul>
 *   <li><b>Is it a back-edge?</b> — one of the dependencies the levelization
 *       had to cut, so it runs against the layout.</li>
 *   <li><b>Which cycle does it lie on?</b> — a class cycle (only a code change
 *       resolves it), a package cycle only (moving a class is enough), or
 *       none.</li>
 *   <li><b>How heavy is it?</b> — the number of method calls that carry it:
 *       what it costs to cut.</li>
 * </ul>
 * The answers come from the whole model, never from what a view happens to
 * display, so every view gives the same answer for the same dependency.
 *
 * <p>Refactoring-preview cuts are treated as removed: a cycle that the cuts
 * break is no longer reported. Back-edges stay as the levelization found them,
 * because a preview does not re-run the layout.
 */
public final class EdgeCycleClassifier {

    /** The strongest cycle a class dependency lies on. */
    public enum CycleKind {
        /** On no cycle. */
        NONE,
        /** Between two members of a container tangle, but on no class cycle. */
        PACKAGE_CYCLE,
        /** Both classes belong to the same class cycle. */
        CLASS_CYCLE
    }

    private final DomainModel model;
    /** Class fqn → dependencies within the model, without the preview cuts. */
    private final Map<String, Set<String>> graph = new HashMap<>();
    /** Class fqn → id of its class cycle; classes outside any cycle are absent. */
    private final Map<String, Integer> classCycleOf = new HashMap<>();
    /** Sibling fqn (class or package) → index of its container tangle. */
    private final Map<String, Integer> tangleOf = new HashMap<>();

    private EdgeCycleClassifier(DomainModel model) {
        this.model = model;
    }

    /**
     * @param cutEdges refactoring-preview cuts, treated as removed; may be {@code null}
     */
    public static EdgeCycleClassifier of(DomainModel model, Set<DependencyEdge> cutEdges, SCCFinder finder) {
        EdgeCycleClassifier result = new EdgeCycleClassifier(model);
        Map<String, DomainModel.CalculatedElementInfo> classes = model.getAllClasses();
        Map<String, Set<String>> graph = result.graph;
        for (DomainModel.CalculatedElementInfo cls : classes.values()) {
            Set<String> targets = new HashSet<>();
            for (String dep : cls.dependencies) {
                if (classes.containsKey(dep)
                        && (cutEdges == null || !cutEdges.contains(new DependencyEdge(cls.fullName, dep)))) {
                    targets.add(dep);
                }
            }
            graph.put(cls.fullName, targets);
        }
        for (StronglyConnectedComponent scc : finder.findSCCs(graph)) {
            if (scc.getSize() >= 2) {
                for (String member : scc.getMembers()) {
                    result.classCycleOf.put(member, scc.getId());
                }
            }
        }
        List<Set<String>> tangles = ContainerProjection.tangles(graph, finder);
        for (int i = 0; i < tangles.size(); i++) {
            for (String member : tangles.get(i)) {
                result.tangleOf.put(member, i);
            }
        }
        return result;
    }

    /** True when the levelization cut {@code from → to}: it runs against the layout. */
    public boolean isBackEdge(String from, String to) {
        return model.isClassBackEdge(from, to);
    }

    /**
     * The weight the levelization gave {@code from → to}: its method-call
     * count. A dependency without calls — inheritance, a field or parameter
     * type — weighs 1, as does one the model has no weight for.
     */
    public int weightOf(String from, String to) {
        return Math.max(1, model.getClassEdgeWeight(from, to));
    }

    public CycleKind cycleOf(String from, String to) {
        Integer cycle = classCycleOf.get(from);
        if (cycle != null && cycle.equals(classCycleOf.get(to))) {
            return CycleKind.CLASS_CYCLE;
        }
        return inTangle(from, to) ? CycleKind.PACKAGE_CYCLE : CycleKind.NONE;
    }

    /**
     * True when the two siblings that {@code from → to} connects — the
     * children of the deepest container holding both classes — belong to one
     * tangle.
     *
     * <p>This is not implied by a class cycle: the way back from {@code to}
     * to {@code from} may lead through classes outside that container. The
     * dependency is then on a class cycle although its two siblings are not
     * tangled with each other.
     */
    public boolean inTangle(String from, String to) {
        ContainerProjection.Projection p = ContainerProjection.project(from, to);
        if (p == null) {
            return false;
        }
        Integer tangle = tangleOf.get(p.fromSibling());
        return tangle != null && tangle.equals(tangleOf.get(p.toSibling()));
    }

    /** Id of the class cycle {@code classFqn} belongs to, or {@code null} when it is on none. */
    public Integer classCycleId(String classFqn) {
        return classCycleOf.get(classFqn);
    }

    /**
     * Why {@code from → to} lies on a cycle: the shortest way back from
     * {@code to} to {@code from} — through classes for a class cycle, through
     * the siblings of the container for a package cycle.
     */
    public CycleReturnPath returnPath(String from, String to) {
        Integer cycle = classCycleOf.get(from);
        if (cycle == null || !cycle.equals(classCycleOf.get(to))) {
            return packageReturnPath(from, to);
        }
        // The whole way back stays inside the class cycle.
        Map<String, Map<String, List<DependencyEdge>>> steps = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            if (!cycle.equals(classCycleOf.get(entry.getKey()))) {
                continue;
            }
            for (String target : entry.getValue()) {
                if (cycle.equals(classCycleOf.get(target))) {
                    addStep(steps, entry.getKey(), target, new DependencyEdge(entry.getKey(), target));
                }
            }
        }
        return new CycleReturnPath(false, shortestPath(steps, to, from));
    }

    /**
     * The way back between the two siblings that {@code from → to} connects;
     * {@link CycleReturnPath#NONE} when they are not tangled.
     */
    public CycleReturnPath packageReturnPath(String from, String to) {
        ContainerProjection.Projection p = ContainerProjection.project(from, to);
        if (p == null || !inTangle(from, to)) {
            return CycleReturnPath.NONE;
        }
        Integer tangle = tangleOf.get(p.fromSibling());
        Map<String, Map<String, List<DependencyEdge>>> steps = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            for (String target : entry.getValue()) {
                ContainerProjection.Projection step = ContainerProjection.project(entry.getKey(), target);
                if (step != null && tangle.equals(tangleOf.get(step.fromSibling()))
                        && tangle.equals(tangleOf.get(step.toSibling()))) {
                    addStep(steps, step.fromSibling(), step.toSibling(), new DependencyEdge(entry.getKey(), target));
                }
            }
        }
        return new CycleReturnPath(true, shortestPath(steps, p.toSibling(), p.fromSibling()));
    }

    private static void addStep(Map<String, Map<String, List<DependencyEdge>>> steps,
                                String from, String to, DependencyEdge edge) {
        steps.computeIfAbsent(from, k -> new TreeMap<>()).computeIfAbsent(to, k -> new ArrayList<>()).add(edge);
    }

    /** Breadth-first; neighbours in name order, so the answer is the same every time. */
    private static List<CycleReturnPath.Step> shortestPath(Map<String, Map<String, List<DependencyEdge>>> steps,
                                                           String start, String end) {
        Map<String, String> cameFrom = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>(List.of(start));
        cameFrom.put(start, start);
        while (!queue.isEmpty() && !cameFrom.containsKey(end)) {
            String node = queue.remove();
            for (String next : steps.getOrDefault(node, Map.of()).keySet()) {
                if (cameFrom.putIfAbsent(next, node) == null) {
                    queue.add(next);
                }
            }
        }
        if (!cameFrom.containsKey(end)) {
            return List.of();
        }
        LinkedList<CycleReturnPath.Step> path = new LinkedList<>();
        for (String node = end; !node.equals(start); node = cameFrom.get(node)) {
            String previous = cameFrom.get(node);
            List<DependencyEdge> edges = steps.get(previous).get(node).stream()
                    .sorted(Comparator.comparing(DependencyEdge::from).thenComparing(DependencyEdge::to))
                    .toList();
            path.addFirst(new CycleReturnPath.Step(previous, node, edges));
        }
        return path;
    }
}
