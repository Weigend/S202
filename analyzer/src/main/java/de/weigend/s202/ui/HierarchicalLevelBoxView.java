package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureModelBuilder.NodeType;
import de.weigend.s202.analysis.scc.EdgeClassification.ClassifiedEdge;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import java.util.*;

/**
 * Hierarchical view showing packages as expandable containers with classes as boxes.
 * Classes are grouped by level and arranged in horizontal rows, all centered.
 */
public class HierarchicalLevelBoxView extends StackPane {
    private Map<String, Boolean> expandedState;
    private List<ClassifiedEdge> classifiedEdges;
    private Canvas violationCanvas;
    
    public HierarchicalLevelBoxView() {
        this.expandedState = new HashMap<>();
        this.classifiedEdges = new ArrayList<>();
        setStyle("-fx-font-family: 'Monospace'; -fx-font-size: 11;");
    }
    
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        setArchitectureRoot(rootNode, new ArrayList<>());
    }
    
    public void setArchitectureRoot(ArchitectureNode rootNode, List<ClassifiedEdge> classifiedEdges) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        
        expandedState.clear();
        this.classifiedEdges = classifiedEdges != null ? classifiedEdges : new ArrayList<>();
        
        System.out.println("[HierarchicalLevelBoxView] setArchitectureRoot: " + rootNode.getFullName());
        
        // Build the tree
        VBox contentBox = new VBox();
        contentBox.setSpacing(10);
        contentBox.setPadding(new Insets(15));
        contentBox.setStyle("-fx-background-color: #f8f8f8;");
        
        // Recursively build hierarchy starting from root's children
        for (ArchitectureNode child : rootNode.getChildren()) {
            if (child.getType() == NodeType.PACKAGE) {
                contentBox.getChildren().add(buildPackageContainer(child));
            }
        }
        
        // Wrap with scroll pane
        var scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-control-inner-background: #f8f8f8;");
        
        // Create violation overlay canvas
        violationCanvas = new Canvas();
        violationCanvas.setMouseTransparent(true);
        violationCanvas.setStyle("-fx-background-color: transparent;");
        
        violationCanvas.widthProperty().bind(widthProperty());
        violationCanvas.heightProperty().bind(heightProperty());
        
        getChildren().clear();
        getChildren().addAll(scrollPane, violationCanvas);
    }
    
    /**
     * Builds a package container with expand/collapse functionality.
     * Shows the package name in a box and its children (sub-packages and classes).
     */
    private VBox buildPackageContainer(ArchitectureNode packageNode) {
        VBox packageContainer = new VBox();
        packageContainer.setSpacing(8);
        packageContainer.setPadding(new Insets(10));
        packageContainer.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 1; -fx-background-color: #ffffff;");
        
        String packageId = packageNode.getFullName();
        boolean isExpanded = expandedState.getOrDefault(packageId, true);
        
        // Package header with expand/collapse button and box
        HBox packageHeader = new HBox(10);
        packageHeader.setAlignment(Pos.CENTER_LEFT);
        packageHeader.setPadding(new Insets(5));
        
        // Expand/collapse button
        Button expandButton = new Button(isExpanded ? "▼" : "▶");
        expandButton.setStyle("-fx-padding: 2; -fx-font-size: 10;");
        expandButton.setPrefWidth(30);
        
        // Package box
        Region packageBox = createPackageBox(packageNode);
        
        expandButton.setOnAction(e -> {
            boolean newState = !expandedState.getOrDefault(packageId, true);
            expandedState.put(packageId, newState);
            expandButton.setText(newState ? "▼" : "▶");
            updatePackageContents(packageContainer, packageNode, newState);
        });
        
        packageHeader.getChildren().addAll(expandButton, packageBox);
        packageContainer.getChildren().add(packageHeader);
        
        // Add contents if expanded
        if (isExpanded) {
            updatePackageContents(packageContainer, packageNode, true);
        }
        
        return packageContainer;
    }
    
    /**
     * Updates the contents of a package container (classes and sub-packages).
     */
    private void updatePackageContents(VBox packageContainer, ArchitectureNode packageNode, boolean isExpanded) {
        // Remove all children except the header (first child)
        if (packageContainer.getChildren().size() > 1) {
            packageContainer.getChildren().remove(1, packageContainer.getChildren().size());
        }
        
        if (!isExpanded) {
            return;
        }
        
        // Collect classes and organize by level
        Map<Integer, java.util.List<ArchitectureNode>> classesByLevel = new TreeMap<>();
        java.util.List<ArchitectureNode> subPackages = new ArrayList<>();
        
        for (ArchitectureNode child : packageNode.getChildren()) {
            if (child.getType() == NodeType.CLASS) {
                int level = child.getLayer();
                classesByLevel.computeIfAbsent(level, k -> new ArrayList<>()).add(child);
            } else if (child.getType() == NodeType.PACKAGE) {
                subPackages.add(child);
            }
        }
        
        // Add classes grouped by level in horizontal rows
        for (Map.Entry<Integer, java.util.List<ArchitectureNode>> entry : classesByLevel.entrySet()) {
            int level = entry.getKey();
            java.util.List<ArchitectureNode> classes = entry.getValue();
            
            // Create a level group
            VBox levelGroup = new VBox();
            levelGroup.setSpacing(5);
            levelGroup.setPadding(new Insets(5, 10, 5, 30));
            
            // Level label
            Text levelLabel = new Text("Level " + level);
            levelLabel.setStyle("-fx-font-size: 10; -fx-fill: #666666; -fx-font-weight: bold;");
            levelGroup.getChildren().add(levelLabel);
            
            // Classes in horizontal row, centered
            HBox classRow = new HBox(10);
            classRow.setAlignment(Pos.CENTER);
            classRow.setPadding(new Insets(5));
            
            // Sort classes by name for consistent ordering
            classes.sort(Comparator.comparing(ArchitectureNode::getSimpleName));
            
            for (ArchitectureNode classNode : classes) {
                classRow.getChildren().add(createClassBox(classNode));
            }
            
            levelGroup.getChildren().add(classRow);
            packageContainer.getChildren().add(levelGroup);
        }
        
        // Add sub-packages
        for (ArchitectureNode subPackage : subPackages) {
            packageContainer.getChildren().add(buildPackageContainer(subPackage));
        }
    }
    
    /**
     * Creates a styled box for a package.
     */
    private Region createPackageBox(ArchitectureNode packageNode) {
        VBox box = new VBox();
        box.setSpacing(3);
        box.setPadding(new Insets(8));
        
        box.setStyle(
            "-fx-border-color: #FF9800; " +
            "-fx-border-width: 2; " +
            "-fx-background-color: #FFF3E0; " +
            "-fx-padding: 8;"
        );
        
        // Icon and type
        Text header = new Text("📦 PACKAGE");
        header.setStyle("-fx-font-size: 9; -fx-font-weight: bold; -fx-fill: #E65100;");
        box.getChildren().add(header);
        
        // Package name
        Text name = new Text(packageNode.getSimpleName());
        name.setStyle("-fx-font-size: 11; -fx-font-weight: bold; -fx-fill: #FF6F00;");
        box.getChildren().add(name);
        
        // Full name (smaller)
        Text fullName = new Text(packageNode.getFullName());
        fullName.setStyle("-fx-font-size: 9; -fx-fill: #999999;");
        fullName.setWrappingWidth(120);
        box.getChildren().add(fullName);
        
        box.setPrefWidth(160);
        box.setMinWidth(150);
        return box;
    }
    
    /**
     * Creates a styled box for a class.
     */
    private Region createClassBox(ArchitectureNode classNode) {
        VBox box = new VBox();
        box.setSpacing(3);
        box.setPadding(new Insets(8));
        
        box.setStyle(
            "-fx-border-color: #0066CC; " +
            "-fx-border-width: 2; " +
            "-fx-background-color: #E3F2FD; " +
            "-fx-padding: 8;"
        );
        
        // Icon and type
        Text header = new Text("📄 CLASS");
        header.setStyle("-fx-font-size: 9; -fx-font-weight: bold; -fx-fill: #0044CC;");
        box.getChildren().add(header);
        
        // Class name
        Text name = new Text(classNode.getSimpleName());
        name.setStyle("-fx-font-size: 11; -fx-font-weight: bold; -fx-fill: #0066CC;");
        box.getChildren().add(name);
        
        // Level info
        int level = classNode.getLayer();
        Text levelInfo = new Text("Level: " + level);
        levelInfo.setStyle("-fx-font-size: 9; -fx-fill: #666666;");
        box.getChildren().add(levelInfo);
        
        box.setPrefWidth(140);
        box.setMinWidth(130);
        return box;
    }
}
