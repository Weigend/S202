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
 * @deprecated Use {@link de.weigend.s202.analysis.calculated.LevelCalculator} instead.
 */
@Deprecated
public class LayerAssigner {
    /**
     * The algorithm:
     * 1. Stabilizes dependencies by sorting neighbors
     * 2. Computes SCCs using Tarjan's algorithm
     * 3. Builds SCC DAG
     * 4. Performs topological sort with stable tie-breaking
     * 5. Computes levels using longest path in DAG
     * 6. Maps nodes to levels
     */
    
    private List<StronglyConnectedComponent> sccs;
    private Map<String, Integer> nodeToSccId;
    private Map<String, Integer> nodeToLevel;
    private List<EdgeClassification.ClassifiedEdge> classifiedEdges;
    
    /**
     * Assigns layers to all nodes in the architecture tree.
     * Treats both packages and classes as nodes with dependencies.
     */
    public void assignLayers(ArchitectureNode rootNode) {
        // Collect all nodes (packages and classes) with their dependencies
        Map<String, NodeInfo> nodeInfoMap = new HashMap<>();
        collectNodeInfo(rootNode, nodeInfoMap);
        
        // Stabilize dependencies: sort all neighbors
        Map<String, Set<String>> stableGraph = stabilizeDependencies(nodeInfoMap);
        
        // Calculate layers using SCC-based approach
        Map<String, Integer> layers = calculateLayersWithSCC(stableGraph);
        
        // Assign layers to nodes
        assignLayersToNodes(rootNode, layers);
    }
    
    /**
     * Collects all nodes (packages and classes) and their dependencies.
     */
    private void collectNodeInfo(ArchitectureNode node, Map<String, NodeInfo> infoMap) {
        String fullName = node.getFullName();
        NodeInfo info = new NodeInfo(fullName, node.getType());
        
        // Get dependencies from the node
        Set<String> deps = node.getDependencies();
        if (deps != null) {
            info.dependencies = new HashSet<>(deps);
        }
        
        infoMap.put(fullName, info);
        
        // Recursively collect from children (both packages and classes)
        for (ArchitectureNode child : node.getChildren()) {
            collectNodeInfo(child, infoMap);
        }
    }
    
    /**
     * Stabilizes dependencies by sorting all neighbors for consistent iteration order.
     */
    private Map<String, Set<String>> stabilizeDependencies(Map<String, NodeInfo> nodeInfoMap) {
        Map<String, Set<String>> stableGraph = new TreeMap<>();
        
        for (String nodeName : nodeInfoMap.keySet()) {
            Set<String> deps = nodeInfoMap.get(nodeName).dependencies;
            // Use TreeSet for sorted, stable iteration
            Set<String> sortedDeps = new TreeSet<>(deps);
            stableGraph.put(nodeName, sortedDeps);
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
     * Assigns calculated layers to nodes in the tree.
     */
    private void assignLayersToNodes(ArchitectureNode node, Map<String, Integer> layers) {
        String fullName = node.getFullName();
        if (layers.containsKey(fullName)) {
            node.setLayer(layers.get(fullName));
        }
        
        // Calculate class-level layers within this package
        if (node.getType() == NodeType.PACKAGE) {
            System.out.println("DEBUG assignLayersToNodes: Package " + node.getSimpleName() + " has " + node.getChildren().size() + " children");
            assignClassLayers(node);
        }
        
        for (ArchitectureNode child : node.getChildren()) {
            assignLayersToNodes(child, layers);
        }
    }
    
    /**
     * Assigns layers to classes within a package based on their inter-class dependencies.
     */
    private void assignClassLayers(ArchitectureNode packageNode) {
        System.out.println("DEBUG: assignClassLayers called for " + packageNode.getSimpleName());
        System.out.println("  Children count: " + packageNode.getChildren().size());
        
        for (ArchitectureNode child : packageNode.getChildren()) {
            System.out.println("  Child: " + child.getSimpleName() + " type=" + child.getType());
        }
        
        List<ArchitectureNode> classNodes = new ArrayList<>();
        
        // Collect all class children
        for (ArchitectureNode child : packageNode.getChildren()) {
            if (child.getType() == NodeType.CLASS) {
                classNodes.add(child);
            }
        }
        
        System.out.println("DEBUG: Found " + classNodes.size() + " class nodes");
        
        if (classNodes.isEmpty()) return;
        
        System.out.println("DEBUG: Calculating class layers for package " + packageNode.getSimpleName());
        System.out.println("  Classes: " + classNodes.stream().map(ArchitectureNode::getSimpleName).toList());
        
        // Build dependency graph for classes within this package
        Map<String, Set<String>> classDependencies = new HashMap<>();
        
        for (ArchitectureNode classNode : classNodes) {
            Set<String> deps = classNode.getDependencies();
            System.out.println("  " + classNode.getSimpleName() + " full dependencies: " + deps);
            
            if (deps == null) deps = new HashSet<>();
            
            // Filter to only dependencies within this package
            Set<String> internalDeps = new HashSet<>();
            for (String dep : deps) {
                System.out.println("    Checking dep: " + dep);
                // Check if this dependency is another class in this package
                for (ArchitectureNode other : classNodes) {
                    System.out.println("      vs " + other.getFullName());
                    if (other.getFullName().equals(dep)) {
                        internalDeps.add(other.getFullName());
                        System.out.println("      ✓ MATCH (exact)");
                        break;
                    } else if (other.getSimpleName().equals(dep)) {
                        internalDeps.add(other.getFullName());
                        System.out.println("      ✓ MATCH (simple name)");
                        break;
                    }
                }
            }
            
            System.out.println("  " + classNode.getSimpleName() + " INTERNAL dependencies: " + internalDeps);
            classDependencies.put(classNode.getFullName(), internalDeps);
        }
        
        // Calculate layers using longest path (reverse: leaf classes get layer 0)
        Map<String, Integer> classLayers = calculateClassLayers(classNodes, classDependencies);
        
        // Assign calculated layers
        for (ArchitectureNode classNode : classNodes) {
            Integer layer = classLayers.get(classNode.getFullName());
            if (layer != null) {
                classNode.setLayer(layer);
                System.out.println("  Class " + classNode.getSimpleName() + " → Layer " + layer);
            }
        }
    }
    
    /**
     * Calculates layers for classes using longest path algorithm.
     */
    private Map<String, Integer> calculateClassLayers(List<ArchitectureNode> classNodes, 
                                                       Map<String, Set<String>> dependencies) {
        Map<String, Integer> classLayers = new HashMap<>();
        Map<String, Integer> memo = new HashMap<>();
        
        // Calculate layer for each class (longest path to leaf)
        for (ArchitectureNode classNode : classNodes) {
            classLayers.put(classNode.getFullName(), calculateClassLayerRecursive(
                classNode.getFullName(), dependencies, memo));
        }
        
        return classLayers;
    }
    
    /**
     * Recursively calculates layer for a single class.
     */
    private int calculateClassLayerRecursive(String className, 
                                              Map<String, Set<String>> dependencies,
                                              Map<String, Integer> memo) {
        if (memo.containsKey(className)) {
            return memo.get(className);
        }
        
        Set<String> deps = dependencies.getOrDefault(className, new HashSet<>());
        
        if (deps.isEmpty()) {
            // Leaf node: layer 0
            memo.put(className, 0);
            return 0;
        }
        
        // Layer = 1 + max(layer of dependencies)
        int maxDepLayer = deps.stream()
            .mapToInt(dep -> calculateClassLayerRecursive(dep, dependencies, memo))
            .max()
            .orElse(-1);
        
        int layer = maxDepLayer + 1;
        memo.put(className, layer);
        return layer;
    }
    
    /**
     * Helper class to store package information during calculation.
     */
    private static class NodeInfo {
        String name;
        NodeType type;
        Set<String> dependencies;
        
        NodeInfo(String name, NodeType type) {
            this.name = name;
            this.type = type;
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
