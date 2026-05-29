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
package de.weigend.s202.graph;

import java.util.*;

/**
 * Computes strongly connected components using Tarjan's algorithm.
 * Tarjan's algorithm runs in O(V + E) time where V is the number of vertices
 * and E is the number of edges.
 * 
 * The algorithm identifies all cycles in a directed graph by finding SCCs.
 */
public class TarjanSCCFinder {
    private final Map<String, Set<String>> graph; // node -> dependencies
    private int index = 0;
    private final Stack<String> stack = new Stack<>();
    private final Map<String, Integer> nodeIndex;
    private final Map<String, Integer> nodeLowLink;
    private final Map<String, Boolean> onStack;
    private final List<StronglyConnectedComponent> sccs = new ArrayList<>();
    private int sccCounter = 0;

    public TarjanSCCFinder(Map<String, Set<String>> graph) {
        this.graph = graph;
        int n = graph.size();
        int capacity = (int)(n / 0.75f) + 1;
        nodeIndex   = new HashMap<>(capacity);
        nodeLowLink = new HashMap<>(capacity);
        onStack     = new HashMap<>(capacity);
        for (String node : graph.keySet()) {
            nodeIndex.put(node, -1);
            onStack.put(node, false);
        }
    }
    
    /**
     * Finds all strongly connected components in the graph.
     */
    public List<StronglyConnectedComponent> findSCCs() {
        for (String node : graph.keySet()) {
            if (nodeIndex.get(node) == -1) {
                strongconnect(node);
            }
        }
        return sccs;
    }
    
    /**
     * Tarjan's strongconnect algorithm.
     */
    private void strongconnect(String node) {
        // Set the depth index for node to the smallest unused index
        nodeIndex.put(node, index);
        nodeLowLink.put(node, index);
        index++;
        stack.push(node);
        onStack.put(node, true);
        
        // Consider successors (dependencies) of node
        Set<String> dependencies = graph.getOrDefault(node, new HashSet<>());
        for (String dep : dependencies) {
            if (!graph.containsKey(dep)) {
                // External dependency, skip
                continue;
            }
            
            if (nodeIndex.get(dep) == -1) {
                // Successor dep has not yet been visited
                strongconnect(dep);
                nodeLowLink.put(node, Math.min(nodeLowLink.get(node), nodeLowLink.get(dep)));
            } else if (onStack.get(dep)) {
                // Successor dep is in stack and hence in the current SCC
                nodeLowLink.put(node, Math.min(nodeLowLink.get(node), nodeIndex.get(dep)));
            }
        }
        
        // If node is a root node, pop the stack and build an SCC
        if (nodeLowLink.get(node).equals(nodeIndex.get(node))) {
            Set<String> component = new HashSet<>();
            String member;
            do {
                member = stack.pop();
                onStack.put(member, false);
                component.add(member);
            } while (!member.equals(node));
            
            sccs.add(new StronglyConnectedComponent(sccCounter++, component));
        }
    }
}
