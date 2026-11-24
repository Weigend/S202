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
package de.weigend.s202.report.text;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @param analysisName display name of the analysis
 * @param sourcePaths  what was analyzed
 * @param annotations  the component mapping the violations refer to
 * @param plannedCuts  dependencies the user already plans to remove; they are reported and marked
 */
public record ViolationReportInput(
        String analysisName,
        List<String> sourcePaths,
        DependencyModel rawModel,
        DomainModel domainModel,
        ArchitectureAnnotations annotations,
        Set<DependencyEdge> plannedCuts) {

    public ViolationReportInput {
        Objects.requireNonNull(rawModel, "rawModel cannot be null");
        Objects.requireNonNull(domainModel, "domainModel cannot be null");
        sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        plannedCuts = plannedCuts == null ? Set.of() : Set.copyOf(plannedCuts);
    }
}
