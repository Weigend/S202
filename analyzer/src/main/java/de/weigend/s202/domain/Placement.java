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
package de.weigend.s202.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where every class and package sits: in the container its name says, or where
 * a planned move put it. The names stay those of the code, so an element is
 * found by the same name with and without the plan.
 */
public final class Placement {

    /** Everything sits where its name says. */
    public static final Placement OF_THE_CODE = new Placement(Map.of());

    private final Map<String, String> moved;

    private Placement(Map<String, String> moved) {
        this.moved = moved;
    }

    /** @param moved moved element → the container it was put into, "" for the root */
    public static Placement of(Map<String, String> moved) {
        return moved == null || moved.isEmpty() ? OF_THE_CODE : new Placement(Map.copyOf(moved));
    }

    public boolean isOfTheCode() {
        return moved.isEmpty();
    }

    /** The container {@code fqn} sits in, "" for the root. */
    public String containerOf(String fqn) {
        String container = moved.get(fqn);
        return container != null ? container : ContainerProjection.parentOf(fqn);
    }

    /** The containers around {@code fqn}, outermost first, without the root. */
    public List<String> containersOf(String fqn) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // An element moved into itself would lead back to itself.
        for (String container = containerOf(fqn);
             !container.isEmpty() && seen.add(container);
             container = containerOf(container)) {
            result.add(container);
        }
        Collections.reverse(result);
        return result;
    }

    /** Whether {@code fqn} is {@code container} or sits somewhere in it; the root "" holds everything. */
    public boolean isIn(String fqn, String container) {
        if (container == null || container.isEmpty() || container.equals(fqn)) {
            return true;
        }
        if (isOfTheCode()) {
            return fqn.startsWith(container + ".");
        }
        return containersOf(fqn).contains(container);
    }

    /**
     * The class dependency as a dependency between two members of one
     * container, see {@link ContainerProjection}.
     *
     * @return {@code null} when both classes are in the same member
     */
    public ContainerProjection.Projection project(String fromClass, String toClass) {
        if (isOfTheCode()) {
            return ContainerProjection.project(fromClass, toClass);
        }
        List<String> from = containersOf(fromClass);
        List<String> to = containersOf(toClass);
        int common = 0;
        while (common < from.size() && common < to.size() && from.get(common).equals(to.get(common))) {
            common++;
        }
        String fromMember = common < from.size() ? from.get(common) : fromClass;
        String toMember = common < to.size() ? to.get(common) : toClass;
        if (fromMember.equals(toMember)) {
            return null;
        }
        return new ContainerProjection.Projection(common == 0 ? "" : from.get(common - 1), fromMember, toMember);
    }

    /**
     * Container tangles of a class graph: per container, the SCCs of size ≥ 2 in
     * the projected sibling graph. Deterministic: containers, siblings and
     * members are visited in sorted order.
     *
     * @param classGraph class fqn → dependencies; targets missing from the key set are ignored
     */
    public List<Set<String>> tangles(Map<String, Set<String>> classGraph, SCCFinder finder) {
        Map<String, Map<String, Set<String>>> siblingGraphs = new TreeMap<>();
        for (String from : new TreeSet<>(classGraph.keySet())) {
            for (String to : new TreeSet<>(classGraph.get(from))) {
                if (!classGraph.containsKey(to)) {
                    continue;
                }
                ContainerProjection.Projection p = project(from, to);
                if (p == null) {
                    continue;
                }
                Map<String, Set<String>> graph = siblingGraphs.computeIfAbsent(p.container(), k -> new TreeMap<>());
                graph.computeIfAbsent(p.fromSibling(), k -> new TreeSet<>()).add(p.toSibling());
                graph.computeIfAbsent(p.toSibling(), k -> new TreeSet<>());
            }
        }
        List<Set<String>> result = new ArrayList<>();
        for (Map<String, Set<String>> graph : siblingGraphs.values()) {
            for (StronglyConnectedComponent scc : finder.findSCCs(new LinkedHashMap<>(graph))) {
                if (scc.getSize() >= 2) {
                    result.add(new TreeSet<>(scc.getMembers()));
                }
            }
        }
        return result;
    }

    /** The one of {@code members} that is {@code fqn} or holds it; {@code null} if none does. */
    public String memberContaining(Set<String> members, String fqn) {
        if (members.contains(fqn)) {
            return fqn;
        }
        List<String> containers = containersOf(fqn);
        for (int i = containers.size() - 1; i >= 0; i--) {
            if (members.contains(containers.get(i))) {
                return containers.get(i);
            }
        }
        return null;
    }

    /**
     * The package whose content the views show at top level: from the root
     * down as long as there is a single package. A move to the top level goes
     * here.
     */
    public static String topLevel(Set<String> packages) {
        Map<String, List<String>> children = new HashMap<>();
        for (String pkg : packages) {
            children.computeIfAbsent(ContainerProjection.parentOf(pkg), k -> new ArrayList<>()).add(pkg);
        }
        String current = "";
        while (children.getOrDefault(current, List.of()).size() == 1) {
            current = children.get(current).get(0);
        }
        return current;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Placement placement && moved.equals(placement.moved);
    }

    @Override
    public int hashCode() {
        return Objects.hash(moved);
    }
}
