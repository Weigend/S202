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
package de.weigend.s202.report.quality.impl;

import de.weigend.s202.report.quality.QualityReportModel;
import de.weigend.s202.reader.EdgeKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Writes the static HTML report and its SVG/CSS assets.
 */
final class QualityReportHtmlWriter {

    public Path write(QualityReportModel model, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Files.createDirectories(outputDir.resolve("assets"));
        Files.createDirectories(outputDir.resolve("assets/scopes"));

        writeText(outputDir.resolve("assets/report.css"), css());
        writeText(outputDir.resolve("assets/quality.svg"), qualitySvg(model.quality()));
        writeText(outputDir.resolve("assets/package-size.svg"), barChart(
                "Top packages by classes",
                model.sizeDistribution().topPackagesByClasses().stream()
                        .map(stat -> new Bar(stat.name(), stat.classCount()))
                        .toList()));
        writeText(outputDir.resolve("assets/package-dependencies.svg"), barChart(
                "Top packages by dependencies",
                model.sizeDistribution().topPackagesByDependencies().stream()
                        .map(stat -> new Bar(stat.name(), stat.dependencyCount()))
                        .toList()));
        writeText(outputDir.resolve("assets/levels.svg"), levelChart(model.sizeDistribution().levels()));
        writeText(outputDir.resolve("assets/dependency-kinds.svg"), dependencyKindsChart(model.codebase().dependencyKindCounts()));

        for (QualityReportModel.ViolationFinding finding : model.layeredViolations()) {
            if (isSvg(finding.imagePath())) {
                writeText(outputDir.resolve(finding.imagePath()), violationScopeSvg(finding, "Layered violation"));
            }
        }
        for (QualityReportModel.CycleFinding finding : model.packageCycles()) {
            if (isSvg(finding.imagePath())) {
                writeText(outputDir.resolve(finding.imagePath()), cycleScopeSvg(finding));
            }
        }
        for (QualityReportModel.CycleFinding finding : model.classCycles()) {
            if (isSvg(finding.imagePath())) {
                writeText(outputDir.resolve(finding.imagePath()), cycleScopeSvg(finding));
            }
        }
        QualityReportModel.ComponentFindings componentFindings = model.componentFindings();
        if (componentFindings != null && componentFindings.reportable()) {
            if (componentFindings.overviewImagePath() != null) {
                writeText(outputDir.resolve(componentFindings.overviewImagePath()),
                        componentOverviewSvg(componentFindings));
            }
            for (QualityReportModel.ViolationFinding finding : componentFindings.violations()) {
                if (isSvg(finding.imagePath())) {
                    writeText(outputDir.resolve(finding.imagePath()),
                            violationScopeSvg(finding, "Component violation"));
                }
            }
        }

        Path html = outputDir.resolve("index.html");
        writeText(html, html(model));
        return html;
    }

    private String html(QualityReportModel model) {
        StringBuilder out = new StringBuilder(32_768);
        QualityReportModel.ReportMetadata meta = model.metadata();
        QualityReportModel.OverallAssessment assessment = model.overallAssessment();
        QualityReportModel.CodebaseProfile codebase = model.codebase();
        QualityReportModel.QualitySummary quality = model.quality();
        out.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(h(meta.title())).append("</title>\n")
                .append("<link rel=\"stylesheet\" href=\"assets/report.css\">\n")
                .append("</head>\n<body>\n")
                .append("<header class=\"report-header\">\n")
                .append("<div><h1>").append(h(meta.title())).append("</h1>\n")
                .append("<p>").append(h(meta.sourceKind())).append(" analysis, generated ")
                .append(h(meta.analyzedAt())).append("</p></div>\n")
                .append("<span class=\"band ").append(h(quality.band().cssClass())).append("\">")
                .append(h(quality.band().label())).append("</span>\n")
                .append("</header>\n");

        assessmentSection(out, assessment);

        out.append("<section><h2>Executive Summary</h2><div class=\"cards\">");
        card(out, "Classes", n(codebase.totalClasses()));
        card(out, "Packages", n(codebase.totalPackages()));
        card(out, "Dependencies", n(codebase.totalDependencies()));
        card(out, "Fat / Tangled", fmt(quality.fat()) + " / " + fmt(quality.tangled()));
        card(out, "Layered Violations", n(quality.layeredViolationCount()));
        card(out, "Package / Class Cycles", n(quality.packageCycleCount()) + " / " + n(quality.classCycleCount()));
        out.append("</div>");
        out.append("<p class=\"lead\">This report summarizes structural quality signals from the S202 dependency model. ")
                .append("It focuses on size distribution, layering violations, package cycles, class cycles, ")
                .append("and component API findings when the component architecture is likely enough to report.</p>")
                .append("</section>\n");

        out.append("<section><h2>Codebase Profile</h2><div class=\"grid two\">");
        out.append("<div>").append(profileTable(codebase, meta)).append("</div>");
        out.append("<figure><img src=\"assets/dependency-kinds.svg\" alt=\"Dependency kinds\"><figcaption>Dependency kinds from the raw reader model.</figcaption></figure>");
        out.append("</div></section>\n");

        out.append("<section><h2>Size Distribution</h2><div class=\"grid two\">")
                .append("<figure><img src=\"assets/package-size.svg\" alt=\"Top packages by class count\"><figcaption>Largest packages by direct class count.</figcaption></figure>")
                .append("<figure><img src=\"assets/package-dependencies.svg\" alt=\"Top packages by dependency weight\"><figcaption>Packages with the highest incoming and outgoing package dependency weight.</figcaption></figure>")
                .append("<figure><img src=\"assets/levels.svg\" alt=\"Architecture levels\"><figcaption>Classes and packages by calculated architecture level.</figcaption></figure>")
                .append("<div>").append(histogramTable(model.sizeDistribution().classCountHistogram())).append("</div>")
                .append("</div></section>\n");

        out.append("<section><h2>Quality Overview</h2><div class=\"grid two\">")
                .append("<figure><img src=\"assets/quality.svg\" alt=\"Fat tangled quality plot\"><figcaption>Lower-left is healthier; upper-right means dense and cyclic coupling.</figcaption></figure>")
                .append("<div>").append(qualityTable(quality)).append("</div>")
                .append("</div></section>\n");

        out.append("<section><h2>Architecture Findings</h2>");
        if (model.layeredViolations().isEmpty()) {
            out.append("<p class=\"empty\">No layered hierarchy violations were found.</p>");
        } else {
            out.append(violationTable(model.layeredViolations()));
            figures(out, model.layeredViolations(), "Layered violation scope");
        }
        out.append("</section>\n");

        out.append("<section><h2>Cycle Findings</h2><h3>Package Cycles</h3>");
        cycleSection(out, model.packageCycles(), "No package cycles were found.");
        out.append("<h3>Class Cycles</h3>");
        cycleSection(out, model.classCycles(), "No class cycles were found.");
        out.append("</section>\n");

        componentSection(out, model.componentFindings());

        out.append("<section><h2>Appendix</h2>");
        for (QualityReportModel.AppendixTable table : model.appendix()) {
            out.append("<h3>").append(h(table.title())).append("</h3>")
                    .append(genericTable(table.headers(), table.rows()));
        }
        out.append("</section>\n");

        out.append("</body>\n</html>\n");
        return out.toString();
    }

    private void assessmentSection(StringBuilder out, QualityReportModel.OverallAssessment assessment) {
        out.append("<section class=\"assessment\"><div class=\"assessment-head\">")
                .append("<div><h2>Overall Quality Score</h2>")
                .append("<p class=\"lead\">").append(h(assessment.summary())).append("</p></div>")
                .append("<div class=\"score-card ").append(h(scoreClass(assessment.score()))).append("\">")
                .append("<strong>").append(n(assessment.score())).append("</strong>")
                .append("<span>of 100</span>")
                .append("<em>").append(h(assessment.label())).append("</em>")
                .append("</div></div>")
                .append("<div class=\"grid two assessment-grid\">")
                .append("<div><h3>Assessment Drivers</h3>")
                .append(bulletList(assessment.keyDrivers()))
                .append("</div><div><h3>Recommended Focus</h3>")
                .append(bulletList(assessment.followUpActions()))
                .append("</div></div></section>\n");
    }

    private String bulletList(List<String> items) {
        StringBuilder out = new StringBuilder("<ul class=\"assessment-list\">");
        if (items == null || items.isEmpty()) {
            out.append("<li>No specific driver text was generated.</li>");
        } else {
            for (String item : items) {
                out.append("<li>").append(h(item)).append("</li>");
            }
        }
        return out.append("</ul>").toString();
    }

    private String profileTable(QualityReportModel.CodebaseProfile codebase,
                                QualityReportModel.ReportMetadata meta) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Source kind", meta.sourceKind()));
        rows.add(List.of("Languages", String.join(", ", codebase.languages())));
        rows.add(List.of("Build systems", String.join(", ", codebase.buildSystems())));
        rows.add(List.of("S202 version", meta.s202Version()));
        rows.add(List.of("Classes", n(codebase.totalClasses())));
        rows.add(List.of("Packages", n(codebase.totalPackages())));
        rows.add(List.of("Modules", n(codebase.totalModules())));
        rows.add(List.of("Interfaces", n(codebase.interfaceCount())));
        rows.add(List.of("Exported packages", n(codebase.exportedPackageCount())));
        rows.add(List.of("S202 component packages", n(codebase.componentAnnotatedPackageCount())));
        rows.add(List.of("S202 API packages", n(codebase.apiAnnotatedPackageCount())));
        return genericTable(List.of("Metric", "Value"), rows);
    }

    private String histogramTable(List<QualityReportModel.HistogramBucket> buckets) {
        return "<h3>Classes per package</h3>" + genericTable(
                List.of("Bucket", "Packages"),
                buckets.stream()
                        .map(bucket -> List.of(bucket.label(), n(bucket.count())))
                        .toList());
    }

    private String qualityTable(QualityReportModel.QualitySummary quality) {
        return genericTable(List.of("Metric", "Value"), List.of(
                List.of("Band", quality.band().label()),
                List.of("Fat", fmt(quality.fat())),
                List.of("Tangled", fmt(quality.tangled())),
                List.of("Classes", n(quality.classCount())),
                List.of("Internal dependencies", n(quality.dependencyCount())),
                List.of("Dependencies inside class cycles", n(quality.intraSccDependencyCount())),
                List.of("Layered violations", n(quality.layeredViolationCount())),
                List.of("Package cycles", n(quality.packageCycleCount())),
                List.of("Class cycles", n(quality.classCycleCount())),
                List.of("Component violations", n(quality.componentViolationCount()))));
    }

    private String violationTable(List<QualityReportModel.ViolationFinding> findings) {
        return genericTable(
                List.of("Finding", "Source scope", "Target scope", "Kind", "Violations", "Back edges", "Score"),
                findings.stream()
                        .map(f -> List.of(f.id(), f.sourceScope(), f.targetScope(), f.kind().name(),
                                n(f.violationCount()), n(f.backEdgeCount()), n(f.score())))
                        .toList());
    }

    private void cycleSection(StringBuilder out, List<QualityReportModel.CycleFinding> findings,
                              String emptyMessage) {
        if (findings.isEmpty()) {
            out.append("<p class=\"empty\">").append(h(emptyMessage)).append("</p>");
            return;
        }
        out.append(genericTable(
                List.of("Finding", "Members", "Internal edges", "Method/weight", "Score"),
                findings.stream()
                        .map(f -> List.of(f.id(), n(f.members().size()), n(f.internalEdgeCount()),
                                n(f.methodCallWeight()), n(f.score())))
                        .toList()));
        figuresCycles(out, findings);
    }

    private void componentSection(StringBuilder out, QualityReportModel.ComponentFindings findings) {
        out.append("<section><h2>Component Findings</h2>");
        if (findings == null || !findings.reportable()) {
            out.append("<p class=\"empty\">No sufficiently likely component architecture was detected. ")
                    .append("Component violations were not included in the quality assessment.</p>");
            if (findings != null) {
                out.append("<h3>Confidence Signals</h3>")
                        .append(genericTable(List.of("Evidence", "Warnings"), confidenceRows(findings)));
            }
            out.append("</section>\n");
            return;
        }
        out.append("<div class=\"cards\">");
        card(out, "Components", n(findings.componentCount()));
        card(out, "API Classes", n(findings.apiClassCount()));
        card(out, "Covered Classes", n(findings.coveredClassCount()) + " / " + n(findings.totalClassCount()));
        card(out, "API Bypass", n(findings.apiBypassCount()));
        card(out, "API Leaks", n(findings.apiLeakCount()));
        card(out, "Confidence", fmt(findings.confidenceScore()));
        out.append("</div>");
        out.append("<figure><img src=\"").append(h(findings.overviewImagePath()))
                .append("\" alt=\"Component overview\"><figcaption>Detected component API and implementation surface.</figcaption></figure>");
        out.append("<h3>Components</h3>");
        out.append(genericTable(
                List.of("Component", "Root package", "API classes", "Implementation elements", "Covered classes"),
                findings.components().stream()
                        .map(c -> List.of(c.displayName(), c.rootPackageFqn(), n(c.apiClassCount()),
                                n(c.implementationElementCount()), n(c.coveredClassCount())))
                        .toList()));
        out.append("<h3>Component Violations</h3>");
        if (findings.violations().isEmpty()) {
            out.append("<p class=\"empty\">No component API violations were found.</p>");
        } else {
            out.append(violationTable(findings.violations()));
            figures(out, findings.violations(), "Component violation scope");
        }
        out.append("</section>\n");
    }

    private List<List<String>> confidenceRows(QualityReportModel.ComponentFindings findings) {
        int rows = Math.max(findings.evidence().size(), findings.warnings().size());
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            out.add(List.of(
                    i < findings.evidence().size() ? findings.evidence().get(i) : "",
                    i < findings.warnings().size() ? findings.warnings().get(i) : ""));
        }
        return out;
    }

    private void figures(StringBuilder out, List<QualityReportModel.ViolationFinding> findings, String captionPrefix) {
        out.append("<div class=\"gallery\">");
        for (QualityReportModel.ViolationFinding finding : findings) {
            if (finding.imagePath() == null) {
                continue;
            }
            out.append("<figure><img src=\"").append(h(finding.imagePath())).append("\" alt=\"")
                    .append(h(captionPrefix)).append("\"><figcaption>")
                    .append(h(captionPrefix)).append(": ").append(h(finding.sourceScope()))
                    .append(" -> ").append(h(finding.targetScope()))
                    .append(", ").append(n(finding.violationCount())).append(" edge(s).</figcaption></figure>");
        }
        out.append("</div>");
    }

    private void figuresCycles(StringBuilder out, List<QualityReportModel.CycleFinding> findings) {
        out.append("<div class=\"gallery\">");
        for (QualityReportModel.CycleFinding finding : findings) {
            if (finding.imagePath() == null) {
                continue;
            }
            out.append("<figure><img src=\"").append(h(finding.imagePath())).append("\" alt=\"")
                    .append(h(finding.title())).append("\"><figcaption>")
                    .append(h(finding.title())).append(": ").append(n(finding.members().size()))
                    .append(" ").append(h(finding.memberType())).append("(s), ")
                    .append(n(finding.internalEdgeCount())).append(" internal edge(s).</figcaption></figure>");
        }
        out.append("</div>");
    }

    private String genericTable(List<String> headers, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        out.append("<table><thead><tr>");
        for (String header : headers) {
            out.append("<th>").append(h(header)).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        if (rows.isEmpty()) {
            out.append("<tr><td colspan=\"").append(headers.size()).append("\" class=\"empty\">No entries</td></tr>");
        } else {
            for (List<String> row : rows) {
                out.append("<tr>");
                for (String cell : row) {
                    out.append("<td>").append(h(cell)).append("</td>");
                }
                out.append("</tr>");
            }
        }
        out.append("</tbody></table>");
        return out.toString();
    }

    private void card(StringBuilder out, String label, String value) {
        out.append("<div class=\"card\"><span>").append(h(label)).append("</span><strong>")
                .append(h(value)).append("</strong></div>");
    }

    private String qualitySvg(QualityReportModel.QualitySummary quality) {
        int width = 640;
        int height = 380;
        double plotX = 70;
        double plotY = 35;
        double plotW = 510;
        double plotH = 270;
        double x = plotX + clamp(quality.fat()) * plotW;
        double y = plotY + (1.0 - clamp(quality.tangled())) * plotH;
        return svgHeader(width, height)
                + "<defs><linearGradient id=\"risk\" x1=\"0\" y1=\"1\" x2=\"1\" y2=\"0\">"
                + "<stop offset=\"0%\" stop-color=\"#2f9e44\"/><stop offset=\"55%\" stop-color=\"#f2c94c\"/>"
                + "<stop offset=\"100%\" stop-color=\"#d9480f\"/></linearGradient></defs>"
                + "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                + "<text x=\"24\" y=\"24\" class=\"title\">Quality: Fat vs. Tangled</text>"
                + "<rect x=\"" + plotX + "\" y=\"" + plotY + "\" width=\"" + plotW + "\" height=\"" + plotH + "\" fill=\"url(#risk)\" opacity=\"0.75\"/>"
                + grid(plotX, plotY, plotW, plotH)
                + "<rect x=\"" + plotX + "\" y=\"" + plotY + "\" width=\"" + plotW + "\" height=\"" + plotH + "\" fill=\"none\" stroke=\"#1f2933\"/>"
                + "<text x=\"" + (plotX + plotW / 2 - 18) + "\" y=\"350\" class=\"axis\">Fat</text>"
                + "<text x=\"16\" y=\"" + (plotY + plotH / 2) + "\" class=\"axis\" transform=\"rotate(-90 16 " + (plotY + plotH / 2) + ")\">Tangled</text>"
                + "<circle cx=\"" + x + "\" cy=\"" + y + "\" r=\"9\" fill=\"#ffffff\" stroke=\"#111827\" stroke-width=\"3\"/>"
                + "<text x=\"" + (plotX + 10) + "\" y=\"" + (plotY + 24) + "\" class=\"small\">fat=" + fmt(quality.fat()) + " tangled=" + fmt(quality.tangled()) + "</text>"
                + "<text x=\"" + (plotX + 10) + "\" y=\"" + (plotY + 44) + "\" class=\"small\">band=" + h(quality.band().label()) + "</text>"
                + "</svg>";
    }

    private String barChart(String title, List<Bar> bars) {
        int width = 760;
        int rowH = 30;
        int height = Math.max(180, 70 + bars.size() * rowH);
        int max = bars.stream().mapToInt(Bar::value).max().orElse(1);
        StringBuilder svg = new StringBuilder(svgHeader(width, height));
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
                .append("<text x=\"24\" y=\"28\" class=\"title\">").append(s(title)).append("</text>");
        int y = 58;
        for (Bar bar : bars) {
            int w = max == 0 ? 0 : (int) Math.round((double) bar.value() / max * 470);
            svg.append("<text x=\"24\" y=\"").append(y + 16).append("\" class=\"label\">")
                    .append(s(shorten(bar.label(), 42))).append("</text>")
                    .append("<rect x=\"300\" y=\"").append(y).append("\" width=\"").append(w)
                    .append("\" height=\"18\" fill=\"#2563eb\" rx=\"3\"/>")
                    .append("<text x=\"").append(310 + w).append("\" y=\"").append(y + 14)
                    .append("\" class=\"small\">").append(n(bar.value())).append("</text>");
            y += rowH;
        }
        return svg.append("</svg>").toString();
    }

    private String levelChart(List<QualityReportModel.LevelStat> levels) {
        List<Bar> bars = levels.stream()
                .map(level -> new Bar("Level " + level.level(),
                        level.classCount() + level.packageCount()))
                .toList();
        return barChart("Architecture levels", bars);
    }

    private String dependencyKindsChart(Map<EdgeKind, Integer> counts) {
        return barChart("Dependency kinds", counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Bar(entry.getKey().label(), entry.getValue()))
                .toList());
    }

    private String violationScopeSvg(QualityReportModel.ViolationFinding finding, String title) {
        int width = 900;
        int height = 330;
        StringBuilder svg = new StringBuilder(svgHeader(width, height));
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
                .append("<text x=\"24\" y=\"30\" class=\"title\">").append(s(title)).append("</text>")
                .append(packageBox(70, 80, 300, 110, finding.sourceScope(), "source"))
                .append(packageBox(530, 80, 300, 110, finding.targetScope(), "target"))
                .append("<path d=\"M 370 135 C 435 80, 465 80, 530 135\" fill=\"none\" stroke=\"#dc2626\" stroke-width=\"5\" marker-end=\"url(#arrowRed)\"/>")
                .append(defs())
                .append("<text x=\"405\" y=\"82\" class=\"riskText\">").append(n(finding.violationCount()))
                .append(" violation(s)</text>")
                .append("<text x=\"405\" y=\"104\" class=\"small\">").append(n(finding.backEdgeCount()))
                .append(" back-edge(s)</text>");
        int y = 235;
        for (QualityReportModel.EdgeSample sample : finding.samples().stream().limit(3).toList()) {
            svg.append("<text x=\"70\" y=\"").append(y).append("\" class=\"small\">")
                    .append(s(shorten(simple(sample.source()) + " -> " + simple(sample.target()) + " (" + sample.label() + ")", 110)))
                    .append("</text>");
            y += 22;
        }
        return svg.append("</svg>").toString();
    }

    private String cycleScopeSvg(QualityReportModel.CycleFinding finding) {
        int width = 900;
        int height = 560;
        int cx = width / 2;
        int cy = 285;
        int radius = 185;
        List<String> members = finding.members().stream().limit(16).toList();
        StringBuilder svg = new StringBuilder(svgHeader(width, height));
        svg.append(defs())
                .append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
                .append("<text x=\"24\" y=\"30\" class=\"title\">").append(s(finding.title())).append("</text>")
                .append("<text x=\"24\" y=\"54\" class=\"small\">").append(n(finding.members().size()))
                .append(" members, ").append(n(finding.internalEdgeCount())).append(" internal edges</text>");
        for (QualityReportModel.EdgeSample edge : finding.samples().stream().limit(28).toList()) {
            int si = members.indexOf(edge.source());
            int ti = members.indexOf(edge.target());
            if (si < 0 || ti < 0 || si == ti) {
                continue;
            }
            Point s = pointOnCircle(cx, cy, radius, si, members.size());
            Point t = pointOnCircle(cx, cy, radius, ti, members.size());
            svg.append("<line x1=\"").append(s.x()).append("\" y1=\"").append(s.y())
                    .append("\" x2=\"").append(t.x()).append("\" y2=\"").append(t.y())
                    .append("\" stroke=\"#dc2626\" stroke-width=\"2\" opacity=\"0.42\" marker-end=\"url(#arrowRed)\"/>");
        }
        for (int i = 0; i < members.size(); i++) {
            Point p = pointOnCircle(cx, cy, radius, i, members.size());
            svg.append("<circle cx=\"").append(p.x()).append("\" cy=\"").append(p.y())
                    .append("\" r=\"34\" fill=\"#eff6ff\" stroke=\"#1d4ed8\" stroke-width=\"2\"/>")
                    .append("<text x=\"").append(p.x() - 28).append("\" y=\"").append(p.y() + 4)
                    .append("\" class=\"nodeText\">").append(s(shorten(simple(members.get(i)), 11))).append("</text>");
        }
        if (finding.members().size() > members.size()) {
            svg.append("<text x=\"").append(cx - 80).append("\" y=\"515\" class=\"small\">")
                    .append(n(finding.members().size() - members.size())).append(" additional members hidden</text>");
        }
        return svg.append("</svg>").toString();
    }

    private String componentOverviewSvg(QualityReportModel.ComponentFindings findings) {
        int count = Math.max(1, findings.components().size());
        int width = Math.max(920, 80 + count * 220);
        int height = 310;
        StringBuilder svg = new StringBuilder(svgHeader(width, height));
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
                .append("<text x=\"24\" y=\"30\" class=\"title\">Detected component architecture</text>");
        int x = 50;
        for (QualityReportModel.ComponentStat component : findings.components()) {
            svg.append("<rect x=\"").append(x).append("\" y=\"75\" width=\"180\" height=\"150\" rx=\"8\" fill=\"#f8fafc\" stroke=\"#334155\"/>")
                    .append("<text x=\"").append(x + 14).append("\" y=\"105\" class=\"label\">")
                    .append(s(shorten(component.displayName(), 22))).append("</text>")
                    .append("<text x=\"").append(x + 14).append("\" y=\"130\" class=\"small\">")
                    .append(s(shorten(component.rootPackageFqn(), 24))).append("</text>")
                    .append("<rect x=\"").append(x + 14).append("\" y=\"152\" width=\"152\" height=\"26\" rx=\"4\" fill=\"#dbeafe\"/>")
                    .append("<text x=\"").append(x + 24).append("\" y=\"170\" class=\"small\">API ")
                    .append(n(component.apiClassCount())).append("</text>")
                    .append("<rect x=\"").append(x + 14).append("\" y=\"187\" width=\"152\" height=\"26\" rx=\"4\" fill=\"#e5e7eb\"/>")
                    .append("<text x=\"").append(x + 24).append("\" y=\"205\" class=\"small\">Impl ")
                    .append(n(component.implementationElementCount())).append("</text>");
            x += 220;
        }
        return svg.append("</svg>").toString();
    }

    private String packageBox(int x, int y, int w, int h, String label, String role) {
        return "<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + w + "\" height=\"" + h
                + "\" rx=\"8\" fill=\"#f8fafc\" stroke=\"#334155\"/>"
                + "<text x=\"" + (x + 16) + "\" y=\"" + (y + 32) + "\" class=\"small\">" + s(role) + "</text>"
                + "<text x=\"" + (x + 16) + "\" y=\"" + (y + 66) + "\" class=\"label\">"
                + s(shorten(label, 36)) + "</text>";
    }

    private String grid(double x, double y, double w, double h) {
        StringBuilder svg = new StringBuilder();
        for (int i = 1; i < 4; i++) {
            double gx = x + i * w / 4.0;
            double gy = y + i * h / 4.0;
            svg.append("<line x1=\"").append(gx).append("\" y1=\"").append(y)
                    .append("\" x2=\"").append(gx).append("\" y2=\"").append(y + h)
                    .append("\" stroke=\"#ffffff\" opacity=\"0.5\"/>");
            svg.append("<line x1=\"").append(x).append("\" y1=\"").append(gy)
                    .append("\" x2=\"").append(x + w).append("\" y2=\"").append(gy)
                    .append("\" stroke=\"#ffffff\" opacity=\"0.5\"/>");
        }
        return svg.toString();
    }

    private String defs() {
        return "<defs><marker id=\"arrowRed\" markerWidth=\"10\" markerHeight=\"10\" refX=\"8\" refY=\"3\" orient=\"auto\" markerUnits=\"strokeWidth\">"
                + "<path d=\"M0,0 L0,6 L9,3 z\" fill=\"#dc2626\"/></marker></defs>";
    }

    private String svgHeader(int width, int height) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height
                + "\" viewBox=\"0 0 " + width + " " + height + "\">"
                + "<style>"
                + ".title{font:700 18px Arial,sans-serif;fill:#111827}"
                + ".label{font:700 13px Arial,sans-serif;fill:#111827}"
                + ".small{font:12px Arial,sans-serif;fill:#334155}"
                + ".axis{font:700 12px Arial,sans-serif;fill:#111827}"
                + ".riskText{font:700 14px Arial,sans-serif;fill:#dc2626}"
                + ".nodeText{font:700 10px Arial,sans-serif;fill:#111827}"
                + "</style>";
    }

    private Point pointOnCircle(int cx, int cy, int radius, int index, int count) {
        double angle = -Math.PI / 2.0 + 2.0 * Math.PI * index / Math.max(1, count);
        return new Point(
                (int) Math.round(cx + Math.cos(angle) * radius),
                (int) Math.round(cy + Math.sin(angle) * radius));
    }

    private String css() {
        return """
                :root {
                  color-scheme: light;
                  --text: #111827;
                  --muted: #64748b;
                  --line: #d7dde8;
                  --bg: #f7f9fc;
                  --card: #ffffff;
                  --blue: #2563eb;
                  --green: #2f9e44;
                  --amber: #d97706;
                  --red: #dc2626;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  font-family: Arial, Helvetica, sans-serif;
                  color: var(--text);
                  background: var(--bg);
                  line-height: 1.45;
                }
                .report-header {
                  display: flex;
                  justify-content: space-between;
                  gap: 24px;
                  align-items: center;
                  padding: 30px 42px;
                  background: #111827;
                  color: #ffffff;
                }
                .report-header h1 { margin: 0 0 6px; font-size: 30px; }
                .report-header p { margin: 0; color: #cbd5e1; }
                section {
                  margin: 22px auto;
                  max-width: 1240px;
                  padding: 24px;
                  background: var(--card);
                  border: 1px solid var(--line);
                  border-radius: 8px;
                }
                h2 { margin: 0 0 18px; font-size: 22px; }
                h3 { margin: 22px 0 12px; font-size: 16px; }
                .lead { max-width: 980px; color: #334155; }
                .assessment {
                  border-top: 5px solid #111827;
                }
                .assessment-head {
                  display: flex;
                  justify-content: space-between;
                  gap: 28px;
                  align-items: stretch;
                }
                .assessment-head h2 { margin-bottom: 10px; }
                .assessment-grid { margin-top: 10px; }
                .score-card {
                  min-width: 170px;
                  border-radius: 8px;
                  padding: 18px;
                  color: #ffffff;
                  text-align: center;
                  align-self: flex-start;
                }
                .score-card strong {
                  display: block;
                  font-size: 48px;
                  line-height: 1;
                }
                .score-card span,
                .score-card em {
                  display: block;
                  font-style: normal;
                  font-weight: 700;
                }
                .score-card span { margin-top: 4px; font-size: 13px; opacity: 0.88; }
                .score-card em { margin-top: 10px; font-size: 16px; }
                .assessment-list {
                  margin: 0;
                  padding-left: 20px;
                  color: #334155;
                }
                .assessment-list li { margin: 7px 0; }
                .cards {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
                  gap: 12px;
                  margin-bottom: 18px;
                }
                .card {
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  padding: 14px;
                  background: #fbfdff;
                }
                .card span { display: block; color: var(--muted); font-size: 12px; }
                .card strong { display: block; margin-top: 6px; font-size: 23px; }
                .band {
                  border-radius: 999px;
                  padding: 10px 18px;
                  font-weight: 700;
                  color: #ffffff;
                  white-space: nowrap;
                }
                .healthy { background: var(--green); }
                .watch { background: var(--amber); }
                .risk { background: #ea580c; }
                .critical { background: var(--red); }
                .grid.two {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
                  gap: 18px;
                  align-items: start;
                }
                figure { margin: 0; }
                img {
                  width: 100%;
                  height: auto;
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  background: #ffffff;
                }
                figcaption { margin-top: 7px; color: var(--muted); font-size: 12px; }
                .gallery {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
                  gap: 18px;
                  margin-top: 18px;
                }
                table {
                  width: 100%;
                  border-collapse: collapse;
                  font-size: 13px;
                }
                th, td {
                  border-bottom: 1px solid var(--line);
                  padding: 8px 10px;
                  text-align: left;
                  vertical-align: top;
                }
                th { color: #334155; background: #f8fafc; }
                .empty { color: var(--muted); }
                @media print {
                  body { background: #ffffff; }
                  section { break-inside: avoid; border: 1px solid #dddddd; }
                  .report-header { color: #111827; background: #ffffff; border-bottom: 2px solid #111827; }
                  .report-header p { color: #334155; }
                }
                @media (max-width: 720px) {
                  .report-header,
                  .assessment-head { display: block; }
                  .score-card { margin-top: 16px; }
                }
                """;
    }

    private static void writeText(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static String h(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String s(String value) {
        return h(value).replace("'", "&apos;");
    }

    private static String n(int value) {
        return String.format("%,d", value);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String simple(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        if (max <= 1) {
            return text.substring(0, max);
        }
        return text.substring(0, max - 1) + "...";
    }

    private static boolean isSvg(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".svg");
    }

    private static String scoreClass(int score) {
        if (score >= 85) return "healthy";
        if (score >= 70) return "watch";
        if (score >= 55) return "risk";
        return "critical";
    }

    private record Bar(String label, int value) {}

    private record Point(int x, int y) {}
}
