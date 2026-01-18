package de.weigend.s202.analysis;

import de.weigend.s202.model.JavaPackage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculates the architectural layer (level) for each package.
 * Layer 0 = top (packages that depend on others)
 * Layer N = bottom (packages with minimal dependencies)
 * 
 * Uses topological sorting based on package dependencies.
 */
public class ArchitectureLayerCalculator {
    
    /**
     * Calculates layers for a package hierarchy.
     * Returns a map from package name to its layer number.
     */
    public Map<String, Integer> calculateLayers(JavaPackage rootPackage) {
        Map<String, Integer> layers = new HashMap<>();
        Map<String, Set<String>> dependencyGraph = buildDependencyGraph(rootPackage);
        
        // Topological sort with level assignment
        Set<String> processed = new HashSet<>();
        int currentLevel = 0;
        
        while (processed.size() < dependencyGraph.size()) {
            // Find all packages that only depend on already-processed packages
            Set<String> nextLevel = new HashSet<>();
            
            for (String pkg : dependencyGraph.keySet()) {
                if (processed.contains(pkg)) {
                    continue;  // Already assigned
                }
                
                Set<String> deps = dependencyGraph.get(pkg);
                if (deps == null || deps.isEmpty()) {
                    // No dependencies, assign to current level
                    nextLevel.add(pkg);
                } else {
                    // Check if all dependencies are processed
                    if (processed.containsAll(deps)) {
                        nextLevel.add(pkg);
                    }
                }
            }
            
            if (nextLevel.isEmpty()) {
                // No progress - circular dependency or isolation
                // Assign remaining unprocessed packages
                for (String pkg : dependencyGraph.keySet()) {
                    if (!processed.contains(pkg)) {
                        nextLevel.add(pkg);
                    }
                }
            }
            
            // Assign all packages in nextLevel to currentLevel
            for (String pkg : nextLevel) {
                layers.put(pkg, currentLevel);
                processed.add(pkg);
            }
            
            currentLevel++;
        }
        
        return layers;
    }
    
    /**
     * Builds a dependency graph: package -> set of packages it depends on.
     */
    private Map<String, Set<String>> buildDependencyGraph(JavaPackage rootPackage) {
        Map<String, Set<String>> graph = new HashMap<>();
        
        // Collect all packages
        Set<String> allPackages = new HashSet<>();
        collectAllPackages(rootPackage, allPackages);
        
        // Initialize all packages with empty dependencies
        for (String pkg : allPackages) {
            graph.put(pkg, new HashSet<>());
        }
        
        // Add dependencies
        addDependencies(rootPackage, graph, allPackages);
        
        return graph;
    }
    
    /**
     * Recursively collects all package names in the hierarchy.
     */
    private void collectAllPackages(JavaPackage pkg, Set<String> allPackages) {
        allPackages.add(pkg.getPackageName());
        for (JavaPackage subPkg : pkg.getSubPackages().values()) {
            collectAllPackages(subPkg, allPackages);
        }
    }
    
    /**
     * Adds dependencies to the graph for all packages.
     */
    private void addDependencies(JavaPackage pkg, Map<String, Set<String>> graph, Set<String> allPackages) {
        String pkgName = pkg.getPackageName();
        Set<String> deps = pkg.getPackageDependencies();
        
        // Only add project-internal dependencies
        if (deps != null) {
            Set<String> internalDeps = deps.stream()
                .filter(allPackages::contains)
                .collect(Collectors.toSet());
            
            graph.put(pkgName, internalDeps);
        }
        
        // Recursively process sub-packages
        for (JavaPackage subPkg : pkg.getSubPackages().values()) {
            addDependencies(subPkg, graph, allPackages);
        }
    }
    
    /**
     * Gets the layer for a package, or -1 if not found.
     */
    public int getLayer(String packageName, Map<String, Integer> layers) {
        return layers.getOrDefault(packageName, -1);
    }
    
    /**
     * Groups packages by their layer.
     */
    public Map<Integer, Set<String>> groupByLayer(Map<String, Integer> layers) {
        Map<Integer, Set<String>> byLayer = new TreeMap<>();
        
        for (Map.Entry<String, Integer> entry : layers.entrySet()) {
            int layer = entry.getValue();
            byLayer.computeIfAbsent(layer, k -> new HashSet<>())
                .add(entry.getKey());
        }
        
        return byLayer;
    }
}
