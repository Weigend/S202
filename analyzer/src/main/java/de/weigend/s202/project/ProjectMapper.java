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

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;

import java.util.List;
import java.util.Set;

/**
 * Converts between the runtime models and the serialisable
 * {@link S202Project} snapshot.
 */
public interface ProjectMapper {

    /**
     * @param previewCuts the edges cut in the refactoring preview
     * @param views       the tabs of the analysis, already in their
     *                    serialisable form — what a tab is, is the business
     *                    of the UI, not of this mapper
     */
    default S202Project toProject(String appVersion,
                                  S202Project.Source source,
                                  DependencyModel rawModel,
                                  ArchitectureAnnotations annotations,
                                  Set<DependencyEdge> previewCuts,
                                  List<S202Project.ViewDto> views) {
        return toProject(appVersion, source, rawModel, annotations, previewCuts, views, null);
    }

    /** @param metricMapping how the 3D view shows metrics; {@code null} for the default */
    S202Project toProject(String appVersion,
                          S202Project.Source source,
                          DependencyModel rawModel,
                          ArchitectureAnnotations annotations,
                          Set<DependencyEdge> previewCuts,
                          List<S202Project.ViewDto> views,
                          S202Project.MetricMappingDto metricMapping);

    DependencyModel toDependencyModel(S202Project.DependencyModelDto dto);

    ArchitectureAnnotations toArchitectureAnnotations(S202Project.ArchitectureAnnotationsDto dto);

    Set<DependencyEdge> toPreviewCuts(List<S202Project.CutDto> dtos);
}
