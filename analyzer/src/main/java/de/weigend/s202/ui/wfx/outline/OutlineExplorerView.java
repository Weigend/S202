/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui.wfx.outline;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.model.ArchitectureNode;
import io.softwareecg.wfx.windowmtg.api.Position;
import io.softwareecg.wfx.windowmtg.api.View;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.net.URL;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
    private final TreeView<OutlineRow> treeView = new TreeView<>();
    private final Label emptyPlaceholder = new Label("No JAR loaded");

    private Consumer<String> nodeDoubleClickHandler = fqn -> { /* no-op */ };
    private Consumer<String> openScopeHandler = fqn -> { /* no-op */ };

    public OutlineExplorerView() {
        emptyPlaceholder.getStyleClass().add("outline-empty");

        treeView.setShowRoot(false);
        treeView.getStyleClass().add("outline-tree");
        treeView.setCellFactory(tv -> new ArchitectureNodeCell());

        treeView.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
                return;
            }
            TreeItem<OutlineRow> selected = treeView.getSelectionModel().getSelectedItem();
            if (selected == null || !(selected.getValue() instanceof NodeRow row)) {
                return;
            }
            nodeDoubleClickHandler.accept(row.node().getFullName());
        });

        showEmpty();
    }

    /**
     * Replace the displayed tree. {@code rootNode} may be null to clear the
     * outline (e.g. when no architecture view is focused).
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        setArchitectureRoot(rootNode, null);
    }

    public void setArchitectureRoot(ArchitectureNode rootNode, DependencyModel rawModel) {
        if (rootNode == null) {
            showEmpty();
            return;
        }
        Set<String> expanded = expandedNodeNames();
        String selected = selectedNodeName();

        TreeItem<OutlineRow> rootItem = buildTreeItem(rootNode, rawModel);
        rootItem.setExpanded(true);
        // The architecture model carries a synthetic root; expand its first
        // level so the user immediately sees the top-level packages.
        for (TreeItem<OutlineRow> child : rootItem.getChildren()) {
            child.setExpanded(true);
        }
        treeView.setRoot(rootItem);
        root.setCenter(treeView);

        restoreExpanded(rootItem, expanded);
        if (selected != null) {
            selectByFullName(selected);
        }
    }

    public void setOnNodeDoubleClick(Consumer<String> handler) {
        this.nodeDoubleClickHandler = handler != null ? handler : fqn -> { };
    }

    public void setOnOpenScope(Consumer<String> handler) {
        this.openScopeHandler = handler != null ? handler : fqn -> { };
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
        selectByFullName(fullName);
    }

    /**
     * Expand the owning class, select the matching method row, and scroll it
     * into view. Falls back to the class row if the method is absent.
     */
    public void revealMethod(String className, String methodName, String descriptor) {
        if (className == null || methodName == null) {
            return;
        }
        TreeItem<OutlineRow> rootItem = treeView.getRoot();
        if (rootItem == null) {
            return;
        }
        TreeItem<OutlineRow> classItem = findItem(rootItem, className);
        if (classItem == null) {
            return;
        }
        TreeItem<OutlineRow> methodItem = findMethodItem(classItem, methodName, descriptor);
        selectTreeItem(methodItem == null ? classItem : methodItem);
    }

    public void clearSelection() {
        treeView.getSelectionModel().clearSelection();
    }

    private void selectByFullName(String fullName) {
        TreeItem<OutlineRow> rootItem = treeView.getRoot();
        if (rootItem == null) {
            return;
        }
        TreeItem<OutlineRow> match = findItem(rootItem, fullName);
        if (match == null) {
            return;
        }
        selectTreeItem(match);
    }

    private void selectTreeItem(TreeItem<OutlineRow> match) {
        TreeItem<OutlineRow> ancestor = match.getParent();
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

    private Set<String> expandedNodeNames() {
        Set<String> out = new HashSet<>();
        TreeItem<OutlineRow> rootItem = treeView.getRoot();
        if (rootItem != null) {
            collectExpanded(rootItem, out);
        }
        return out;
    }

    private void collectExpanded(TreeItem<OutlineRow> item, Set<String> out) {
        if (item.isExpanded() && item.getValue() instanceof NodeRow row) {
            out.add(row.node().getFullName());
        }
        for (TreeItem<OutlineRow> child : item.getChildren()) {
            collectExpanded(child, out);
        }
    }

    private void restoreExpanded(TreeItem<OutlineRow> item, Set<String> expanded) {
        if (item.getValue() instanceof NodeRow row && expanded.contains(row.node().getFullName())) {
            item.setExpanded(true);
        }
        for (TreeItem<OutlineRow> child : item.getChildren()) {
            restoreExpanded(child, expanded);
        }
    }

    private String selectedNodeName() {
        TreeItem<OutlineRow> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() instanceof NodeRow row) {
            return row.node().getFullName();
        }
        return null;
    }

    private TreeItem<OutlineRow> findMethodItem(TreeItem<OutlineRow> classItem,
                                                String methodName,
                                                String descriptor) {
        for (TreeItem<OutlineRow> child : classItem.getChildren()) {
            if (!(child.getValue() instanceof MethodRow row)) {
                continue;
            }
            DependencyModel.MethodInfo method = row.method();
            if (methodName.equals(method.name)
                    && (descriptor == null || descriptor.equals(method.descriptor))) {
                return child;
            }
        }
        return null;
    }

    private TreeItem<OutlineRow> findItem(TreeItem<OutlineRow> item, String fullName) {
        OutlineRow value = item.getValue();
        if (value instanceof NodeRow row && fullName.equals(row.node().getFullName())) {
            return item;
        }
        for (TreeItem<OutlineRow> child : item.getChildren()) {
            TreeItem<OutlineRow> found = findItem(child, fullName);
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

    private TreeItem<OutlineRow> buildTreeItem(ArchitectureNode node, DependencyModel rawModel) {
        TreeItem<OutlineRow> item = new TreeItem<>(new NodeRow(node));
        for (ArchitectureNode child : node.getChildren()) {
            item.getChildren().add(buildTreeItem(child, rawModel));
        }
        if (node.getType() == ArchitectureNode.NodeType.CLASS && rawModel != null) {
            DependencyModel.ClassInfo classInfo = rawModel.getClass(node.getFullName());
            if (classInfo != null) {
                List<DependencyModel.MethodInfo> methods = classInfo.methods.values().stream()
                        .sorted(Comparator.comparing((DependencyModel.MethodInfo m) -> m.name)
                                .thenComparing(m -> m.descriptor))
                        .toList();
                for (DependencyModel.MethodInfo method : methods) {
                    item.getChildren().add(new TreeItem<>(new MethodRow(method)));
                }
            }
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

    private sealed interface OutlineRow permits NodeRow, MethodRow {}
    private record NodeRow(ArchitectureNode node) implements OutlineRow {}
    private record MethodRow(DependencyModel.MethodInfo method) implements OutlineRow {}

    /** Renders package, class, interface, and method rows with distinct icons and style classes. */
    private final class ArchitectureNodeCell extends javafx.scene.control.TreeCell<OutlineRow> {
        private static final Color PACKAGE_COLOR = Color.web("#e6c46a");
        private static final Color CLASS_COLOR   = Color.web("#7fb3ff");
        private static final Color INTERFACE_COLOR = Color.web("#4caf50");
        private static final Color METHOD_COLOR = Color.web("#b0bec5");

        @Override
        protected void updateItem(OutlineRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                getStyleClass().removeAll("outline-package", "outline-class", "outline-interface", "outline-method");
                return;
            }
            getStyleClass().removeAll("outline-package", "outline-class", "outline-interface", "outline-method");
            setContextMenu(null);

            FontIcon icon;
            if (item instanceof MethodRow row) {
                setText(methodLabel(row.method()));
                icon = new FontIcon(MaterialDesignF.FUNCTION);
                icon.setIconColor(METHOD_COLOR);
                getStyleClass().add("outline-method");
            } else if (item instanceof NodeRow row && row.node().isInterfaceType()) {
                ArchitectureNode node = row.node();
                setText(node.getSimpleName());
                icon = new FontIcon(MaterialDesignA.ALPHA_I_CIRCLE);
                icon.setIconColor(INTERFACE_COLOR);
                getStyleClass().add("outline-interface");
            } else if (item instanceof NodeRow row && row.node().getType() == ArchitectureNode.NodeType.CLASS) {
                ArchitectureNode node = row.node();
                setText(node.getSimpleName());
                icon = new FontIcon(MaterialDesignA.ALPHA_C_CIRCLE);
                icon.setIconColor(CLASS_COLOR);
                getStyleClass().add("outline-class");
            } else {
                ArchitectureNode node = ((NodeRow) item).node();
                setText(node.getSimpleName());
                icon = new FontIcon(MaterialDesignP.PACKAGE_VARIANT_CLOSED);
                icon.setIconColor(PACKAGE_COLOR);
                getStyleClass().add("outline-package");
                MenuItem openScope = new MenuItem("Open Scope");
                openScope.setOnAction(e -> openScopeHandler.accept(node.getFullName()));
                setContextMenu(new ContextMenu(openScope));
            }
            icon.setIconSize(14);
            setGraphic(icon);
        }

        private static String methodLabel(DependencyModel.MethodInfo method) {
            return method.name + method.descriptor;
        }
    }
}
