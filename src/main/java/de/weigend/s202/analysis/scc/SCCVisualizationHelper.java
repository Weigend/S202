package de.weigend.s202.analysis.scc;

import java.util.*;

/**
 * Renders SCCs and edges for visualization in the architecture diagram.
 * Handles tangle boxes and edge classification for UI rendering.
 */
public class SCCVisualizationHelper {
    private final List<StronglyConnectedComponent> sccs;
    private final Map<String, Integer> nodeToSccId;
    private final List<EdgeClassification.ClassifiedEdge> classifiedEdges;
    
    public SCCVisualizationHelper(List<StronglyConnectedComponent> sccs,
                                 Map<String, Integer> nodeToSccId,
                                 List<EdgeClassification.ClassifiedEdge> classifiedEdges) {
        this.sccs = sccs;
        this.nodeToSccId = nodeToSccId;
        this.classifiedEdges = classifiedEdges;
    }
    
    /**
     * Gets all tangles (SCCs with size > 1).
     */
    public List<StronglyConnectedComponent> getTangles() {
        List<StronglyConnectedComponent> tangles = new ArrayList<>();
        for (StronglyConnectedComponent scc : sccs) {
            if (scc.isTangle()) {
                tangles.add(scc);
            }
        }
        return tangles;
    }
    
    /**
     * Gets edges of a specific type.
     */
    public List<EdgeClassification.ClassifiedEdge> getEdgesByType(EdgeClassification.EdgeType type) {
        List<EdgeClassification.ClassifiedEdge> result = new ArrayList<>();
        for (EdgeClassification.ClassifiedEdge edge : classifiedEdges) {
            if (edge.type == type) {
                result.add(edge);
            }
        }
        return result;
    }
    
    /**
     * Generates a summary of architecture violations.
     */
    public ArchitectureSummary generateSummary() {
        List<EdgeClassification.ClassifiedEdge> violations = 
            getEdgesByType(EdgeClassification.EdgeType.VIOLATION);
        List<EdgeClassification.ClassifiedEdge> intraSccEdges = 
            getEdgesByType(EdgeClassification.EdgeType.INTRA_SCC);
        List<StronglyConnectedComponent> tangles = getTangles();
        
        return new ArchitectureSummary(
            sccs.size(),
            tangles.size(),
            violations.size(),
            intraSccEdges.size(),
            tangles
        );
    }
    
    /**
     * Sorts nodes within a tangle using greedy out-in heuristic.
     * Nodes with more outgoing edges come first.
     */
    public List<String> sortTangleMembers(StronglyConnectedComponent tangle,
                                          Map<String, Set<String>> graph) {
        List<String> members = new ArrayList<>(tangle.getMembers());
        
        // Count outgoing edges for each member
        Map<String, Integer> outDegree = new HashMap<>();
        for (String member : members) {
            Set<String> deps = graph.getOrDefault(member, new HashSet<>());
            int outCount = 0;
            for (String dep : deps) {
                if (tangle.getMembers().contains(dep) && !dep.equals(member)) {
                    outCount++;
                }
            }
            outDegree.put(member, outCount);
        }
        
        // Sort: higher out-degree first (greedy out-in)
        members.sort((a, b) -> Integer.compare(
            outDegree.getOrDefault(b, 0),
            outDegree.getOrDefault(a, 0)
        ));
        
        return members;
    }
    
    /**
     * Summary of architecture statistics.
     */
    public static class ArchitectureSummary {
        public final int totalSCCs;
        public final int tangleCount;
        public final int violationCount;
        public final int intraSccEdgeCount;
        public final List<StronglyConnectedComponent> tangles;
        
        public ArchitectureSummary(int totalSCCs, int tangleCount, int violationCount,
                                  int intraSccEdgeCount,
                                  List<StronglyConnectedComponent> tangles) {
            this.totalSCCs = totalSCCs;
            this.tangleCount = tangleCount;
            this.violationCount = violationCount;
            this.intraSccEdgeCount = intraSccEdgeCount;
            this.tangles = new ArrayList<>(tangles);
        }
        
        @Override
        public String toString() {
            return String.format(
                "Architecture Summary: %d SCCs, %d tangles, %d violations, %d intra-SCC edges",
                totalSCCs, tangleCount, violationCount, intraSccEdgeCount
            );
        }
    }
}
