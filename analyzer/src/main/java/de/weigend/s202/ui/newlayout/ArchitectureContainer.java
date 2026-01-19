package de.weigend.s202.ui.newlayout;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Reusable container for architecture elements.
 * Can contain HBoxes with labels and other nested ArchitectureContainers.
 */
public class ArchitectureContainer extends VBox {

    /**
     * Create a new ArchitectureContainer with default spacing and padding.
     */
    public ArchitectureContainer() {
        super(10);
        this.setStyle("-fx-background-color: #ffffff;");
        this.setPadding(new Insets(10));
        this.setMaxWidth(Double.MAX_VALUE);
    }

    /**
     * Add an HBox with test labels (elements).
     */
    public void addHBox(int elementCount) {
        HBox hbox = createHBox(elementCount);
        this.getChildren().add(hbox);
    }

    /**
     * Add multiple HBoxes.
     */
    public void addHBoxes(int... elementCounts) {
        for (int count : elementCounts) {
            addHBox(count);
        }
    }

    /**
     * Add a custom HBox with mixed content (labels, nested containers, etc).
     */
    public void addCustomHBox(Node... children) {
        HBox hbox = new HBox(10);
        hbox.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1; -fx-padding: 5;");
        hbox.setMaxWidth(Double.MAX_VALUE);
        hbox.setAlignment(Pos.CENTER);
        
        for (Node child : children) {
            if (child instanceof Label) {
                Label label = (Label) child;
                label.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(label, Priority.ALWAYS);
            }
            hbox.getChildren().add(child);
        }
        
        this.getChildren().add(hbox);
    }

    /**
     * Add a nested container (for hierarchical structure).
     */
    public void addNestedContainer(ArchitectureContainer container) {
        this.getChildren().add(container);
    }

    /**
     * Create an HBox with the specified number of test labels.
     */
    private HBox createHBox(int elementCount) {
        HBox hbox = new HBox(10);
        hbox.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1; -fx-padding: 5;");
        hbox.setMaxWidth(Double.MAX_VALUE);
        hbox.setAlignment(Pos.CENTER);
        
        for (int i = 1; i <= elementCount; i++) {
            Label label = new Label("Element " + elementCount + "." + i);
            label.setStyle("-fx-font-size: 12; -fx-border-color: #0066cc; -fx-border-width: 2; -fx-padding: 8; -fx-background-color: #e6f0ff;");
            label.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);
            hbox.getChildren().add(label);
        }
        
        return hbox;
    }
}
