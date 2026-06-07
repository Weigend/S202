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
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.domain.impl.ComponentArchitectureBuilder;
import de.weigend.s202.domain.impl.HierarchicalLayeredArchitectureBuilder;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import io.softwareecg.wfx.lookup.api.Lookup;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds the report snapshot from existing S202 analysis models.
 */
public final class QualityReportModelBuilder {

    private static final int TOP_LIMIT = 10;
    private static final int DEFAULT_SCOPE_IMAGE_LIMIT = 5;
    private final String scopeImageExtension;
    private final int scopeImageLimit;

    public QualityReportModelBuilder() {
        this("svg");
    }

    public QualityReportModelBuilder(String scopeImageExtension) {
        this(scopeImageExtension, DEFAULT_SCOPE_IMAGE_LIMIT);
    }

    public QualityReportModelBuilder(String scopeImageExtension, int scopeImageLimit) {
        this.scopeImageExtension = normalizeExtension(scopeImageExtension);
        this.scopeImageLimit = Math.max(0, scopeImageLimit);
    }

    public QualityReportModel build(QualityReportInput input) {
        Objects.requireNonNull(input, "input");
        DependencyModel rawModel = input.rawModel();
        DomainModel domain = input.domainModel();
        ArchitectureAnnotations annotations = input.annotations();

        Architecture layered = new HierarchicalLayeredArchitectureBuilder().build(domain);
        List<Violation> upwardViolations = layered.violations().stream()
                .filter(v -> v.kind() == ViolationKind.UPWARD)
                .toList();

        QualityReportModel.CodebaseProfile codebase = codebaseProfile(input);
        QualityReportModel.SizeDistribution sizeDistribution = sizeDistribution(domain);
        List<QualityReportModel.ViolationFinding> layeredFindings =
                violationFindings(upwardViolations, "package-violation",
                        "assets/scopes/package-violation-%03d." + scopeImageExtension);
        List<QualityReportModel.CycleFinding> packageCycles = packageCycles(domain);
        List<QualityReportModel.CycleFinding> classCycles = classCycles(rawModel, domain);
        QualityReportModel.ComponentFindings componentFindings =
                componentFindings(rawModel, domain, annotations);
        QualityReportModel.QualitySummary quality = qualitySummary(input.qualityMetrics(), upwardViolations.size(),
                packageCycles.size(), classCycles.size(), componentFindings);

        return new QualityReportModel(
                metadata(input),
                overallAssessment(codebase, sizeDistribution, quality),
                codebase,
                sizeDistribution,
                quality,
                layeredFindings,
                packageCycles,
                classCycles,
                componentFindings,
                appendix(input, upwardViolations));
    }

    private QualityReportModel.ReportMetadata metadata(QualityReportInput input) {
        S202Project.Source source = input.source();
        List<String> paths = source == null ? sourcePaths(input.invariantReport()) : nullToList(source.paths());
        return new QualityReportModel.ReportMetadata(
                input.title(),
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                source == null || source.kind() == null || source.kind().isBlank() ? "UNKNOWN" : source.kind(),
                paths,
                source == null ? null : source.projectRoot(),
                input.s202Version());
    }

    private QualityReportModel.CodebaseProfile codebaseProfile(QualityReportInput input) {
        DependencyModel rawModel = input.rawModel();
        DomainModel domain = input.domainModel();
        Map<EdgeKind, Integer> kindCounts = dependencyKindCounts(rawModel);
        int dependencyCount = domain.getAllClasses().values().stream()
                .mapToInt(cls -> internalDependencies(cls, domain).size())
                .sum();
        int interfaceCount = (int) domain.getAllClasses().values().stream()
                .filter(cls -> cls.interfaceType)
                .count();
        return new QualityReportModel.CodebaseProfile(
                domain.getAllClasses().size(),
                domain.getAllPackages().size(),
                rawModel.getModuleCount(),
                dependencyCount,
                interfaceCount,
                rawModel.getExportedPackageNames().size(),
                rawModel.getComponentAnnotatedPackages().size(),
                rawModel.getApiAnnotatedPackages().size(),
                rawModel.getPlainPackages().size(),
                languages(input),
                buildSystems(input.source()),
                kindCounts);
    }

    private QualityReportModel.SizeDistribution sizeDistribution(DomainModel domain) {
        List<QualityReportModel.PackageStat> packageStats = packageStats(domain);
        List<QualityReportModel.LevelStat> levels = levelStats(domain);
        int totalClasses = domain.getAllClasses().size();
        int nonEmptyPackageCount = (int) packageStats.stream()
                .filter(stat -> stat.classCount() > 0)
                .count();
        int maxPackageClassCount = packageStats.stream()
                .mapToInt(QualityReportModel.PackageStat::classCount)
                .max()
                .orElse(0);
        double topPackageClassShare = totalClasses == 0 ? 0 : (double) maxPackageClassCount / totalClasses;
        double averageClassesPerNonEmptyPackage = nonEmptyPackageCount == 0
                ? 0
                : (double) totalClasses / nonEmptyPackageCount;
        double packageClassImbalance = averageClassesPerNonEmptyPackage == 0
                ? 0
                : maxPackageClassCount / averageClassesPerNonEmptyPackage;
        int oversizedPackageCount = (int) packageStats.stream()
                .filter(stat -> stat.classCount() >= 50)
                .count();
        int maxArchitectureLevel = levels.stream()
                .mapToInt(QualityReportModel.LevelStat::level)
                .max()
                .orElse(0);
        return new QualityReportModel.SizeDistribution(
                packageStats.stream()
                        .sorted(Comparator.comparingInt(QualityReportModel.PackageStat::classCount).reversed()
                                .thenComparing(QualityReportModel.PackageStat::name))
                        .limit(TOP_LIMIT)
                        .toList(),
                packageStats.stream()
                        .sorted(Comparator.comparingInt(QualityReportModel.PackageStat::dependencyCount).reversed()
                        .thenComparing(QualityReportModel.PackageStat::name))
                        .limit(TOP_LIMIT)
                        .toList(),
                levels,
                classCountHistogram(packageStats),
                nonEmptyPackageCount,
                maxPackageClassCount,
                topPackageClassShare,
                packageClassImbalance,
                oversizedPackageCount,
                maxArchitectureLevel);
    }

    private QualityReportModel.QualitySummary qualitySummary(QualityMetrics metrics,
                                                             int layeredViolationCount,
                                                             int packageCycleCount,
                                                             int classCycleCount,
                                                             QualityReportModel.ComponentFindings componentFindings) {
        int componentViolationCount = componentFindings == null || !componentFindings.reportable()
                ? 0
                : componentFindings.apiBypassCount() + componentFindings.apiLeakCount();
        return new QualityReportModel.QualitySummary(
                metrics.getFat(),
                metrics.getTangled(),
                qualityBand(metrics.getFat(), metrics.getTangled(), layeredViolationCount, classCycleCount),
                metrics.getClassCount(),
                metrics.getDependencyCount(),
                metrics.getIntraSccDependencyCount(),
                layeredViolationCount,
                packageCycleCount,
                classCycleCount,
                componentViolationCount);
    }

    private QualityReportModel.QualityBand qualityBand(double fat, double tangled,
                                                       int violationCount, int classCycleCount) {
        double severity = Math.max(fat, tangled);
        if (severity >= 0.75 || violationCount >= 100 || classCycleCount >= 25) {
            return QualityReportModel.QualityBand.CRITICAL;
        }
        if (severity >= 0.50 || violationCount >= 25 || classCycleCount >= 10) {
            return QualityReportModel.QualityBand.RISK;
        }
        if (severity >= 0.25 || violationCount > 0 || classCycleCount > 0) {
            return QualityReportModel.QualityBand.WATCH;
        }
        return QualityReportModel.QualityBand.HEALTHY;
    }

    private QualityReportModel.OverallAssessment overallAssessment(QualityReportModel.CodebaseProfile codebase,
                                                                   QualityReportModel.SizeDistribution distribution,
                                                                   QualityReportModel.QualitySummary quality) {
        double averageDependenciesPerClass = codebase.totalClasses() == 0
                ? 0
                : (double) codebase.totalDependencies() / codebase.totalClasses();
        int sizePenalty = sizePenalty(codebase.totalClasses(), averageDependenciesPerClass);
        int distributionPenalty = distributionPenalty(distribution);
        int anomalyPenalty = anomalyPenalty(codebase, quality);
        int score = Math.max(0, Math.min(100, 100 - sizePenalty - distributionPenalty - anomalyPenalty));

        List<String> drivers = new ArrayList<>();
        drivers.add(sizeDriver(codebase, averageDependenciesPerClass, sizePenalty));
        drivers.add(distributionDriver(distribution, distributionPenalty));
        drivers.add(anomalyDriver(quality, anomalyPenalty));
        if (quality.componentViolationCount() > 0) {
            drivers.add("A reportable component architecture was detected and shows "
                    + quality.componentViolationCount() + " API boundary violation(s).");
        } else {
            drivers.add("No component API violations contribute to this score.");
        }

        List<String> actions = followUpActions(codebase, distribution, quality);
        return new QualityReportModel.OverallAssessment(
                score,
                scoreLabel(score),
                scoreSummary(score),
                drivers,
                actions);
    }

    private int sizePenalty(int classCount, double averageDependenciesPerClass) {
        int penalty = 0;
        if (classCount >= 5_000) {
            penalty += 12;
        } else if (classCount >= 1_000) {
            penalty += 8;
        } else if (classCount >= 250) {
            penalty += 4;
        }
        if (averageDependenciesPerClass >= 12.0) {
            penalty += 8;
        } else if (averageDependenciesPerClass >= 8.0) {
            penalty += 4;
        }
        return penalty;
    }

    private int distributionPenalty(QualityReportModel.SizeDistribution distribution) {
        int penalty = 0;
        if (distribution.topPackageClassShare() >= 0.35) {
            penalty += 10;
        } else if (distribution.topPackageClassShare() >= 0.25) {
            penalty += 6;
        }
        if (distribution.maxPackageClassCount() >= 100) {
            penalty += 8;
        } else if (distribution.maxPackageClassCount() >= 50) {
            penalty += 5;
        } else if (distribution.maxPackageClassCount() >= 25) {
            penalty += 3;
        }
        if (distribution.packageClassImbalance() >= 8.0) {
            penalty += 6;
        } else if (distribution.packageClassImbalance() >= 4.0) {
            penalty += 3;
        }
        if (distribution.maxArchitectureLevel() >= 25) {
            penalty += 6;
        } else if (distribution.maxArchitectureLevel() >= 12) {
            penalty += 3;
        }
        return penalty;
    }

    private int anomalyPenalty(QualityReportModel.CodebaseProfile codebase,
                               QualityReportModel.QualitySummary quality) {
        int penalty = 0;
        penalty += (int) Math.round(clamp01(quality.fat()) * 10.0);
        penalty += (int) Math.round(clamp01(quality.tangled()) * 14.0);
        double violationRatio = codebase.totalClasses() == 0
                ? 0
                : (double) quality.layeredViolationCount() / codebase.totalClasses();
        if (quality.layeredViolationCount() >= 100 || violationRatio >= 0.20) {
            penalty += 15;
        } else if (quality.layeredViolationCount() >= 25 || violationRatio >= 0.08) {
            penalty += 10;
        } else if (quality.layeredViolationCount() > 0) {
            penalty += 5;
        }
        if (quality.packageCycleCount() >= 10) {
            penalty += 10;
        } else if (quality.packageCycleCount() > 0) {
            penalty += 4;
        }
        if (quality.classCycleCount() >= 25) {
            penalty += 16;
        } else if (quality.classCycleCount() >= 10) {
            penalty += 10;
        } else if (quality.classCycleCount() > 0) {
            penalty += 5;
        }
        double cyclicDependencyRatio = quality.dependencyCount() == 0
                ? 0
                : (double) quality.intraSccDependencyCount() / quality.dependencyCount();
        if (cyclicDependencyRatio >= 0.20) {
            penalty += 8;
        } else if (cyclicDependencyRatio >= 0.05) {
            penalty += 4;
        }
        if (quality.componentViolationCount() > 0) {
            penalty += Math.min(10, 3 + quality.componentViolationCount());
        }
        return penalty;
    }

    private String sizeDriver(QualityReportModel.CodebaseProfile codebase,
                              double averageDependenciesPerClass,
                              int penalty) {
        String sizeClass;
        if (codebase.totalClasses() >= 5_000) {
            sizeClass = "very large";
        } else if (codebase.totalClasses() >= 1_000) {
            sizeClass = "large";
        } else if (codebase.totalClasses() >= 250) {
            sizeClass = "medium-sized";
        } else {
            sizeClass = "small";
        }
        if (penalty == 0) {
            return "Project size is " + sizeClass + " and dependency density is within the expected range.";
        }
        return "Project size is " + sizeClass + " with an average dependency density of "
                + oneDecimal(averageDependenciesPerClass) + " internal dependency edge(s) per class.";
    }

    private String distributionDriver(QualityReportModel.SizeDistribution distribution,
                                      int penalty) {
        if (penalty == 0) {
            return "Package size distribution is balanced; no dominant package concentration was detected.";
        }
        return "Package distribution is concentrated: the largest package contains "
                + distribution.maxPackageClassCount() + " class(es), about "
                + Math.round(distribution.topPackageClassShare() * 100.0)
                + "% of the codebase.";
    }

    private String anomalyDriver(QualityReportModel.QualitySummary quality, int penalty) {
        if (penalty == 0) {
            return "No major structural anomaly signal was detected in layering, package cycles, or class cycles.";
        }
        return "Structural anomaly pressure is visible through " + quality.layeredViolationCount()
                + " layered violation(s), " + quality.packageCycleCount() + " package cycle(s), and "
                + quality.classCycleCount() + " class cycle(s).";
    }

    private List<String> followUpActions(QualityReportModel.CodebaseProfile codebase,
                                         QualityReportModel.SizeDistribution distribution,
                                         QualityReportModel.QualitySummary quality) {
        List<String> actions = new ArrayList<>();
        if (distribution.topPackageClassShare() >= 0.25 || distribution.maxPackageClassCount() >= 50) {
            actions.add("Review the largest packages first; they are likely to dominate change cost and ownership.");
        }
        if (quality.layeredViolationCount() > 0) {
            actions.add("Prioritize the highest-scoring package hierarchy violations and remove upward dependencies.");
        }
        if (quality.packageCycleCount() > 0 || quality.classCycleCount() > 0) {
            actions.add("Use the generated cycle screenshots to identify stable cut points for package and class tangles.");
        }
        if (quality.componentViolationCount() > 0) {
            actions.add("Treat component API bypasses and API leaks as architecture boundary decisions, not only code cleanup.");
        }
        if (codebase.totalClasses() >= 1_000 && actions.size() < 4) {
            actions.add("Track this score over time; larger codebases benefit from a structural quality trend.");
        }
        if (actions.isEmpty()) {
            actions.add("Keep the current architecture rules visible and monitor the score after significant changes.");
        }
        return actions;
    }

    private static String scoreLabel(int score) {
        if (score >= 85) return "Strong";
        if (score >= 70) return "Stable";
        if (score >= 55) return "Needs Attention";
        if (score >= 40) return "High Risk";
        return "Critical";
    }

    private static String scoreSummary(int score) {
        if (score >= 85) {
            return "The codebase shows a strong structural baseline. Existing findings should be handled selectively, but there is no broad structural risk signal.";
        }
        if (score >= 70) {
            return "The codebase is structurally usable with some visible risk areas. Targeted remediation should keep architectural erosion under control.";
        }
        if (score >= 55) {
            return "The project needs focused structural attention. The most important findings should be planned explicitly instead of being handled opportunistically.";
        }
        if (score >= 40) {
            return "The structural risk is high. Size pressure, distribution effects, or anomaly signals are likely to affect delivery predictability.";
        }
        return "The report indicates critical structural risk. Architectural cleanup should be treated as a managed initiative with visible milestones.";
    }

    private List<QualityReportModel.PackageStat> packageStats(DomainModel domain) {
        Map<String, Integer> classCounts = new TreeMap<>();
        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            classCounts.merge(parentOf(cls.fullName), 1, Integer::sum);
        }

        Map<String, Integer> outgoing = new TreeMap<>();
        Map<String, Integer> incoming = new TreeMap<>();
        for (Map.Entry<String, Map<String, Integer>> from : domain.getPackageEdgeWeights().entrySet()) {
            int out = from.getValue().values().stream().mapToInt(Integer::intValue).sum();
            outgoing.merge(from.getKey(), out, Integer::sum);
            for (Map.Entry<String, Integer> to : from.getValue().entrySet()) {
                incoming.merge(to.getKey(), to.getValue(), Integer::sum);
            }
        }

        Set<String> names = new TreeSet<>();
        names.addAll(domain.getAllPackages().keySet());
        names.addAll(classCounts.keySet());
        List<QualityReportModel.PackageStat> stats = new ArrayList<>();
        for (String name : names) {
            int in = incoming.getOrDefault(name, 0);
            int out = outgoing.getOrDefault(name, 0);
            stats.add(new QualityReportModel.PackageStat(
                    name, classCounts.getOrDefault(name, 0), in + out, in, out));
        }
        return stats;
    }

    private List<QualityReportModel.LevelStat> levelStats(DomainModel domain) {
        Map<Integer, int[]> levels = new TreeMap<>();
        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            levels.computeIfAbsent(cls.architectureLevel, ignored -> new int[2])[0]++;
        }
        for (CalculatedElementInfo pkg : domain.getAllPackages().values()) {
            levels.computeIfAbsent(pkg.architectureLevel, ignored -> new int[2])[1]++;
        }
        List<QualityReportModel.LevelStat> stats = new ArrayList<>();
        for (Map.Entry<Integer, int[]> entry : levels.entrySet()) {
            stats.add(new QualityReportModel.LevelStat(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        return stats;
    }

    private List<QualityReportModel.HistogramBucket> classCountHistogram(
            List<QualityReportModel.PackageStat> packageStats) {
        int[] buckets = new int[6];
        for (QualityReportModel.PackageStat stat : packageStats) {
            int n = stat.classCount();
            if (n == 0) buckets[0]++;
            else if (n <= 2) buckets[1]++;
            else if (n <= 5) buckets[2]++;
            else if (n <= 10) buckets[3]++;
            else if (n <= 25) buckets[4]++;
            else buckets[5]++;
        }
        return List.of(
                new QualityReportModel.HistogramBucket("0", buckets[0]),
                new QualityReportModel.HistogramBucket("1-2", buckets[1]),
                new QualityReportModel.HistogramBucket("3-5", buckets[2]),
                new QualityReportModel.HistogramBucket("6-10", buckets[3]),
                new QualityReportModel.HistogramBucket("11-25", buckets[4]),
                new QualityReportModel.HistogramBucket(">25", buckets[5]));
    }

    private List<QualityReportModel.ViolationFinding> violationFindings(List<Violation> violations,
                                                                        String idPrefix,
                                                                        String imagePattern) {
        Map<String, List<Violation>> grouped = new LinkedHashMap<>();
        for (Violation violation : violations.stream()
                .sorted(Comparator.comparing(Violation::sourceFqn).thenComparing(Violation::targetFqn))
                .toList()) {
            String key = parentOf(violation.sourceFqn()) + "\0" + parentOf(violation.targetFqn())
                    + "\0" + violation.kind();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(violation);
        }

        List<QualityReportModel.ViolationFinding> findings = new ArrayList<>();
        int index = 1;
        for (List<Violation> group : grouped.values()) {
            Violation first = group.getFirst();
            int backEdges = (int) group.stream().filter(Violation::backEdge).count();
            int distinctSources = (int) group.stream().map(Violation::sourceFqn).distinct().count();
            int distinctTargets = (int) group.stream().map(Violation::targetFqn).distinct().count();
            int score = group.size() * 10 + distinctSources * 3 + distinctTargets * 3 + backEdges * 5;
            findings.add(new QualityReportModel.ViolationFinding(
                    idPrefix + "-" + index,
                    first.kind(),
                    parentOf(first.sourceFqn()),
                    parentOf(first.targetFqn()),
                    group.size(),
                    backEdges,
                    score,
                    null,
                    group.stream()
                            .limit(6)
                            .map(v -> new QualityReportModel.EdgeSample(v.sourceFqn(), v.targetFqn(),
                                    v.backEdge() ? "back-edge" : v.kind().name()))
                            .toList()));
            index++;
        }
        List<QualityReportModel.ViolationFinding> sorted = findings.stream()
                .sorted(Comparator.comparingInt(QualityReportModel.ViolationFinding::score).reversed()
                        .thenComparing(QualityReportModel.ViolationFinding::sourceScope)
                        .thenComparing(QualityReportModel.ViolationFinding::targetScope))
                .limit(TOP_LIMIT)
                .toList();
        List<QualityReportModel.ViolationFinding> withImages = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            QualityReportModel.ViolationFinding finding = sorted.get(i);
            withImages.add(new QualityReportModel.ViolationFinding(
                    finding.id(),
                    finding.kind(),
                    finding.sourceScope(),
                    finding.targetScope(),
                    finding.violationCount(),
                    finding.backEdgeCount(),
                    finding.score(),
                    i < scopeImageLimit ? imagePattern.formatted(i + 1) : null,
                    finding.samples()));
        }
        return withImages;
    }

    private List<QualityReportModel.CycleFinding> packageCycles(DomainModel domain) {
        List<QualityReportModel.CycleFinding> findings = new ArrayList<>();
        int index = 1;
        for (Set<String> members : domain.getPackageTangles()) {
            List<String> sortedMembers = members.stream().sorted().toList();
            List<QualityReportModel.EdgeSample> samples = packageCycleSamples(domain, members);
            int weight = samples.stream()
                    .mapToInt(sample -> parseInt(sample.label()))
                    .sum();
            int score = sortedMembers.size() * 10 + samples.size() * 5 + weight;
            findings.add(new QualityReportModel.CycleFinding(
                    "package-cycle-" + index,
                    "Package cycle #" + index,
                    "package",
                    sortedMembers,
                    samples.size(),
                    weight,
                    score,
                    null,
                    samples.stream().limit(12).toList()));
            index++;
        }
        List<QualityReportModel.CycleFinding> sorted = findings.stream()
                .sorted(Comparator.comparingInt(QualityReportModel.CycleFinding::score).reversed()
                        .thenComparing(f -> String.join("|", f.members())))
                .limit(TOP_LIMIT)
                .toList();
        return cycleFindingsWithImages(sorted, "assets/scopes/package-cycle-%03d." + scopeImageExtension);
    }

    private List<QualityReportModel.EdgeSample> packageCycleSamples(DomainModel domain, Set<String> members) {
        List<QualityReportModel.EdgeSample> samples = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> from : domain.getPackageEdgeWeights().entrySet()) {
            if (!members.contains(from.getKey())) {
                continue;
            }
            for (Map.Entry<String, Integer> to : from.getValue().entrySet()) {
                if (members.contains(to.getKey())) {
                    String label = Integer.toString(to.getValue());
                    if (domain.isPackageBackEdge(from.getKey(), to.getKey())) {
                        label += " back-edge";
                    }
                    samples.add(new QualityReportModel.EdgeSample(from.getKey(), to.getKey(), label));
                }
            }
        }
        samples.sort(Comparator.comparing(QualityReportModel.EdgeSample::source)
                .thenComparing(QualityReportModel.EdgeSample::target));
        return samples;
    }

    private List<QualityReportModel.CycleFinding> classCycles(DependencyModel rawModel, DomainModel domain) {
        Map<String, Set<String>> graph = classGraph(domain);
        SCCFinder finder = Lookup.lookup(SCCFinder.class);
        List<StronglyConnectedComponent> sccs = finder.findSCCs(graph).stream()
                .filter(StronglyConnectedComponent::isTangle)
                .toList();

        List<QualityReportModel.CycleFinding> findings = new ArrayList<>();
        int index = 1;
        for (StronglyConnectedComponent scc : sccs) {
            Set<String> members = scc.getMembers();
            List<QualityReportModel.EdgeSample> samples = classCycleSamples(rawModel, graph, members);
            int methodCalls = samples.stream()
                    .mapToInt(sample -> parseInt(sample.label()))
                    .sum();
            int score = members.size() * 10 + samples.size() * 5 + methodCalls;
            findings.add(new QualityReportModel.CycleFinding(
                    "class-cycle-" + index,
                    "Class cycle #" + index,
                    "class",
                    members.stream().sorted().toList(),
                    samples.size(),
                    methodCalls,
                    score,
                    null,
                    samples.stream().limit(16).toList()));
            index++;
        }
        List<QualityReportModel.CycleFinding> sorted = findings.stream()
                .sorted(Comparator.comparingInt(QualityReportModel.CycleFinding::score).reversed()
                        .thenComparing(f -> String.join("|", f.members())))
                .limit(TOP_LIMIT)
                .toList();
        return cycleFindingsWithImages(sorted, "assets/scopes/class-cycle-%03d." + scopeImageExtension);
    }

    private List<QualityReportModel.CycleFinding> cycleFindingsWithImages(
            List<QualityReportModel.CycleFinding> sorted,
            String imagePattern) {
        List<QualityReportModel.CycleFinding> withImages = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            QualityReportModel.CycleFinding finding = sorted.get(i);
            withImages.add(new QualityReportModel.CycleFinding(
                    finding.id(),
                    finding.title(),
                    finding.memberType(),
                    finding.members(),
                    finding.internalEdgeCount(),
                    finding.methodCallWeight(),
                    finding.score(),
                    i < scopeImageLimit ? imagePattern.formatted(i + 1) : null,
                    finding.samples()));
        }
        return withImages;
    }

    private List<QualityReportModel.EdgeSample> classCycleSamples(DependencyModel rawModel,
                                                                  Map<String, Set<String>> graph,
                                                                  Set<String> members) {
        List<QualityReportModel.EdgeSample> samples = new ArrayList<>();
        for (String source : new TreeSet<>(members)) {
            for (String target : new TreeSet<>(graph.getOrDefault(source, Set.of()))) {
                if (members.contains(target)) {
                    int calls = methodCallWeight(rawModel, source, target);
                    samples.add(new QualityReportModel.EdgeSample(source, target,
                            calls == 0 ? "1" : Integer.toString(calls)));
                }
            }
        }
        return samples;
    }

    private QualityReportModel.ComponentFindings componentFindings(DependencyModel rawModel,
                                                                   DomainModel domain,
                                                                   ArchitectureAnnotations annotations) {
        ComponentArchitecture architecture =
                new ComponentArchitectureBuilder().build(domain, annotations, rawModel);
        List<ComponentArchitecture.ComponentElement> components = architecture.components();
        Set<String> coveredClasses = new TreeSet<>();
        List<QualityReportModel.ComponentStat> stats = new ArrayList<>();
        int apiClassCount = 0;
        int implementationElementCount = 0;
        for (ComponentArchitecture.ComponentElement component : components) {
            Set<String> componentClasses = classesUnder(domain, component.rootPackageFqn());
            coveredClasses.addAll(componentClasses);
            int apiCount = component.api().size();
            int implCount = countElements(component.implementationRows());
            apiClassCount += apiCount;
            implementationElementCount += implCount;
            stats.add(new QualityReportModel.ComponentStat(
                    component.id(),
                    component.displayName(),
                    component.rootPackageFqn(),
                    apiCount,
                    implCount,
                    componentClasses.size()));
        }

        int apiBypass = (int) architecture.violations().stream()
                .filter(v -> v.kind() == ViolationKind.COMPONENT_API_BYPASS)
                .count();
        int apiLeak = (int) architecture.violations().stream()
                .filter(v -> v.kind() == ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION)
                .count();

        ComponentConfidence confidence = componentConfidence(rawModel, annotations, domain,
                components, coveredClasses.size(), apiClassCount, implementationElementCount);
        List<QualityReportModel.ViolationFinding> violations = confidence.reportable()
                ? violationFindings(architecture.violations(), "component-violation",
                        "assets/scopes/component-violation-%03d." + scopeImageExtension)
                : List.of();

        return new QualityReportModel.ComponentFindings(
                confidence.reportable(),
                confidence.score(),
                confidence.evidence(),
                confidence.warnings(),
                components.size(),
                apiClassCount,
                coveredClasses.size(),
                domain.getAllClasses().size(),
                apiBypass,
                apiLeak,
                confidence.reportable() ? "assets/component-overview.svg" : null,
                stats.stream()
                        .sorted(Comparator.comparing(QualityReportModel.ComponentStat::rootPackageFqn))
                        .toList(),
                violations);
    }

    private ComponentConfidence componentConfidence(DependencyModel rawModel,
                                                    ArchitectureAnnotations annotations,
                                                    DomainModel domain,
                                                    List<ComponentArchitecture.ComponentElement> components,
                                                    int coveredClassCount,
                                                    int apiClassCount,
                                                    int implementationElementCount) {
        List<String> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int totalClasses = Math.max(1, domain.getAllClasses().size());
        double coveredRatio = (double) coveredClassCount / totalClasses;
        int explicitComponentAnnotations =
                annotations.components().size() + rawModel.getComponentAnnotatedPackages().size();
        int exportedPackageCount = rawModel.getExportedPackageNames().size();
        int apiImplPatternCount = (int) components.stream()
                .filter(component -> component.api().size() > 0 && !component.implementationRows().isEmpty())
                .count();

        if (components.size() >= 2) evidence.add(components.size() + " component roots detected");
        else warnings.add("Less than two component roots detected");

        if (apiClassCount >= 2) evidence.add(apiClassCount + " API classes detected");
        else warnings.add("Too few API classes detected");

        if (coveredRatio >= 0.35) evidence.add("%d%% of classes covered by components".formatted(Math.round(coveredRatio * 100)));
        else warnings.add("Component coverage below 35%");

        if (explicitComponentAnnotations > 0) evidence.add(explicitComponentAnnotations + " explicit component annotations");
        if (exportedPackageCount > 0) evidence.add(exportedPackageCount + " JPMS exported packages");
        if (apiImplPatternCount >= 2) evidence.add(apiImplPatternCount + " API/implementation package patterns");
        if (implementationElementCount == 0) warnings.add("No implementation bodies detected for components");

        for (ComponentArchitecture.ComponentElement component : components) {
            String simple = simple(component.rootPackageFqn()).toLowerCase(Locale.ROOT);
            if (simple.equals("util") || simple.equals("utils") || simple.equals("common")
                    || simple.equals("shared")) {
                warnings.add("Technical component root detected: " + component.rootPackageFqn());
            }
        }

        boolean reportable = components.size() >= 2
                && apiClassCount >= 2
                && coveredRatio >= 0.35
                && (explicitComponentAnnotations > 0
                    || exportedPackageCount > 0
                    || apiImplPatternCount >= 2);
        double score = 0;
        if (components.size() >= 2) score += 0.25;
        if (apiClassCount >= 2) score += 0.20;
        score += Math.min(0.25, coveredRatio * 0.25 / 0.35);
        if (explicitComponentAnnotations > 0) score += 0.15;
        if (exportedPackageCount > 0) score += 0.10;
        if (apiImplPatternCount >= 2) score += 0.15;
        score = Math.min(1.0, score);
        return new ComponentConfidence(reportable, score, evidence, warnings);
    }

    private List<QualityReportModel.AppendixTable> appendix(QualityReportInput input,
                                                            List<Violation> upwardViolations) {
        List<QualityReportModel.AppendixTable> tables = new ArrayList<>();
        LayoutInvariantReport invariants = input.invariantReport();
        if (invariants != null) {
            tables.add(new QualityReportModel.AppendixTable(
                    "Layout Invariants",
                    List.of("Metric", "Value"),
                    List.of(
                            List.of("Districts", Integer.toString(invariants.districtCount())),
                            List.of("Buildings", Integer.toString(invariants.buildingCount())),
                            List.of("Dependencies", Integer.toString(invariants.dependencyCount())),
                            List.of("Max level", Integer.toString(invariants.maxLevel())),
                            List.of("Invariant findings", Integer.toString(invariants.findings().size())))));
        }
        tables.add(new QualityReportModel.AppendixTable(
                "Layered Violation Samples",
                List.of("Source", "Target", "Back edge"),
                upwardViolations.stream()
                        .sorted(Comparator.comparing(Violation::sourceFqn).thenComparing(Violation::targetFqn))
                        .limit(50)
                        .map(v -> List.of(v.sourceFqn(), v.targetFqn(), Boolean.toString(v.backEdge())))
                        .toList()));
        return tables;
    }

    private Map<EdgeKind, Integer> dependencyKindCounts(DependencyModel rawModel) {
        Map<EdgeKind, Integer> counts = new EnumMap<>(EdgeKind.class);
        for (EdgeKind kind : EdgeKind.values()) {
            counts.put(kind, 0);
        }
        for (DependencyModel.ClassInfo cls : rawModel.getAllClasses().values()) {
            for (Set<EdgeKind> kinds : cls.dependencyKinds.values()) {
                for (EdgeKind kind : kinds) {
                    counts.merge(kind, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private List<String> languages(QualityReportInput input) {
        Set<String> languages = new LinkedHashSet<>();
        String kind = input.source() == null ? "" : nullToEmpty(input.source().kind()).toUpperCase(Locale.ROOT);
        if (kind.contains("PYTHON")) languages.add("Python");
        if (kind.equals("C")) languages.add("C");
        if (kind.contains("JAR") || kind.contains("MAVEN") || kind.contains("GRADLE")
                || input.rawModel().getModuleCount() > 0) {
            languages.add("Java");
        }
        List<String> paths = input.source() == null ? List.of() : nullToList(input.source().paths());
        for (String path : paths) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".py")) languages.add("Python");
            if (lower.endsWith(".c") || lower.endsWith(".h")) languages.add("C");
            if (lower.endsWith(".jar") || lower.endsWith(".java") || lower.endsWith("pom.xml")
                    || lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts")) {
                languages.add("Java");
            }
        }
        if (languages.isEmpty()) {
            languages.add("Unknown");
        }
        return List.copyOf(languages);
    }

    private List<String> buildSystems(S202Project.Source source) {
        Set<String> systems = new LinkedHashSet<>();
        String kind = source == null ? "" : nullToEmpty(source.kind()).toUpperCase(Locale.ROOT);
        if (kind.contains("MAVEN")) systems.add("Maven");
        if (kind.contains("GRADLE")) systems.add("Gradle");
        if (kind.contains("JAR")) systems.add("JAR input");
        if (kind.contains("PYTHON")) systems.add("Source root");
        if (kind.equals("C")) systems.add("Source root");
        if (source != null) {
            for (String path : nullToList(source.paths())) {
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.endsWith("pom.xml")) systems.add("Maven");
                if (lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts")
                        || lower.endsWith("settings.gradle") || lower.endsWith("settings.gradle.kts")) {
                    systems.add("Gradle");
                }
            }
        }
        return systems.isEmpty() ? List.of("Unknown") : List.copyOf(systems);
    }

    private Map<String, Set<String>> classGraph(DomainModel domain) {
        Map<String, CalculatedElementInfo> classes = domain.getAllClasses();
        Map<String, Set<String>> graph = new TreeMap<>();
        for (CalculatedElementInfo cls : classes.values()) {
            graph.put(cls.fullName, internalDependencies(cls, domain));
        }
        return graph;
    }

    private Set<String> internalDependencies(CalculatedElementInfo cls, DomainModel domain) {
        Set<String> deps = new TreeSet<>();
        for (String dep : cls.dependencies) {
            if (domain.getClass(dep) != null) {
                deps.add(dep);
            }
        }
        return deps;
    }

    private int methodCallWeight(DependencyModel rawModel, String source, String target) {
        DependencyModel.ClassInfo classInfo = rawModel.getClass(source);
        if (classInfo == null) {
            return 0;
        }
        int calls = 0;
        for (DependencyModel.MethodInfo method : classInfo.methods.values()) {
            for (Map.Entry<String, Integer> call : method.methodCalls.entrySet()) {
                int dot = call.getKey().lastIndexOf('.');
                if (dot <= 0) {
                    continue;
                }
                if (target.equals(call.getKey().substring(0, dot))) {
                    calls += call.getValue();
                }
            }
        }
        return calls;
    }

    private Set<String> classesUnder(DomainModel domain, String packageFqn) {
        return domain.getAllClasses().keySet().stream()
                .filter(className -> className.startsWith(packageFqn + "."))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private int countElements(List<List<Element>> rows) {
        int count = 0;
        for (List<Element> row : rows) {
            for (Element element : row) {
                count++;
                if (element instanceof Element.PackageElement pkg) {
                    count += countElements(pkg.rows());
                }
            }
        }
        return count;
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int space = value.indexOf(' ');
        String numeric = space < 0 ? value : value.substring(0, space);
        try {
            return Integer.parseInt(numeric);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String parentOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }

    private static String simple(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> nullToList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<String> sourcePaths(LayoutInvariantReport report) {
        return report == null ? List.of() : report.sourcePaths();
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return "svg";
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

    private record ComponentConfidence(
            boolean reportable,
            double score,
            List<String> evidence,
            List<String> warnings) {}
}
