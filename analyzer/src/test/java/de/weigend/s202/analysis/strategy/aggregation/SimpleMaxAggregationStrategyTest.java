package de.weigend.s202.analysis.strategy.aggregation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleMaxAggregationStrategyTest {
    
    private final SimpleMaxAggregationStrategy strategy = new SimpleMaxAggregationStrategy();
    
    @Test
    void testAggregateEmptySet() {
        Set<Integer> levels = new HashSet<>();
        assertEquals(0, strategy.aggregate(levels));
    }
    
    @Test
    void testAggregateSingleLevel() {
        Set<Integer> levels = Set.of(2);
        assertEquals(3, strategy.aggregate(levels));
    }
    
    @Test
    void testAggregateMultipleLevels() {
        Set<Integer> levels = Set.of(1, 2, 0);
        assertEquals(3, strategy.aggregate(levels)); // max(1,2,0) + 1 = 3
    }
    
    @Test
    void testAggregateZeroLevel() {
        Set<Integer> levels = Set.of(0);
        assertEquals(1, strategy.aggregate(levels));
    }
    
    @Test
    void testAggregateLargeLevels() {
        Set<Integer> levels = Set.of(5, 10, 3);
        assertEquals(11, strategy.aggregate(levels)); // max(5,10,3) + 1 = 11
    }
    
    @Test
    void testGetName() {
        assertEquals("SimpleMaxAggregation", strategy.getName());
    }
}
