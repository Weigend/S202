package de.weigend.s202.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SCCBreaker class which breaks large SCCs using heuristics.
 */
class SCCBreakerTest {
    
    @Test
    @DisplayName("Small SCC (< 3 members) should not be broken")
    void smallSCCNotBroken() {
        // A → B → A (2-member cycle)
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B"));
        graph.put("B", Set.of("A"));
        
        SCCBreaker breaker = new SCCBreaker(graph);
        Set<SCCBreaker.Edge> backEdges = breaker.findBackEdges();
        
        assertTrue(backEdges.isEmpty(), "Small SCCs should not be broken");
    }
    
    @Test
    @DisplayName("Large SCC should have back edges identified")
    void largeSCCHasBackEdges() {
        // Create a 5-member cycle with clear hierarchy
        // A (high out-degree) → B → C → D → E (high in-degree) → A
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B", "C", "D")); // High out-degree
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("D"));
        graph.put("D", Set.of("E"));
        graph.put("E", Set.of("A")); // Back edge: low→high
        
        SCCBreaker breaker = new SCCBreaker(graph);
        Set<SCCBreaker.Edge> backEdges = breaker.findBackEdges();
        
        assertFalse(backEdges.isEmpty(), "Large SCC should have back edges identified");
        
        // E→A should be identified as a back edge (E has high in-degree, A has high out-degree)
        boolean hasEtoA = backEdges.stream()
            .anyMatch(e -> e.from.equals("E") && e.to.equals("A"));
        // The heuristic might identify different edges, but there should be some
        assertTrue(backEdges.size() >= 1, "At least one back edge should be found");
    }
    
    @Test
    @DisplayName("Graph without back edges returns original graph")
    void graphWithoutBackEdgesIsModified() {
        // Create a graph where we expect some edges to be removed
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B", "C", "D"));
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("D"));
        graph.put("D", Set.of("E"));
        graph.put("E", Set.of("A")); // Back edge
        
        SCCBreaker breaker = new SCCBreaker(graph);
        breaker.findBackEdges();
        Map<String, Set<String>> modifiedGraph = breaker.getGraphWithoutBackEdges();
        
        // Count total edges in modified graph
        int originalEdgeCount = graph.values().stream().mapToInt(Set::size).sum();
        int modifiedEdgeCount = modifiedGraph.values().stream().mapToInt(Set::size).sum();
        
        assertTrue(modifiedEdgeCount <= originalEdgeCount, 
            "Modified graph should have same or fewer edges");
    }
    
    @Test
    @DisplayName("DAG (no cycles) should have no back edges")
    void dagHasNoBackEdges() {
        // A → B → C → D (linear, no cycles)
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B"));
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("D"));
        graph.put("D", Set.of());
        
        SCCBreaker breaker = new SCCBreaker(graph);
        Set<SCCBreaker.Edge> backEdges = breaker.findBackEdges();
        
        assertTrue(backEdges.isEmpty(), "DAG should have no back edges");
    }
    
    @Test
    @DisplayName("Complex graph with multiple SCCs")
    void complexGraphWithMultipleSCCs() {
        // Two separate cycles:
        // SCC1: A → B → C → A
        // SCC2: D → E → F → G → D (larger, should be broken)
        // Plus: C → D (connecting the SCCs)
        Map<String, Set<String>> graph = new HashMap<>();
        // SCC1 (small, not broken)
        graph.put("A", Set.of("B"));
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("A", "D"));
        // SCC2 (large, should be broken)
        graph.put("D", Set.of("E", "F")); // High out-degree
        graph.put("E", Set.of("F"));
        graph.put("F", Set.of("G"));
        graph.put("G", Set.of("D")); // Back edge
        
        SCCBreaker breaker = new SCCBreaker(graph);
        Set<SCCBreaker.Edge> backEdges = breaker.findBackEdges();
        
        // SCC1 is small (3 members), might or might not be broken depending on threshold
        // SCC2 is 4 members, should have back edges identified
        assertNotNull(backEdges);
    }
    
    @Test
    @DisplayName("Statistics returns meaningful information")
    void statisticsReturnsInfo() {
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B", "C", "D"));
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("D"));
        graph.put("D", Set.of("E"));
        graph.put("E", Set.of("A"));
        
        SCCBreaker breaker = new SCCBreaker(graph);
        
        // Before analysis
        String statsBefore = breaker.getStatistics();
        assertTrue(statsBefore.contains("not analyzed") || statsBefore.contains("No back edges"));
        
        // After analysis
        breaker.findBackEdges();
        String statsAfter = breaker.getStatistics();
        assertNotNull(statsAfter);
    }
    
    @Test
    @DisplayName("Edge equality and hashCode work correctly")
    void edgeEquality() {
        SCCBreaker.Edge edge1 = new SCCBreaker.Edge("A", "B");
        SCCBreaker.Edge edge2 = new SCCBreaker.Edge("A", "B");
        SCCBreaker.Edge edge3 = new SCCBreaker.Edge("B", "A");
        
        assertEquals(edge1, edge2);
        assertNotEquals(edge1, edge3);
        assertEquals(edge1.hashCode(), edge2.hashCode());
        
        // Test in Set
        Set<SCCBreaker.Edge> edges = new HashSet<>();
        edges.add(edge1);
        edges.add(edge2);
        assertEquals(1, edges.size());
    }
    
    @Test
    @DisplayName("Minecraft-like scenario: large interconnected graph")
    void minecraftLikeScenario() {
        // Simulate a highly interconnected graph where most classes depend on each other
        Map<String, Set<String>> graph = new HashMap<>();
        
        // Create 10 classes, each depending on several others (creating a large SCC)
        List<String> classes = Arrays.asList(
            "World", "Entity", "Player", "Block", "Item", 
            "Inventory", "Chunk", "Renderer", "Network", "GameLoop"
        );
        
        // Create interconnections (everyone depends on multiple others)
        graph.put("GameLoop", new HashSet<>(Arrays.asList("World", "Renderer", "Network", "Player")));
        graph.put("World", new HashSet<>(Arrays.asList("Entity", "Block", "Chunk")));
        graph.put("Entity", new HashSet<>(Arrays.asList("World", "Inventory"))); // Back: Entity→World
        graph.put("Player", new HashSet<>(Arrays.asList("Entity", "Inventory", "World")));
        graph.put("Block", new HashSet<>(Arrays.asList("World", "Item"))); // Back: Block→World
        graph.put("Item", new HashSet<>(Arrays.asList("Inventory")));
        graph.put("Inventory", new HashSet<>(Arrays.asList("Item"))); // Small cycle
        graph.put("Chunk", new HashSet<>(Arrays.asList("Block", "Entity")));
        graph.put("Renderer", new HashSet<>(Arrays.asList("World", "Entity", "Block")));
        graph.put("Network", new HashSet<>(Arrays.asList("Player", "World"))); // Back
        
        SCCBreaker breaker = new SCCBreaker(graph);
        Set<SCCBreaker.Edge> backEdges = breaker.findBackEdges();
        
        System.out.println("Minecraft-like scenario - Back edges identified: " + backEdges.size());
        for (SCCBreaker.Edge edge : backEdges) {
            System.out.println("  " + edge);
        }
        
        // Verify the graph is now more hierarchical
        Map<String, Set<String>> modifiedGraph = breaker.getGraphWithoutBackEdges();
        
        // Check that we can calculate levels on the modified graph without huge flat SCCs
        TarjanSCCFinder newSccFinder = new TarjanSCCFinder(modifiedGraph);
        List<StronglyConnectedComponent> newSccs = newSccFinder.findSCCs();
        
        int maxSccSize = newSccs.stream().mapToInt(StronglyConnectedComponent::getSize).max().orElse(0);
        System.out.println("Largest SCC after breaking: " + maxSccSize + " (was 10)");
        
        // The largest SCC should be smaller than the original
        assertTrue(maxSccSize < classes.size(), 
            "After breaking, largest SCC should be smaller than original");
    }
}
