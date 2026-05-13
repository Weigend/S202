package de.weigend.s202.domain.strategy.impl;

import de.weigend.s202.domain.strategy.aggregation.SimpleMaxAggregationStrategy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BasicClassLevelCalculationStrategyTest {
    
    private final BasicClassLevelCalculationStrategy strategy = 
        new BasicClassLevelCalculationStrategy(new SimpleMaxAggregationStrategy());
    
    @Test
    void testCalculateClassLevelsSimpleChain() {
        // A -> B -> C chain (bottom-up for topological order)
        Map<String, Set<String>> dependencies = new HashMap<>();
        dependencies.put("ClassA", Set.of("ClassB"));
        dependencies.put("ClassB", Set.of("ClassC"));
        dependencies.put("ClassC", Set.of());
        
        Map<String, Integer> result = strategy.calculateClassLevels(dependencies);
        
        assertEquals(0, result.get("ClassC")); // No deps -> level 0
        assertEquals(1, result.get("ClassB")); // Depends on C (level 0) -> level 1
        assertEquals(2, result.get("ClassA")); // Depends on B (level 1) -> level 2
    }
    
    @Test
    void testCalculateClassLevelsDiamond() {
        // Diamond: A -> B,C -> D (iterative, needs multiple passes)
        Map<String, Set<String>> dependencies = new HashMap<>();
        dependencies.put("ClassD", Set.of());        // D has no deps
        dependencies.put("ClassB", Set.of("ClassD"));
        dependencies.put("ClassC", Set.of("ClassD"));
        dependencies.put("ClassA", Set.of("ClassB", "ClassC"));
        
        Map<String, Integer> result = strategy.calculateClassLevels(dependencies);
        
        assertEquals(0, result.get("ClassD"));
        assertEquals(1, result.get("ClassB"));
        assertEquals(1, result.get("ClassC"));
        assertEquals(2, result.get("ClassA"));
    }
    
    @Test
    void testCalculateClassLevelsNoDefaults() {
        Map<String, Set<String>> dependencies = new HashMap<>();
        dependencies.put("ClassA", Set.of());
        dependencies.put("ClassB", Set.of());
        
        Map<String, Integer> result = strategy.calculateClassLevels(dependencies);
        
        assertEquals(0, result.get("ClassA"));
        assertEquals(0, result.get("ClassB"));
    }
    
    @Test
    void testGetName() {
        String name = strategy.getName();
        assertEquals("BasicClassLevelCalculation (SCC-aware, with SimpleMaxAggregation)", name);
    }
}
