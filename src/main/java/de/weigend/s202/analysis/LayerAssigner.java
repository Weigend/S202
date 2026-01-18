package de.weigend.s202.analysis;

import de.weigend.s202.analysis.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.analysis.ArchitectureModelBuilder.NodeType;

import java.util.*;

/**
 * Assigns architectural layers to all nodes in the tree.
 * Layers are assigned based on package dependencies using topological sorting.
 */
public class LayerAssigner {
    
    /**
     * Assigns layers to all nodes in the architecture tree.
     * Recursive processing to handle nested packages.
     */
    public void assignLayers(ArchitectureNode rootNode) {
        // First, collect all packages with their dependency information
        Map<String, PackageInfo> packageInfoMap = new HashMap<>();
        collectPackageInfo(rootNode, packageInfoMap);
        
        System.out.println("\n=== LAYER ASSIGNER START ===");
        System.out.println("Found " + packageInfoMap.size() + " packages");
        
        // Calculate layers using topological sort
        Map<String, Integer> layers = calculateLayers(packageInfoMap);
        
        System.out.println("Calculated layers:");
        layers.forEach((pkg, layer) -> System.out.println("  " + pkg + " -> Layer " + layer));
        
        // Assign layers to nodes
        assignLayersToNodes(rootNode, layers);
        System.out.println("=== LAYER ASSIGNER END ===\n");
    }
    
    /**
     * Collects all packages and their dependencies.
     */
    private void collectPackageInfo(ArchitectureNode node, Map<String, PackageInfo> infoMap) {
        if (node.getType() == NodeType.PACKAGE) {
            String fullName = node.getFullName();
            PackageInfo info = new PackageInfo(fullName);
            
            // Normalize dependencies to full package names (should already be full names from ArchitectureNode)
            Set<String> deps = node.getDependencies();
            if (deps != null) {
                info.dependencies = new HashSet<>(deps);
            }
            
            System.out.println("COLLECT: Package=" + fullName + ", RawDeps=" + deps);
            infoMap.put(fullName, info);
        }
        
        for (ArchitectureNode child : node.getChildren()) {
            collectPackageInfo(child, infoMap);
        }
    }
    
    /**
     * Calculates layers using reverse topological sort.
     * Layer 0 = packages that depend on many others (top layer - e.g., UI)
     * Layer N = packages with fewer/no dependencies (bottom layer - e.g., Model)
     */
    private Map<String, Integer> calculateLayers(Map<String, PackageInfo> packageInfoMap) {
        Map<String, Integer> layers = new HashMap<>();
        
        // Build reverse dependency graph: package -> packages that depend on it
        Map<String, Set<String>> reverseDeps = new HashMap<>();
        Map<String, Set<String>> internalDeps = new HashMap<>();
        
        System.out.println("=== DEPENDENCY COLLECTION ===");
        for (String pkgName : packageInfoMap.keySet()) {
            reverseDeps.put(pkgName, new HashSet<>());
            Set<String> deps = new HashSet<>();
            Set<String> allDeps = packageInfoMap.get(pkgName).dependencies;
            System.out.println("  Package: " + pkgName);
            System.out.println("    All dependencies: " + allDeps);
            
            for (String dep : allDeps) {
                if (packageInfoMap.containsKey(dep)) {
                    deps.add(dep);
                    System.out.println("      -> INTERNAL: " + dep);
                } else {
                    System.out.println("      -> EXTERNAL: " + dep);
                }
            }
            internalDeps.put(pkgName, deps);
            System.out.println("    Internal deps: " + deps);
        }
        System.out.println("=== END DEPENDENCY COLLECTION ===\n");
        
        // Build reverse dependencies
        for (String pkgName : internalDeps.keySet()) {
            for (String dep : internalDeps.get(pkgName)) {
                reverseDeps.get(dep).add(pkgName);
            }
        }
        
        // Find layer for each package by calculating the maximum distance to a leaf
        for (String pkgName : packageInfoMap.keySet()) {
            layers.put(pkgName, calculatePackageLayer(pkgName, internalDeps, new HashMap<>()));
        }
        
        return layers;
    }
    
    /**
     * Calculates the layer of a package by finding the longest path to a leaf.
     * Package with longest dependency path = layer 0 (top).
     */
    private int calculatePackageLayer(String pkgName, Map<String, Set<String>> internalDeps, Map<String, Integer> cache) {
        if (cache.containsKey(pkgName)) {
            return cache.get(pkgName);
        }
        
        Set<String> deps = internalDeps.get(pkgName);
        System.out.println("  CALC_LAYER: " + pkgName + " -> deps=" + deps);
        
        if (deps == null || deps.isEmpty()) {
            // Leaf package (no internal dependencies)
            System.out.println("    -> is LEAF, layer=0");
            cache.put(pkgName, 0);
            return 0;
        }
        
        // Layer = 1 + max layer of dependencies
        int maxDepLayer = 0;
        for (String dep : deps) {
            int depLayer = calculatePackageLayer(dep, internalDeps, cache);
            System.out.println("    -> dep " + dep + " has layer " + depLayer);
            maxDepLayer = Math.max(maxDepLayer, depLayer);
        }
        
        int layer = maxDepLayer + 1;
        System.out.println("    -> calculated layer=" + layer);
        cache.put(pkgName, layer);
        return layer;
    }
    
    /**
     * Assigns calculated layers to nodes in the tree.
     */
    private void assignLayersToNodes(ArchitectureNode node, Map<String, Integer> layers) {
        if (node.getType() == NodeType.PACKAGE && layers.containsKey(node.getFullName())) {
            node.setLayer(layers.get(node.getFullName()));
        }
        
        for (ArchitectureNode child : node.getChildren()) {
            assignLayersToNodes(child, layers);
        }
    }
    
    /**
     * Helper class to store package information during calculation.
     */
    private static class PackageInfo {
        String name;
        Set<String> dependencies;
        
        PackageInfo(String name) {
            this.name = name;
            this.dependencies = new HashSet<>();
        }
    }
}
