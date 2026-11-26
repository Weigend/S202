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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.domain.ContainerProjection;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What an element reaches over the class dependencies, step by step: the
 * classes that use it, directly or through others, or the classes it uses.
 * An element is a class, or a package or component with every class in it.
 *
 * <p>The way leads through the whole analysis, also through classes the view
 * does not show: a view of one scope still learns who uses it from outside.
 * Only what the view shows can be marked, though; the rest is counted.
 */
final class Trace {

    /** Which way the dependencies are followed. */
    enum Direction {
        /** Against the dependencies: who uses the element. */
        USERS,
        /** Along the dependencies: what the element uses. */
        DEPENDENCIES
    }

    /** As many steps as there are. */
    static final int ALL = Integer.MAX_VALUE;

    private final String start;
    private final Direction direction;
    /** Every class reached → the number of steps it takes. */
    private final Map<String, Integer> steps;
    /** Every element of the view → the classes it is made of. */
    private final Map<String, Set<String>> classesOf;
    private final String rootName;
    private final int depth;

    private Trace(String start, Direction direction, Map<String, Integer> steps,
                  Map<String, Set<String>> classesOf, String rootName) {
        this.start = start;
        this.direction = direction;
        this.steps = steps;
        this.classesOf = classesOf;
        this.rootName = rootName;
        this.depth = steps.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * @param root      what the view shows
     * @param model     the dependencies that count
     * @param start     a class or package of the view
     * @param direction which way to go
     * @return {@code null} when the view does not show {@code start}
     */
    static Trace of(ArchitectureNode root, DomainModel model, String start, Direction direction) {
        Map<String, Set<String>> classesOf = new HashMap<>();
        collect(root, classesOf);
        Set<String> seeds = classesOf.get(start);
        if (seeds == null || start.equals(root.getFullName())) {
            return null;
        }
        Map<String, Set<String>> next = direction == Direction.DEPENDENCIES ? uses(model) : usedBy(model);
        Map<String, Integer> steps = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>(seeds);
        Map<String, Integer> distance = new HashMap<>();
        seeds.forEach(seed -> distance.put(seed, 0));
        while (!queue.isEmpty()) {
            String cls = queue.poll();
            int step = distance.get(cls) + 1;
            for (String neighbour : next.getOrDefault(cls, Set.of())) {
                if (!distance.containsKey(neighbour)) {
                    distance.put(neighbour, step);
                    steps.put(neighbour, step);
                    queue.add(neighbour);
                }
            }
        }
        return new Trace(start, direction, steps, classesOf, root.getFullName());
    }

    private static Set<String> collect(ArchitectureNode node, Map<String, Set<String>> classesOf) {
        Set<String> classes = new HashSet<>();
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            classes.add(node.getFullName());
        }
        for (ArchitectureNode child : node.getChildren()) {
            classes.addAll(collect(child, classesOf));
        }
        classesOf.put(node.getFullName(), classes);
        return classes;
    }

    private static Map<String, Set<String>> uses(DomainModel model) {
        Map<String, Set<String>> uses = new HashMap<>();
        model.getAllClasses().forEach((from, info) -> {
            for (String to : info.dependencies) {
                if (!to.equals(from) && model.getClass(to) != null) {
                    uses.computeIfAbsent(from, k -> new HashSet<>()).add(to);
                }
            }
        });
        return uses;
    }

    private static Map<String, Set<String>> usedBy(DomainModel model) {
        Map<String, Set<String>> usedBy = new HashMap<>();
        uses(model).forEach((from, targets) ->
                targets.forEach(to -> usedBy.computeIfAbsent(to, k -> new HashSet<>()).add(from)));
        return usedBy;
    }

    String start() {
        return start;
    }

    Direction direction() {
        return direction;
    }

    /** The most steps it takes to reach a class; 0 when nothing is reached. */
    int depth() {
        return depth;
    }

    /** The classes reached in at most {@code maxSteps} steps, in the view or not. */
    Set<String> classes(int maxSteps) {
        Set<String> result = new HashSet<>();
        steps.forEach((cls, step) -> {
            if (step <= maxSteps) {
                result.add(cls);
            }
        });
        return result;
    }

    /** The number of steps to a class; {@code -1} when it is not reached. */
    int stepsTo(String cls) {
        return steps.getOrDefault(cls, -1);
    }

    /**
     * What the view marks: the classes reached in at most {@code maxSteps}
     * steps and every package or component around one of them.
     */
    Set<String> marked(int maxSteps) {
        Set<String> classes = classes(maxSteps);
        Set<String> result = new HashSet<>();
        classesOf.forEach((element, members) -> {
            if (!element.equals(rootName) && !Collections.disjoint(members, classes)) {
                result.add(element);
            }
        });
        return result;
    }

    /** How many of the classes reached in at most {@code maxSteps} steps the view does not show. */
    int outsideView(int maxSteps) {
        Set<String> shown = classesOf.getOrDefault(rootName, Set.of());
        return (int) classes(maxSteps).stream().filter(cls -> !shown.contains(cls)).count();
    }

    /** The number of packages the classes reached in at most {@code maxSteps} steps lie in. */
    int packages(int maxSteps) {
        Set<String> packages = new HashSet<>();
        classes(maxSteps).forEach(cls -> packages.add(ContainerProjection.parentOf(cls)));
        return packages.size();
    }
}
