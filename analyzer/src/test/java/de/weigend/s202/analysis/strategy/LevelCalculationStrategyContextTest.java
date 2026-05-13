package de.weigend.s202.analysis.strategy;

import de.weigend.s202.analysis.strategy.LevelCalculationStrategyFactory;
import de.weigend.s202.analysis.strategy.aggregation.SimpleMaxAggregationStrategy;
import de.weigend.s202.analysis.strategy.impl.BasicClassLevelCalculationStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class LevelCalculationStrategyContextTest {
    
    @Test
    void testCreateDefault() {
        LevelCalculationStrategyContext context = LevelCalculationStrategyFactory.createDefault();
        
        assertNotNull(context);
        assertNotNull(context.getClassLevelStrategy());
        assertNotNull(context.getAggregationStrategy());
    }
    
    @Test
    void testCreateCustom() {
        ClassAggregationStrategy aggregation = new SimpleMaxAggregationStrategy();
        ClassLevelCalculationStrategy classStrategy = 
            new BasicClassLevelCalculationStrategy(aggregation);
        
        LevelCalculationStrategyContext context = 
            new LevelCalculationStrategyContext(classStrategy, aggregation);
        
        assertNotNull(context);
        assertEquals(classStrategy, context.getClassLevelStrategy());
        assertEquals(aggregation, context.getAggregationStrategy());
    }
    
    @Test
    void testToString() {
        LevelCalculationStrategyContext context = LevelCalculationStrategyFactory.createDefault();
        String description = context.toString();
        
        assertNotNull(description);
        assertEquals(true, description.contains("LevelCalculationStrategyContext"));
        assertEquals(true, description.contains("HeuristicSCCBreaking"));
    }
    
    private void assertEquals(Object expected, Object actual) {
        assert expected.equals(actual) : "Expected " + expected + " but was " + actual;
    }
}
