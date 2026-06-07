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

import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.reader.EdgeKind;

import java.util.List;
import java.util.Map;

/**
 * UI-independent report snapshot. HTML and asset writers consume only this
 * model, so report content can be tested without JavaFX scene state.
 */
public record QualityReportModel(
        ReportMetadata metadata,
        OverallAssessment overallAssessment,
        CodebaseProfile codebase,
        SizeDistribution sizeDistribution,
        QualitySummary quality,
        List<ViolationFinding> layeredViolations,
        List<CycleFinding> packageCycles,
        List<CycleFinding> classCycles,
        ComponentFindings componentFindings,
        List<AppendixTable> appendix) {

    public QualityReportModel {
        layeredViolations = layeredViolations == null ? List.of() : List.copyOf(layeredViolations);
        packageCycles = packageCycles == null ? List.of() : List.copyOf(packageCycles);
        classCycles = classCycles == null ? List.of() : List.copyOf(classCycles);
        appendix = appendix == null ? List.of() : List.copyOf(appendix);
    }

    public record ReportMetadata(
            String title,
            String analyzedAt,
            String sourceKind,
            List<String> sourcePaths,
            String projectRoot,
            String s202Version) {

        public ReportMetadata {
            sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        }
    }

    public record CodebaseProfile(
            int totalClasses,
            int totalPackages,
            int totalModules,
            int totalDependencies,
            int interfaceCount,
            int exportedPackageCount,
            int componentAnnotatedPackageCount,
            int apiAnnotatedPackageCount,
            int plainPackageCount,
            List<String> languages,
            List<String> buildSystems,
            Map<EdgeKind, Integer> dependencyKindCounts) {

        public CodebaseProfile {
            languages = languages == null ? List.of() : List.copyOf(languages);
            buildSystems = buildSystems == null ? List.of() : List.copyOf(buildSystems);
            dependencyKindCounts = dependencyKindCounts == null ? Map.of() : Map.copyOf(dependencyKindCounts);
        }
    }

    public record OverallAssessment(
            int score,
            String label,
            String summary,
            List<String> keyDrivers,
            List<String> followUpActions) {

        public OverallAssessment {
            score = Math.max(0, Math.min(100, score));
            keyDrivers = keyDrivers == null ? List.of() : List.copyOf(keyDrivers);
            followUpActions = followUpActions == null ? List.of() : List.copyOf(followUpActions);
        }
    }

    public record SizeDistribution(
            List<PackageStat> topPackagesByClasses,
            List<PackageStat> topPackagesByDependencies,
            List<LevelStat> levels,
            List<HistogramBucket> classCountHistogram,
            int nonEmptyPackageCount,
            int maxPackageClassCount,
            double topPackageClassShare,
            double packageClassImbalance,
            int oversizedPackageCount,
            int maxArchitectureLevel) {

        public SizeDistribution {
            topPackagesByClasses = topPackagesByClasses == null ? List.of() : List.copyOf(topPackagesByClasses);
            topPackagesByDependencies = topPackagesByDependencies == null ? List.of() : List.copyOf(topPackagesByDependencies);
            levels = levels == null ? List.of() : List.copyOf(levels);
            classCountHistogram = classCountHistogram == null ? List.of() : List.copyOf(classCountHistogram);
        }
    }

    public record QualitySummary(
            double fat,
            double tangled,
            QualityBand band,
            int classCount,
            int dependencyCount,
            int intraSccDependencyCount,
            int layeredViolationCount,
            int packageCycleCount,
            int classCycleCount,
            int componentViolationCount) {

        public QualitySummary {
            band = band == null ? QualityBand.HEALTHY : band;
        }
    }

    public enum QualityBand {
        HEALTHY("Healthy", "healthy"),
        WATCH("Watch", "watch"),
        RISK("Risk", "risk"),
        CRITICAL("Critical", "critical");

        private final String label;
        private final String cssClass;

        QualityBand(String label, String cssClass) {
            this.label = label;
            this.cssClass = cssClass;
        }

        public String label() {
            return label;
        }

        public String cssClass() {
            return cssClass;
        }
    }

    public record PackageStat(
            String name,
            int classCount,
            int dependencyCount,
            int incomingDependencyCount,
            int outgoingDependencyCount) {}

    public record LevelStat(int level, int classCount, int packageCount) {}

    public record HistogramBucket(String label, int count) {}

    public record ViolationFinding(
            String id,
            ViolationKind kind,
            String sourceScope,
            String targetScope,
            int violationCount,
            int backEdgeCount,
            int score,
            String imagePath,
            List<EdgeSample> samples) {

        public ViolationFinding {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }

    public record CycleFinding(
            String id,
            String title,
            String memberType,
            List<String> members,
            int internalEdgeCount,
            int methodCallWeight,
            int score,
            String imagePath,
            List<EdgeSample> samples) {

        public CycleFinding {
            members = members == null ? List.of() : List.copyOf(members);
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }

    public record ComponentFindings(
            boolean reportable,
            double confidenceScore,
            List<String> evidence,
            List<String> warnings,
            int componentCount,
            int apiClassCount,
            int coveredClassCount,
            int totalClassCount,
            int apiBypassCount,
            int apiLeakCount,
            String overviewImagePath,
            List<ComponentStat> components,
            List<ViolationFinding> violations) {

        public ComponentFindings {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            components = components == null ? List.of() : List.copyOf(components);
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }

    public record ComponentStat(
            String id,
            String displayName,
            String rootPackageFqn,
            int apiClassCount,
            int implementationElementCount,
            int coveredClassCount) {}

    public record EdgeSample(String source, String target, String label) {}

    public record AppendixTable(String title, List<String> headers, List<List<String>> rows) {

        public AppendixTable {
            headers = headers == null ? List.of() : List.copyOf(headers);
            if (rows == null) {
                rows = List.of();
            } else {
                rows = rows.stream().map(List::copyOf).toList();
            }
        }
    }
}
