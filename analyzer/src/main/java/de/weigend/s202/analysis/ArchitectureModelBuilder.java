package de.weigend.s202.analysis;

import java.util.*;

/**
 * Minimal architecture model builder.
 * Provides ArchitectureNode and NodeType for UI visualization.
 * Legacy buildModel() methods have been removed - use UIModel pipeline instead.
 */
public class ArchitectureModelBuilder {

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
        private int layer = -1;  // Legacy layer assignment (use UIModel instead)

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
            return children;
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
