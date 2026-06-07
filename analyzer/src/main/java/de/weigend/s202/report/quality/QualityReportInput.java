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
package de.weigend.s202.report.quality;

import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;

/**
 * All analyzed state needed to build a static quality report. The report layer
 * consumes these models but does not mutate or recalculate them.
 */
public record QualityReportInput(
        String title,
        String s202Version,
        S202Project.Source source,
        DependencyModel rawModel,
        DomainModel domainModel,
        ArchitectureAnnotations annotations,
        QualityMetrics qualityMetrics,
        LayoutInvariantReport invariantReport) {

    public QualityReportInput {
        if (rawModel == null) {
            throw new IllegalArgumentException("rawModel must not be null");
        }
        if (domainModel == null) {
            throw new IllegalArgumentException("domainModel must not be null");
        }
        title = title == null || title.isBlank() ? "S202 Quality Report" : title;
        s202Version = s202Version == null || s202Version.isBlank() ? "dev" : s202Version;
        annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        qualityMetrics = qualityMetrics == null ? QualityMetrics.compute(domainModel) : qualityMetrics;
    }
}
