package de.weigend.s202.ui;

import de.weigend.s202.ui.model.ArchitectureModelBuilder.ArchitectureNode;
import javafx.scene.control.TreeItem;

import java.util.Objects;

/**
 * Wrapper for ArchitectureNode to integrate with JavaFX TreeView.
 */
public class ArchitectureTreeItem extends TreeItem<ArchitectureNode> {
    private boolean isExpanded = false;

    public ArchitectureTreeItem(ArchitectureNode value) {
        super(Objects.requireNonNull(value, "value cannot be null"));
        setupChildren();
    }

    private void setupChildren() {
        ArchitectureNode node = getValue();
        
        if (node.hasChildren()) {
            for (ArchitectureNode child : node.getChildren()) {
                ArchitectureTreeItem childItem = new ArchitectureTreeItem(child);
                getChildren().add(childItem);
            }
        }
    }

    @Override
    public boolean isLeaf() {
        return !getValue().hasChildren();
    }
}
