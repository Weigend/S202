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
package de.weigend.s202.ui.views.component;

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.ui.views.component.ComponentBox;
import de.weigend.s202.ui.core.canvas.TreeBuilderSupport;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Projection logic of the component view: turns the API and implementation
 * elements of a {@link ComponentArchitecture} back into {@link ArchitectureNode}
 * trees. What is a component and what is its API is decided in the domain. Also owns the "Add To / Remove From Api" context menus that edit the
 * API annotations. The {@link ComponentArchitectureTreeBuilder} delegates
 * here and keeps only the JavaFX tree assembly.
 */
final class ComponentProjection {

    private static final String API_CLASS_STYLE =
            "; -fx-border-color: #1f5e9d; -fx-border-width: 2; -fx-background-color: #ffffff;";

    private final ArchitectureAnnotations annotations;
    private final BiConsumer<ArchitectureAnnotations, String> apiChangeSink;

    ComponentProjection(ArchitectureAnnotations annotations,
                        BiConsumer<ArchitectureAnnotations, String> apiChangeSink) {
        this.annotations = annotations;
        this.apiChangeSink = apiChangeSink;
    }

    static ArchitectureNode apiProjectionRoot(ComponentArchitecture.ComponentElement component,
                                              Map<String, ArchitectureNode> sourceNodes) {
        ArchitectureNode root = packageNode(component.rootPackageFqn(), sourceNodes);
        Map<String, ArchitectureNode> packages = new HashMap<>();
        packages.put(root.getFullName(), root);
        for (Element element : component.api()) {
            addApiElement(root, component.rootPackageFqn(), element, sourceNodes, packages);
        }
        return root;
    }

    static ArchitectureNode implementationProjectionRoot(ComponentArchitecture.ComponentElement component,
                                                         Map<String, ArchitectureNode> sourceNodes) {
        ArchitectureNode root = packageNode(component.rootPackageFqn(), sourceNodes);
        for (List<Element> row : component.implementationRows()) {
            for (Element element : row) {
                addChildInProjectionOrder(root, toArchitectureNode(element, sourceNodes));
            }
        }
        return root;
    }

    private static void addApiElement(ArchitectureNode root,
                                      String rootPackageFqn,
                                      Element element,
                                      Map<String, ArchitectureNode> sourceNodes,
                                      Map<String, ArchitectureNode> packages) {
        if (element instanceof Element.ClassElement cls) {
            ArchitectureNode parent = ensureApiPackagePath(root, rootPackageFqn,
                    TreeBuilderSupport.parentOf(cls.fqn()), sourceNodes, packages);
            addChildInProjectionOrder(parent, toArchitectureNode(cls, sourceNodes));
        } else if (element instanceof Element.PackageElement pkg) {
            ArchitectureNode parent = ensureApiPackagePath(root, rootPackageFqn,
                    TreeBuilderSupport.parentOf(pkg.fqn()), sourceNodes, packages);
            addChildInProjectionOrder(parent, toArchitectureNode(pkg, sourceNodes));
        }
    }

    private static ArchitectureNode ensureApiPackagePath(ArchitectureNode root,
                                                         String rootPackageFqn,
                                                         String packageFqn,
                                                         Map<String, ArchitectureNode> sourceNodes,
                                                         Map<String, ArchitectureNode> packages) {
        if (packageFqn == null || packageFqn.isBlank() || packageFqn.equals(rootPackageFqn)) {
            return root;
        }
        if (!packageFqn.startsWith(rootPackageFqn + ".")) {
            return root;
        }

        ArchitectureNode current = root;
        String currentFqn = rootPackageFqn;
        String suffix = packageFqn.substring(rootPackageFqn.length() + 1);
        for (String part : suffix.split("\\.")) {
            currentFqn = currentFqn + "." + part;
            ArchitectureNode next = packages.get(currentFqn);
            if (next == null) {
                next = packageNode(currentFqn, sourceNodes);
                packages.put(currentFqn, next);
                addChildInProjectionOrder(current, next);
            }
            current = next;
        }
        return current;
    }

    private static ArchitectureNode toArchitectureNode(Element element,
                                                       Map<String, ArchitectureNode> sourceNodes) {
        if (element instanceof Element.ClassElement cls) {
            ArchitectureNode source = sourceNodes.get(cls.fqn());
            ArchitectureNode node = new ArchitectureNode(
                    cls.fqn(),
                    source == null ? TreeBuilderSupport.simpleName(cls.fqn()) : source.getSimpleName(),
                    NodeType.CLASS,
                    true,
                    cls.localLevel(),
                    source != null && source.isInterfaceType());
            node.setArchitectureLevel(cls.architectureLevel());
            copyDependencies(source, node);
            return node;
        }

        Element.PackageElement pkg = (Element.PackageElement) element;
        ArchitectureNode node = new ArchitectureNode(
                pkg.fqn(),
                simpleNameFromSource(pkg.fqn(), sourceNodes),
                NodeType.PACKAGE,
                true,
                pkg.localLevel());
        node.setArchitectureLevel(pkg.architectureLevel());
        copyDependencies(sourceNodes.get(pkg.fqn()), node);
        for (List<Element> row : pkg.rows()) {
            for (Element child : row) {
                addChildInProjectionOrder(node, toArchitectureNode(child, sourceNodes));
            }
        }
        return node;
    }

    private static ArchitectureNode packageNode(String fqn, Map<String, ArchitectureNode> sourceNodes) {
        ArchitectureNode source = sourceNodes.get(fqn);
        ArchitectureNode node = new ArchitectureNode(
                fqn,
                source == null ? TreeBuilderSupport.simpleName(fqn) : source.getSimpleName(),
                NodeType.PACKAGE,
                true,
                source == null ? 0 : source.getLevel());
        node.setArchitectureLevel(source == null ? -1 : source.getArchitectureLevel());
        copyDependencies(source, node);
        return node;
    }

    private static void addChildInProjectionOrder(ArchitectureNode parent, ArchitectureNode child) {
        child.setHorizontalLayoutOrder(parent.getChildren().size());
        parent.addChild(child);
    }

    private static void copyDependencies(ArchitectureNode source, ArchitectureNode target) {
        if (source == null) {
            return;
        }
        target.setDependencies(source.getDependencies());
        target.setDependents(source.getDependents());
    }

    static Map<String, ArchitectureNode> indexNodes(ArchitectureNode root) {
        Map<String, ArchitectureNode> index = new HashMap<>();
        indexNodes(root, index);
        return index;
    }

    private static void indexNodes(ArchitectureNode node, Map<String, ArchitectureNode> index) {
        if (node == null) {
            return;
        }
        index.put(node.getFullName(), node);
        for (ArchitectureNode child : node.getChildren()) {
            indexNodes(child, index);
        }
    }

    static int componentNodeCount(ComponentArchitecture architecture) {
        int count = 0;
        for (ComponentArchitecture.ComponentElement component : architecture.components()) {
            count++;
            count += component.api().size();
            count += elementCount(component.implementationRows());
        }
        return count;
    }

    private static int elementCount(List<List<Element>> rows) {
        int count = 0;
        for (List<Element> row : rows) {
            for (Element element : row) {
                count++;
                if (element instanceof Element.PackageElement pkg) {
                    count += elementCount(pkg.rows());
                }
            }
        }
        return count;
    }

    private static String simpleNameFromSource(String fqn, Map<String, ArchitectureNode> sourceNodes) {
        ArchitectureNode source = sourceNodes.get(fqn);
        return source == null ? TreeBuilderSupport.simpleName(fqn) : source.getSimpleName();
    }

    void installApiProjectionNodes(Map<String, Node> registry) {
        for (Map.Entry<String, Node> entry : registry.entrySet()) {
            Node node = entry.getValue();
            installApiContextMenu(node, entry.getKey());
            ComponentBox.markApiElement(node);
            if (node instanceof LevelClassBox apiBox) {
                apiBox.setStyle(apiBox.getStyle() + API_CLASS_STYLE);
            }
        }
    }

    void installApiMenus(Map<String, Node> registry) {
        for (Map.Entry<String, Node> entry : registry.entrySet()) {
            installApiContextMenu(entry.getValue(), entry.getKey());
        }
    }

    void installApiContextMenu(Node target, String fullName) {
        if (target == null || fullName == null || fullName.isBlank()) {
            return;
        }
        target.setOnContextMenuRequested(event -> {
            MenuItem addToApi = new MenuItem("Add To Api");
            addToApi.setOnAction(action -> {
                apiChangeSink.accept(
                        annotations.withComponentApiIncluded(fullName),
                        "Added to API: " + fullName);
            });

            MenuItem removeFromApi = new MenuItem("Remove From Api");
            removeFromApi.setOnAction(action -> {
                apiChangeSink.accept(
                        annotations.withComponentApiExcluded(fullName),
                        "Removed from API: " + fullName);
            });

            ContextMenu menu = new ContextMenu(addToApi, removeFromApi);
            menu.show(target, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }
}
