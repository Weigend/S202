package de.weigend.s202.analysis.scc;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Breaks up large Strongly Connected Components (SCCs) using heuristics.
 * 
 * <h2>Problem</h2>
 * In highly interconnected projects (like Minecraft), almost all classes end up in one
 * giant SCC, resulting in a flat, useless hierarchy where everything is on the same level.
 * 
 * <h2>Solution</h2>
 * This class identifies "back edges" - edges that go against the natural flow of dependencies
 * and breaks cycles by ignoring these edges for level calculation purposes.
 * 
 * <h2>Heuristic: In-Degree / Out-Degree Analysis</h2>
 * <ul>
 *   <li>Classes with HIGH in-degree (many dependents) are likely "foundational" → lower levels</li>
 *   <li>Classes with HIGH out-degree (many dependencies) are likely "high-level" → higher levels</li>
 *   <li>Edges FROM high-in-degree TO high-out-degree classes are "back edges" → ignored</li>
 * </ul>
 * 
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Calculate a "rank score" for each class: outDegree - inDegree</li>
 *   <li>Higher score = higher in hierarchy (uses more than it provides)</li>
 *   <li>Identify edges that go from lower-ranked to higher-ranked classes as "back edges"</li>
 *   <li>Mark these back edges as "violations" to be ignored in level calculation</li>
 * </ol>
 */
public class SCCBreaker {
    
    /** Minimum SCC size to consider for breaking. Smaller SCCs are left as-is. */
    private static final int MIN_SCC_SIZE_TO_BREAK = 3;
    
    private final Map<String, Set<String>> originalGraph;
    private final Set<Edge> backEdges = new HashSet<>();
    
    /**
     * Represents a directed edge in the dependency graph.
     */
    public static class Edge {
        public final String from;
        public final String to;
        
        public Edge(String from, String to) {
            this.from = from;
            this.to = to;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Edge edge = (Edge) o;
            return Objects.equals(from, edge.from) && Objects.equals(to, edge.to);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(from, to);
        }
        
        @Override
        public String toString() {
            return from + " → " + to;
        }
    }
    
    /**
     * Creates an SCCBreaker for the given dependency graph.
     * 
     * @param dependencyGraph Map from class name to set of classes it depends on
     */
    public SCCBreaker(Map<String, Set<String>> dependencyGraph) {
        this.originalGraph = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
            this.originalGraph.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }
    
    /**
     * Analyzes the graph and identifies back edges that should be ignored for level calculation.
     * 
     * @return Set of edges that are identified as "back edges" (violations)
     */
    public Set<Edge> findBackEdges() {
        backEdges.clear();
        
        // Find all SCCs
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(originalGraph);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();
        
        // Process each large SCC
        for (StronglyConnectedComponent scc : sccs) {
            if (scc.getSize() >= MIN_SCC_SIZE_TO_BREAK) {
                Set<Edge> sccBackEdges = breakSCC(scc);
                backEdges.addAll(sccBackEdges);
            }
        }
        
        return new HashSet<>(backEdges);
    }
    
    /**
     * Returns a modified dependency graph with back edges removed.
     * This graph should be acyclic or have much smaller SCCs.
     * 
     * @return Dependency graph without the identified back edges
     */
    public Map<String, Set<String>> getGraphWithoutBackEdges() {
        if (backEdges.isEmpty()) {
            findBackEdges();
        }
        
        Map<String, Set<String>> modifiedGraph = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : originalGraph.entrySet()) {
            String from = entry.getKey();
            Set<String> filteredDeps = new HashSet<>();
            
            for (String to : entry.getValue()) {
                Edge edge = new Edge(from, to);
                if (!backEdges.contains(edge)) {
                    filteredDeps.add(to);
                }
            }
            modifiedGraph.put(from, filteredDeps);
        }
        
        return modifiedGraph;
    }
    
    /**
     * Breaks a single SCC by identifying back edges using the rank heuristic.
     */
    private Set<Edge> breakSCC(StronglyConnectedComponent scc) {
        Set<String> members = scc.getMembers();
        Set<Edge> identifiedBackEdges = new HashSet<>();
        
        // Calculate in-degree and out-degree for each member (only within SCC)
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        
        for (String member : members) {
            inDegree.put(member, 0);
            outDegree.put(member, 0);
        }
        
        // Count edges within the SCC
        for (String member : members) {
            Set<String> deps = originalGraph.getOrDefault(member, Set.of());
            for (String dep : deps) {
                if (members.contains(dep)) {
                    outDegree.merge(member, 1, Integer::sum);
                    inDegree.merge(dep, 1, Integer::sum);
                }
            }
        }
        
        // Calculate rank score: higher = more likely to be high-level
        // Score = (outDegree - inDegree) normalized
        Map<String, Double> rankScore = new HashMap<>();
        for (String member : members) {
            int out = outDegree.get(member);
            int in = inDegree.get(member);
            // Normalize to avoid division issues
            double score = (out - in) / (double) Math.max(1, out + in);
            rankScore.put(member, score);
        }
        
        // Sort members by rank score (ascending = low-level first)
        List<String> sortedMembers = members.stream()
            .sorted(Comparator.comparingDouble(rankScore::get))
            .collect(Collectors.toList());
        
        // Assign preliminary levels based on sorted order
        Map<String, Integer> preliminaryLevel = new HashMap<>();
        for (int i = 0; i < sortedMembers.size(); i++) {
            preliminaryLevel.put(sortedMembers.get(i), i);
        }
        
        // Find back edges: edges from lower preliminary level to higher preliminary level
        // that also go from higher rank score to lower rank score
        for (String member : members) {
            Set<String> deps = originalGraph.getOrDefault(member, Set.of());
            for (String dep : deps) {
                if (members.contains(dep)) {
                    // This is an edge within the SCC: member → dep
                    // Normal flow: high-level depends on low-level
                    // Back edge: low-level depends on high-level (violation)
                    
                    double memberRank = rankScore.get(member);
                    double depRank = rankScore.get(dep);
                    
                    // If the dependency goes from low-rank to high-rank, it's likely a back edge
                    // We use a threshold to avoid over-cutting
                    if (memberRank < depRank - 0.1) {
                        identifiedBackEdges.add(new Edge(member, dep));
                    }
                }
            }
        }
        
        // Limit the number of back edges to avoid breaking too many connections
        // Use Feedback Arc Set approximation: cut at most (edges / 2) edges
        int maxBackEdges = countInternalEdges(scc) / 3;
        if (identifiedBackEdges.size() > maxBackEdges) {
            // Keep only the most "violating" back edges (largest rank difference)
            List<Edge> sortedBackEdges = identifiedBackEdges.stream()
                .sorted((e1, e2) -> {
                    double diff1 = rankScore.get(e1.to) - rankScore.get(e1.from);
                    double diff2 = rankScore.get(e2.to) - rankScore.get(e2.from);
                    return Double.compare(diff2, diff1); // Descending
                })
                .limit(maxBackEdges)
                .collect(Collectors.toList());
            identifiedBackEdges = new HashSet<>(sortedBackEdges);
        }
        
        return identifiedBackEdges;
    }
    
    /**
     * Counts the number of edges within an SCC.
     */
    private int countInternalEdges(StronglyConnectedComponent scc) {
        Set<String> members = scc.getMembers();
        int count = 0;
        for (String member : members) {
            Set<String> deps = originalGraph.getOrDefault(member, Set.of());
            for (String dep : deps) {
                if (members.contains(dep)) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Returns statistics about the breaking operation.
     */
    public String getStatistics() {
        if (backEdges.isEmpty()) {
            return "No back edges identified (no large SCCs or not analyzed yet)";
        }
        return String.format("Identified %d back edges to break cycles", backEdges.size());
    }
}
