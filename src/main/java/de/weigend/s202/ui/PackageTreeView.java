package de.weigend.s202.ui;

import de.weigend.s202.analysis.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.analysis.ArchitectureModelBuilder.NodeType;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.*;

/**
 * Hierarchical tree view of packages using JavaFX VBox/HBox components.
 * Each package is a collapsible container showing sub-packages and dependencies.
 */
public class PackageTreeView extends ScrollPane {
    private Set<String> projectPackageNames;
    private Map<String, Boolean> expandedState;
    
    public PackageTreeView() {
        this.projectPackageNames = new HashSet<>();
        this.expandedState = new HashMap<>();
        
        setFitToWidth(true);
        setStyle("-fx-font-family: 'Monospace'; -fx-font-size: 11;");
    }
    
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        
        projectPackageNames.clear();
        expandedState.clear();
        collectProjectPackageNames(rootNode);
        
        // Initialize all packages as expanded
        initializeExpandedState(rootNode);
        
        // DEBUG: Print layers
        printLayers(rootNode);
        
        // Build the tree - start from the root and display the full hierarchy
        VBox root = new VBox();
        root.setSpacing(5);
        root.setPadding(new Insets(10));
        
        // Display the complete hierarchy, starting from rootNode
        root.getChildren().add(buildFullHierarchy(rootNode));
        
        setContent(root);
    }
    
    /**
     * Builds the full hierarchical structure for the root node.
     */
    private VBox buildFullHierarchy(ArchitectureNode rootNode) {
        System.out.println("buildFullHierarchy: " + rootNode.getFullName() + " (type=" + rootNode.getType() + ")");
        
        if (rootNode.getType() == NodeType.PACKAGE) {
            // This is a package, build it as a package box
            System.out.println("  -> Building as package box");
            return buildPackageBox(rootNode, 0);
        } else {
            // This is the root container, just show its package children
            System.out.println("  -> Building as container");
            VBox container = new VBox();
            container.setSpacing(5);
            
            // Find package children and build them
            for (ArchitectureNode child : rootNode.getChildren()) {
                System.out.println("    Child: " + child.getFullName() + " (type=" + child.getType() + ")");
                if (child.getType() == NodeType.PACKAGE) {
                    container.getChildren().add(buildFullHierarchy(child));
                }
            }
            
            return container;
        }
    }
    
    /**
     * Recursively builds a UI box for a package.
     */
    private VBox buildPackageBox(ArchitectureNode packageNode, int depth) {
        VBox packageBox = new VBox();
        packageBox.setSpacing(3);
        packageBox.setStyle("-fx-border-color: #0066CC; -fx-border-width: 2; -fx-padding: 10;");
        
        // Set background color based on depth
        String bgColor = switch(depth % 3) {
            case 0 -> "#E3F2FD";
            case 1 -> "#F3E5F5";
            default -> "#E0F2F1";
        };
        packageBox.setStyle(packageBox.getStyle() + "; -fx-background-color: " + bgColor + ";");
        
        // Header with expand/collapse toggle and package info
        HBox header = buildPackageHeader(packageNode, depth);
        packageBox.getChildren().add(header);
        
        // Content container (sub-packages and classes)
        VBox contentBox = new VBox();
        contentBox.setSpacing(8);
        contentBox.setPadding(new Insets(10, 0, 0, 20));
        
        boolean isExpanded = expandedState.getOrDefault(packageNode.getFullName(), true);
        contentBox.setVisible(isExpanded);
        contentBox.setManaged(isExpanded);
        
        // Add sub-packages (sorted by dependencies)
        List<ArchitectureNode> subPackages = new ArrayList<>();
        List<ArchitectureNode> classes = new ArrayList<>();
        
        for (ArchitectureNode child : packageNode.getChildren()) {
            if (child.getType() == NodeType.PACKAGE) {
                subPackages.add(child);
            } else if (child.getType() == NodeType.CLASS) {
                classes.add(child);
            }
        }
        
        // Group sub-packages by their layer
        Map<Integer, List<ArchitectureNode>> subPackagesByLayer = new TreeMap<>((a, b) -> Integer.compare(b, a)); // descending
        for (ArchitectureNode subPkg : subPackages) {
            int layer = subPkg.getLayer();
            if (layer == -1) layer = Integer.MIN_VALUE;
            subPackagesByLayer.computeIfAbsent(layer, k -> new ArrayList<>()).add(subPkg);
        }
        
        // Create horizontal rows for each layer of sub-packages
        for (Integer layer : subPackagesByLayer.keySet()) {
            HBox layerRow = new HBox();
            layerRow.setSpacing(8);
            layerRow.setAlignment(javafx.geometry.Pos.CENTER);
            layerRow.setStyle("-fx-border-color: #dddddd; -fx-border-width: 1; -fx-padding: 5;");
            
            for (ArchitectureNode subPkg : subPackagesByLayer.get(layer)) {
                layerRow.getChildren().add(buildPackageBox(subPkg, depth + 1));
            }
            
            contentBox.getChildren().add(layerRow);
        }
        
        // Add classes (if not too many)
        if (!classes.isEmpty()) {
            VBox classesBox = new VBox();
            classesBox.setSpacing(2);
            for (ArchitectureNode classNode : classes) {
                Label classLabel = new Label("📄 " + classNode.getSimpleName());
                classLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 10;");
                classesBox.getChildren().add(classLabel);
            }
            contentBox.getChildren().add(classesBox);
        }
        
        packageBox.getChildren().add(contentBox);
        
        // Store reference for toggling visibility
        header.setUserData(new Object[]{contentBox, packageNode});
        
        return packageBox;
    }
    
    /**
     * Builds the header (with toggle button) for a package.
     */
    private HBox buildPackageHeader(ArchitectureNode packageNode, int depth) {
        HBox header = new HBox();
        header.setSpacing(8);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.setPadding(new Insets(5));
        header.setStyle("-fx-background-color: #BBDEFB; -fx-border-color: #0066CC; -fx-border-width: 1;");
        
        // Expand/Collapse toggle
        Button toggleBtn = new Button("▼");
        toggleBtn.setPrefWidth(20);
        toggleBtn.setPrefHeight(20);
        toggleBtn.setStyle("-fx-font-size: 9; -fx-padding: 2;");
        
        boolean isExpanded = expandedState.getOrDefault(packageNode.getFullName(), true);
        toggleBtn.setText(isExpanded ? "▼" : "▶");
        
        toggleBtn.setOnAction(event -> {
            boolean currentState = expandedState.getOrDefault(packageNode.getFullName(), true);
            boolean newState = !currentState;
            expandedState.put(packageNode.getFullName(), newState);
            toggleBtn.setText(newState ? "▼" : "▶");
            
            // Find and update the content box visibility
            VBox parentBox = (VBox) toggleBtn.getParent().getParent();
            VBox contentBox = (VBox) parentBox.getChildren().get(1);
            contentBox.setVisible(newState);
            contentBox.setManaged(newState);
        });
        
        header.getChildren().add(toggleBtn);
        
        // Package name
        Label nameLabel = new Label(packageNode.getSimpleName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12; -fx-text-fill: #0066CC;");
        header.getChildren().add(nameLabel);
        
        // External dependencies count
        int extDeps = countExternalDependencies(packageNode);
        Label depsLabel = new Label("(" + extDeps + " ext.deps)");
        depsLabel.setStyle("-fx-font-size: 10; -fx-text-fill: #666666;");
        header.getChildren().add(depsLabel);
        
        return header;
    }
    
    /**
     * Recursively collect all project package names.
     */
    private void collectProjectPackageNames(ArchitectureNode node) {
        if (node.getType() == NodeType.PACKAGE) {
            projectPackageNames.add(node.getFullName());
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectProjectPackageNames(child);
        }
    }
    
    /**
     * Initialize expanded state for all packages.
     */
    private void initializeExpandedState(ArchitectureNode node) {
        if (node.getType() == NodeType.PACKAGE) {
            expandedState.put(node.getFullName(), true);
        }
        for (ArchitectureNode child : node.getChildren()) {
            initializeExpandedState(child);
        }
    }
    
    /**
     * Count external dependencies (not in projectPackageNames).
     */
    private int countExternalDependencies(ArchitectureNode node) {
        Set<String> deps = node.getDependencies();
        if (deps == null) return 0;
        
        return (int) deps.stream()
            .filter(dep -> !projectPackageNames.contains(dep))
            .count();
    }
    
    /**
     * DEBUG: Print layers for all packages.
     */
    private void printLayers(ArchitectureNode node) {
        System.out.println("=== LAYER DEBUG ===");
        printLayersRecursive(node, 0);
    }
    
    private void printLayersRecursive(ArchitectureNode node, int depth) {
        if (node.getType() == NodeType.PACKAGE) {
            System.out.println("  ".repeat(depth) + node.getSimpleName() + " -> Layer: " + node.getLayer());
        }
        for (ArchitectureNode child : node.getChildren()) {
            printLayersRecursive(child, depth + 1);
        }
    }}