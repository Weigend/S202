package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.analysis.scc.EdgeClassification.ClassifiedEdge;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.*;

/**
 * Level-based layout view: displays elements organized by levels as horizontal rows.
 * Each level represents a row with elements (classes and packages) arranged horizontally and centered.
 * Works directly with ArchitectureNode for correct level-based display.
 */
public class LevelBasedLayoutView extends StackPane {
    private Map<String, Boolean> expandedState;
    private List<ClassifiedEdge> classifiedEdges;
    private Canvas violationCanvas;
    private VBox contentBox;
    private ArchitectureNode currentRoot;
    
    public LevelBasedLayoutView() {
        this.expandedState = new HashMap<>();
        this.classifiedEdges = new ArrayList<>();
        setStyle("-fx-font-family: 'Monospace'; -fx-font-size: 11;");
    }
    
    /**
     * Set the ArchitectureNode root for level-based display.
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        setArchitectureRoot(rootNode, new ArrayList<>());
    }
    
    /**
     * Set the ArchitectureNode root with classified edges for level-based display.
     */
    public void setArchitectureRoot(ArchitectureNode rootNode, List<ClassifiedEdge> classifiedEdges) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        
        this.currentRoot = rootNode;
        this.classifiedEdges = classifiedEdges != null ? classifiedEdges : new ArrayList<>();
        expandedState.clear();
        
        int levelCount = rootNode.getLevelCount();
        int maxLevel = rootNode.getMaxLevel();
        System.out.println("[LevelBasedLayoutView] Setting ArchitectureNode with " + levelCount + " levels, max=" + maxLevel);
        
        // Build level-based layout
        contentBox = new VBox();
        contentBox.setSpacing(15);
        contentBox.setPadding(new Insets(15));
        contentBox.setStyle("-fx-background-color: #f8f8f8;");
        
        // Collect all nodes by level
        Map<Integer, List<ArchitectureNode>> nodesByLevel = new TreeMap<>();
        collectNodesByLevel(rootNode, nodesByLevel);
        
        // Create rows for each level (in ascending order)
        for (Map.Entry<Integer, List<ArchitectureNode>> entry : nodesByLevel.entrySet()) {
            int level = entry.getKey();
            List<ArchitectureNode> elements = entry.getValue();
            System.out.println("  Level " + level + ": " + elements.size() + " elements");
            HBox levelRow = createLevelRow(level, elements);
            contentBox.getChildren().add(levelRow);
        }
        
        // Create wrapper with scroll pane
        var scrollPane = new javafx.scene.control.ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-control-inner-background: #f8f8f8;");
        
        // Create violation overlay canvas
        violationCanvas = new Canvas();
        violationCanvas.setMouseTransparent(true);
        violationCanvas.setStyle("-fx-background-color: transparent;");
        
        // Bind canvas size to pane
        violationCanvas.widthProperty().bind(widthProperty());
        violationCanvas.heightProperty().bind(heightProperty());
        
        // Stack: scroll pane behind, canvas on top for violations
        getChildren().clear();
        getChildren().addAll(scrollPane, violationCanvas);
    }
    
    /**
     * Recursively collects all nodes by their level.
     */
    private void collectNodesByLevel(ArchitectureNode node, Map<Integer, List<ArchitectureNode>> nodesByLevel) {
        // Skip root node (it's synthetic)
        if (!"root".equals(node.getFullName())) {
            nodesByLevel.computeIfAbsent(node.getLevel(), k -> new ArrayList<>()).add(node);
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectNodesByLevel(child, nodesByLevel);
        }
    }
    
    /**
     * Creates a horizontal row for a level, centered with boxes for each element.
     */
    private HBox createLevelRow(int level, List<ArchitectureNode> elements) {
        HBox row = new HBox();
        row.setSpacing(15);
        row.setPadding(new Insets(10));
        row.setAlignment(Pos.CENTER);
        row.setStyle("-fx-border-color: #cccccc; -fx-border-width: 0 0 1 0; -fx-padding: 10;");
        
        // Sort elements by name for consistent ordering
        List<ArchitectureNode> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparing(ArchitectureNode::getFullName));
        
        // Create a box for each element
        for (ArchitectureNode elem : sorted) {
            Region nodeBox = createElementBox(elem);
            row.getChildren().add(nodeBox);
        }
        
        return row;
    }
    
    /**
     * Creates a styled box for a single element (class or package).
     */
    private Region createElementBox(ArchitectureNode elem) {
        VBox box = new VBox();
        box.setSpacing(5);
        box.setPadding(new Insets(10));
        
        // Determine colors based on type
        boolean isClass = elem.getType() == NodeType.CLASS;
        String borderColor = isClass ? "#0066CC" : "#FF9800";
        String bgColor = isClass ? "#E3F2FD" : "#FFF3E0";
        String icon = isClass ? "📄" : "📦";
        String typeName = isClass ? "CLASS" : "PACKAGE";
        
        box.setStyle(String.format(
            "-fx-border-color: %s; -fx-border-width: 2; -fx-background-color: %s; -fx-padding: 10;",
            borderColor, bgColor
        ));
        
        // Header with icon and type
        Text header = new Text(icon + " " + typeName);
        header.setStyle("-fx-font-size: 10; -fx-font-weight: bold; -fx-fill: #333333;");
        box.getChildren().add(header);
        
        // Full name
        Text fullName = new Text(elem.getFullName());
        fullName.setStyle("-fx-font-size: 11; -fx-fill: #000000; -fx-word-spacing: -2;");
        fullName.setWrappingWidth(120);
        box.getChildren().add(fullName);
        
        // Simple name (for emphasis)
        Text simpleName = new Text(elem.getSimpleName());
        simpleName.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-fill: #0066CC;");
        box.getChildren().add(simpleName);
        
        // Add size constraint - preferred size
        box.setPrefWidth(150);
        box.setMinWidth(140);
        box.setMaxWidth(160);
        
        return box;
    }
}
