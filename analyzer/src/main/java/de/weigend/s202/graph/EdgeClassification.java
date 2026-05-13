package de.weigend.s202.graph;

import java.util.*;

/**
 * Classifies edges in the layered architecture based on their direction.
 */
public class EdgeClassification {
    public enum EdgeType {
        NORMAL,      // Edge goes downward (from higher to lower level)
        VIOLATION,   // Edge goes upward (from lower to higher level) - architectural violation
        INTRA_SCC    // Edge within the same SCC (tangle)
    }
    
    public static class ClassifiedEdge {
        public final String from;
        public final String to;
        public final EdgeType type;
        
        public ClassifiedEdge(String from, String to, EdgeType type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }
        
        @Override
        public String toString() {
            return String.format("%s -> %s [%s]", from, to, type);
        }
    }
    
    private final Map<String, Integer> nodeToLevel;
    private final Map<String, Integer> nodeToSccId;
    private final Map<String, Set<String>> graph;
    
    public EdgeClassification(Map<String, Integer> nodeToLevel,
                            Map<String, Integer> nodeToSccId,
                            Map<String, Set<String>> graph) {
        this.nodeToLevel = nodeToLevel;
        this.nodeToSccId = nodeToSccId;
        this.graph = graph;
    }
    
    /**
     * Classifies all edges in the graph.
     */
    public List<ClassifiedEdge> classifyAllEdges() {
        List<ClassifiedEdge> edges = new ArrayList<>();
        
        for (String from : graph.keySet()) {
            Set<String> deps = graph.get(from);
            for (String to : deps) {
                edges.add(classifyEdge(from, to));
            }
        }
        
        return edges;
    }
    
    /**
     * Classifies a single edge based on source and target levels.
     */
    public ClassifiedEdge classifyEdge(String from, String to) {
        int fromLevel = nodeToLevel.getOrDefault(from, -1);
        int toLevel = nodeToLevel.getOrDefault(to, -1);
        
        int fromSccId = nodeToSccId.getOrDefault(from, -1);
        int toSccId = nodeToSccId.getOrDefault(to, -1);
        
        // Intra-SCC edge
        if (fromSccId == toSccId && fromSccId >= 0) {
            return new ClassifiedEdge(from, to, EdgeType.INTRA_SCC);
        }
        
        // Determine edge direction
        // Higher level number = higher in hierarchy (top of the system)
        // Lower level number = lower in hierarchy (bottom/leaves)
        // NORMAL: from higher to lower (from top to bottom) = from > to
        // VIOLATION: from lower to higher (from bottom to top) = from < to
        if (fromLevel > toLevel) {
            // Going downward (from higher to lower level) = normal
            return new ClassifiedEdge(from, to, EdgeType.NORMAL);
        } else if (fromLevel < toLevel) {
            // Going upward (from lower to higher level) = violation
            return new ClassifiedEdge(from, to, EdgeType.VIOLATION);
        } else {
            // Same level = normal
            return new ClassifiedEdge(from, to, EdgeType.NORMAL);
        }
    }
}
