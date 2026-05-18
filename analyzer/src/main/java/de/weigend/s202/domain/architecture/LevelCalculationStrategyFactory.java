/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.strategy.ClassAggregationStrategy;
import de.weigend.s202.domain.strategy.ClassLevelCalculationStrategy;
import de.weigend.s202.domain.strategy.LevelCalculationStrategyContext;
import de.weigend.s202.domain.strategy.aggregation.SimpleMaxAggregationStrategy;
import de.weigend.s202.domain.strategy.impl.BasicClassLevelCalculationStrategy;
import de.weigend.s202.domain.strategy.impl.HeuristicSCCBreakingStrategy;

/**
 * Factory for creating LevelCalculationStrategyContext instances. Lives
 * in {@code domain.architecture} next to the {@link LevelCalculator}
 * that consumes it — the strategies in {@code domain.strategy} are pure
 * policy types, the factory plus calculator are their natural callers.
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
