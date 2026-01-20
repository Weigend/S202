package de.weigend.s202.analysis.strategy;

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
