package de.weigend.s202.graph;

import java.util.*;

/**
 * Builds the DAG (Directed Acyclic Graph) of SCCs and assigns levels
 * based on the longest path from leaves.
 */
public class SCCDAGBuilder {
    private final List<StronglyConnectedComponent> sccs;
    private final Map<String, Integer> nodeToSccId;
    private final Map<String, Set<String>> originalGraph;
    
    public SCCDAGBuilder(List<StronglyConnectedComponent> sccs, 
                        Map<String, Set<String>> originalGraph) {
        this.sccs = sccs;
        this.originalGraph = originalGraph;
        this.nodeToSccId = buildNodeToSccMapping();
    }
    
    /**
     * Builds the mapping from nodes to their SCC IDs.
     */
    private Map<String, Integer> buildNodeToSccMapping() {
        Map<String, Integer> mapping = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                mapping.put(member, scc.getId());
            }
        }
        return mapping;
    }
    
    /**
     * Builds the SCC DAG by computing inter-SCC dependencies.
     */
    public void buildDAG() {
        // Initialize all SCCs with empty dependencies
        for (StronglyConnectedComponent scc : sccs) {
            if (scc.getOutgoingDependencies().isEmpty()) {
                // Leaf node needs explicit initialization
                scc.setLevel(0); // Will be recalculated in assignLevels
            }
        }
        
        // For each SCC, find dependencies on other SCCs
        for (StronglyConnectedComponent scc : sccs) {
            Set<Integer> dependentSccIds = new HashSet<>();
            
            // Check all outgoing edges from members of this SCC
            for (String member : scc.getMembers()) {
                Set<String> deps = originalGraph.getOrDefault(member, new HashSet<>());
                for (String dep : deps) {
                    if (nodeToSccId.containsKey(dep)) {
                        int depSccId = nodeToSccId.get(dep);
                        if (depSccId != scc.getId()) {
                            dependentSccIds.add(depSccId);
                        }
                    }
                }
            }
            
            // Add SCC dependencies
            for (Integer depSccId : dependentSccIds) {
                scc.addOutgoingDependency(String.valueOf(depSccId));
                // Find the corresponding SCC and add incoming dependency
                findSccById(depSccId).ifPresent(
                    depScc -> depScc.addIncomingDependency(String.valueOf(scc.getId()))
                );
            }
        }
    }
    
    /**
     * Assigns levels to SCCs using longest path in the DAG.
     * Level 0 = SCCs with no outgoing dependencies (leaves).
     * Higher levels = longer paths to leaves.
     */
    public void assignLevels() {
        Map<Integer, Integer> levelCache = new HashMap<>();
        Set<Integer> visiting = new HashSet<>();
        
        for (StronglyConnectedComponent scc : sccs) {
            if (!levelCache.containsKey(scc.getId())) {
                assignLevelRecursive(scc.getId(), levelCache, visiting);
            }
        }
    }
    
    /**
     * Recursively assigns level to an SCC based on longest path to leaves.
     */
    private int assignLevelRecursive(int sccId, Map<Integer, Integer> cache, Set<Integer> visiting) {
        if (cache.containsKey(sccId)) {
            return cache.get(sccId);
        }
        
        if (visiting.contains(sccId)) {
            // Cycle in SCC dependencies (shouldn't happen in DAG, but be safe)
            return 0;
        }
        
        Optional<StronglyConnectedComponent> sccOpt = findSccById(sccId);
        if (!sccOpt.isPresent()) {
            return 0;
        }
        
        StronglyConnectedComponent scc = sccOpt.get();
        Set<String> deps = scc.getOutgoingDependencies();
        
        if (deps.isEmpty()) {
            // Leaf SCC
            cache.put(sccId, 0);
            return 0;
        }
        
        visiting.add(sccId);
        int maxDepLevel = 0;
        
        for (String depIdStr : deps) {
            int depId = Integer.parseInt(depIdStr);
            int depLevel = assignLevelRecursive(depId, cache, visiting);
            maxDepLevel = Math.max(maxDepLevel, depLevel);
        }
        
        visiting.remove(sccId);
        
        int level = maxDepLevel + 1;
        scc.setLevel(level);
        cache.put(sccId, level);
        
        return level;
    }
    
    /**
     * Finds an SCC by its ID.
     */
    private Optional<StronglyConnectedComponent> findSccById(int id) {
        return sccs.stream().filter(scc -> scc.getId() == id).findFirst();
    }
    
    /**
     * Returns SCCs sorted by level (stable sort).
     */
    public List<StronglyConnectedComponent> getSortedSCCs() {
        List<StronglyConnectedComponent> sorted = new ArrayList<>(sccs);
        Collections.sort(sorted);
        return sorted;
    }
}
