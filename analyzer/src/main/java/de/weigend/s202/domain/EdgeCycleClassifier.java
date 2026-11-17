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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Map<String, Set<String>> graph = new HashMap<>();
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
}
