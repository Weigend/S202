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
package de.weigend.s202.analysis.quality;

import de.weigend.s202.domain.ContainerProjection;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.Placement;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.StronglyConnectedComponent;
import io.softwareecg.wfx.lookup.api.Lookup;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Measures the whole of a model and any part of it, all with one ruler: what a
 * class depends on, and whether a dependency lies on a class cycle, is a
 * property of the class in the whole model. A part is the average over its
 * classes, so the whole is the average over its parts - a dependency that
 * leaves the part counts, and so does a cycle that does.
 */
public final class QualityCalculator {

    private final Placement placement;
    private final String topLevel;
    /** Class → the other classes of the model it depends on. */
    private final Map<String, Set<String>> dependencies = new HashMap<>();
    /** Class → id of the class cycle it lies on; absent without one. */
    private final Map<String, Integer> cycleOf = new HashMap<>();

    /** The whole of {@code model}. */
    public static QualityMetrics measure(DomainModel model) {
        return new QualityCalculator(model, Lookup.lookup(SCCFinder.class)).ofAll();
    }

    public QualityCalculator(DomainModel model, SCCFinder finder) {
        this(model, finder, Placement.OF_THE_CODE);
    }

    /** @param placement where the elements sit; decides about {@link QualityMetrics#getBreakout()} */
    public QualityCalculator(DomainModel model, SCCFinder finder, Placement placement) {
        this.placement = placement;
        this.topLevel = Placement.topLevel(model.getAllPackages().keySet());
        Map<String, CalculatedElementInfo> classes = model.getAllClasses();
        for (CalculatedElementInfo cls : classes.values()) {
            Set<String> targets = new HashSet<>();
            for (String target : cls.dependencies) {
                if (classes.containsKey(target) && !target.equals(cls.fullName)) {
                    targets.add(target);
                }
            }
            dependencies.put(cls.fullName, targets);
        }
        for (StronglyConnectedComponent scc : finder.findSCCs(dependencies)) {
            if (scc.isTangle()) {
                scc.getMembers().forEach(member -> cycleOf.put(member, scc.getId()));
            }
        }
    }

    public QualityMetrics ofAll() {
        return measure(dependencies.keySet(), breakout(topLevel));
    }

    /** @param packageName {@code null} or "" for everything */
    public QualityMetrics ofPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return ofAll();
        }
        List<String> classes = dependencies.keySet().stream()
                .filter(name -> placement.isIn(name, packageName))
                .toList();
        // The packages above the top level have one child each; there is nothing to break out.
        boolean above = topLevel.startsWith(packageName + ".");
        return measure(classes, breakout(above ? topLevel : packageName));
    }

    /**
     * Every package at once, each as {@link #ofPackage}: the packages the
     * classes sit in and those around them, planned ones included.
     */
    public Map<String, QualityMetrics> ofEveryPackage() {
        // classes, dependencies, dependencies on a cycle
        Map<String, int[]> counts = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            Integer cycle = cycleOf.get(entry.getKey());
            int cyclic = 0;
            for (String to : entry.getValue()) {
                if (cycle != null && cycle.equals(cycleOf.get(to))) {
                    cyclic++;
                }
            }
            for (String container : placement.containersOf(entry.getKey())) {
                int[] count = counts.computeIfAbsent(container, k -> new int[3]);
                count[0]++;
                count[1] += entry.getValue().size();
                count[2] += cyclic;
            }
        }
        Map<String, Set<String>> edges = new HashMap<>();
        dependencies.forEach((from, targets) -> {
            for (String to : targets) {
                ContainerProjection.Projection p = placement.project(from, to);
                if (p != null) {
                    edges.computeIfAbsent(p.container(), k -> new HashSet<>()).add(p.key());
                }
            }
        });
        int ofTopLevel = edges.getOrDefault(topLevel, Set.of()).size();
        Map<String, QualityMetrics> result = new TreeMap<>();
        counts.forEach((container, count) -> {
            boolean above = topLevel.startsWith(container + ".");
            int breakout = above ? ofTopLevel : edges.getOrDefault(container, Set.of()).size();
            result.put(container, new QualityMetrics(count[0], count[1], count[2], breakout));
        });
        return result;
    }

    /** Any set of classes; they have no common container and so no breakout. */
    public QualityMetrics ofClasses(Collection<String> classNames) {
        return measure(classNames.stream().filter(dependencies::containsKey).toList(), QualityMetrics.NO_BREAKOUT);
    }

    private QualityMetrics measure(Collection<String> classes, int breakout) {
        int total = 0;
        int cyclic = 0;
        for (String from : classes) {
            Integer cycle = cycleOf.get(from);
            for (String to : dependencies.get(from)) {
                total++;
                if (cycle != null && cycle.equals(cycleOf.get(to))) {
                    cyclic++;
                }
            }
        }
        return new QualityMetrics(classes.size(), total, cyclic, breakout);
    }

    /** The dependencies between the direct members of {@code container}, each pair and direction once. */
    private int breakout(String container) {
        Set<String> edges = new HashSet<>();
        dependencies.forEach((from, targets) -> {
            for (String to : targets) {
                ContainerProjection.Projection p = placement.project(from, to);
                if (p != null && p.container().equals(container)) {
                    edges.add(p.key());
                }
            }
        });
        return edges.size();
    }
}
