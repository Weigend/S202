package de.weigend.s202.analysis.strategy.impl;

import de.weigend.s202.analysis.strategy.aggregation.SimpleMaxAggregationStrategy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BasicPackageLevelCalculationStrategyTest {
    
    private final BasicPackageLevelCalculationStrategy strategy = 
        new BasicPackageLevelCalculationStrategy(new SimpleMaxAggregationStrategy());
    
    @Test
    void testCalculatePackageLevelsSimpleChain() {
        // com.example2 <- com.example1 <- com.example chain (bottom-up for topological order)
        Map<String, Set<String>> dependencies = new HashMap<>();
        dependencies.put("com.example", Set.of("com.example1"));
        dependencies.put("com.example1", Set.of("com.example2"));
        dependencies.put("com.example2", Set.of());
        
        Map<String, Integer> result = strategy.calculatePackageLevels(dependencies);
        
        assertEquals(0, result.get("com.example2")); // No deps -> level 0
        assertEquals(1, result.get("com.example1")); // Depends on example2 (level 0) -> level 1
        assertEquals(2, result.get("com.example"));  // Depends on example1 (level 1) -> level 2
    }
    
    @Test
    void testCalculatePackageLevelsDiamond() {
        // Diamond: com.A -> com.B,com.C -> com.D (iterative calculation)
        Map<String, Set<String>> dependencies = new HashMap<>();
        dependencies.put("com.D", Set.of());                // D has no deps
        dependencies.put("com.B", Set.of("com.D"));
        dependencies.put("com.C", Set.of("com.D"));
        dependencies.put("com.A", Set.of("com.B", "com.C"));
        
        Map<String, Integer> result = strategy.calculatePackageLevels(dependencies);
        
        assertEquals(0, result.get("com.D"));
        assertEquals(1, result.get("com.B"));
        assertEquals(1, result.get("com.C"));
        assertEquals(2, result.get("com.A"));
    }
    
    @Test
    void testCalculatePackageLevelsNoDependencies() {
        Map<String, Set<String>> dependencies = new HashMap<>();
        dependencies.put("com.example", Set.of());
        dependencies.put("com.other", Set.of());
        
        Map<String, Integer> result = strategy.calculatePackageLevels(dependencies);
        
        assertEquals(0, result.get("com.example"));
        assertEquals(0, result.get("com.other"));
    }
    
    @Test
    void testCalculatePackageLevelsComplexScenario() {
        // Scenario: com.example (0) <- com.example2 (1) <- com.example1 (2) and com.app (2)
        Map<String, Set<String>> dependencies = new HashMap<>();
        dependencies.put("com.example", Set.of());
        dependencies.put("com.example2", Set.of("com.example"));
        dependencies.put("com.example1", Set.of("com.example2"));
        dependencies.put("com.app", Set.of("com.example2"));
        
        Map<String, Integer> result = strategy.calculatePackageLevels(dependencies);
        
        assertEquals(0, result.get("com.example"));
        assertEquals(1, result.get("com.example2"));
        assertEquals(2, result.get("com.example1"));
        assertEquals(2, result.get("com.app")); // max(com.example2 level 1) + 1 = 2
    }
    
    @Test
    void testGetName() {
        String name = strategy.getName();
        assertEquals("BasicPackageLevelCalculation (with SimpleMaxAggregation)", name);
    }
}
