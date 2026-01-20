package de.weigend.s202.analysis.strategy;

import de.weigend.s202.analysis.strategy.aggregation.SimpleMaxAggregationStrategy;
import de.weigend.s202.analysis.strategy.aggregation.WeightedAggregationStrategy;
import de.weigend.s202.analysis.strategy.impl.BasicClassLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.impl.BasicPackageLevelCalculationStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class LevelCalculationStrategyContextTest {
    
    @Test
    void testCreateDefault() {
        LevelCalculationStrategyContext context = LevelCalculationStrategyContext.createDefault();
        
        assertNotNull(context);
        assertNotNull(context.getClassLevelStrategy());
        assertNotNull(context.getPackageLevelStrategy());
        assertNotNull(context.getAggregationStrategy());
    }
    
    @Test
    void testCreateCustom() {
        ClassAggregationStrategy aggregation = new SimpleMaxAggregationStrategy();
        ClassLevelCalculationStrategy classStrategy = 
            new BasicClassLevelCalculationStrategy(aggregation);
        PackageLevelCalculationStrategy packageStrategy = 
            new BasicPackageLevelCalculationStrategy(aggregation);
        
        LevelCalculationStrategyContext context = 
            new LevelCalculationStrategyContext(classStrategy, packageStrategy, aggregation);
        
        assertNotNull(context);
        assertEquals(classStrategy, context.getClassLevelStrategy());
        assertEquals(packageStrategy, context.getPackageLevelStrategy());
        assertEquals(aggregation, context.getAggregationStrategy());
    }
    
    @Test
    void testToString() {
        LevelCalculationStrategyContext context = LevelCalculationStrategyContext.createDefault();
        String description = context.toString();
        
        assertNotNull(description);
        assertEquals(true, description.contains("LevelCalculationStrategyContext"));
        assertEquals(true, description.contains("BasicClassLevelCalculation"));
        assertEquals(true, description.contains("BasicPackageLevelCalculation"));
    }
    
    private void assertEquals(Object expected, Object actual) {
        assert expected.equals(actual) : "Expected " + expected + " but was " + actual;
    }
}
