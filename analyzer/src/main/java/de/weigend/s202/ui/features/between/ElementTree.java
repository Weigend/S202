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
package de.weigend.s202.ui.features.between;

import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.css.PseudoClass;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** One side of the question: the elements as a tree, with a field to search them. */
final class ElementTree extends BorderPane {

    private static final PseudoClass UNCONNECTED = PseudoClass.getPseudoClass("unconnected");
    private static final Color PACKAGE = Color.web("#e6c46a");
    private static final Color CLASS = Color.web("#7fb3ff");
    private static final Color INTERFACE = Color.web("#4caf50");
    private static final Color EXTERNAL = Color.web("#a0a4aa");

    private final TextField search = new TextField();
    private final TreeView<ArchitectureNode> tree = new TreeView<>();
    private ArchitectureNode rootNode;
    /** What lies outside the analysis, shown beside the top level; {@code null} for nothing. */
    private ArchitectureNode externalNode;
    private Set<String> externalNames = Set.of();
    private Set<String> connected = Set.of();
    private Consumer<String> onSelected = fqn -> { };
    private boolean selecting;

    ElementTree(String prompt) {
        search.setPromptText(prompt);
        search.textProperty().addListener((obs, was, isNow) -> rebuild());
        tree.setShowRoot(false);
        tree.getStyleClass().add("between-tree");
        tree.setCellFactory(view -> new ElementCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, was, isNow) -> {
            if (!selecting) {
                onSelected.accept(isNow == null ? null : isNow.getValue().getFullName());
            }
        });
        setTop(search);
        setCenter(tree);
    }

    /** Receives what the user selects; {@code null} when nothing is. */
    void setOnSelected(Consumer<String> handler) {
        onSelected = handler;
    }

    void setElements(ArchitectureNode newRoot) {
        setElements(newRoot, null);
    }

    /** @param external what lies outside the analysis, see {@link Between#external()} */
    void setElements(ArchitectureNode newRoot, ArchitectureNode external) {
        rootNode = newRoot;
        externalNode = newRoot == null ? null : external;
        Set<String> names = new HashSet<>();
        collectNames(externalNode, names);
        externalNames = names;
        rebuild();
    }

    /** What is not greyed out. */
    void setConnected(Set<String> names) {
        connected = names;
        tree.refresh();
    }

    /** Selects an element, which is searched away no longer; {@code null} for none. */
    void select(String fqn) {
        quietly(() -> {
            TreeItem<ArchitectureNode> item = fqn == null ? null : find(tree.getRoot(), fqn);
            if (fqn != null && item == null && !search.getText().isEmpty()) {
                search.clear();
                item = find(tree.getRoot(), fqn);
            }
            if (item == null) {
                tree.getSelectionModel().clearSelection();
                return;
            }
            for (TreeItem<ArchitectureNode> p = item.getParent(); p != null; p = p.getParent()) {
                p.setExpanded(true);
            }
            if (tree.getSelectionModel().getSelectedItem() != item) {
                tree.getSelectionModel().select(item);
                tree.scrollTo(Math.max(0, tree.getRow(item) - 2));
            }
        });
    }

    private void rebuild() {
        TreeItem<ArchitectureNode> selected = tree.getSelectionModel().getSelectedItem();
        String selectedName = selected == null ? null : selected.getValue().getFullName();
        Set<String> expanded = new HashSet<>();
        collectExpanded(tree.getRoot(), expanded);
        quietly(() -> {
            String wanted = search.getText().trim().toLowerCase(Locale.ROOT);
            TreeItem<ArchitectureNode> root = rootNode == null ? null : item(rootNode, wanted, expanded);
            if (root != null) {
                expandUntilThereIsAChoice(root);
                TreeItem<ArchitectureNode> outside = externalNode == null ? null : item(externalNode, wanted, expanded);
                if (outside != null) {
                    root.getChildren().add(outside);
                }
            }
            tree.setRoot(root);
            TreeItem<ArchitectureNode> again = selectedName == null ? null : find(root, selectedName);
            if (again != null) {
                tree.getSelectionModel().select(again);
            }
        });
    }

    /** @return {@code null} when neither the element nor anything in it is wanted */
    private static TreeItem<ArchitectureNode> item(ArchitectureNode node, String wanted, Set<String> expanded) {
        boolean all = wanted.isEmpty() || node.getSimpleName().toLowerCase(Locale.ROOT).contains(wanted);
        TreeItem<ArchitectureNode> item = new TreeItem<>(node);
        boolean found = false;
        for (ArchitectureNode child : node.getChildren()) {
            TreeItem<ArchitectureNode> childItem = item(child, wanted, expanded);
            if (childItem == null && all) {
                childItem = item(child, "", expanded);
            } else if (childItem != null) {
                found = true;
            }
            if (childItem != null) {
                item.getChildren().add(childItem);
            }
        }
        if (!all && !found) {
            return null;
        }
        item.setExpanded(expanded.contains(node.getFullName()) || !wanted.isEmpty() && found);
        return item;
    }

    private static void expandUntilThereIsAChoice(TreeItem<ArchitectureNode> root) {
        TreeItem<ArchitectureNode> item = root;
        item.setExpanded(true);
        while (item.getChildren().size() == 1) {
            item = item.getChildren().get(0);
            item.setExpanded(true);
        }
    }

    private static void collectNames(ArchitectureNode node, Set<String> names) {
        if (node != null) {
            names.add(node.getFullName());
            node.getChildren().forEach(child -> collectNames(child, names));
        }
    }

    private static void collectExpanded(TreeItem<ArchitectureNode> item, Set<String> names) {
        if (item != null) {
            if (item.isExpanded()) {
                names.add(item.getValue().getFullName());
            }
            item.getChildren().forEach(child -> collectExpanded(child, names));
        }
    }

    private static TreeItem<ArchitectureNode> find(TreeItem<ArchitectureNode> item, String fqn) {
        if (item == null || fqn.equals(item.getValue().getFullName())) {
            return item;
        }
        for (TreeItem<ArchitectureNode> child : item.getChildren()) {
            TreeItem<ArchitectureNode> found = find(child, fqn);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void quietly(Runnable action) {
        boolean before = selecting;
        selecting = true;
        try {
            action.run();
        } finally {
            selecting = before;
        }
    }

    private final class ElementCell extends TreeCell<ArchitectureNode> {

        private ElementCell() {
            // A click on the selected element lifts the selection.
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (!isEmpty() && isSelected() && event.getClickCount() == 1 && getDisclosureNode() != event.getTarget()
                        && event.getX() > getInsets().getLeft()) {
                    tree.getSelectionModel().clearSelection();
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(ArchitectureNode node, boolean empty) {
            super.updateItem(node, empty);
            pseudoClassStateChanged(UNCONNECTED, !empty && node != null && !connected.contains(node.getFullName()));
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            boolean isClass = node.getType() == ArchitectureNode.NodeType.CLASS;
            FontIcon icon = new FontIcon(!isClass ? MaterialDesignP.PACKAGE_VARIANT_CLOSED
                    : node.isInterfaceType() ? MaterialDesignA.ALPHA_I_CIRCLE : MaterialDesignA.ALPHA_C_CIRCLE);
            boolean external = externalNames.contains(node.getFullName());
            icon.setIconColor(external ? EXTERNAL : !isClass ? PACKAGE : node.isInterfaceType() ? INTERFACE : CLASS);
            icon.setIconSize(14);
            setText(node.getSimpleName());
            setGraphic(icon);
            setTooltip(new Tooltip(external ? (Between.EXTERNAL.equals(node.getFullName())
                    ? "Used outside the analysis: the JDK, frameworks, other libraries"
                    : node.getFullName() + "\noutside the analysis") : node.getFullName()));
        }
    }
}
