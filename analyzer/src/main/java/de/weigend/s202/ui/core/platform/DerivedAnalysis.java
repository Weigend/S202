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
package de.weigend.s202.ui.core.platform;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.PhysicalHierarchy;
import de.weigend.s202.domain.SliceHierarchy;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import io.softwareecg.wfx.lookup.api.Lookup;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The analysis of a document once more, with the classes ordered otherwise:
 * by the files they were read from (Physical View) or as a horizontal slice.
 * It is computed the way the analysis itself is, from a model in which the
 * classes carry other names; see {@link PhysicalHierarchy} and
 * {@link SliceHierarchy}.
 *
 * @param ownName         the name of a class of the analysis in this model
 * @param rawModel        the classes under their names here
 * @param domainModel     levels, tangles and back-edges of that model
 * @param rootNode        the tree the tab shows
 * @param cycleBreakEdges the class back-edges, under the names of this model
 */
public record DerivedAnalysis(UnaryOperator<String> ownName,
                              DependencyModel rawModel,
                              DomainModel domainModel,
                              ArchitectureNode rootNode,
                              Set<DependencyEdge> cycleBreakEdges) {

    /** The classes by their files. Slow like the analysis itself; not for the JavaFX thread. */
    static DerivedAnalysis physical(DependencyModel logical) {
        PhysicalHierarchy hierarchy = PhysicalHierarchy.of(logical);
        return of(hierarchy::toPhysical, hierarchy.model());
    }

    /** The packages of {@code depth} side by side. Slow like the analysis itself; not for the JavaFX thread. */
    static DerivedAnalysis slice(DependencyModel logical, int depth) {
        SliceHierarchy hierarchy = SliceHierarchy.of(logical, depth);
        return of(hierarchy::toSlice, hierarchy.model());
    }

    private static DerivedAnalysis of(UnaryOperator<String> ownName, DependencyModel model) {
        DomainModel domain = Lookup.require(DomainComputer.class).compute(model);
        ArchitectureNode root = new ArchitectureNodeBuilder().build(domain);
        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
        return new DerivedAnalysis(ownName, model, domain, root, domain.getClassBackEdges());
    }

    /** Dependencies of the analysis under the names of this model. */
    public Set<DependencyEdge> toOwn(Set<DependencyEdge> edges) {
        Set<DependencyEdge> result = new LinkedHashSet<>();
        edges.forEach(edge -> result.add(new DependencyEdge(ownName.apply(edge.from()), ownName.apply(edge.to()))));
        return result;
    }
}
