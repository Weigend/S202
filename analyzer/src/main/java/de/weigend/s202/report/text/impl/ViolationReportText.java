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
package de.weigend.s202.report.text.impl;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.architecture.ArchitectureInsightsProvider;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.report.text.ViolationReportInput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** The violation report: every finding with the rule it breaks and the code that breaks it. */
final class ViolationReportText {

    private final ArchitectureInsightsProvider insights;
    private final ViolationReportInput input;
    private final DomainModel domain;

    ViolationReportText(ArchitectureInsightsProvider insights, ViolationReportInput input) {
        this.insights = insights;
        this.input = input;
        this.domain = input.domainModel();
    }

    String write() {
        ComponentArchitecture components =
                insights.component(domain, input.annotations(), input.rawModel());
        List<Violation> bypasses = ofKind(components.violations(), ViolationKind.COMPONENT_API_BYPASS);
        List<Violation> leaks = ofKind(components.violations(), ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION);
        List<Violation> upward = ofKind(insights.layered(domain).violations(), ViolationKind.UPWARD);
        List<Set<String>> packageCycles = domain.getPackageTangles();
        List<StronglyConnectedComponent> classCycles = insights.classSccs(domain).stream()
                .filter(StronglyConnectedComponent::isTangle)
                .sorted(Comparator.comparingInt(StronglyConnectedComponent::getSize).reversed())
                .toList();

        TextReport report = new TextReport(
                "Architecture Violations", input.analysisName(), input.sourcePaths());
        report.head("Found", String.join(", ",
                bypasses.size() + " past a component API",
                leaks.size() + " API leaks",
                upward.size() + " against the layers",
                TextReport.count(packageCycles.size(), "package cycle", "package cycles"),
                TextReport.count(classCycles.size(), "class cycle", "class cycles")));
        report.line("");
        report.paragraph("Found in the analyzed code by static analysis. A dependency is written as "
                + "source class -> target class, followed by how the source uses the target. Moves made in "
                + "S202 are not applied; a dependency the user already plans to remove is marked "
                + "[planned to be removed].");

        writeBypasses(report, bypasses, new Components(components));
        writeLeaks(report, leaks);
        writePackageCycles(report, packageCycles);
        writeClassCycles(report, classCycles);
        writeUpward(report, upward);
        if (bypasses.isEmpty() && leaks.isEmpty() && upward.isEmpty()
                && packageCycles.isEmpty() && classCycles.isEmpty()) {
            report.line("");
            report.line("No violations.");
        }
        return report.toString();
    }

    private void writeBypasses(TextReport report, List<Violation> bypasses, Components components) {
        if (bypasses.isEmpty()) {
            return;
        }
        report.section("Access past the API of a component");
        report.paragraph("Rule: code outside of a component uses its public classes only, i.e. the ones in "
                + "its sub-packages api and spi or marked as API. Everything else is private. To fix one, use "
                + "the API of the component, add what is missing there, or move one of the classes.");
        Map<String, List<Violation>> byComponent = new TreeMap<>();
        for (Violation violation : bypasses) {
            String boundary = components.boundaryBetween(violation.sourceFqn(), violation.targetFqn());
            byComponent.computeIfAbsent(boundary == null ? "" : boundary, k -> new ArrayList<>()).add(violation);
        }
        byComponent.forEach((root, violations) -> {
            report.line("");
            report.line("Component " + root);
            Set<String> apiPackages = components.publicClasses(root).stream()
                    .map(Names::parentOf)
                    .collect(Collectors.toCollection(TreeSet::new));
            report.line("  public: " + (apiPackages.isEmpty() ? "nothing" : String.join(", ", apiPackages)));
            writeEdges(report, violations);
        });
    }

    private void writeLeaks(TextReport report, List<Violation> leaks) {
        if (leaks.isEmpty()) {
            return;
        }
        report.section("API that exposes the implementation");
        report.paragraph("Rule: a public class does not depend on a private one. Otherwise whoever uses the "
                + "API depends on the implementation as well.");
        report.line("");
        writeEdges(report, leaks);
    }

    private void writePackageCycles(TextReport report, List<Set<String>> cycles) {
        if (cycles.isEmpty()) {
            return;
        }
        report.section("Package cycles");
        report.paragraph("Rule: packages do not depend on each other in a circle. The dependencies listed "
                + "below run against the main direction of the cycle; removing them breaks it.");
        for (Set<String> cycle : cycles) {
            report.line("");
            report.line("Cycle of " + cycle.size() + ": " + String.join(", ", new TreeSet<>(cycle)));
            cycleEdges(cycle).forEach(edge -> report.line(edgeText(edge.from(), edge.to())));
        }
    }

    /** Class dependencies between members of a cycle that run along a back edge of their container. */
    private List<DependencyEdge> cycleEdges(Set<String> cycle) {
        List<DependencyEdge> edges = new ArrayList<>();
        for (DomainModel.CalculatedElementInfo source : domain.getAllClasses().values()) {
            String sourceMember = member(cycle, source.fullName);
            if (sourceMember == null) {
                continue;
            }
            for (String target : source.dependencies) {
                String targetMember = domain.getClass(target) == null ? null : member(cycle, target);
                if (targetMember != null && !targetMember.equals(sourceMember)
                        && domain.isContainerBackEdge(sourceMember, targetMember)) {
                    edges.add(new DependencyEdge(source.fullName, target));
                }
            }
        }
        edges.sort(Comparator.comparing(DependencyEdge::from).thenComparing(DependencyEdge::to));
        return edges;
    }

    private static String member(Set<String> cycle, String fqn) {
        for (String current = fqn; !current.isEmpty(); current = Names.parentOf(current)) {
            if (cycle.contains(current)) {
                return current;
            }
        }
        return null;
    }

    private void writeClassCycles(TextReport report, List<StronglyConnectedComponent> cycles) {
        if (cycles.isEmpty()) {
            return;
        }
        report.section("Class cycles");
        report.paragraph("Classes that depend on each other in a circle. Harmless inside one package, a "
                + "problem when the circle crosses packages.");
        for (StronglyConnectedComponent cycle : cycles) {
            Set<String> members = new TreeSet<>(cycle.getMembers());
            long packages = members.stream().map(Names::parentOf).distinct().count();
            report.line("- " + (packages == 1 ? "inside one package" : "across " + packages + " packages")
                    + ": " + String.join(", ", members));
        }
    }

    private void writeUpward(TextReport report, List<Violation> upward) {
        if (upward.isEmpty()) {
            return;
        }
        report.section("Dependencies against the layers");
        report.paragraph("Rule: dependencies run from the upper layer to the lower one. These run upwards, "
                + "most of them as part of a cycle above.");
        Map<String, List<Violation>> byPackages = new TreeMap<>();
        for (Violation violation : upward) {
            String from = Names.parentOf(violation.sourceFqn());
            String to = Names.parentOf(violation.targetFqn());
            byPackages.computeIfAbsent(from.equals(to) ? "inside " + from : from + " -> " + to,
                    k -> new ArrayList<>()).add(violation);
        }
        byPackages.forEach((packages, violations) -> {
            report.line("");
            report.line(packages);
            writeEdges(report, violations);
        });
    }

    private void writeEdges(TextReport report, List<Violation> violations) {
        violations.stream()
                .sorted(Comparator.comparing(Violation::sourceFqn).thenComparing(Violation::targetFqn))
                .forEach(v -> report.line(edgeText(v.sourceFqn(), v.targetFqn())));
    }

    private String edgeText(String source, String target) {
        String usage = usage(source, target);
        return "- " + source + " -> " + target
                + (usage.isEmpty() ? "" : " (" + usage + ")")
                + (input.plannedCuts().contains(new DependencyEdge(source, target))
                        ? " [planned to be removed]" : "");
    }

    /** How the source uses the target: the kinds of relation and the methods it calls. */
    private String usage(String source, String target) {
        DependencyModel.ClassInfo info = input.rawModel().getClass(source);
        if (info == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        Set<String> methods = calledMethods(info, target);
        Set<EdgeKind> kinds = info.dependencyKinds.get(target);
        if (kinds != null) {
            for (EdgeKind kind : kinds) {
                boolean calls = kind == EdgeKind.CALLS;
                parts.add(calls && !methods.isEmpty() ? "calls " + String.join(", ", methods) : kind.label());
            }
        }
        return String.join("; ", parts);
    }

    private static Set<String> calledMethods(DependencyModel.ClassInfo info, String target) {
        Set<String> methods = new TreeSet<>();
        for (DependencyModel.MethodInfo method : info.methods.values()) {
            for (String call : method.methodCalls.keySet()) {
                int dot = call.lastIndexOf('.');
                if (dot > 0 && call.substring(0, dot).equals(target) && !call.endsWith(".<init>")) {
                    methods.add(call.substring(dot + 1));
                }
            }
        }
        return methods;
    }

    private static List<Violation> ofKind(List<Violation> violations, ViolationKind kind) {
        return violations.stream().filter(v -> v.kind() == kind).toList();
    }
}
