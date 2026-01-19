package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureModelBuilder.NodeType;
import de.weigend.s202.ui.model.UIModel;
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
 * Works directly with UIModel for correct level-based display.
 */
public class LevelBasedLayoutView extends StackPane {
    private Map<String, Boolean> expandedState;
    private List<ClassifiedEdge> classifiedEdges;
    private Canvas violationCanvas;
    private VBox contentBox;
    private UIModel currentUIModel;
    
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
        
        System.out.println("[LevelBasedLayoutView] setArchitectureRoot called - but this should use UIModel directly!");
        // This method is now deprecated - we should work with UIModel directly
        // For now, just show empty to indicate proper flow is needed
        
        contentBox = new VBox();
        contentBox.setSpacing(15);
        contentBox.setPadding(new Insets(15));
        contentBox.setStyle("-fx-background-color: #f8f8f8;");
        
        Text message = new Text("Use setUIModel() instead of setArchitectureRoot()");
        message.setStyle("-fx-font-size: 12; -fx-fill: #666666;");
        contentBox.getChildren().add(message);
        
        var scrollPane = new javafx.scene.control.ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        
        violationCanvas = new Canvas();
        violationCanvas.setMouseTransparent(true);
        violationCanvas.setStyle("-fx-background-color: transparent;");
        violationCanvas.widthProperty().bind(widthProperty());
        violationCanvas.heightProperty().bind(heightProperty());
        
        getChildren().clear();
        getChildren().addAll(scrollPane, violationCanvas);
    }
    
    /**
     * Set the UIModel - this is the proper way to display level-based layout.
     */
    public void setUIModel(UIModel uiModel) {
        Objects.requireNonNull(uiModel, "uiModel cannot be null");
        
        System.out.println("[LevelBasedLayoutView] Setting UIModel with " + uiModel.getLevelCount() + " levels");
        
        this.currentUIModel = uiModel;
        expandedState.clear();
        
        // Build level-based layout
        contentBox = new VBox();
        contentBox.setSpacing(15);
        contentBox.setPadding(new Insets(15));
        contentBox.setStyle("-fx-background-color: #f8f8f8;");
        
        // Create rows for each level (in ascending order)
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            List<UIModel.UIElementInfo> elements = uiModel.getElementsAtLevel(level);
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
     * Creates a horizontal row for a level, centered with boxes for each element.
     */
    private HBox createLevelRow(int level, List<UIModel.UIElementInfo> elements) {
        HBox row = new HBox();
        row.setSpacing(15);
        row.setPadding(new Insets(10));
        row.setAlignment(Pos.CENTER);
        row.setStyle("-fx-border-color: #cccccc; -fx-border-width: 0 0 1 0; -fx-padding: 10;");
        
        // Sort elements by name for consistent ordering
        List<UIModel.UIElementInfo> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparing(e -> e.fullName));
        
        // Create a box for each element
        for (UIModel.UIElementInfo elem : sorted) {
            Region nodeBox = createElementBox(elem);
            row.getChildren().add(nodeBox);
        }
        
        return row;
    }
    
    /**
     * Creates a styled box for a single element (class or package).
     */
    private Region createElementBox(UIModel.UIElementInfo elem) {
        VBox box = new VBox();
        box.setSpacing(5);
        box.setPadding(new Insets(10));
        
        // Determine colors based on type
        String borderColor = "CLASS".equals(elem.type) ? "#0066CC" : "#FF9800";
        String bgColor = "CLASS".equals(elem.type) ? "#E3F2FD" : "#FFF3E0";
        String icon = "CLASS".equals(elem.type) ? "📄" : "📦";
        
        box.setStyle(String.format(
            "-fx-border-color: %s; -fx-border-width: 2; -fx-background-color: %s; -fx-padding: 10;",
            borderColor, bgColor
        ));
        
        // Header with icon and type
        Text header = new Text(icon + " " + elem.type);
        header.setStyle("-fx-font-size: 10; -fx-font-weight: bold; -fx-fill: #333333;");
        box.getChildren().add(header);
        
        // Full name
        Text fullName = new Text(elem.fullName);
        fullName.setStyle("-fx-font-size: 11; -fx-fill: #000000; -fx-word-spacing: -2;");
        fullName.setWrappingWidth(120);
        box.getChildren().add(fullName);
        
        // Simple name (for emphasis)
        Text simpleName = new Text(elem.simpleName);
        simpleName.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-fill: #0066CC;");
        box.getChildren().add(simpleName);
        
        // Add size constraint - preferred size
        box.setPrefWidth(150);
        box.setMinWidth(140);
        box.setMaxWidth(160);
        
        return box;
    }
}
