package de.weigend.s202.analysis.strategy;

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
