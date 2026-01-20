package de.weigend.s202.analysis.strategy.aggregation;

import de.weigend.s202.analysis.strategy.ClassAggregationStrategy;

import java.util.Set;
import java.util.Objects;

/**
 * Simple aggregation strategy that uses the maximum level + 1.
 * This is the most straightforward approach: a class's level is one above its highest dependency.
 */
public class SimpleMaxAggregationStrategy implements ClassAggregationStrategy {
    
    @Override
    public int aggregate(Set<Integer> dependencyLevels) {
        Objects.requireNonNull(dependencyLevels, "dependencyLevels cannot be null");
        
        if (dependencyLevels.isEmpty()) {
            return 0; // No dependencies -> level 0 (leaf)
        }
        
        // Return max dependency level + 1
        return dependencyLevels.stream()
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0) + 1;
    }
    
    @Override
    public String getName() {
        return "SimpleMaxAggregation";
    }
}
