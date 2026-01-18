package de.weigend.s202.analysis;

import de.weigend.s202.model.*;

import java.util.*;

/**
 * Builds the architecture model for UI presentation.
 * Handles ordering based on dependencies, cycle detection, and expansion levels.
 */
public class ArchitectureModelBuilder {

    /**
     * Builds the UI model with proper ordering for package visualization.
     *
     * @param rootPackage The root package to visualize
     * @param maxDepth Maximum depth to automatically expand (typically 3)
     * @return ArchitectureNode representing the hierarchy
     */
    public ArchitectureNode buildModel(JavaPackage rootPackage, int maxDepth) {
        Objects.requireNonNull(rootPackage, "rootPackage cannot be null");
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth cannot be negative");

        // Build the hierarchy from the actual root package
        ArchitectureNode rootNode = createNode(rootPackage, 0, maxDepth);
        
        // Add dependency information BEFORE wrapping
        addAllDependencies(rootNode, rootPackage);
        
        // Only wrap with parent packages if the package has deep hierarchy (3+ levels)
        String fullName = rootPackage.getPackageName();
        String[] parts = fullName.split("\\.");
        ArchitectureNode finalRoot = rootNode;
        if (parts.length >= 3) {
            finalRoot = wrapWithParentPackages(rootNode, rootPackage);
        }
        
        sortNodesByDependencies(finalRoot);
        return finalRoot;
    }
    
    /**
     * Wraps a package node with its parent package nodes.
     * Creates the full hierarchy from root packages (e.g., "de") down to the actual package.
     */
    private ArchitectureNode wrapWithParentPackages(ArchitectureNode node, JavaPackage pkg) {
        String fullName = pkg.getPackageName();
        String[] parts = fullName.split("\\.");
        
        // Build parent wrappers from the inside out
        ArchitectureNode currentNode = node;
        
        // Start from the second-to-last part and work backwards to include even the first part ("de")
        for (int i = parts.length - 2; i >= 0; i--) {
            String pkgName = String.join(".", java.util.Arrays.copyOfRange(parts, 0, i + 1));
            String simpleName = parts[i];
            
            // Create a parent wrapper node
            ArchitectureNode parentNode = new ArchitectureNode(
                pkgName,
                simpleName,
                NodeType.PACKAGE,
                true  // auto-expand parent packages
            );
            
            parentNode.addChild(currentNode);
            currentNode = parentNode;
        }
        
        return currentNode;  // Return the outermost (top) node
    }

    private ArchitectureNode createNode(JavaPackage pkg, int currentDepth, int maxDepth) {
        ArchitectureNode node = new ArchitectureNode(
            pkg.getPackageName(),
            pkg.getSimpleName(),
            NodeType.PACKAGE,
            currentDepth <= maxDepth
        );

        // Add classes
        for (JavaClass javaClass : pkg.getClasses().values()) {
            ArchitectureNode classNode = new ArchitectureNode(
                javaClass.getClassName(),
                javaClass.getSimpleName(),
                NodeType.CLASS,
                false
            );
            node.addChild(classNode);
        }

        // Add sub-packages recursively
        if (currentDepth < maxDepth) {
            for (JavaPackage subPkg : pkg.getSubPackages().values()) {
                ArchitectureNode subNode = createNode(subPkg, currentDepth + 1, maxDepth);
                node.addChild(subNode);
            }
        }

        return node;
    }

    /**
     * Adds dependency information to all nodes in the tree.
     */
    private void addAllDependencies(ArchitectureNode node, JavaPackage pkg) {
        // Add this package's dependencies to the node
        Set<String> dependencies = pkg.getPackageDependencies();
        node.setDependencies(new HashSet<>(dependencies));
        
        // Recursively add dependencies for sub-packages
        for (JavaPackage subPkg : pkg.getSubPackages().values()) {
            // Find the corresponding node for this sub-package
            for (ArchitectureNode child : node.getChildren()) {
                if (child.getType() == NodeType.PACKAGE && 
                    child.getFullName().equals(subPkg.getPackageName())) {
                    addAllDependencies(child, subPkg);
                    break;
                }
            }
        }
    }

    /**
     * Sorts children nodes based on their dependencies.
     * Packages with more incoming dependencies are placed lower.
     */
    private void sortNodesByDependencies(ArchitectureNode node) {
        // Recursively sort all nodes
        for (ArchitectureNode child : node.getChildren()) {
            sortNodesByDependencies(child);
        }

        // Sort this node's children
        List<ArchitectureNode> children = new ArrayList<>(node.getChildren());
        children.sort((n1, n2) -> {
            // Packages before classes
            if (n1.getType() != n2.getType()) {
                return Integer.compare(n1.getType().getOrder(), n2.getType().getOrder());
            }
            // Within same type, sort by name
            return n1.getSimpleName().compareTo(n2.getSimpleName());
        });

        node.clearChildren();
        for (ArchitectureNode child : children) {
            node.addChild(child);
        }
    }

    /**
     * Adds dependency information to a node.
     */
    public void addDependencyInfo(ArchitectureNode node, JavaPackage pkg) {
        Objects.requireNonNull(node, "node cannot be null");
        Objects.requireNonNull(pkg, "pkg cannot be null");

        Set<String> dependencies = pkg.getPackageDependencies();
        node.setDependencies(new HashSet<>(dependencies));
    }

    /**
     * Represents a node in the architecture tree for UI visualization.
     */
    public static class ArchitectureNode {
        private final String fullName;
        private final String simpleName;
        private final NodeType type;
        private final boolean autoExpanded;
        private final List<ArchitectureNode> children;
        private Set<String> dependencies;
        private int layer = -1;  // Architectural layer (0 = top/most dependencies)

        public ArchitectureNode(String fullName, String simpleName, NodeType type, boolean autoExpanded) {
            this.fullName = Objects.requireNonNull(fullName, "fullName cannot be null");
            this.simpleName = Objects.requireNonNull(simpleName, "simpleName cannot be null");
            this.type = Objects.requireNonNull(type, "type cannot be null");
            this.autoExpanded = autoExpanded;
            this.children = new ArrayList<>();
            this.dependencies = new HashSet<>();
        }

        public String getFullName() {
            return fullName;
        }

        public String getSimpleName() {
            return simpleName;
        }

        public NodeType getType() {
            return type;
        }

        public boolean isAutoExpanded() {
            return autoExpanded;
        }

        public List<ArchitectureNode> getChildren() {
            return new ArrayList<>(children);
        }

        public void addChild(ArchitectureNode child) {
            Objects.requireNonNull(child, "child cannot be null");
            children.add(child);
        }

        public void clearChildren() {
            children.clear();
        }

        public void setDependencies(Set<String> dependencies) {
            this.dependencies = new HashSet<>(Objects.requireNonNull(dependencies, "dependencies cannot be null"));
        }

        public Set<String> getDependencies() {
            return new HashSet<>(dependencies);
        }

        public boolean hasChildren() {
            return !children.isEmpty();
        }

        public int getChildCount() {
            return children.size();
        }

        public void setLayer(int layer) {
            this.layer = layer;
        }

        public int getLayer() {
            return layer;
        }
    }

    public enum NodeType {
        PACKAGE(0),
        CLASS(1);

        private final int order;

        NodeType(int order) {
            this.order = order;
        }

        public int getOrder() {
            return order;
        }
    }
}
