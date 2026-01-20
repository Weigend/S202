package de.weigend.s202.analysis.strategy;

import java.util.Set;
import java.util.Map;

/**
 * Strategy for calculating levels of Java packages based on package dependencies.
 * This is the second phase after class levels are calculated.
 * 
 * This interface is independent of the model layer to allow pluggable implementations.
 */
public interface PackageLevelCalculationStrategy {
    
    /**
     * Calculate levels for all packages based on their dependencies.
     * A package's level is determined by the packages it depends on.
     * 
     * @param packageDependencies Map from package name to set of package names it depends on
     * @return Map of package names to their calculated levels
     */
    Map<String, Integer> calculatePackageLevels(Map<String, Set<String>> packageDependencies);
    
    /**
     * Get a human-readable name of this strategy.
     * 
     * @return Strategy name for logging and debugging
     */
    String getName();
}
