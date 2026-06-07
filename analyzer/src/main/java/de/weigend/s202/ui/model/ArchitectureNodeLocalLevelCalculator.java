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
package de.weigend.s202.ui.model;

import io.softwareecg.wfx.lookup.api.Lookup;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recalculates local levels for a projected {@link ArchitectureNode} subtree
 * without changing the analyzed domain model.
 */
public final class ArchitectureNodeLocalLevelCalculator {

    public void assign(ArchitectureNode root, DependencyModel rawModel) {
        Objects.requireNonNull(root, "root cannot be null");

        Map<String, ArchitectureNode> nodes = new HashMap<>();
        index(root, nodes);
        Map<String, List<ArchitectureNode>> childrenByParent = new HashMap<>();
        for (ArchitectureNode node : nodes.values()) {
            childrenByParent.computeIfAbsent(parentOf(node.getFullName()), ignored -> new ArrayList<>()).add(node);
        }
        for (List<ArchitectureNode> siblings : childrenByParent.values()) {
            assignForParent(siblings, nodes);
        }
    }

    private static void assignForParent(List<ArchitectureNode> siblings,
                                        Map<String, ArchitectureNode> allNodes) {
        if (siblings.size() <= 1) {
            return;
        }
        Set<String> siblingFqns = new LinkedHashSet<>();
        for (ArchitectureNode sibling : siblings) {
            siblingFqns.add(sibling.getFullName());
        }
        Map<String, Set<String>> graph = new HashMap<>();
        for (String sibling : siblingFqns) {
            graph.put(sibling, new HashSet<>());
        }

        for (ArchitectureNode node : allNodes.values()) {
            if (node.getType() != ArchitectureNode.NodeType.CLASS) {
                continue;
            }
            String fromSibling = containingSibling(node.getFullName(), siblingFqns);
            if (fromSibling == null) {
                continue;
            }
            for (String dep : node.getDependencies()) {
                if (!allNodes.containsKey(dep)) {
                    continue;
                }
                String toSibling = containingSibling(dep, siblingFqns);
                if (toSibling != null && !toSibling.equals(fromSibling)) {
                    graph.get(fromSibling).add(toSibling);
                }
            }
        }

        Map<String, Integer> layers = computeLayers(graph, siblingFqns);
        for (ArchitectureNode sibling : siblings) {
            sibling.setLevel(layers.getOrDefault(sibling.getFullName(), 0));
        }
    }

    private static void index(ArchitectureNode node, Map<String, ArchitectureNode> nodes) {
        nodes.put(node.getFullName(), node);
        for (ArchitectureNode child : node.getChildren()) {
            index(child, nodes);
        }
    }

    private static Map<String, Integer> computeLayers(Map<String, Set<String>> graph, Set<String> nodes) {
        breakCycles(graph);

        Map<String, Integer> levels = new HashMap<>();
        for (String node : nodes) {
            levels.put(node, 0);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            List<String> sortedNodes = new ArrayList<>(nodes);
            Collections.sort(sortedNodes);
            for (String node : sortedNodes) {
                int maxDep = -1;
                List<String> deps = new ArrayList<>(graph.getOrDefault(node, Set.of()));
                Collections.sort(deps);
                for (String dep : deps) {
                    maxDep = Math.max(maxDep, levels.getOrDefault(dep, 0));
                }
                int newLevel = maxDep >= 0 ? maxDep + 1 : 0;
                if (!levels.get(node).equals(newLevel)) {
                    levels.put(node, newLevel);
                    changed = true;
                }
            }
        }
        return levels;
    }

    private static void breakCycles(Map<String, Set<String>> graph) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StronglyConnectedComponent scc : Lookup.lookup(SCCFinder.class).findSCCs(graph)) {
                if (scc.getSize() < 2) {
                    continue;
                }
                for (String member : scc.getMembers()) {
                    Set<String> edges = graph.get(member);
                    if (edges != null) {
                        edges.removeIf(scc.getMembers()::contains);
                    }
                }
                changed = true;
                break;
            }
        }
    }

    private static String containingSibling(String fqn, Set<String> siblingFqns) {
        String current = fqn;
        while (current != null && !current.isEmpty()) {
            if (siblingFqns.contains(current)) {
                return current;
            }
            int dot = current.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            current = current.substring(0, dot);
        }
        return null;
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }
}
