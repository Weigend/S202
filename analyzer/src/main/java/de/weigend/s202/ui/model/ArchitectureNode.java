/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui.model;

import java.util.*;

/**
 * Represents a node in the architecture tree for UI visualization.
 * Can represent either a CLASS or a PACKAGE with hierarchical children.
 * This is the unified UI model - replaces the former UIModel/UIElementInfo.
 */
public class ArchitectureNode {
    
    /**
     * Type of architecture node.
     */
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
    
    private final String fullName;
    private final String simpleName;
    private final NodeType type;
    private final boolean interfaceType;
    private final boolean autoExpanded;
    private final List<ArchitectureNode> children;
    private Set<String> dependencies;
    private Set<String> dependents;
    private int level;
    // Global architecture level (longest dependency-chain depth across the
    // whole DAG). Separate from the local-layer {@link #level} used for
    // vertical placement inside the parent. {@code -1} means "unknown".
    private int architectureLevel = -1;
    private int horizontalLayoutOrder;

    public ArchitectureNode(String fullName, String simpleName, NodeType type, boolean autoExpanded) {
        this.fullName = Objects.requireNonNull(fullName, "fullName cannot be null");
        this.simpleName = Objects.requireNonNull(simpleName, "simpleName cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.interfaceType = false;
        this.autoExpanded = autoExpanded;
        this.children = new ArrayList<>();
        this.dependencies = new HashSet<>();
        this.dependents = new HashSet<>();
        this.level = 0;
        this.horizontalLayoutOrder = 0;
    }
    
    /**
     * Constructor with level.
     */
    public ArchitectureNode(String fullName, String simpleName, NodeType type, boolean autoExpanded, int level) {
        this(fullName, simpleName, type, autoExpanded);
        this.level = level;
    }

    public ArchitectureNode(String fullName, String simpleName, NodeType type, boolean autoExpanded, int level,
                            boolean interfaceType) {
        this.fullName = Objects.requireNonNull(fullName, "fullName cannot be null");
        this.simpleName = Objects.requireNonNull(simpleName, "simpleName cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.interfaceType = interfaceType;
        this.autoExpanded = autoExpanded;
        this.children = new ArrayList<>();
        this.dependencies = new HashSet<>();
        this.dependents = new HashSet<>();
        this.level = level;
        this.horizontalLayoutOrder = 0;
    }

    // ===== Getters =====
    
    public String getFullName() {
        return fullName;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public NodeType getType() {
        return type;
    }

    public boolean isInterfaceType() {
        return interfaceType;
    }

    public boolean isAutoExpanded() {
        return autoExpanded;
    }

    public List<ArchitectureNode> getChildren() {
        return children;
    }
    
    public int getLevel() {
        return level;
    }

    public int getArchitectureLevel() {
        return architectureLevel;
    }

    public int getHorizontalLayoutOrder() {
        return horizontalLayoutOrder;
    }

    public Set<String> getDependencies() {
        return new HashSet<>(dependencies);
    }
    
    public Set<String> getDependents() {
        return new HashSet<>(dependents);
    }

    // ===== Setters / Mutators =====
    
    public void setLevel(int level) {
        this.level = level;
    }

    public void setArchitectureLevel(int architectureLevel) {
        this.architectureLevel = architectureLevel;
    }

    public void setHorizontalLayoutOrder(int horizontalLayoutOrder) {
        this.horizontalLayoutOrder = horizontalLayoutOrder;
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
    
    public void setDependents(Set<String> dependents) {
        this.dependents = new HashSet<>(Objects.requireNonNull(dependents, "dependents cannot be null"));
    }

    // ===== Utility Methods =====
    
    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public int getChildCount() {
        return children.size();
    }
    
    /**
     * Counts total number of nodes in this subtree (including this node).
     */
    public int getTotalNodeCount() {
        int count = 1;
        for (ArchitectureNode child : children) {
            count += child.getTotalNodeCount();
        }
        return count;
    }
    
    /**
     * Gets the maximum level in this subtree.
     */
    public int getMaxLevel() {
        int max = this.level;
        for (ArchitectureNode child : children) {
            max = Math.max(max, child.getMaxLevel());
        }
        return max;
    }
    
    /**
     * Counts number of distinct levels in this subtree.
     */
    public int getLevelCount() {
        Set<Integer> levels = new HashSet<>();
        collectLevels(levels);
        return levels.size();
    }
    
    private void collectLevels(Set<Integer> levels) {
        levels.add(this.level);
        for (ArchitectureNode child : children) {
            child.collectLevels(levels);
        }
    }
    
    /**
     * Collects all nodes at a specific level in this subtree.
     */
    public List<ArchitectureNode> getNodesAtLevel(int targetLevel) {
        List<ArchitectureNode> result = new ArrayList<>();
        collectNodesAtLevel(targetLevel, result);
        return result;
    }
    
    private void collectNodesAtLevel(int targetLevel, List<ArchitectureNode> result) {
        if (this.level == targetLevel) {
            result.add(this);
        }
        for (ArchitectureNode child : children) {
            child.collectNodesAtLevel(targetLevel, result);
        }
    }
    
    /**
     * Returns statistics about this architecture tree.
     */
    public String getStatistics() {
        int totalNodes = getTotalNodeCount();
        int maxLevel = getMaxLevel();
        int levelCount = getLevelCount();
        
        return String.format("ArchitectureNode{name=%s, totalNodes=%d, levels=%d, maxLevel=%d}",
            simpleName, totalNodes, levelCount, maxLevel);
    }

    @Override
    public String toString() {
        return String.format("ArchitectureNode{name='%s', type=%s, level=%d, children=%d}",
            simpleName, type, level, children.size());
    }
}
