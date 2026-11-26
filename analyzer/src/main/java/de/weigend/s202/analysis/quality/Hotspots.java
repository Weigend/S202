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
import de.weigend.s202.domain.Externals;
import de.weigend.s202.domain.Placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The packages side by side, measured as the Quality panel measures one: where
 * to look first in a review. Sorted by breakout, the largest first.
 */
public final class Hotspots {

    /**
     * One package.
     *
     * @param pkg         the package, or the id of a planned one
     * @param classes     the classes in it, at any depth
     * @param breakout    the dependencies between its direct members, see {@link QualityMetrics#getBreakout()}
     * @param average     the average class dependencies of its classes, the number behind fat
     * @param tangled     the share of those that lie on a class cycle, 0 to 1
     * @param against     the class dependencies inside it that run against the layers
     * @param externals   the classes outside the analysis its classes use
     */
    public record Hotspot(String pkg, int classes, int breakout, double average, double tangled, int against,
                          int externals) {
    }

    private Hotspots() {
    }

    /**
     * @param model      the model as the tab shows it, with the plan or without
     * @param calculator measures {@code model} with {@code placement}
     * @param shown      the classes of the tab; a package without one of them is left out
     */
    public static List<Hotspot> of(DomainModel model, QualityCalculator calculator, Placement placement,
                                   Externals externals, Collection<String> shown) {
        Set<String> wanted = new HashSet<>();
        Map<String, Set<String>> externalsOf = new HashMap<>();
        for (String cls : shown) {
            List<String> containers = placement.containersOf(cls);
            wanted.addAll(containers);
            Set<String> used = externals.usedBy(cls).keySet();
            if (!used.isEmpty()) {
                containers.forEach(container ->
                        externalsOf.computeIfAbsent(container, k -> new HashSet<>()).addAll(used));
            }
        }
        Map<String, Integer> against = new HashMap<>();
        for (DomainModel.CalculatedElementInfo source : model.getAllClasses().values()) {
            for (String target : source.dependencies) {
                if (model.getClass(target) == null || target.equals(source.fullName)) {
                    continue;
                }
                ContainerProjection.Projection p = placement.project(source.fullName, target);
                if (p != null && (model.isContainerBackEdge(p.fromSibling(), p.toSibling())
                        || model.isClassBackEdge(source.fullName, target))) {
                    if (!p.container().isEmpty()) {
                        against.merge(p.container(), 1, Integer::sum);
                        placement.containersOf(p.container()).forEach(c -> against.merge(c, 1, Integer::sum));
                    }
                }
            }
        }
        // The packages above the top level have one child each and nothing of their own.
        String topLevel = Placement.topLevel(model.getAllPackages().keySet());
        List<Hotspot> result = new ArrayList<>();
        calculator.ofEveryPackage().forEach((pkg, metrics) -> {
            if (wanted.contains(pkg) && !topLevel.startsWith(pkg + ".")) {
                result.add(new Hotspot(pkg, metrics.getClassCount(), metrics.getBreakout(),
                        metrics.getAverageDependencies(), metrics.getTangled(), against.getOrDefault(pkg, 0),
                        externalsOf.getOrDefault(pkg, Set.of()).size()));
            }
        });
        result.sort(Comparator.comparingInt(Hotspot::breakout).reversed().thenComparing(Hotspot::pkg));
        return result;
    }
}
