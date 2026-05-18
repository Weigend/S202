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
import java.util.Map;

/**
 * Strategy for calculating levels of individual Java classes based on their dependencies.
 * Implementations define how class levels are determined within the dependency graph.
 * 
 * This interface is independent of the model layer to allow pluggable implementations.
 */
public interface ClassLevelCalculationStrategy {
    
    /**
     * Calculate levels for all classes in the model.
     * 
     * @param classDependencies Map from class name to set of class names it depends on
     * @return Map of class names to their calculated levels
     */
    Map<String, Integer> calculateClassLevels(Map<String, Set<String>> classDependencies);
    
    /**
     * Get a human-readable name of this strategy.
     * 
     * @return Strategy name for logging and debugging
     */
    String getName();
}
