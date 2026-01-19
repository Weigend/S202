package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureModelBuilder.NodeType;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Custom TreeCell for displaying architecture nodes with icons and styling.
 */
public class ArchitectureTreeCell extends TreeCell<ArchitectureNode> {
    private static final String PACKAGE_ICON = "📦";
    private static final String CLASS_ICON = "📄";
    
    private final HBox container;
    private final Label iconLabel;
    private final Label nameLabel;
    private final Label depCountLabel;

    public ArchitectureTreeCell() {
        this.container = new HBox(8);
        this.iconLabel = new Label();
        this.nameLabel = new Label();
        this.depCountLabel = new Label();

        setupUI();
    }

    private void setupUI() {
        container.setPadding(new Insets(4));
        container.setStyle("-fx-border-radius: 4; -fx-background-radius: 4;");

        iconLabel.setStyle("-fx-font-size: 14;");
        nameLabel.setStyle("-fx-font-size: 12;");
        depCountLabel.setStyle("-fx-font-size: 10; -fx-text-fill: #999999;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        container.getChildren().addAll(iconLabel, nameLabel, spacer, depCountLabel);
    }

    @Override
    protected void updateItem(ArchitectureNode node, boolean empty) {
        super.updateItem(node, empty);

        if (empty || node == null) {
            setText(null);
            setGraphic(null);
            return;
        }

        // Update icon
        if (node.getType() == NodeType.PACKAGE) {
            iconLabel.setText(PACKAGE_ICON);
            nameLabel.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-text-fill: #0066cc;");
        } else {
            iconLabel.setText(CLASS_ICON);
            nameLabel.setStyle("-fx-font-size: 12; -fx-text-fill: #333333;");
        }

        // Update name
        nameLabel.setText(node.getSimpleName());

        // Update dependency count
        if (node.getDependencies().isEmpty()) {
            depCountLabel.setText("");
        } else {
            depCountLabel.setText("[" + node.getDependencies().size() + " deps]");
        }

        setGraphic(container);
    }
}
