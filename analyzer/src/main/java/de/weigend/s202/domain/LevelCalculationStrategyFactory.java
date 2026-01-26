package de.weigend.s202.domain;

import de.weigend.s202.analysis.strategy.ClassAggregationStrategy;
import de.weigend.s202.analysis.strategy.ClassLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.LevelCalculationStrategyContext;
import de.weigend.s202.analysis.strategy.aggregation.SimpleMaxAggregationStrategy;
import de.weigend.s202.analysis.strategy.impl.BasicClassLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.impl.HeuristicSCCBreakingStrategy;

/**
 * Factory for creating LevelCalculationStrategyContext instances.
 * Located in domain package to avoid cyclic dependencies between strategy and strategy.impl packages.
 */
public final class LevelCalculationStrategyFactory {
    
    private LevelCalculationStrategyFactory() {
        // Utility class - no instantiation
    }
    
    /**
     * Create a context with default strategies.
     * Uses HeuristicSCCBreakingStrategy which breaks large cycles for better visualization.
     * 
     * Note: Package-level calculation is handled directly in LevelCalculator
     * with specialized logic for subtree detection and cross-package dependencies.
     */
    public static LevelCalculationStrategyContext createDefault() {
        ClassAggregationStrategy aggregation = new SimpleMaxAggregationStrategy();
        return new LevelCalculationStrategyContext(
            new HeuristicSCCBreakingStrategy(),
            aggregation
        );
    }
    
    /**
     * Create a context with heuristic SCC breaking strategy.
     * This is recommended for projects with many cyclic dependencies (e.g., Minecraft).
     * 
     * Instead of putting all classes in a cycle on the same level, this strategy
     * uses heuristics to break cycles and create a more meaningful hierarchy.
     * 
     * @return Strategy context with HeuristicSCCBreakingStrategy
     */
    public static LevelCalculationStrategyContext createWithHeuristicSCCBreaking() {
        ClassAggregationStrategy aggregation = new SimpleMaxAggregationStrategy();
        return new LevelCalculationStrategyContext(
            new HeuristicSCCBreakingStrategy(),
            aggregation
        );
    }
    
    /**
     * Create a context with the basic (non-heuristic) SCC strategy.
     * All classes in a cycle get the same level - no cycle breaking.
     * Useful for testing and cases where strict SCC grouping is needed.
     * 
     * @return Strategy context with BasicClassLevelCalculationStrategy
     */
    public static LevelCalculationStrategyContext createWithBasicStrategy() {
        ClassAggregationStrategy aggregation = new SimpleMaxAggregationStrategy();
        return new LevelCalculationStrategyContext(
            new BasicClassLevelCalculationStrategy(aggregation),
            aggregation
        );
    }
    
    /**
     * Create a context with a custom class level calculation strategy.
     * 
     * @param classLevelStrategy Custom strategy for calculating class levels
     * @return Strategy context with the provided strategy
     */
    public static LevelCalculationStrategyContext createWithStrategy(
            ClassLevelCalculationStrategy classLevelStrategy) {
        ClassAggregationStrategy aggregation = new SimpleMaxAggregationStrategy();
        return new LevelCalculationStrategyContext(
            classLevelStrategy,
            aggregation
        );
    }
}
