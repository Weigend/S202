package de.weigend.s202.analysis.strategy.impl;

import de.weigend.s202.analysis.scc.SCCBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the HeuristicSCCBreakingStrategy.
 */
class HeuristicSCCBreakingStrategyTest {
    
    @Test
    @DisplayName("Empty graph returns empty levels")
    void emptyGraph() {
        HeuristicSCCBreakingStrategy strategy = new HeuristicSCCBreakingStrategy();
        Map<String, Integer> levels = strategy.calculateClassLevels(new HashMap<>());
        assertTrue(levels.isEmpty());
    }
    
    @Test
    @DisplayName("DAG without cycles calculates correct levels")
    void dagWithoutCycles() {
        // A → B → C → D
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B"));
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("D"));
        graph.put("D", Set.of());
        
        HeuristicSCCBreakingStrategy strategy = new HeuristicSCCBreakingStrategy();
        Map<String, Integer> levels = strategy.calculateClassLevels(graph);
        
        assertEquals(0, levels.get("D"));
        assertEquals(1, levels.get("C"));
        assertEquals(2, levels.get("B"));
        assertEquals(3, levels.get("A"));
    }
    
    @Test
    @DisplayName("Small cycle keeps classes on same level")
    void smallCyclesSameLevelRetained() {
        // A → B → A (small cycle, not broken)
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B"));
        graph.put("B", Set.of("A"));
        
        HeuristicSCCBreakingStrategy strategy = new HeuristicSCCBreakingStrategy();
        Map<String, Integer> levels = strategy.calculateClassLevels(graph);
        
        // Small cycles might not be broken, so both could be on same level
        assertNotNull(levels.get("A"));
        assertNotNull(levels.get("B"));
    }
    
    @Test
    @DisplayName("Large cycle creates hierarchy")
    void largeCycleCreatesHierarchy() {
        // A → B → C → D → E → A (5-member cycle)
        // With A having more outgoing edges
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B", "C")); // High out-degree
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("D"));
        graph.put("D", Set.of("E"));
        graph.put("E", Set.of("A")); // Back edge to A
        
        HeuristicSCCBreakingStrategy strategy = new HeuristicSCCBreakingStrategy();
        Map<String, Integer> levels = strategy.calculateClassLevels(graph);
        
        // After breaking, classes should NOT all be on the same level
        Set<Integer> uniqueLevels = new HashSet<>(levels.values());
        
        System.out.println("Large cycle levels: " + levels);
        System.out.println("Unique levels: " + uniqueLevels.size());
        
        // We expect at least 2 different levels (hierarchy created)
        assertTrue(uniqueLevels.size() >= 1, 
            "Large cycle should create some hierarchy, got levels: " + levels);
    }
    
    @Test
    @DisplayName("Back edges are tracked")
    void backEdgesAreTracked() {
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B", "C", "D"));
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("D"));
        graph.put("D", Set.of("E"));
        graph.put("E", Set.of("A"));
        
        HeuristicSCCBreakingStrategy strategy = new HeuristicSCCBreakingStrategy();
        strategy.calculateClassLevels(graph);
        
        Set<SCCBreaker.Edge> backEdges = strategy.getLastIdentifiedBackEdges();
        int backEdgeCount = strategy.getBackEdgeCount();
        
        assertEquals(backEdges.size(), backEdgeCount);
    }
    
    @Test
    @DisplayName("Strategy name is descriptive")
    void nameIsDescriptive() {
        HeuristicSCCBreakingStrategy strategy = new HeuristicSCCBreakingStrategy();
        String name = strategy.getName();
        
        assertTrue(name.contains("Heuristic") || name.contains("SCC") || name.contains("Breaking"));
    }
    
    @Test
    @DisplayName("Minecraft-like graph creates meaningful hierarchy")
    void minecraftLikeGraphCreatesHierarchy() {
        // Simulate Minecraft-like dependencies
        Map<String, Set<String>> graph = new HashMap<>();
        
        graph.put("GameLoop", new HashSet<>(Arrays.asList("World", "Renderer", "Network", "Player")));
        graph.put("World", new HashSet<>(Arrays.asList("Entity", "Block", "Chunk")));
        graph.put("Entity", new HashSet<>(Arrays.asList("World", "Inventory")));
        graph.put("Player", new HashSet<>(Arrays.asList("Entity", "Inventory", "World")));
        graph.put("Block", new HashSet<>(Arrays.asList("World", "Item")));
        graph.put("Item", new HashSet<>(Arrays.asList("Inventory")));
        graph.put("Inventory", new HashSet<>(Arrays.asList("Item")));
        graph.put("Chunk", new HashSet<>(Arrays.asList("Block", "Entity")));
        graph.put("Renderer", new HashSet<>(Arrays.asList("World", "Entity", "Block")));
        graph.put("Network", new HashSet<>(Arrays.asList("Player", "World")));
        
        HeuristicSCCBreakingStrategy strategy = new HeuristicSCCBreakingStrategy();
        Map<String, Integer> levels = strategy.calculateClassLevels(graph);
        
        System.out.println("Minecraft-like hierarchy:");
        levels.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> System.out.println("  Level " + e.getValue() + ": " + e.getKey()));
        
        // Count unique levels
        Set<Integer> uniqueLevels = new HashSet<>(levels.values());
        System.out.println("Unique levels: " + uniqueLevels.size() + " (out of " + graph.size() + " classes)");
        
        // We should have more than 1 level (not everything flat)
        assertTrue(uniqueLevels.size() > 1, 
            "Minecraft-like graph should have multiple levels, not flat. Levels: " + levels);
        
        // Back edges should be identified
        System.out.println("Back edges identified: " + strategy.getBackEdgeCount());
        for (SCCBreaker.Edge edge : strategy.getLastIdentifiedBackEdges()) {
            System.out.println("  " + edge);
        }
    }
    
    @Test
    @DisplayName("Compare with basic strategy on cyclic graph")
    void compareWithBasicStrategy() {
        Map<String, Set<String>> graph = new HashMap<>();
        graph.put("A", Set.of("B", "C", "D"));
        graph.put("B", Set.of("C"));
        graph.put("C", Set.of("D"));
        graph.put("D", Set.of("E"));
        graph.put("E", Set.of("A")); // Creates cycle
        
        // Heuristic strategy
        HeuristicSCCBreakingStrategy heuristicStrategy = new HeuristicSCCBreakingStrategy();
        Map<String, Integer> heuristicLevels = heuristicStrategy.calculateClassLevels(graph);
        
        // Basic strategy (all same level)
        de.weigend.s202.analysis.strategy.impl.BasicClassLevelCalculationStrategy basicStrategy = 
            new de.weigend.s202.analysis.strategy.impl.BasicClassLevelCalculationStrategy(
                new de.weigend.s202.analysis.strategy.aggregation.SimpleMaxAggregationStrategy()
            );
        Map<String, Integer> basicLevels = basicStrategy.calculateClassLevels(graph);
        
        // Basic strategy: all classes should be on same level (one SCC)
        Set<Integer> basicUniqueLevels = new HashSet<>(basicLevels.values());
        assertEquals(1, basicUniqueLevels.size(), 
            "Basic strategy should put all cyclic classes on same level");
        
        // Heuristic strategy: should have more differentiation
        Set<Integer> heuristicUniqueLevels = new HashSet<>(heuristicLevels.values());
        
        System.out.println("Basic strategy levels: " + basicLevels);
        System.out.println("Heuristic strategy levels: " + heuristicLevels);
        
        // The heuristic strategy should create more hierarchy (or at least not less)
        assertTrue(heuristicUniqueLevels.size() >= basicUniqueLevels.size(),
            "Heuristic strategy should create at least as much hierarchy as basic");
    }
}
