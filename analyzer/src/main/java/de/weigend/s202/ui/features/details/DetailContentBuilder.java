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
package de.weigend.s202.ui.features.details;

import de.weigend.s202.domain.ContainerProjection;
import de.weigend.s202.domain.CycleReturnPath;
import de.weigend.s202.domain.DependencyDetails;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.TypeUse;
import de.weigend.s202.ui.core.arrows.DependencySelection;
import de.weigend.s202.ui.features.details.DetailContent.Place;
import de.weigend.s202.ui.features.details.DetailContent.Row;
import de.weigend.s202.ui.features.details.DetailContent.Style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explains a selected dependency arrow: the calls and other references that
 * carry it, and the way back that makes it part of a cycle.
 */
final class DetailContentBuilder {

    private static final String NESTED = "One of the two classes is a nested class, or inherits from one of the other.";

    private static final String REFERENCE =
            "A place that names the class without calling it. It keeps the dependency\n"
                    + "alive even when every call is removed; a dependency of nothing else weighs 1.";

    private final DependencyModel rawModel;
    private final EdgeCycleClassifier cycles;
    private final Set<DependencyEdge> cuts;

    /** @param cuts the refactoring-preview cuts {@code cycles} already leaves out */
    DetailContentBuilder(DependencyModel rawModel, EdgeCycleClassifier cycles, Set<DependencyEdge> cuts) {
        this.rawModel = rawModel;
        this.cycles = cycles;
        this.cuts = cuts;
    }

    DetailContent build(DependencySelection selection) {
        String title = MethodLabels.simple(selection.from()) + " \u2192 " + MethodLabels.simple(selection.to());
        return selection.isClassDependency()
                ? classDependency(title, selection.edges().get(0))
                : aggregate(title, selection);
    }

    private DetailContent classDependency(String title, DependencyEdge edge) {
        boolean cut = cuts.contains(edge);
        List<String> summary = new ArrayList<>();
        if (isBackEdge(edge)) {
            summary.add("back-edge");
        }
        summary.add(cut ? "cut in the refactoring preview" : cycleLabel(cycles.cycleOf(edge.from(), edge.to())));
        summary.add("weight " + cycles.weightOf(edge.from(), edge.to()));

        List<Row> rows = new ArrayList<>(carriers(edge, true));
        if (!cut) {
            addReturnPath(rows, cycles.returnPath(edge.from(), edge.to()));
        }
        return new DetailContent(title, String.join(" \u00b7 ", summary), rows);
    }

    private DetailContent aggregate(String title, DependencySelection selection) {
        List<DependencyEdge> edges = selection.edges().stream()
                .sorted(Comparator.comparing((DependencyEdge e) -> !isBackEdge(e))
                        .thenComparing(Comparator.comparingInt(
                                (DependencyEdge e) -> cycles.weightOf(e.from(), e.to())).reversed())
                        .thenComparing(DependencyEdge::from)
                        .thenComparing(DependencyEdge::to))
                .toList();
        long backEdges = edges.stream().filter(this::isBackEdge).count();
        int weight = edges.stream().mapToInt(e -> cycles.weightOf(e.from(), e.to())).sum();

        List<String> summary = new ArrayList<>();
        summary.add(edges.size() == 1 ? "1 class dependency" : edges.size() + " class dependencies");
        if (backEdges > 0) {
            summary.add(backEdges == 1 ? "1 back-edge" : backEdges + " back-edges");
        }
        summary.add(cycleLabel(strongestCycle(edges)));
        summary.add("weight " + weight);

        List<Row> rows = new ArrayList<>();
        for (DependencyEdge edge : edges) {
            rows.add(edgeRow(edge, edges.size() == 1));
        }
        addReturnPath(rows, packageReturnPath(edges));
        return new DetailContent(title, String.join(" \u00b7 ", summary), rows);
    }

    private CycleKind strongestCycle(List<DependencyEdge> edges) {
        CycleKind strongest = CycleKind.NONE;
        for (DependencyEdge edge : edges) {
            CycleKind kind = cuts.contains(edge) ? CycleKind.NONE : cycles.cycleOf(edge.from(), edge.to());
            if (kind.compareTo(strongest) > 0) {
                strongest = kind;
            }
        }
        return strongest;
    }

    /** The way back of the first dependency that is on a package cycle; they usually share one. */
    private CycleReturnPath packageReturnPath(List<DependencyEdge> edges) {
        Set<String> asked = new HashSet<>();
        for (DependencyEdge edge : edges) {
            ContainerProjection.Projection p = ContainerProjection.project(edge.from(), edge.to());
            if (p == null || cuts.contains(edge) || !asked.add(p.key())) {
                continue;
            }
            CycleReturnPath path = cycles.packageReturnPath(edge.from(), edge.to());
            if (path.exists()) {
                return path;
            }
        }
        return CycleReturnPath.NONE;
    }

    private void addReturnPath(List<Row> rows, CycleReturnPath path) {
        if (!path.exists()) {
            return;
        }
        boolean direct = path.steps().size() == 1;
        List<Row> steps = new ArrayList<>();
        for (CycleReturnPath.Step step : path.steps()) {
            steps.add(path.packageLevel() ? siblingRow(step, direct) : edgeRow(step.edges().get(0), direct));
        }
        rows.add(Row.group(path.packageLevel() ? "Package cycle closes via" : "Cycle closes via", steps));
    }

    private Row siblingRow(CycleReturnPath.Step step, boolean expanded) {
        int weight = 0;
        boolean backEdge = false;
        List<Row> children = new ArrayList<>();
        for (DependencyEdge edge : step.edges()) {
            weight += cycles.weightOf(edge.from(), edge.to());
            backEdge |= isBackEdge(edge);
            children.add(edgeRow(edge, false));
        }
        return new Row(backEdge ? Style.BACK_EDGE : Style.EDGE,
                MethodLabels.simple(step.from()), MethodLabels.simple(step.to()), Integer.toString(weight),
                step.from() + " \u2192 " + step.to(), Place.of(step.from()), Place.of(step.to()), expanded, children);
    }

    private Row edgeRow(DependencyEdge edge, boolean expanded) {
        String to = MethodLabels.simple(edge.to()) + (cuts.contains(edge) ? "  (cut)" : "");
        return new Row(isBackEdge(edge) ? Style.BACK_EDGE : Style.EDGE,
                MethodLabels.simple(edge.from()), to, Integer.toString(cycles.weightOf(edge.from(), edge.to())),
                edge.from() + " \u2192 " + edge.to(), Place.of(edge.from()), Place.of(edge.to()),
                expanded, carriers(edge, false));
    }

    /** What makes up one class dependency: the calls, inheritance and imports, and the other references. */
    private List<Row> carriers(DependencyEdge edge, boolean expandReferences) {
        DependencyDetails details = DependencyDetails.of(rawModel, edge.from(), edge.to());
        String target = MethodLabels.simple(edge.to());
        List<Row> rows = new ArrayList<>();
        for (DependencyDetails.Call call : details.calls()) {
            rows.add(callRow(edge, call));
        }
        List<Row> references = new ArrayList<>();
        for (EdgeKind kind : details.otherKinds()) {
            (kind == EdgeKind.USES ? references : rows).add(new Row(Style.REFERENCE,
                    MethodLabels.simple(edge.from()), kind.label() + " " + target, "",
                    kind == EdgeKind.NESTED_INHERITANCE ? NESTED : null,
                    Place.of(edge.from()), Place.of(edge.to()), false, List.of()));
        }
        // One row per member; the uses come sorted, fields first.
        Map<Member, List<TypeUse>> byMember = new LinkedHashMap<>();
        for (TypeUse use : details.references()) {
            byMember.computeIfAbsent(new Member(use.member(), use.descriptor()), k -> new ArrayList<>()).add(use);
        }
        byMember.forEach((member, uses) -> references.add(referenceRow(edge, member, uses)));
        if (!references.isEmpty()) {
            rows.add(new Row(Style.GROUP, "Other references (" + references.size() + ")", "", "", REFERENCE,
                    null, null, expandReferences, references));
        }
        return rows;
    }

    /** A method, a field (no descriptor) or the class itself (no name; a nested class ends with a dot). */
    private record Member(String name, String descriptor) {

        boolean isMethod() {
            return descriptor != null;
        }

        String label(String classFqn) {
            if (isMethod()) {
                return MethodLabels.method(classFqn, name, descriptor);
            }
            if (name.isEmpty()) {
                return "class " + MethodLabels.simple(classFqn);
            }
            return name.endsWith(".")
                    ? "class " + MethodLabels.nested(name.substring(0, name.length() - 1))
                    : "field " + MethodLabels.nested(name);
        }
    }

    private static Row referenceRow(DependencyEdge edge, Member member, List<TypeUse> uses) {
        List<String> roles = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        for (TypeUse use : uses) {
            if (use.kind() == TypeUse.Kind.FIELD_ACCESS) {
                fields.add(use.detail());
            } else {
                roles.add(roleOf(use.kind()));
            }
        }
        String to = MethodLabels.simple(edge.to())
                + (roles.isEmpty() ? "" : " as " + String.join(", ", roles))
                + (fields.isEmpty() ? "" : " via field " + String.join(", ", fields));
        // The outline lists methods, not fields.
        Place fromPlace = member.isMethod() ? new Place(edge.from(), member.name(), member.descriptor())
                : Place.of(edge.from());
        return new Row(Style.REFERENCE, member.label(edge.from()), to, "", REFERENCE, fromPlace, Place.of(edge.to()), false, List.of());
    }

    private static String roleOf(TypeUse.Kind kind) {
        return switch (kind) {
            case FIELD -> "field type";
            case PARAMETER -> "parameter type";
            case RETURN -> "return type";
            case THROWS -> "thrown exception";
            case CATCH -> "caught exception";
            case TYPE_PARAMETER -> "type parameter bound";
            case TYPE_ARGUMENT -> "type argument of a supertype";
            case ANNOTATION -> "annotation";
            case CAST -> "cast target";
            case INSTANCEOF -> "instanceof target";
            case ARRAY -> "array type";
            case CLASS_LITERAL -> "class literal";
            case FIELD_ACCESS -> "accessed field";
            case NEW -> "new instance";
        };
    }

    private static Row callRow(DependencyEdge edge, DependencyDetails.Call call) {
        String declaring = call.declaringClass();
        String target = MethodLabels.method(edge.to(), call.member(), call.descriptor());
        if (call.descriptors().size() > 1) {
            target += "  (" + call.descriptors().size() + " overloads)";
        }
        if (declaring != null && !declaring.equals(edge.to())) {
            target += "  (inherited from " + MethodLabels.simple(declaring) + ")";
        }
        // A descriptor the model does not know would find nothing; the name may.
        String descriptor = declaring == null ? null : call.descriptor();
        return new Row(Style.CALL,
                MethodLabels.method(edge.from(), call.callerName(), call.callerDescriptor()), target,
                Integer.toString(call.count()), "Click a method to show it in the outline",
                new Place(edge.from(), call.callerName(), call.callerDescriptor()),
                new Place(edge.to(), call.member(), descriptor), false, List.of());
    }

    private boolean isBackEdge(DependencyEdge edge) {
        return cycles.isBackEdge(edge.from(), edge.to());
    }

    private static String cycleLabel(CycleKind kind) {
        return switch (kind) {
            case CLASS_CYCLE -> "class cycle";
            case PACKAGE_CYCLE -> "package cycle";
            case NONE -> "on no cycle";
        };
    }
}
