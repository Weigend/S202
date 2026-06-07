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
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureInsightsProvider;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.reader.DependencyModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Default architecture-analysis facade. This is the single boundary where
 * report and UI consumers cross into concrete architecture builders.
 */
@Singleton
public final class DefaultArchitectureInsightsProvider implements ArchitectureInsightsProvider {

    private final HierarchicalLayeredArchitectureBuilder layeredBuilder;
    private final ComponentArchitectureBuilder componentBuilder;
    private final SCCFinder sccFinder;

    @Inject
    public DefaultArchitectureInsightsProvider(HierarchicalLayeredArchitectureBuilder layeredBuilder,
                                               ComponentArchitectureBuilder componentBuilder,
                                               SCCFinder sccFinder) {
        this.layeredBuilder = Objects.requireNonNull(layeredBuilder, "layeredBuilder");
        this.componentBuilder = Objects.requireNonNull(componentBuilder, "componentBuilder");
        this.sccFinder = Objects.requireNonNull(sccFinder, "sccFinder");
    }

    @Override
    public Architecture layered(DomainModel domain) {
        return layeredBuilder.build(domain);
    }

    @Override
    public ComponentArchitecture component(DomainModel domain,
                                           ArchitectureAnnotations annotations,
                                           DependencyModel rawModel) {
        return componentBuilder.build(domain, annotations, rawModel);
    }

    @Override
    public List<StronglyConnectedComponent> classSccs(DomainModel domain) {
        return sccFinder.findSCCs(classGraph(domain));
    }

    private static Map<String, Set<String>> classGraph(DomainModel domain) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (DomainModel.CalculatedElementInfo cls : domain.getAllClasses().values()) {
            graph.put(cls.fullName, new TreeSet<>(cls.dependencies));
        }
        return graph;
    }
}
