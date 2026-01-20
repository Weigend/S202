package de.weigend.s202.analysis.strategy.impl;

import de.weigend.s202.analysis.strategy.PackageLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.ClassAggregationStrategy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Basic package level calculation strategy.
 * Calculates package levels based on package-to-package dependencies using an aggregation strategy.
 * 
 * Algorithm:
 * 1. Iterate over packages, calculating levels based on dependencies
 * 2. Use aggregation strategy to combine dependency levels
 * 3. Handle cycles by using max level in the cycle + 1
 */
public class BasicPackageLevelCalculationStrategy implements PackageLevelCalculationStrategy {
    
    private final ClassAggregationStrategy aggregationStrategy;
    
    public BasicPackageLevelCalculationStrategy(ClassAggregationStrategy aggregationStrategy) {
        this.aggregationStrategy = Objects.requireNonNull(aggregationStrategy, 
            "aggregationStrategy cannot be null");
    }
    
    @Override
    public Map<String, Integer> calculatePackageLevels(Map<String, Set<String>> packageDependencies) {
        Objects.requireNonNull(packageDependencies, "packageDependencies cannot be null");
        
        Map<String, Integer> packageLevels = new HashMap<>();
        
        // Initialize all packages with level 0
        for (String packageName : packageDependencies.keySet()) {
            packageLevels.put(packageName, 0);
        }
        
        // Process packages iteratively until all levels are stable
        boolean changed = true;
        int iterations = 0;
        int maxIterations = packageDependencies.size() + 10; // Prevent infinite loops
        
        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;
            
            for (Map.Entry<String, Set<String>> entry : packageDependencies.entrySet()) {
                String packageName = entry.getKey();
                Set<String> dependencies = entry.getValue();
                
                // Collect dependency levels
                Set<Integer> dependencyLevels = new java.util.HashSet<>();
                boolean allDependenciesFound = true;
                
                for (String depName : dependencies) {
                    if (packageLevels.containsKey(depName)) {
                        dependencyLevels.add(packageLevels.get(depName));
                    } else {
                        allDependenciesFound = false;
                        break;
                    }
                }
                
                // Only calculate level if all dependencies have been assigned levels
                if (allDependenciesFound) {
                    int newLevel = aggregationStrategy.aggregate(dependencyLevels);
                    
                    if (packageLevels.get(packageName) != newLevel) {
                        packageLevels.put(packageName, newLevel);
                        changed = true;
                    }
                }
            }
        }
        
        return packageLevels;
    }
    
    @Override
    public String getName() {
        return "BasicPackageLevelCalculation (with " + aggregationStrategy.getName() + ")";
    }
}
