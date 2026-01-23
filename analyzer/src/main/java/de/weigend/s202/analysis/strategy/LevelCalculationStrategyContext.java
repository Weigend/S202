package de.weigend.s202.analysis.strategy;

import java.util.Objects;

/**
 * Context for managing and applying level calculation strategies.
 * Provides class-level calculation strategy configuration.
 * 
 * Note: Package-level calculation is handled directly in LevelCalculator
 * with specialized logic for subtree detection and cross-package dependencies.
 * 
 * Use LevelCalculationStrategyFactory.createDefault() for default configuration.
 */
public class LevelCalculationStrategyContext {
    
    private final ClassLevelCalculationStrategy classLevelStrategy;
    private final ClassAggregationStrategy aggregationStrategy;
    
    /**
     * Create a context with explicit strategy configuration.
     */
    public LevelCalculationStrategyContext(
        ClassLevelCalculationStrategy classLevelStrategy,
        ClassAggregationStrategy aggregationStrategy
    ) {
        this.classLevelStrategy = Objects.requireNonNull(classLevelStrategy, 
            "classLevelStrategy cannot be null");
        this.aggregationStrategy = Objects.requireNonNull(aggregationStrategy, 
            "aggregationStrategy cannot be null");
    }
    
    public ClassLevelCalculationStrategy getClassLevelStrategy() {
        return classLevelStrategy;
    }
    
    public ClassAggregationStrategy getAggregationStrategy() {
        return aggregationStrategy;
    }
    
    @Override
    public String toString() {
        return "LevelCalculationStrategyContext{" +
            "classLevelStrategy=" + classLevelStrategy.getName() +
            ", aggregationStrategy=" + aggregationStrategy.getName() +
            '}';
    }
}
