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
package de.weigend.s202.ui.views.tangle;

import de.weigend.s202.domain.DependencyEdge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cycle bookkeeping for the tangle overlay: which edges are recommended cuts,
 * which cuts are already applied, and which edges still belong to an active
 * tangle once the applied cuts are removed from the graph (Tarjan SCC).
 */
final class TangleCycleModel {

    private Set<DependencyEdge> cycleBreakEdges = Set.of();
    private Set<DependencyEdge> appliedCutEdges = Set.of();
    private Set<DependencyEdge> activeTangleEdges = Set.of();

    void setCycleBreakEdges(Set<DependencyEdge> cycleBreakEdges) {
        this.cycleBreakEdges = cycleBreakEdges == null ? Set.of() : Set.copyOf(cycleBreakEdges);
    }

    void setAppliedCutEdges(Set<DependencyEdge> appliedCutEdges) {
        this.appliedCutEdges = appliedCutEdges == null ? Set.of() : Set.copyOf(appliedCutEdges);
    }

    boolean isCycleBreakEdge(DependencyEdge edge) {
        // cycleBreakEdges are always class-level; normalise method-level to() before lookup.
        return cycleBreakEdges.contains(new DependencyEdge(edge.from(), TangleLaneLayout.targetClassOf(edge.to())));
    }

    boolean isAppliedCutEdge(DependencyEdge edge) {
        // Accept an exact method-level cut OR a coarser class-level cut of the same pair.
        return appliedCutEdges.contains(edge)
                || appliedCutEdges.contains(new DependencyEdge(edge.from(), TangleLaneLayout.targetClassOf(edge.to())));
    }

    boolean isActiveTangleEdge(DependencyEdge edge) {
        return activeTangleEdges.contains(edge);
    }

    void recomputeActiveTangleEdges(List<DependencyEdge> edges) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (DependencyEdge edge : edges) {
            String toClass = TangleLaneLayout.targetClassOf(edge.to());
            graph.computeIfAbsent(edge.from(), k -> new java.util.HashSet<>());
            graph.computeIfAbsent(toClass, k -> new java.util.HashSet<>());
            if (!isAppliedCutEdge(edge)) {
                graph.get(edge.from()).add(toClass);
            }
        }

        List<Set<String>> activeComponents = stronglyConnectedComponents(graph).stream()
                .filter(component -> component.size() > 1)
                .toList();

        activeTangleEdges = edges.stream()
                .filter(edge -> !isAppliedCutEdge(edge))
                .filter(edge -> activeComponents.stream()
                        .anyMatch(component -> component.contains(edge.from())
                                && component.contains(TangleLaneLayout.targetClassOf(edge.to()))))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<Set<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
        List<Set<String>> components = new ArrayList<>();
        Map<String, Integer> indexByNode = new HashMap<>();
        Map<String, Integer> lowlinkByNode = new HashMap<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        int[] nextIndex = {0};

        for (String node : graph.keySet()) {
            if (!indexByNode.containsKey(node)) {
                strongConnect(node, graph, indexByNode, lowlinkByNode, stack, onStack, nextIndex, components);
            }
        }
        return components;
    }

    private static void strongConnect(String node,
                                      Map<String, Set<String>> graph,
                                      Map<String, Integer> indexByNode,
                                      Map<String, Integer> lowlinkByNode,
                                      java.util.Deque<String> stack,
                                      Set<String> onStack,
                                      int[] nextIndex,
                                      List<Set<String>> components) {
        indexByNode.put(node, nextIndex[0]);
        lowlinkByNode.put(node, nextIndex[0]);
        nextIndex[0]++;
        stack.push(node);
        onStack.add(node);

        for (String target : graph.getOrDefault(node, Set.of())) {
            if (!indexByNode.containsKey(target)) {
                strongConnect(target, graph, indexByNode, lowlinkByNode, stack, onStack, nextIndex, components);
                lowlinkByNode.put(node, Math.min(lowlinkByNode.get(node), lowlinkByNode.get(target)));
            } else if (onStack.contains(target)) {
                lowlinkByNode.put(node, Math.min(lowlinkByNode.get(node), indexByNode.get(target)));
            }
        }

        if (!lowlinkByNode.get(node).equals(indexByNode.get(node))) {
            return;
        }
        Set<String> component = new java.util.HashSet<>();
        String member;
        do {
            member = stack.pop();
            onStack.remove(member);
            component.add(member);
        } while (!node.equals(member));
        components.add(component);
    }
}
