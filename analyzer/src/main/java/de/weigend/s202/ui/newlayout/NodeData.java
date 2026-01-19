package de.weigend.s202.ui.newlayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Data model for a hierarchical node (Package, Class, or Interface).
 * Supports parent-child relationships for packages.
 */
public class NodeData {
    
    public enum NodeType {
        PACKAGE, CLASS, INTERFACE
    }

    private final String simpleName;
    private final String fullName;
    private final NodeType nodeType;
    private final List<NodeData> children;
    private String marker;

    /**
     * Create a node with optional children.
     */
    public NodeData(String simpleName, String fullName, NodeType nodeType) {
        this.simpleName = Objects.requireNonNull(simpleName, "simpleName cannot be null");
        this.fullName = Objects.requireNonNull(fullName, "fullName cannot be null");
        this.nodeType = Objects.requireNonNull(nodeType, "nodeType cannot be null");
        this.children = new ArrayList<>();
    }

    /**
     * Add a child node (for packages).
     */
    public void addChild(NodeData child) {
        Objects.requireNonNull(child, "child cannot be null");
        children.add(child);
    }

    /**
     * Add multiple children.
     */
    public void addChildren(NodeData... nodes) {
        for (NodeData node : nodes) {
            addChild(node);
        }
    }

    // Getters
    public String getSimpleName() {
        return simpleName;
    }

    public String getFullName() {
        return fullName;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public List<NodeData> getChildren() {
        return new ArrayList<>(children);
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public String getMarker() {
        return marker;
    }

    public void setMarker(String marker) {
        this.marker = marker;
    }

    @Override
    public String toString() {
        return fullName;
    }
}
