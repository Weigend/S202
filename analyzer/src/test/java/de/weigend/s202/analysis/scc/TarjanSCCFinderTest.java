package de.weigend.s202.analysis.scc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Tests for Tarjan's SCC finder algorithm.
 */
public class TarjanSCCFinderTest {
    
    private Map<String, Set<String>> graph;
    
    @BeforeEach
    public void setUp() {
        graph = new HashMap<>();
    }
    
    @Test
    public void testSimpleCycle() {
        // A -> B -> C -> A
        graph.put("A", new HashSet<>(Arrays.asList("B")));
        graph.put("B", new HashSet<>(Arrays.asList("C")));
        graph.put("C", new HashSet<>(Arrays.asList("A")));
        
        TarjanSCCFinder finder = new TarjanSCCFinder(graph);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        
        assertEquals(1, sccs.size(), "Should find one SCC containing all three nodes");
        assertTrue(sccs.get(0).isTangle(), "SCC with 3 nodes should be a tangle");
        assertEquals(3, sccs.get(0).getSize());
    }
    
    @Test
    public void testNoCycle() {
        // A -> B -> C (linear)
        graph.put("A", new HashSet<>(Arrays.asList("B")));
        graph.put("B", new HashSet<>(Arrays.asList("C")));
        graph.put("C", new HashSet<>());
        
        TarjanSCCFinder finder = new TarjanSCCFinder(graph);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        
        assertEquals(3, sccs.size(), "Should find three separate SCCs");
        for (StronglyConnectedComponent scc : sccs) {
            assertFalse(scc.isTangle(), "Single-node SCCs should not be tangles");
            assertEquals(1, scc.getSize());
        }
    }
    
    @Test
    public void testMultipleSCCs() {
        // Cycle 1: A -> B -> A
        // Cycle 2: C -> D -> C
        // No connection between cycles
        graph.put("A", new HashSet<>(Arrays.asList("B")));
        graph.put("B", new HashSet<>(Arrays.asList("A")));
        graph.put("C", new HashSet<>(Arrays.asList("D")));
        graph.put("D", new HashSet<>(Arrays.asList("C")));
        
        TarjanSCCFinder finder = new TarjanSCCFinder(graph);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        
        assertEquals(2, sccs.size(), "Should find two separate SCCs");
        for (StronglyConnectedComponent scc : sccs) {
            assertTrue(scc.isTangle(), "Both SCCs should be tangles");
            assertEquals(2, scc.getSize());
        }
    }
    
    @Test
    public void testSelfLoop() {
        // A -> A (self-loop)
        graph.put("A", new HashSet<>(Arrays.asList("A")));
        
        TarjanSCCFinder finder = new TarjanSCCFinder(graph);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        
        assertEquals(1, sccs.size(), "Should find one SCC");
        // Note: Self-loop creates size-1 SCC, not a tangle (tangle requires size > 1)
        assertEquals(1, sccs.get(0).getSize());
    }
    
    @Test
    public void testComplexGraph() {
        // DAG with one cycle: 1 -> 2 -> 3 -> 2, 3 -> 4 -> 5
        graph.put("1", new HashSet<>(Arrays.asList("2")));
        graph.put("2", new HashSet<>(Arrays.asList("3")));
        graph.put("3", new HashSet<>(Arrays.asList("2", "4")));
        graph.put("4", new HashSet<>(Arrays.asList("5")));
        graph.put("5", new HashSet<>());
        
        TarjanSCCFinder finder = new TarjanSCCFinder(graph);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        
        // Expected SCCs: {1}, {2,3}, {4}, {5} = 4 SCCs
        assertEquals(4, sccs.size(), "Should find one tangle (2-3) and three single nodes");
        
        long tangleCount = sccs.stream().filter(StronglyConnectedComponent::isTangle).count();
        assertEquals(1, tangleCount, "Should have exactly one tangle");
    }
    
    @Test
    public void testEmptyGraph() {
        TarjanSCCFinder finder = new TarjanSCCFinder(graph);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        
        assertEquals(0, sccs.size(), "Empty graph should have no SCCs");
    }
}
