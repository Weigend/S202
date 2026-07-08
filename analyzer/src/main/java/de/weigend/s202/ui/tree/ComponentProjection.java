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
package de.weigend.s202.ui.tree;

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.ui.component.ComponentBox;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalLayoutOrdering;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.core.model.ArchitectureNodeCloner;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Projection logic of the component view: detects a package's API surface,
 * splits its tree into API and implementation projections and turns
 * {@link ComponentArchitecture} elements back into {@link ArchitectureNode}
 * trees. Also owns the "Add To / Remove From Api" context menus that edit the
 * API annotations. The {@link ComponentArchitectureTreeBuilder} delegates
 * here and keeps only the JavaFX tree assembly.
 */
final class ComponentProjection {

    private static final String API_CLASS_STYLE =
            "; -fx-border-color: #1f5e9d; -fx-border-width: 2; -fx-background-color: #ffffff;";

    private final ArchitectureAnnotations annotations;
    private final Set<String> exportedPackages;
    private final BiConsumer<ArchitectureAnnotations, String> apiChangeSink;

    ComponentProjection(ArchitectureAnnotations annotations,
                        Set<String> exportedPackages,
                        BiConsumer<ArchitectureAnnotations, String> apiChangeSink) {
        this.annotations = annotations;
        this.exportedPackages = exportedPackages;
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

    List<ArchitectureNode> selectedApiClasses(ArchitectureNode component) {
        List<ArchitectureNode> api = new ArrayList<>();
        collectSelectedApiClasses(component, api, false, false);
        api.sort(Comparator.comparing(ArchitectureNode::getFullName, String.CASE_INSENSITIVE_ORDER));
        return api;
    }

    private void collectSelectedApiClasses(ArchitectureNode node,
                                           List<ArchitectureNode> api,
                                           boolean inheritedApiPackage,
                                           boolean inheritedImplementationPackage) {
        boolean inApiPackage = inheritedApiPackage
                || (node.getType() == NodeType.PACKAGE && isApiPackageName(node.getSimpleName()));
        boolean inImplementationPackage = inheritedImplementationPackage
                || (node.getType() == NodeType.PACKAGE && isImplementationPackageName(node.getSimpleName()));
        if (node.getType() == NodeType.CLASS && isSelectedApiClass(node, inApiPackage, inImplementationPackage)) {
            api.add(node);
            return;
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectSelectedApiClasses(child, api, inApiPackage, inImplementationPackage);
        }
    }

    private boolean isSelectedApiClass(ArchitectureNode node,
                                       boolean inApiPackage,
                                       boolean inImplementationPackage) {
        Boolean explicit = annotations.explicitComponentApiDecision(node.getFullName());
        if (explicit != null) {
            return explicit;
        }
        if (exportedPackages.contains(TreeBuilderSupport.parentOf(node.getFullName()))) {
            return true;
        }
        if (inImplementationPackage) {
            return false;
        }
        return inApiPackage || isApiClass(node);
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

    static List<ArchitectureNode> topLevelElements(ArchitectureNode rootNode) {
        return HorizontalLayoutOrdering.childrenInLayoutOrder(
                TreeBuilderSupport.effectiveRoot(rootNode, true, null));
    }

    static boolean isComponentRoot(ArchitectureNode node) {
        if ("root".equals(node.getFullName())) {
            return false;
        }
        for (ArchitectureNode child : node.getChildren()) {
            if (child.getType() == NodeType.CLASS && isApiClass(child)) {
                return true;
            }
            if (child.getType() == NodeType.PACKAGE
                    && isApiPackageName(child.getSimpleName())
                    && containsClass(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsClass(ArchitectureNode node) {
        if (node.getType() == NodeType.CLASS) {
            return true;
        }
        for (ArchitectureNode child : node.getChildren()) {
            if (containsClass(child)) {
                return true;
            }
        }
        return false;
    }

    static List<ArchitectureNode> apiClasses(ArchitectureNode component) {
        List<ArchitectureNode> api = new ArrayList<>();
        collectApiClasses(component, api, false);
        api.sort(Comparator.comparing(ArchitectureNode::getFullName, String.CASE_INSENSITIVE_ORDER));
        return api;
    }

    private static void collectApiClasses(ArchitectureNode node,
                                          List<ArchitectureNode> api,
                                          boolean inheritedApiPackage) {
        boolean inApiPackage = inheritedApiPackage
                || (node.getType() == NodeType.PACKAGE && isApiPackageName(node.getSimpleName()));
        if (node.getType() == NodeType.CLASS && (inApiPackage || isApiClass(node))) {
            api.add(node);
            return;
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectApiClasses(child, api, inApiPackage);
        }
    }

    static boolean isApiClass(ArchitectureNode node) {
        if (node.getType() != NodeType.CLASS) {
            return false;
        }
        String name = node.getSimpleName() == null ? "" : node.getSimpleName();
        return node.isInterfaceType() || name.endsWith("Api") || name.endsWith("API");
    }

    private static boolean isApiPackageName(String simpleName) {
        String normalized = simpleName == null ? "" : simpleName.toLowerCase();
        return normalized.equals("api")
                || normalized.equals("apis")
                || normalized.equals("port")
                || normalized.equals("ports");
    }

    private static boolean isImplementationPackageName(String simpleName) {
        String normalized = simpleName == null ? "" : simpleName.toLowerCase();
        return normalized.equals("impl")
                || normalized.equals("implementation")
                || normalized.equals("internal")
                || normalized.equals("private");
    }

    static ArchitectureNode cloneImplementation(ArchitectureNode node,
                                                Set<String> apiFullNames,
                                                boolean keepEmptyPackage) {
        if (node.getType() == NodeType.CLASS) {
            return apiFullNames.contains(node.getFullName())
                    ? null
                    : ArchitectureNodeCloner.cloneShallow(node);
        }

        ArchitectureNode clone = ArchitectureNodeCloner.cloneShallow(node);
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(node)) {
            ArchitectureNode childClone = cloneImplementation(child, apiFullNames, false);
            if (childClone != null) {
                clone.addChild(childClone);
            }
        }
        if (!keepEmptyPackage && !clone.hasChildren()) {
            return null;
        }
        return clone;
    }

    static ArchitectureNode cloneApi(ArchitectureNode node,
                                     Set<String> apiFullNames,
                                     boolean keepEmptyPackage) {
        if (node.getType() == NodeType.CLASS) {
            return apiFullNames.contains(node.getFullName())
                    ? ArchitectureNodeCloner.cloneShallow(node)
                    : null;
        }

        ArchitectureNode clone = ArchitectureNodeCloner.cloneShallow(node);
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(node)) {
            ArchitectureNode childClone = cloneApi(child, apiFullNames, false);
            if (childClone != null) {
                clone.addChild(childClone);
            }
        }
        if (!keepEmptyPackage && !clone.hasChildren()) {
            return null;
        }
        return clone;
    }
}
