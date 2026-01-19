package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureModelBuilder.NodeType;
import de.weigend.s202.analysis.scc.EdgeClassification.ClassifiedEdge;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
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
 */
public class LevelBasedLayoutView extends StackPane {
    private Map<String, Boolean> expandedState;
    private List<ClassifiedEdge> classifiedEdges;
    private Canvas violationCanvas;
    private VBox contentBox;
    
    public LevelBasedLayoutView() {
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
        
        // Build level-based layout
        contentBox = new VBox();
        contentBox.setSpacing(15);
        contentBox.setPadding(new Insets(15));
        contentBox.setStyle("-fx-background-color: #f8f8f8;");
        
        // Collect all nodes and group by level
        Map<Integer, List<ArchitectureNode>> nodesByLevel = collectNodesByLevel(rootNode);
        
        // Create rows for each level (in ascending order)
        for (int level : new TreeSet<>(nodesByLevel.keySet())) {
            HBox levelRow = createLevelRow(level, nodesByLevel.get(level));
            contentBox.getChildren().add(levelRow);
        }
        
        // Create wrapper with scroll pane
        var scrollPane = new ScrollPane(contentBox);
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
     * Recursively collect all nodes and group by their level.
     */
    private Map<Integer, List<ArchitectureNode>> collectNodesByLevel(ArchitectureNode node) {
        Map<Integer, List<ArchitectureNode>> result = new HashMap<>();
        
        if (node.getType() == NodeType.CLASS || node.getType() == NodeType.PACKAGE) {
            int level = node.getLayer();
            if (level >= 0) {
                result.computeIfAbsent(level, k -> new ArrayList<>()).add(node);
            }
        }
        
        // Recursively collect from children
        for (ArchitectureNode child : node.getChildren()) {
            Map<Integer, List<ArchitectureNode>> childLevels = collectNodesByLevel(child);
            childLevels.forEach((level, nodes) ->
                result.computeIfAbsent(level, k -> new ArrayList<>()).addAll(nodes)
            );
        }
        
        return result;
    }
    
    /**
     * Creates a horizontal row for a level, centered with boxes for each element.
     */
    private HBox createLevelRow(int level, List<ArchitectureNode> nodes) {
        HBox row = new HBox();
        row.setSpacing(15);
        row.setPadding(new Insets(10));
        row.setAlignment(Pos.CENTER);
        row.setStyle("-fx-border-color: #cccccc; -fx-border-width: 0 0 1 0; -fx-padding: 10;");
        
        // Sort nodes by name for consistent ordering
        nodes.sort(Comparator.comparing(ArchitectureNode::getFullName));
        
        // Create a box for each node
        for (ArchitectureNode node : nodes) {
            Region nodeBox = createElementBox(node);
            row.getChildren().add(nodeBox);
        }
        
        return row;
    }
    
    /**
     * Creates a styled box for a single element (class or package).
     */
    private Region createElementBox(ArchitectureNode node) {
        VBox box = new VBox();
        box.setSpacing(5);
        box.setPadding(new Insets(10));
        
        // Determine colors based on type
        String borderColor = node.getType() == NodeType.CLASS ? "#0066CC" : "#FF9800";
        String bgColor = node.getType() == NodeType.CLASS ? "#E3F2FD" : "#FFF3E0";
        String icon = node.getType() == NodeType.CLASS ? "📄" : "📦";
        
        box.setStyle(String.format(
            "-fx-border-color: %s; -fx-border-width: 2; -fx-background-color: %s; -fx-padding: 10;",
            borderColor, bgColor
        ));
        
        // Header with icon and type
        Text header = new Text(icon + " " + node.getType());
        header.setStyle("-fx-font-size: 10; -fx-font-weight: bold; -fx-fill: #333333;");
        box.getChildren().add(header);
        
        // Full name
        Text fullName = new Text(node.getFullName());
        fullName.setStyle("-fx-font-size: 11; -fx-fill: #000000; -fx-word-spacing: -2;");
        fullName.setWrappingWidth(120);
        box.getChildren().add(fullName);
        
        // Simple name (for emphasis)
        Text simpleName = new Text(node.getSimpleName());
        simpleName.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-fill: #0066CC;");
        box.getChildren().add(simpleName);
        
        // Add size constraint - preferred size
        box.setPrefWidth(150);
        box.setMinWidth(140);
        box.setMaxWidth(160);
        
        return box;
    }
}
