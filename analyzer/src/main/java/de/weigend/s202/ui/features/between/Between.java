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
package de.weigend.s202.ui.features.between;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.Externals;
import de.weigend.s202.ui.core.model.ArchitectureNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The dependencies among the classes of one view, asked from both ends: what
 * lies between two elements, what an element uses and what uses it. An element
 * is a class, or a package with everything in it.
 */
final class Between {

    static final Between NOTHING = new Between();

    /** The name of the branch with what lies outside the analysis. */
    static final String EXTERNAL = "(external)";

    private final Map<String, Set<String>> uses = new TreeMap<>();
    private final Map<String, Set<String>> usedBy = new HashMap<>();
    /** Every element of the view → the classes it is made of. */
    private final Map<String, Set<String>> classesOf = new HashMap<>();
    /** The packages and classes outside the analysis that the view uses; {@code null} for none. */
    private ArchitectureNode external;

    private Between() {
    }

    /** @param model the dependencies that count */
    static Between of(ArchitectureNode root, DomainModel model) {
        return of(root, model, Externals.NONE);
    }

    /**
     * @param model     the dependencies that count
     * @param externals what the classes use outside the analysis
     */
    static Between of(ArchitectureNode root, DomainModel model, Externals externals) {
        Between result = new Between();
        result.collect(root);
        Set<String> shown = result.classesOf.get(root.getFullName());
        result.addExternals(shown, externals);
        for (String from : shown) {
            DomainModel.CalculatedElementInfo info = model.getClass(from);
            if (info == null) {
                continue;
            }
            for (String to : info.dependencies) {
                if (shown.contains(to) && !to.equals(from)) {
                    result.uses.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
                    result.usedBy.computeIfAbsent(to, k -> new TreeSet<>()).add(from);
                }
            }
        }
        return result;
    }

    private void addExternals(Set<String> shown, Externals externals) {
        Map<String, Set<String>> used = new TreeMap<>();
        for (String from : shown) {
            for (String to : externals.usedBy(from).keySet()) {
                uses.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
                usedBy.computeIfAbsent(to, k -> new TreeSet<>()).add(from);
                used.computeIfAbsent(Externals.packageOf(to), k -> new TreeSet<>()).add(to);
            }
        }
        if (used.isEmpty()) {
            return;
        }
        external = new ArchitectureNode(EXTERNAL, "External", ArchitectureNode.NodeType.PACKAGE, false);
        used.forEach((pkg, classes) -> {
            ArchitectureNode pkgNode = new ArchitectureNode(pkg, pkg.isEmpty() ? "(default)" : pkg,
                    ArchitectureNode.NodeType.PACKAGE, false);
            for (String cls : classes) {
                pkgNode.addChild(new ArchitectureNode(cls, cls.substring(cls.lastIndexOf('.') + 1),
                        ArchitectureNode.NodeType.CLASS, false));
            }
            external.addChild(pkgNode);
        });
        collect(external);
    }

    /** Whether the element lies outside the analysis. */
    boolean isExternal(String element) {
        return external != null && element != null && (element.equals(EXTERNAL)
                || external.getChildren().stream().anyMatch(pkg -> element.equals(pkg.getFullName())
                        || element.startsWith(pkg.getFullName() + ".")));
    }

    /** What the view uses outside the analysis, by package; {@code null} for nothing. */
    ArchitectureNode external() {
        return external;
    }

    private Set<String> collect(ArchitectureNode node) {
        Set<String> classes = new TreeSet<>();
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            classes.add(node.getFullName());
        }
        for (ArchitectureNode child : node.getChildren()) {
            classes.addAll(collect(child));
        }
        classesOf.put(node.getFullName(), classes);
        return classes;
    }

    boolean knows(String element) {
        return classesOf.containsKey(element);
    }

    /**
     * The class dependencies from one element to another. {@code null} as
     * {@code from} or {@code to} means everything that is not the other one;
     * both {@code null} gives nothing.
     */
    List<DependencyEdge> edges(String from, String to) {
        List<DependencyEdge> result = new ArrayList<>();
        Set<String> sources = classesOf(from);
        Set<String> targets = classesOf(to);
        if (from == null && to == null) {
            return result;
        }
        for (Map.Entry<String, Set<String>> entry : uses.entrySet()) {
            String source = entry.getKey();
            if (from == null ? targets.contains(source) : !sources.contains(source)) {
                continue;
            }
            for (String target : entry.getValue()) {
                if (to == null ? !sources.contains(target) : targets.contains(target)) {
                    result.add(new DependencyEdge(source, target));
                }
            }
        }
        return result;
    }

    /** Everything the element uses, with the packages around it; everything there is for {@code null}. */
    Set<String> usedBy(String element) {
        return element == null ? classesOf.keySet() : neighbours(element, uses);
    }

    /** Everything that uses the element, with the packages around it; everything there is for {@code null}. */
    Set<String> usersOf(String element) {
        return element == null ? classesOf.keySet() : neighbours(element, usedBy);
    }

    private Set<String> neighbours(String element, Map<String, Set<String>> direction) {
        Set<String> classes = new HashSet<>();
        classesOf(element).forEach(cls -> classes.addAll(direction.getOrDefault(cls, Set.of())));
        Set<String> result = new HashSet<>(classes);
        classesOf.forEach((name, members) -> {
            if (members.stream().anyMatch(classes::contains)) {
                result.add(name);
            }
        });
        return result;
    }

    private Set<String> classesOf(String element) {
        return element == null ? Set.of() : classesOf.getOrDefault(element, Set.of());
    }
}
