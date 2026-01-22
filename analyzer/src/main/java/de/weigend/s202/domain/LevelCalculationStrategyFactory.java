package de.weigend.s202.analysis.domain;

import de.weigend.s202.analysis.strategy.ClassAggregationStrategy;
import de.weigend.s202.analysis.strategy.LevelCalculationStrategyContext;
import de.weigend.s202.analysis.strategy.aggregation.SimpleMaxAggregationStrategy;
import de.weigend.s202.analysis.strategy.impl.BasicClassLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.impl.BasicPackageLevelCalculationStrategy;

/**
 * Factory for creating LevelCalculationStrategyContext instances.
 * Located in domain package to avoid cyclic dependencies between strategy and strategy.impl packages.
 */
public final class LevelCalculationStrategyFactory {
    
    private LevelCalculationStrategyFactory() {
        // Utility class - no instantiation
    }
    
    /**
     * Create a context with default simple strategies.
     * Uses SimpleMaxAggregationStrategy with BasicClassLevelCalculationStrategy
     * and BasicPackageLevelCalculationStrategy.
     */
    public static LevelCalculationStrategyContext createDefault() {
        ClassAggregationStrategy aggregation = new SimpleMaxAggregationStrategy();
        return new LevelCalculationStrategyContext(
            new BasicClassLevelCalculationStrategy(aggregation),
            new BasicPackageLevelCalculationStrategy(aggregation),
            aggregation
        );
    }
}
