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
package de.weigend.s202.project;

import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;

import java.util.List;
import java.util.Set;

/**
 * Converts between the runtime domain model and the serialisable
 * {@link S202Project} snapshot.
 */
public interface ProjectMapper {

    S202Project toProject(String appVersion,
                          S202Project.Source source,
                          DependencyModel rawModel,
                          DomainModel domainModel,
                          ArchitectureAnnotations annotations,
                          LayoutInvariantReport invariantReport,
                          Set<DependencyEdge> cycleBreakEdges);

    DependencyModel toDependencyModel(S202Project.DependencyModelDto dto);

    DomainModel toDomainModel(S202Project.DomainModelDto dto);

    ArchitectureAnnotations toArchitectureAnnotations(S202Project.ArchitectureAnnotationsDto dto);

    LayoutInvariantReport toLayoutInvariantReport(S202Project.LayoutInvariantReportDto dto);

    Set<DependencyEdge> toCycleBreakEdges(List<S202Project.CycleBreakEdgeDto> dtos);
}
