package de.weigend.s202.analysis.strategy.impl;

import de.weigend.s202.analysis.strategy.ClassLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.ClassAggregationStrategy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Basic class level calculation strategy.
 * Uses an aggregation strategy to combine dependency levels.
 */
public class BasicClassLevelCalculationStrategy implements ClassLevelCalculationStrategy {
    
    private final ClassAggregationStrategy aggregationStrategy;
    
    public BasicClassLevelCalculationStrategy(ClassAggregationStrategy aggregationStrategy) {
        this.aggregationStrategy = Objects.requireNonNull(aggregationStrategy, 
            "aggregationStrategy cannot be null");
    }
    
    @Override
    public Map<String, Integer> calculateClassLevels(Map<String, Set<String>> classDependencies) {
        Objects.requireNonNull(classDependencies, "classDependencies cannot be null");
        
        Map<String, Integer> classLevels = new HashMap<>();
        
        // Initialize all classes with level 0
        for (String className : classDependencies.keySet()) {
            classLevels.put(className, 0);
        }
        
        // Iteratively calculate levels until stable
        boolean changed = true;
        int iterations = 0;
        int maxIterations = classDependencies.size() + 10;
        
        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;
            
            for (Map.Entry<String, Set<String>> entry : classDependencies.entrySet()) {
                String className = entry.getKey();
                Set<String> dependencies = entry.getValue();
                
                // Collect dependency levels (only for classes within the analyzed scope)
                Set<Integer> dependencyLevels = new java.util.HashSet<>();
                
                for (String depName : dependencies) {
                    if (classLevels.containsKey(depName)) {
                        // Internal dependency - include its level
                        dependencyLevels.add(classLevels.get(depName));
                    }
                    // External dependencies (not in the JAR) are ignored for level calculation
                }
                
                // Always calculate level based on known internal dependencies
                int newLevel = aggregationStrategy.aggregate(dependencyLevels);
                
                if (classLevels.get(className) != newLevel) {
                    classLevels.put(className, newLevel);
                    changed = true;
                }
            }
        }
        
        return classLevels;
    }
    
    @Override
    public String getName() {
        return "BasicClassLevelCalculation (with " + aggregationStrategy.getName() + ")";
    }
}
