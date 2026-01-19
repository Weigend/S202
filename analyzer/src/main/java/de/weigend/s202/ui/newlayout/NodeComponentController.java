package de.weigend.s202.ui.newlayout;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Controller for a reusable node component (Package, Class, or Interface).
 * Supports expand/collapse for packages and selection for all node types.
 * Can display child nodes dynamically.
 */
public class NodeComponentController {

    @FXML
    private HBox nodeBox;
    
    @FXML
    private StackPane iconContainer;
    
    @FXML
    private HBox disclosureControl;
    
    @FXML
    private Label nodeLabel;
    
    @FXML
    private Label markerLabel;

    private NodeData nodeData;
    private boolean isExpanded = false;
    private boolean isSelected = false;
    private Runnable onExpandCollapse;
    private Runnable onSelect;
    private FlowPane childrenContainer;
    private List<NodeComponentController> childControllers = new ArrayList<>();

    /**
     * Create a new NodeComponent programmatically.
     */
    public static NodeComponentController createNodeComponent(NodeData nodeData) throws IOException {
        FXMLLoader loader = new FXMLLoader(
            NodeComponentController.class.getResource("fxml/node-component.fxml")
        );
        loader.load();
        NodeComponentController controller = loader.getController();
        controller.initializeWithData(nodeData);
        return controller;
    }

    /**
     * Called by FXML after loading (empty initialize).
     */
    @FXML
    private void initialize() {
        // Empty - will be populated when initializeWithData is called
    }

    /**
     * Initialize the component with data after FXML loading.
     */
    private void initializeWithData(NodeData nodeData) {
        this.nodeData = Objects.requireNonNull(nodeData, "nodeData cannot be null");

        nodeLabel.setText(nodeData.getSimpleName());
        
        // Set visual style based on node type
        nodeBox.getStyleClass().add("node-" + nodeData.getNodeType().name().toLowerCase());

        // Setup click handlers
        nodeBox.setOnMouseClicked(event -> handleNodeClick());

        // Setup disclosure control (only for packages with children)
        if (nodeData.getNodeType() == NodeData.NodeType.PACKAGE && nodeData.hasChildren()) {
            disclosureControl.setVisible(true);
            disclosureControl.setOnMouseClicked(event -> {
                event.consume();
                toggleExpanded();
            });
            updateDisclosureIcon();
        } else {
            disclosureControl.setVisible(false);
        }

        // Set marker if available
        if (nodeData.getMarker() != null) {
            setMarker(nodeData.getMarker());
        }
    }

    /**
     * Handle node selection (packages and classes/interfaces).
     */
    private void handleNodeClick() {
        setSelected(!isSelected);
        if (onSelect != null) {
            onSelect.run();
        }
    }

    /**
     * Toggle the expanded/collapsed state (packages only).
     */
    private void toggleExpanded() {
        isExpanded = !isExpanded;
        updateDisclosureIcon();
        
        if (onExpandCollapse != null) {
            onExpandCollapse.run();
        }
    }

    /**
     * Update the disclosure icon (+ or -).
     */
    private void updateDisclosureIcon() {
        disclosureControl.getChildren().clear();
        String text = isExpanded ? "−" : "+";
        Label icon = new Label(text);
        icon.setStyle("-fx-font-size: 12; -fx-font-weight: bold;");
        disclosureControl.getChildren().add(icon);
    }

    /**
     * Set the selected state and update visual styling.
     */
    public void setSelected(boolean selected) {
        this.isSelected = selected;
        if (selected) {
            nodeBox.getStyleClass().add("selected");
        } else {
            nodeBox.getStyleClass().remove("selected");
        }
    }

    /**
     * Set the children container FlowPane (where child nodes will be added).
     */
    public void setChildrenContainer(FlowPane container) {
        this.childrenContainer = container;
    }

    /**
     * Update the children display based on expanded state.
     */
    public void updateChildrenDisplay() {
        if (childrenContainer == null) {
            return;
        }

        if (isExpanded && !childControllers.isEmpty()) {
            // Add children to container if not already there
            for (NodeComponentController child : childControllers) {
                if (!childrenContainer.getChildren().contains(child.getNode())) {
                    childrenContainer.getChildren().add(child.getNode());
                }
            }
        } else if (!isExpanded) {
            // Remove children from container
            for (NodeComponentController child : childControllers) {
                childrenContainer.getChildren().remove(child.getNode());
            }
        }
    }

    /**
     * Add child node controllers.
     */
    public void addChildControllers(List<NodeComponentController> children) {
        this.childControllers.addAll(Objects.requireNonNull(children, "children cannot be null"));
    }

    /**
     * Get the root HBox component.
     */
    public HBox getNode() {
        return nodeBox;
    }

    /**
     * Check if this node is expanded.
     */
    public boolean isExpanded() {
        return isExpanded;
    }

    /**
     * Check if this node is selected.
     */
    public boolean isSelected() {
        return isSelected;
    }

    /**
     * Get the node data.
     */
    public NodeData getNodeData() {
        return nodeData;
    }

    /**
     * Set optional marker text (e.g. "*" for cyclic dependencies).
     */
    public void setMarker(String marker) {
        markerLabel.setText(Objects.requireNonNull(marker, "marker cannot be null"));
    }

    /**
     * Set the expand/collapse callback.
     */
    public void setOnExpandCollapse(Runnable callback) {
        this.onExpandCollapse = callback;
    }

    /**
     * Set the selection callback.
     */
    public void setOnSelect(Runnable callback) {
        this.onSelect = callback;
    }
}
