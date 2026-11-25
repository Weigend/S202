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
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureInsightsProvider;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.ComponentMoves;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.report.text.RefactoringReportInput;
import de.weigend.s202.report.text.RefactoringReportInput.ElementMove;
import de.weigend.s202.report.text.RefactoringReportInput.ViewChanges;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** The refactoring report: the difference between the analyzed code and what the user made of it. */
final class RefactoringReportText {

    private final RefactoringReportInput input;
    private final DomainModel domain;
    private final ComponentArchitecture analyzed;
    /** The component architecture with the mapping and the moves of the user. */
    private final ComponentArchitecture refactored;
    private final Components analyzedComponents;
    private final Components mappedComponents;
    private final String topLevelPackage;

    RefactoringReportText(ArchitectureInsightsProvider insights, RefactoringReportInput input) {
        this.input = input;
        this.domain = input.domainModel();
        this.analyzed = insights.component(domain, ArchitectureAnnotations.empty(), input.rawModel());
        this.refactored = insights.component(
                domain, input.annotations(), input.rawModel(), componentMoves(input));
        this.analyzedComponents = new Components(analyzed);
        this.mappedComponents = new Components(refactored);
        this.topLevelPackage = topLevelPackage(domain);
    }

    String write() {
        TextReport report = new TextReport(
                "Refactoring Report", input.analysisName(), input.sourcePaths());
        report.line("");
        report.paragraph("The changes below were planned in S202 on the analyzed code. The code itself is "
                + "unchanged. Package names are the ones of the analyzed code.");
        int before = report.toString().length();
        writePlannedPackages(report);
        writeMoves(report);
        writeComponentMapping(report);
        writeViolationChanges(report);
        writeRolesAndPorts(report);
        writeCuts(report);
        if (report.toString().length() == before) {
            report.line("");
            report.line("No changes.");
        }
        return report.toString();
    }

    /* ----- moves ------------------------------------------------------------- */

    private void writePlannedPackages(TextReport report) {
        List<String> names = input.views().stream()
                .flatMap(view -> view.plannedPackages().values().stream())
                .sorted()
                .toList();
        if (names.isEmpty()) {
            return;
        }
        report.section("New packages");
        names.forEach(name -> report.line("- " + name));
    }

    private void writeMoves(TextReport report) {
        Map<String, List<String>> moved = new LinkedHashMap<>();
        Map<String, List<String>> reordered = new LinkedHashMap<>();
        for (ViewChanges view : input.views()) {
            for (ElementMove move : lastMoves(view)) {
                boolean stays = move.containerFqn().equals(originalContainer(move.elementFqn()));
                Map<String, List<String>> target = stays ? reordered : moved;
                target.computeIfAbsent(view.view(), k -> new ArrayList<>())
                        .add(stays ? reorderedText(move) : movedText(move, view));
            }
        }
        if (!moved.isEmpty()) {
            report.section("Moved to another package or component");
            writeByView(report, moved);
        }
        if (!reordered.isEmpty()) {
            report.section("Moved to another layer inside the same package");
            writeByView(report, reordered);
        }
    }

    private static void writeByView(TextReport report, Map<String, List<String>> byView) {
        byView.forEach((view, entries) -> {
            report.line("[" + view + "]");
            entries.forEach(report::line);
        });
    }

    /** The last position of every element; moves into a package that was deleted since are void. */
    private List<ElementMove> lastMoves(ViewChanges view) {
        Map<String, ElementMove> last = new LinkedHashMap<>();
        for (ElementMove move : view.moves()) {
            boolean known = move.containerFqn().isEmpty()
                    || domain.getPackage(move.containerFqn()) != null
                    || view.plannedPackages().containsKey(move.containerFqn());
            if (known) {
                last.put(move.elementFqn(), move);
            }
        }
        return last.values().stream().sorted(Comparator.comparing(ElementMove::elementFqn)).toList();
    }

    private String movedText(ElementMove move, ViewChanges view) {
        String planned = view.plannedPackages().get(move.containerFqn());
        String target;
        if (planned != null) {
            target = "new package " + planned;
        } else if (move.containerFqn().isEmpty()) {
            target = topLevelPackage.isEmpty() ? "top level" : topLevelPackage + " (top level)";
        } else {
            target = move.containerFqn();
        }
        return "- %s %s\n    from: %s%s\n    to:   %s%s".formatted(
                kind(move.elementFqn()), move.elementFqn(),
                Names.parentOf(move.elementFqn()), component(Names.parentOf(move.elementFqn()), false),
                target, planned != null
                        ? (move.api() ? " (API)" : "")
                        : component(move.containerFqn(), move.api()));
    }

    private String reorderedText(ElementMove move) {
        return "- %s %s: row %d from the top".formatted(
                kind(move.elementFqn()), move.elementFqn(), move.row() + 1);
    }

    private String component(String packageFqn, boolean api) {
        String root = mappedComponents.innermost(packageFqn);
        if (root == null) {
            return "";
        }
        return " (" + (api ? "API of " : "") + "component "
                + mappedComponents.byRoot().get(root).displayName() + ")";
    }

    private String kind(String fqn) {
        return domain.getClass(fqn) != null ? "class" : "package";
    }

    private String originalContainer(String fqn) {
        String parent = Names.parentOf(fqn);
        return parent.equals(topLevelPackage) ? "" : parent;
    }

    /** The last package of the name prefix that the views do not draw. */
    private static String topLevelPackage(DomainModel domain) {
        String current = "";
        while (true) {
            String parent = current;
            List<String> children = domain.getAllPackages().keySet().stream()
                    .filter(fqn -> Names.parentOf(fqn).equals(parent))
                    .toList();
            if (children.size() != 1) {
                return current;
            }
            current = children.get(0);
        }
    }

    /* ----- component mapping -------------------------------------------------- */

    private void writeComponentMapping(TextReport report) {
        ArchitectureAnnotations annotations = input.annotations();
        Set<String> includes = new TreeSet<>(annotations.componentApiIncludes());
        Set<String> excludes = new TreeSet<>(annotations.componentApiExcludes());
        if (includes.isEmpty() && excludes.isEmpty() && annotations.components().isEmpty()) {
            return;
        }
        report.section("Component mapping");
        report.paragraph("By convention the sub-packages api, spi, port and ports of a component are public and "
                + "everything else is private. The marks below override that.");
        report.line("");
        annotations.components().forEach(spec ->
                report.line("- declared as component " + spec.displayName() + ": " + spec.rootPackageFqn()));
        includes.forEach(fqn -> report.line("- add to API:      " + kind(fqn) + " " + fqn));
        excludes.forEach(fqn -> report.line("- remove from API: " + kind(fqn) + " " + fqn));

        report.line("");
        report.line("Effect:");
        Set<String> roots = new TreeSet<>(mappedComponents.byRoot().keySet());
        roots.addAll(analyzedComponents.byRoot().keySet());
        boolean effect = false;
        for (String root : roots) {
            effect |= writeEffect(report, root);
        }
        if (!effect) {
            report.line("- none");
        }
    }

    private void writeViolationChanges(TextReport report) {
        boolean moved = input.views().stream().anyMatch(view -> view.componentView() && !view.moves().isEmpty());
        if (!moved && input.annotations().equals(ArchitectureAnnotations.empty())) {
            return;
        }
        report.section("Component violations");
        report.paragraph("In the analyzed code -> after the moves in the component view and with the changed "
                + "component mapping.");
        report.line("");
        writeViolationChange(report, "access past the API of a component", ViolationKind.COMPONENT_API_BYPASS);
        writeViolationChange(report, "API that exposes the implementation",
                ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION);
        writeViolationDifference(report, "solved:", analyzed, refactored);
        writeViolationDifference(report, "new:", refactored, analyzed);
    }

    private static void writeViolationDifference(TextReport report,
                                                 String label,
                                                 ComponentArchitecture in,
                                                 ComponentArchitecture notIn) {
        Set<String> edges = edges(in);
        edges.removeAll(edges(notIn));
        if (!edges.isEmpty()) {
            report.line(label);
            edges.forEach(edge -> report.line("    " + edge));
        }
    }

    private static Set<String> edges(ComponentArchitecture architecture) {
        return architecture.violations().stream()
                .map(violation -> violation.sourceFqn() + " -> " + violation.targetFqn())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static ComponentMoves componentMoves(RefactoringReportInput input) {
        List<ComponentMoves.Move> moves = new ArrayList<>();
        Set<String> plannedPackages = new TreeSet<>();
        for (ViewChanges view : input.views()) {
            if (view.componentView()) {
                view.moves().forEach(move -> moves.add(
                        new ComponentMoves.Move(move.elementFqn(), move.containerFqn(), move.api())));
                plannedPackages.addAll(view.plannedPackages().keySet());
            }
        }
        return new ComponentMoves(moves, plannedPackages);
    }

    private boolean writeEffect(TextReport report, String root) {
        Set<String> nowPublic = new TreeSet<>(mappedComponents.publicClasses(root));
        nowPublic.removeAll(analyzedComponents.publicClasses(root));
        Set<String> nowPrivate = new TreeSet<>(analyzedComponents.publicClasses(root));
        nowPrivate.removeAll(mappedComponents.publicClasses(root));
        boolean created = !analyzedComponents.isRoot(root);
        if (nowPublic.isEmpty() && nowPrivate.isEmpty() && !created) {
            return false;
        }
        report.line("- " + (created ? "new component " : "component ") + root);
        writeClasses(report, "now public:  ", nowPublic);
        writeClasses(report, "now private: ", nowPrivate);
        return true;
    }

    private static void writeClasses(TextReport report, String label, Collection<String> classes) {
        classes.forEach(fqn -> report.line("    " + label + fqn));
    }

    private void writeViolationChange(TextReport report, String label, ViolationKind kind) {
        long before = analyzed.violations().stream().filter(v -> v.kind() == kind).count();
        long after = refactored.violations().stream().filter(v -> v.kind() == kind).count();
        report.line("- " + label + ": " + before + " -> " + after);
    }

    private void writeRolesAndPorts(TextReport report) {
        ArchitectureAnnotations annotations = input.annotations();
        if (annotations.roles().isEmpty() && annotations.ports().isEmpty()) {
            return;
        }
        report.section("Roles and ports (hexagonal view)");
        annotations.roles().stream()
                .sorted(Comparator.comparing(ArchitectureAnnotations.ElementRoleMark::elementFqn))
                .forEach(mark -> report.line("- role " + mark.role() + ": " + mark.elementFqn()));
        annotations.ports().stream()
                .sorted(Comparator.comparing(ArchitectureAnnotations.PortSpec::classFqn))
                .forEach(port -> report.line("- port " + port.direction() + ": " + port.classFqn()));
    }

    private void writeCuts(TextReport report) {
        if (input.cuts().isEmpty()) {
            return;
        }
        report.section("Dependencies to remove");
        input.cuts().stream()
                .sorted(Comparator.comparing(DependencyEdge::from).thenComparing(DependencyEdge::to))
                .forEach(edge -> report.line("- " + edge.from() + " -> " + edge.to()));
    }
}
