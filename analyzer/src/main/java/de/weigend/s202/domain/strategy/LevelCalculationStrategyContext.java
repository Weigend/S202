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
package de.weigend.s202.domain.strategy;

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
