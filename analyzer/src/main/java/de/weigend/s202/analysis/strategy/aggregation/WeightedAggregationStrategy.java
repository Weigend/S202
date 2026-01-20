package de.weigend.s202.analysis.strategy.aggregation;

import de.weigend.s202.analysis.strategy.ClassAggregationStrategy;

import java.util.Set;
import java.util.Objects;

/**
 * Weighted aggregation strategy that considers both max and average dependencies.
 * Useful for cases where many lightweight dependencies should increase level less than one heavy dependency.
 */
public class WeightedAggregationStrategy implements ClassAggregationStrategy {
    
    private static final double WEIGHT_MAX = 0.7;
    private static final double WEIGHT_AVG = 0.3;
    
    @Override
    public int aggregate(Set<Integer> dependencyLevels) {
        Objects.requireNonNull(dependencyLevels, "dependencyLevels cannot be null");
        
        if (dependencyLevels.isEmpty()) {
            return 0; // No dependencies -> level 0 (leaf)
        }
        
        int maxLevel = dependencyLevels.stream()
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
        
        double avgLevel = dependencyLevels.stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);
        
        // Weighted combination: favor max level but consider average
        double weightedLevel = (maxLevel * WEIGHT_MAX) + (avgLevel * WEIGHT_AVG);
        
        // Return weighted value + 1, rounded up
        return (int) Math.ceil(weightedLevel) + 1;
    }
    
    @Override
    public String getName() {
        return "WeightedAggregation";
    }
}
