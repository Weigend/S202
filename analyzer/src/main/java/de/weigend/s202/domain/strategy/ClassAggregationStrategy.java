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

import java.util.Set;

/**
 * Strategy for aggregating levels from multiple dependencies.
 * Used when a class or package depends on multiple elements to decide its level.
 */
public interface ClassAggregationStrategy {
    
    /**
     * Aggregate levels from multiple dependencies.
     * 
     * @param dependencyLevels Levels of all elements this class/package depends on
     * @return The aggregated level for this element (typically max + 1 or similar)
     */
    int aggregate(Set<Integer> dependencyLevels);
    
    /**
     * Get a human-readable name of this strategy.
     * 
     * @return Strategy name for logging and debugging
     */
    String getName();
}
