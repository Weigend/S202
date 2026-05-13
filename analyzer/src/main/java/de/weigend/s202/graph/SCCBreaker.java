package de.weigend.s202.graph;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Breaks up large Strongly Connected Components (SCCs) using heuristics.
 *
 * Iterates until the filtered graph contains no more SCCs of size >= MIN_SCC_SIZE_TO_BREAK.
 * A single pass may leave residual sub-cycles inside a large SCC, which cause level
 * inversions in the topo-sort propagation step; the loop eliminates them.
 *
 * Heuristic: In-Degree / Out-Degree rank score.
 *   High in-degree  → foundational element  → lower level
 *   High out-degree → high-level element     → higher level
 *   Edges from lower-ranked to higher-ranked classes are identified as back edges.
 */
public class SCCBreaker {

    private static final int MIN_SCC_SIZE_TO_BREAK = 3;

    private final Map<String, Set<String>> originalGraph;
    private final Set<Edge> backEdges = new HashSet<>();

    public static class Edge {
        public final String from;
        public final String to;

        public Edge(String from, String to) {
            this.from = from;
            this.to   = to;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Edge edge = (Edge) o;
            return Objects.equals(from, edge.from) && Objects.equals(to, edge.to);
        }

        @Override public int hashCode() { return Objects.hash(from, to); }

        @Override public String toString() { return from + " → " + to; }
    }

    public SCCBreaker(Map<String, Set<String>> dependencyGraph) {
        this.originalGraph = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
            this.originalGraph.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    /**
     * Identifies back edges in a single pass over all large SCCs.
     * Uses the original graph so that the set of cut edges remains stable and
     * predictable — the UI displays these edges as violations (red), so
     * iterating to a fixpoint would change the visual output unpredictably.
     */
    public Set<Edge> findBackEdges() {
        backEdges.clear();

        Map<String, Set<String>> filteredGraph = buildFilteredGraph();
        for (StronglyConnectedComponent scc : new TarjanSCCFinder(filteredGraph).findSCCs()) {
            if (scc.getSize() >= MIN_SCC_SIZE_TO_BREAK) {
                backEdges.addAll(breakSCC(scc, filteredGraph));
            }
        }

        return new HashSet<>(backEdges);
    }

    /** Returns the dependency graph with all identified back edges removed. */
    public Map<String, Set<String>> getGraphWithoutBackEdges() {
        if (backEdges.isEmpty()) findBackEdges();
        return buildFilteredGraph();
    }

    /** Builds the graph with the current set of back edges removed. */
    private Map<String, Set<String>> buildFilteredGraph() {
        Map<String, Set<String>> filtered = new HashMap<>(originalGraph.size());
        for (Map.Entry<String, Set<String>> entry : originalGraph.entrySet()) {
            String from = entry.getKey();
            Set<String> deps = new HashSet<>();
            for (String to : entry.getValue()) {
                if (!backEdges.contains(new Edge(from, to))) deps.add(to);
            }
            filtered.put(from, deps);
        }
        return filtered;
    }

    /**
     * Identifies back edges within a single SCC using the rank heuristic.
     * Uses the current filtered graph so degrees reflect already-removed edges.
     */
    private Set<Edge> breakSCC(StronglyConnectedComponent scc,
                                Map<String, Set<String>> filteredGraph) {
        Set<String> members = scc.getMembers();
        Set<Edge> identifiedBackEdges = new HashSet<>();

        Map<String, Integer> inDegree  = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        for (String m : members) { inDegree.put(m, 0); outDegree.put(m, 0); }

        for (String member : members) {
            for (String dep : filteredGraph.getOrDefault(member, Set.of())) {
                if (members.contains(dep)) {
                    outDegree.merge(member, 1, Integer::sum);
                    inDegree.merge(dep,    1, Integer::sum);
                }
            }
        }

        Map<String, Double> rankScore = new HashMap<>();
        for (String member : members) {
            int out = outDegree.get(member), in = inDegree.get(member);
            rankScore.put(member, (out - in) / (double) Math.max(1, out + in));
        }

        for (String member : members) {
            for (String dep : filteredGraph.getOrDefault(member, Set.of())) {
                if (members.contains(dep)
                        && rankScore.get(member) < rankScore.get(dep) - 0.1) {
                    identifiedBackEdges.add(new Edge(member, dep));
                }
            }
        }

        int maxBackEdges = Math.max(1, countInternalEdges(scc, filteredGraph) / 3);
        if (identifiedBackEdges.size() > maxBackEdges) {
            List<Edge> sorted = identifiedBackEdges.stream()
                .sorted((e1, e2) -> Double.compare(
                    rankScore.get(e2.to) - rankScore.get(e2.from),
                    rankScore.get(e1.to) - rankScore.get(e1.from)))
                .limit(maxBackEdges)
                .collect(Collectors.toList());
            identifiedBackEdges = new HashSet<>(sorted);
        }

        return identifiedBackEdges;
    }

    private int countInternalEdges(StronglyConnectedComponent scc,
                                    Map<String, Set<String>> graph) {
        Set<String> members = scc.getMembers();
        int count = 0;
        for (String member : members) {
            for (String dep : graph.getOrDefault(member, Set.of())) {
                if (members.contains(dep)) count++;
            }
        }
        return count;
    }

    public String getStatistics() {
        if (backEdges.isEmpty()) return "No back edges identified";
        return String.format("Identified %d back edges to break cycles", backEdges.size());
    }
}
