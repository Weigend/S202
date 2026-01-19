package de.weigend.s202.analysis;

import de.weigend.s202.analysis.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.analysis.ArchitectureModelBuilder.NodeType;
import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.analysis.scc.SCCDAGBuilder;
import de.weigend.s202.analysis.scc.EdgeClassification;

import java.util.*;

/**
 * Assigns architectural layers to all nodes in the tree.
 * Layers are assigned based on package dependencies using SCC analysis and topological sorting.
 * 
 * The algorithm:
 * 1. Stabilizes dependencies by sorting neighbors
 * 2. Computes SCCs using Tarjan's algorithm
 * 3. Builds SCC DAG
 * 4. Performs topological sort with stable tie-breaking
 * 5. Computes levels using longest path in DAG
 * 6. Maps nodes to levels
 */
public class LayerAssigner {
    private List<StronglyConnectedComponent> sccs;
    private Map<String, Integer> nodeToSccId;
    private Map<String, Integer> nodeToLevel;
    private List<EdgeClassification.ClassifiedEdge> classifiedEdges;
    
    /**
     * Assigns layers to all nodes in the architecture tree.
     * Recursive processing to handle nested packages.
     */
    public void assignLayers(ArchitectureNode rootNode) {
        // First, collect all packages with their dependency information
        Map<String, PackageInfo> packageInfoMap = new HashMap<>();
        collectPackageInfo(rootNode, packageInfoMap);
        
        // Stabilize dependencies: sort all neighbors
        Map<String, Set<String>> stableGraph = stabilizeDependencies(packageInfoMap);
        
        // Calculate layers using SCC-based approach
        Map<String, Integer> layers = calculateLayersWithSCC(stableGraph);
        
        // Assign layers to nodes
        assignLayersToNodes(rootNode, layers);
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
            
            infoMap.put(fullName, info);
        }
        
        for (ArchitectureNode child : node.getChildren()) {
            collectPackageInfo(child, infoMap);
        }
    }
    
    /**
     * Stabilizes dependencies by sorting all neighbors for consistent iteration order.
     */
    private Map<String, Set<String>> stabilizeDependencies(Map<String, PackageInfo> packageInfoMap) {
        Map<String, Set<String>> stableGraph = new TreeMap<>();
        
        for (String pkgName : packageInfoMap.keySet()) {
            Set<String> deps = packageInfoMap.get(pkgName).dependencies;
            // Use TreeSet for sorted, stable iteration
            Set<String> sortedDeps = new TreeSet<>(deps);
            stableGraph.put(pkgName, sortedDeps);
        }
        
        return stableGraph;
    }
    
    /**
     * Calculates layers using SCC analysis and topological sort.
     * Returns a mapping of package names to their assigned levels.
     */
    private Map<String, Integer> calculateLayersWithSCC(Map<String, Set<String>> stableGraph) {
        // Step 1: Find SCCs using Tarjan's algorithm
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(stableGraph);
        this.sccs = sccFinder.findSCCs();
        
        // Build node -> SCC mapping
        this.nodeToSccId = buildNodeToSccMapping();
        
        // Step 2: Build SCC DAG
        SCCDAGBuilder dagBuilder = new SCCDAGBuilder(sccs, stableGraph);
        dagBuilder.buildDAG();
        
        // Step 3: Assign levels to SCCs using longest path
        dagBuilder.assignLevels();
        
        List<StronglyConnectedComponent> sortedSccs = dagBuilder.getSortedSCCs();
        
        // Step 4: Map nodes to their levels
        this.nodeToLevel = mapNodesToLevels();
        
        // Step 5: Classify edges for UI rendering
        EdgeClassification edgeClassifier = new EdgeClassification(nodeToLevel, nodeToSccId, stableGraph);
        this.classifiedEdges = edgeClassifier.classifyAllEdges();
        
        int violations = 0, normal = 0, intra = 0;
        for (EdgeClassification.ClassifiedEdge edge : classifiedEdges) {
            if (edge.type == EdgeClassification.EdgeType.VIOLATION) {
                violations++;
            } else if (edge.type == EdgeClassification.EdgeType.INTRA_SCC) {
                intra++;
            } else {
                normal++;
            }
        }
        System.out.println("Summary: " + violations + " violations, " + normal + " normal, " + intra + " intra-SCC");
        
        return nodeToLevel;
    }
    
    /**
     * Builds mapping from node names to their SCC IDs.
     */
    private Map<String, Integer> buildNodeToSccMapping() {
        Map<String, Integer> mapping = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                mapping.put(member, scc.getId());
            }
        }
        return mapping;
    }
    
    /**
     * Maps each node to its level based on its SCC's level.
     */
    private Map<String, Integer> mapNodesToLevels() {
        Map<String, Integer> mapping = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                mapping.put(member, scc.getLevel());
            }
        }
        return mapping;
    }
    
    /**
     * Calculates layers using reverse topological sort (legacy method).
     * Kept for backward compatibility.
     * 
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
            layers.put(pkgName, calculatePackageLayer(pkgName, internalDeps, new HashMap<>(), new HashSet<>()));
        }
        
        return layers;
    }
    
    /**
     * Calculates the layer of a package by finding the longest path to a leaf.
     * Handles cyclic dependencies by detecting cycles during traversal.
     * Package with longest dependency path = layer 0 (top).
     */
    private int calculatePackageLayer(String pkgName, Map<String, Set<String>> internalDeps, 
                                     Map<String, Integer> cache, Set<String> visiting) {
        // Already calculated
        if (cache.containsKey(pkgName)) {
            return cache.get(pkgName);
        }
        
        // Cycle detected: currently visiting this package
        if (visiting.contains(pkgName)) {
            System.out.println("    -> CYCLE DETECTED at " + pkgName + ", assigning layer 0");
            return 0;  // Break cycle by assigning layer 0
        }
        
        Set<String> deps = internalDeps.get(pkgName);
        System.out.println("  CALC_LAYER: " + pkgName + " -> deps=" + deps);
        
        if (deps == null || deps.isEmpty()) {
            // Leaf package (no internal dependencies)
            System.out.println("    -> is LEAF, layer=0");
            cache.put(pkgName, 0);
            return 0;
        }
        
        // Mark as visiting
        visiting.add(pkgName);
        
        // Layer = 1 + max layer of dependencies
        int maxDepLayer = 0;
        for (String dep : deps) {
            int depLayer = calculatePackageLayer(dep, internalDeps, cache, visiting);
            System.out.println("    -> dep " + dep + " has layer " + depLayer);
            maxDepLayer = Math.max(maxDepLayer, depLayer);
        }
        
        // Done visiting
        visiting.remove(pkgName);
        
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
    
    /**
     * Getters for SCC analysis results (used by UI and other components).
     */
    public List<StronglyConnectedComponent> getSCCs() {
        return sccs != null ? new ArrayList<>(sccs) : new ArrayList<>();
    }
    
    public Map<String, Integer> getNodeToSccId() {
        return nodeToSccId != null ? new HashMap<>(nodeToSccId) : new HashMap<>();
    }
    
    public Map<String, Integer> getNodeToLevel() {
        return nodeToLevel != null ? new HashMap<>(nodeToLevel) : new HashMap<>();
    }
    
    public List<EdgeClassification.ClassifiedEdge> getClassifiedEdges() {
        return classifiedEdges != null ? new ArrayList<>(classifiedEdges) : new ArrayList<>();
    }
}
