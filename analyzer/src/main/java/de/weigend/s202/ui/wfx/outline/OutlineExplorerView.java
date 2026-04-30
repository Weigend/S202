package de.weigend.s202.ui.wfx.outline;

import de.weigend.s202.ui.model.ArchitectureNode;
import io.softwareecg.wfx.windowmtg.api.Position;
import io.softwareecg.wfx.windowmtg.api.View;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.net.URL;
import java.util.function.Consumer;

/**
 * WFX side-panel view that shows the entire package/class hierarchy of the
 * currently focused {@link de.weigend.s202.ui.ArchitectureView ArchitectureView}
 * as a {@link TreeView}. Double-clicking any node — class or package —
 * forwards a selection request through the
 * {@link #setOnNodeDoubleClick(Consumer) configured handler}.
 */
public class OutlineExplorerView implements View {

    public static final String VIEW_ID = "s202-outline-explorer";

    private final BorderPane root = new BorderPane();
    private final TreeView<ArchitectureNode> treeView = new TreeView<>();
    private final Label emptyPlaceholder = new Label("No JAR loaded");

    private Consumer<String> nodeDoubleClickHandler = fqn -> { /* no-op */ };

    public OutlineExplorerView() {
        emptyPlaceholder.getStyleClass().add("outline-empty");

        treeView.setShowRoot(false);
        treeView.getStyleClass().add("outline-tree");
        treeView.setCellFactory(tv -> new ArchitectureNodeCell());

        treeView.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
                return;
            }
            TreeItem<ArchitectureNode> selected = treeView.getSelectionModel().getSelectedItem();
            if (selected == null || selected.getValue() == null) {
                return;
            }
            nodeDoubleClickHandler.accept(selected.getValue().getFullName());
        });

        showEmpty();
    }

    /**
     * Replace the displayed tree. {@code rootNode} may be null to clear the
     * outline (e.g. when no architecture view is focused).
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        if (rootNode == null) {
            showEmpty();
            return;
        }
        TreeItem<ArchitectureNode> rootItem = buildTreeItem(rootNode);
        rootItem.setExpanded(true);
        // The architecture model carries a synthetic root; expand its first
        // level so the user immediately sees the top-level packages.
        for (TreeItem<ArchitectureNode> child : rootItem.getChildren()) {
            child.setExpanded(true);
        }
        treeView.setRoot(rootItem);
        root.setCenter(treeView);
    }

    public void setOnNodeDoubleClick(Consumer<String> handler) {
        this.nodeDoubleClickHandler = handler != null ? handler : fqn -> { };
    }

    /**
     * Expand all ancestors of the node with the given full name, select it,
     * and scroll it into view. No-op if the tree is empty or the name is
     * unknown.
     */
    public void revealByFullName(String fullName) {
        if (fullName == null) {
            return;
        }
        TreeItem<ArchitectureNode> rootItem = treeView.getRoot();
        if (rootItem == null) {
            return;
        }
        TreeItem<ArchitectureNode> match = findItem(rootItem, fullName);
        if (match == null) {
            return;
        }
        TreeItem<ArchitectureNode> ancestor = match.getParent();
        while (ancestor != null) {
            ancestor.setExpanded(true);
            ancestor = ancestor.getParent();
        }
        treeView.getSelectionModel().select(match);
        int row = treeView.getRow(match);
        if (row >= 0) {
            treeView.scrollTo(row);
        }
    }

    private TreeItem<ArchitectureNode> findItem(TreeItem<ArchitectureNode> item, String fullName) {
        ArchitectureNode value = item.getValue();
        if (value != null && fullName.equals(value.getFullName())) {
            return item;
        }
        for (TreeItem<ArchitectureNode> child : item.getChildren()) {
            TreeItem<ArchitectureNode> found = findItem(child, fullName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void showEmpty() {
        treeView.setRoot(null);
        root.setCenter(emptyPlaceholder);
    }

    private TreeItem<ArchitectureNode> buildTreeItem(ArchitectureNode node) {
        TreeItem<ArchitectureNode> item = new TreeItem<>(node);
        for (ArchitectureNode child : node.getChildren()) {
            item.getChildren().add(buildTreeItem(child));
        }
        return item;
    }

    @Override
    public String getViewId() {
        return VIEW_ID;
    }

    @Override
    public String getTitle() {
        return "Outline Explorer";
    }

    @Override
    public String getToolTipInfo() {
        return "Package and class hierarchy of the focused architecture view";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.LEFT;
    }

    @Override
    public Parent getRootNode() {
        return root;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 0.30;
    }

    /** Renders package, class, and interface nodes with distinct icons and style classes. */
    private static final class ArchitectureNodeCell extends javafx.scene.control.TreeCell<ArchitectureNode> {
        private static final Color PACKAGE_COLOR = Color.web("#e6c46a");
        private static final Color CLASS_COLOR   = Color.web("#7fb3ff");
        private static final Color INTERFACE_COLOR = Color.web("#4caf50");

        @Override
        protected void updateItem(ArchitectureNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                getStyleClass().removeAll("outline-package", "outline-class", "outline-interface");
                return;
            }
            setText(item.getSimpleName());
            getStyleClass().removeAll("outline-package", "outline-class", "outline-interface");

            FontIcon icon;
            if (item.isInterfaceType()) {
                icon = new FontIcon(MaterialDesignA.ALPHA_I_CIRCLE);
                icon.setIconColor(INTERFACE_COLOR);
                getStyleClass().add("outline-interface");
            } else if (item.getType() == ArchitectureNode.NodeType.CLASS) {
                icon = new FontIcon(MaterialDesignA.ALPHA_C_CIRCLE);
                icon.setIconColor(CLASS_COLOR);
                getStyleClass().add("outline-class");
            } else {
                icon = new FontIcon(MaterialDesignP.PACKAGE_VARIANT_CLOSED);
                icon.setIconColor(PACKAGE_COLOR);
                getStyleClass().add("outline-package");
            }
            icon.setIconSize(14);
            setGraphic(icon);
        }
    }
}
