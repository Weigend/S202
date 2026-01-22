package de.weigend.s202.analysis.strategy;

import java.util.Objects;

/**
 * Context for managing and applying level calculation strategies.
 * Allows custom configurations of class and package level calculation strategies.
 * 
 * Note: Use LevelCalculationStrategyFactory.createDefault() for default configuration.
 */
public class LevelCalculationStrategyContext {
    
    private final ClassLevelCalculationStrategy classLevelStrategy;
    private final PackageLevelCalculationStrategy packageLevelStrategy;
    private final ClassAggregationStrategy aggregationStrategy;
    
    /**
     * Create a context with explicit strategy configuration.
     */
    public LevelCalculationStrategyContext(
        ClassLevelCalculationStrategy classLevelStrategy,
        PackageLevelCalculationStrategy packageLevelStrategy,
        ClassAggregationStrategy aggregationStrategy
    ) {
        this.classLevelStrategy = Objects.requireNonNull(classLevelStrategy, 
            "classLevelStrategy cannot be null");
        this.packageLevelStrategy = Objects.requireNonNull(packageLevelStrategy, 
            "packageLevelStrategy cannot be null");
        this.aggregationStrategy = Objects.requireNonNull(aggregationStrategy, 
            "aggregationStrategy cannot be null");
    }
    
    public ClassLevelCalculationStrategy getClassLevelStrategy() {
        return classLevelStrategy;
    }
    
    public PackageLevelCalculationStrategy getPackageLevelStrategy() {
        return packageLevelStrategy;
    }
    
    public ClassAggregationStrategy getAggregationStrategy() {
        return aggregationStrategy;
    }
    
    @Override
    public String toString() {
        return "LevelCalculationStrategyContext{" +
            "classLevelStrategy=" + classLevelStrategy.getName() +
            ", packageLevelStrategy=" + packageLevelStrategy.getName() +
            ", aggregationStrategy=" + aggregationStrategy.getName() +
            '}';
    }
}
