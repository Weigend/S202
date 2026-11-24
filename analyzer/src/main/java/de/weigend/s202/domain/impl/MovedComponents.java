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
package de.weigend.s202.domain.impl;

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ComponentMoves;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The component structure after the what-if moves: every element gets the name
 * it would have where the user put it, and the component rule is applied to
 * those names. Whether a moved element is public is what the user said by
 * dropping it into the API area of another component or not; inside its own
 * component it keeps the side it was on.
 */
final class MovedComponents {

    private final Map<String, ComponentMoves.Move> lastMoves = new LinkedHashMap<>();
    private final Map<String, String> movedNames = new HashMap<>();
    private final Set<String> takenNames = new HashSet<>();
    private final String topLevel;
    private final Set<String> plannedPackages;
    private final ComponentStructure structure;

    /**
     * @param analyzed the structure without moves
     * @param topLevel the package whose children the view shows at top level
     */
    MovedComponents(ComponentStructure analyzed,
                    Set<String> packages,
                    Set<String> classes,
                    String topLevel,
                    ArchitectureAnnotations annotations,
                    ComponentStructure.Declarations declarations,
                    ComponentMoves moves) {
        this.topLevel = topLevel;
        this.plannedPackages = moves.plannedPackages();
        takenNames.addAll(packages);
        takenNames.addAll(classes);
        Set<String> leftHome = new HashSet<>();
        for (ComponentMoves.Move move : moves.moves()) {
            String container = move.containerFqn();
            boolean known = container.isEmpty() || packages.contains(container)
                    || plannedPackages.contains(container);
            if (known && (packages.contains(move.elementFqn()) || classes.contains(move.elementFqn()))) {
                lastMoves.put(move.elementFqn(), move);
                if (!Objects.equals(home(analyzed, move.elementFqn()), componentAt(analyzed, container))) {
                    leftHome.add(move.elementFqn());
                }
            }
        }

        Set<String> movedPackages = new HashSet<>(packages);
        packages.forEach(fqn -> movedPackages.add(nameOf(fqn)));
        movedPackages.addAll(plannedPackages);
        ComponentStructure moved = ComponentStructure.of(
                movedPackages, topLevel,
                rename(annotations),
                new ComponentStructure.Declarations(
                        rename(declarations.plain()),
                        rename(declarations.components()),
                        rename(declarations.apiPackages()),
                        rename(declarations.exported())));

        Map<String, Boolean> marks = new HashMap<>();
        for (ComponentMoves.Move move : lastMoves.values()) {
            String fqn = move.elementFqn();
            boolean atHome = Objects.equals(home(analyzed, fqn), componentAt(analyzed, move.containerFqn()));
            boolean marked = annotations.componentApiIncludes().contains(fqn)
                    || annotations.componentApiExcludes().contains(fqn);
            if (!atHome || (leftHome.contains(fqn) && !marked)) {
                marks.put(nameOf(fqn), move.api());
            } else if (classes.contains(fqn) && analyzed.componentOf(fqn) != null) {
                marks.put(nameOf(fqn), analyzed.isInContract(analyzed.componentOf(fqn), fqn));
            }
        }
        this.structure = moved.withMarks(marks);
    }

    ComponentStructure structure() {
        return structure;
    }

    /** The name an element has after the moves. */
    String nameOf(String fqn) {
        String known = movedNames.get(fqn);
        if (known != null) {
            return known;
        }
        // Until it is known: its own name. Guards against an element moved into itself.
        movedNames.put(fqn, fqn);
        ComponentMoves.Move move = lastMoves.get(fqn);
        String parent = parentOf(fqn);
        String newParent = move == null
                ? (parent.isEmpty() ? "" : nameOf(parent))
                : move.containerFqn().isEmpty() ? topLevel : nameOf(move.containerFqn());
        String name = fqn;
        if (!newParent.equals(parent)) {
            name = newParent.isEmpty() ? simpleName(fqn) : newParent + "." + simpleName(fqn);
            while (!takenNames.add(name)) {
                name += "~";
            }
        }
        movedNames.put(fqn, name);
        return name;
    }

    private Set<String> rename(Set<String> names) {
        return names.stream().map(this::nameOf).collect(Collectors.toSet());
    }

    private ArchitectureAnnotations rename(ArchitectureAnnotations annotations) {
        List<ArchitectureAnnotations.ComponentSpec> specs = annotations.components().stream()
                .map(spec -> new ArchitectureAnnotations.ComponentSpec(
                        spec.id(), spec.displayName(), nameOf(spec.rootPackageFqn())))
                .collect(Collectors.toCollection(ArrayList::new));
        plannedPackages.forEach(id -> specs.add(new ArchitectureAnnotations.ComponentSpec(id, id, id)));
        return new ArchitectureAnnotations(
                specs,
                rename(annotations.componentApiIncludes()),
                rename(annotations.componentApiExcludes()),
                annotations.ports(),
                annotations.roles());
    }

    /** Root of the component around an element, {@code null} if there is none. */
    private static String home(ComponentStructure analyzed, String fqn) {
        ComponentStructure.Component component = analyzed.componentOf(fqn);
        return component == null ? null : component.rootFqn();
    }

    /** Root of the component whose box a container is or lies in; a planned package is one itself. */
    private String componentAt(ComponentStructure analyzed, String container) {
        boolean component = analyzed.componentWithRoot(container) != null || plannedPackages.contains(container);
        return component ? container : home(analyzed, container);
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
