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
import de.weigend.s202.domain.architecture.ComponentApiClassifier;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.ArchitectureDragController;
import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import de.weigend.s202.ui.component.ComponentBox;
import de.weigend.s202.ui.layout.horizontal.HorizontalLayoutOrdering;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeCloner;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds the component architecture projection. Components are packages with a
 * detectable API surface. They are rendered as flat top-level boxes; packages
 * remain nested inside the component implementation area.
 */
public class ComponentArchitectureTreeBuilder {

    private static final double TOP_LEVEL_HORIZONTAL_PADDING = 60.0;
    private static final double TOP_LEVEL_VERTICAL_PADDING = 52.0;

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> selectionChangeSink;
    private final ArchitectureAnnotations annotations;
    private final ComponentApiClassifier apiClassifier;
    private final java.util.function.BiConsumer<ArchitectureAnnotations, String> apiChangeSink;

    public ComponentArchitectureTreeBuilder(Map<String, Node> elementRegistry,
                                            Consumer<String> selectionChangeSink) {
        this(elementRegistry, selectionChangeSink, ArchitectureAnnotations.empty(),
                (java.util.function.BiConsumer<ArchitectureAnnotations, String>) null);
    }

    public ComponentArchitectureTreeBuilder(Map<String, Node> elementRegistry,
                                            Consumer<String> selectionChangeSink,
                                            ArchitectureAnnotations annotations,
                                            java.util.function.BiConsumer<ArchitectureAnnotations, String> apiChangeSink) {
        this(elementRegistry, selectionChangeSink, annotations, null, apiChangeSink);
    }

    public ComponentArchitectureTreeBuilder(Map<String, Node> elementRegistry,
                                            Consumer<String> selectionChangeSink,
                                            ArchitectureAnnotations annotations,
                                            DependencyModel rawModel,
                                            java.util.function.BiConsumer<ArchitectureAnnotations, String> apiChangeSink) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.selectionChangeSink = selectionChangeSink;
        this.annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        this.apiClassifier = new ComponentApiClassifier(this.annotations, rawModel);
        this.apiChangeSink = apiChangeSink != null ? apiChangeSink : (next, message) -> {};
    }

    public VBox buildTree(ArchitectureNode rootNode, int maxDepth) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        elementRegistry.clear();

        VBox topLevelContainer = createTopLevelContainer();
        ArchitectureNode effectiveRoot = effectiveRoot(rootNode, skipped ->
                elementRegistry.put(skipped.getFullName(), topLevelContainer));
        topLevelContainer.getProperties().put("s202.whatif.rootFqcn",
                effectiveRoot.getFullName() == null ? "" : effectiveRoot.getFullName());

        Map<Integer, HBox> topLevelRows = new HashMap<>();
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot)) {
            HBox row = topLevelRows.computeIfAbsent(child.getLevel(), level -> {
                HBox hbox = new HBox(10);
                hbox.setMaxWidth(Double.MAX_VALUE);
                hbox.setAlignment(Pos.CENTER);
                VBox.setVgrow(hbox, Priority.ALWAYS);
                ArchitectureDragController.markAsRow(hbox);
                topLevelContainer.getChildren().add(hbox);
                return hbox;
            });

            Node childView = createTopLevelNode(child, maxDepth);
            HBox.setHgrow(childView, Priority.ALWAYS);
            row.getChildren().add(childView);
        }
        return topLevelContainer;
    }

    public void buildTreeAsync(ArchitectureNode rootNode,
                               int maxDepth,
                               ArchitectureTreeBuilder.ProgressSink progressSink,
                               Consumer<VBox> onComplete) {
        Objects.requireNonNull(onComplete, "onComplete cannot be null");
        Runnable build = () -> {
            VBox tree = buildTree(rootNode, maxDepth);
            if (progressSink != null) {
                progressSink.accept(rootNode.getTotalNodeCount(), rootNode.getTotalNodeCount(), rootNode.getFullName());
            }
            onComplete.accept(tree);
        };
        if (Platform.isFxApplicationThread()) {
            build.run();
        } else {
            Platform.runLater(build);
        }
    }

    private Node createTopLevelNode(ArchitectureNode child, int maxDepth) {
        if (child.getType() == NodeType.PACKAGE && isSelectedComponentRoot(child)) {
            return createComponentBox(child, maxDepth);
        }
        return createStandardTopLevelNode(child, maxDepth);
    }

    private Node createStandardTopLevelNode(ArchitectureNode child, int maxDepth) {
        ArchitectureNode fakeRoot = new ArchitectureNode(
                "component-standard-root-" + child.getFullName(),
                "component-standard-root",
                NodeType.PACKAGE,
                true,
                0);
        fakeRoot.addChild(child);

        Map<String, Node> nestedRegistry = new HashMap<>();
        ArchitectureTreeBuilder nestedBuilder = new ArchitectureTreeBuilder(nestedRegistry, selectionChangeSink);
        VBox nestedTree = nestedBuilder.buildTree(fakeRoot, maxDepth, false);
        installApiMenus(nestedRegistry);
        elementRegistry.putAll(nestedRegistry);

        Node topLevelNode = nestedRegistry.get(child.getFullName());
        if (topLevelNode != null) {
            if (topLevelNode.getParent() instanceof HBox row) {
                row.getChildren().remove(topLevelNode);
            }
            return topLevelNode;
        }
        return nestedTree;
    }

    private ComponentBox createComponentBox(ArchitectureNode component, int maxDepth) {
        List<ArchitectureNode> apiClasses = selectedApiClasses(component);
        Set<String> apiFullNames = new HashSet<>();
        for (ArchitectureNode apiClass : apiClasses) {
            apiFullNames.add(apiClass.getFullName());
        }

        ComponentBox componentBox = new ComponentBox(component.getSimpleName(), component.getFullName(), apiClasses.size());
        componentBox.setSelectionChangeSink(selectionChangeSink);
        installApiContextMenu(componentBox, component.getFullName());
        elementRegistry.put(component.getFullName(), componentBox);

        for (ArchitectureNode apiClass : apiClasses) {
            LevelClassBox apiBox = new LevelClassBox(
                    apiClass.getSimpleName(),
                    apiClass.getLevel(),
                    apiClass.getFullName(),
                    apiClass.isInterfaceType(),
                    apiClass.getArchitectureLevel());
            apiBox.setSelectionChangeSink(selectionChangeSink);
            apiBox.setStyle(apiBox.getStyle()
                    + "; -fx-border-color: #1f5e9d; -fx-border-width: 2; -fx-background-color: #ffffff;");
            installApiContextMenu(apiBox, apiClass.getFullName());
            ComponentBox.markApiElement(apiBox);
            elementRegistry.put(apiClass.getFullName(), apiBox);
            componentBox.addApiNode(apiClass.getLevel(), apiBox);
        }

        ArchitectureNode implementationRoot = cloneImplementation(component, apiFullNames, true);
        if (implementationRoot == null || !implementationRoot.hasChildren()) {
            return componentBox;
        }
        componentBox.setImplementationContent(buildImplementationContent(component, implementationRoot, maxDepth));
        return componentBox;
    }

    private Node buildImplementationContent(ArchitectureNode component,
                                            ArchitectureNode implementationRoot,
                                            int maxDepth) {
        ArchitectureNode fakeRoot = new ArchitectureNode(
                "component-root-" + component.getFullName(),
                "component-root",
                NodeType.PACKAGE,
                true,
                0);
        fakeRoot.addChild(implementationRoot);

        Map<String, Node> nestedRegistry = new HashMap<>();
        ArchitectureTreeBuilder nestedBuilder = new ArchitectureTreeBuilder(nestedRegistry, selectionChangeSink);
        VBox nestedTree = nestedBuilder.buildTree(fakeRoot, maxDepth, false);
        installApiMenus(nestedRegistry);

        Node componentPackageNode = nestedRegistry.remove(component.getFullName());
        if (componentPackageNode instanceof LevelPackageBox componentPackageBox) {
            VBox content = componentPackageBox.getContentContainer();
            componentPackageBox.getChildren().remove(content);
            elementRegistry.putAll(nestedRegistry);
            return content;
        }

        elementRegistry.putAll(nestedRegistry);
        return nestedTree;
    }

    private boolean isSelectedComponentRoot(ArchitectureNode node) {
        return node.getType() == NodeType.PACKAGE
                && !"root".equals(node.getFullName())
                && !selectedApiClasses(node).isEmpty();
    }

    List<ArchitectureNode> selectedApiClasses(ArchitectureNode component) {
        List<ArchitectureNode> api = new ArrayList<>();
        collectSelectedApiClasses(component, api, false);
        api.sort(Comparator.comparing(ArchitectureNode::getFullName, String.CASE_INSENSITIVE_ORDER));
        return api;
    }

    private void collectSelectedApiClasses(ArchitectureNode node,
                                           List<ArchitectureNode> api,
                                           boolean inheritedApiPackage) {
        boolean inApiPackage = inheritedApiPackage
                || (node.getType() == NodeType.PACKAGE && isApiPackageName(node.getSimpleName()));
        if (node.getType() == NodeType.CLASS && isSelectedApiClass(node, inApiPackage)) {
            api.add(node);
            return;
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectSelectedApiClasses(child, api, inApiPackage);
        }
    }

    private boolean isSelectedApiClass(ArchitectureNode node, boolean inApiPackage) {
        return apiClassifier.isSelectedApiClass(
                node.getFullName(),
                node.getSimpleName(),
                node.isInterfaceType(),
                inApiPackage);
    }

    private void installApiMenus(Map<String, Node> registry) {
        for (Map.Entry<String, Node> entry : registry.entrySet()) {
            installApiContextMenu(entry.getValue(), entry.getKey());
        }
    }

    private void installApiContextMenu(Node target, String fullName) {
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
        return HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot(rootNode, null));
    }

    private static ArchitectureNode effectiveRoot(ArchitectureNode rootNode,
                                                  Consumer<ArchitectureNode> skippedPackageSink) {
        ArchitectureNode effectiveRoot = rootNode;
        while (shouldChildrenBeTransparent(effectiveRoot)) {
            ArchitectureNode singleChild = effectiveRoot.getChildren().stream()
                    .filter(c -> c.getType() == NodeType.PACKAGE)
                    .findFirst()
                    .orElse(null);
            if (singleChild == null) {
                break;
            }
            if (skippedPackageSink != null) {
                skippedPackageSink.accept(singleChild);
            }
            effectiveRoot = singleChild;
        }
        return effectiveRoot;
    }

    private static boolean shouldChildrenBeTransparent(ArchitectureNode parentNode) {
        long packageCount = parentNode.getChildren().stream()
                .filter(c -> c.getType() == NodeType.PACKAGE)
                .count();
        return packageCount == 1;
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
        return ComponentApiClassifier.isHeuristicApiClass(node.getSimpleName(), node.isInterfaceType());
    }

    private static boolean isApiPackageName(String simpleName) {
        return ComponentApiClassifier.isApiPackageName(simpleName);
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

    private static VBox createTopLevelContainer() {
        VBox topLevelContainer = new VBox(10);
        topLevelContainer.setPadding(new Insets(
                TOP_LEVEL_VERTICAL_PADDING,
                TOP_LEVEL_HORIZONTAL_PADDING,
                TOP_LEVEL_VERTICAL_PADDING,
                TOP_LEVEL_HORIZONTAL_PADDING));
        topLevelContainer.setStyle("-fx-background-color: #eef2f6;");
        ArchitectureDragController.markAsRowStack(topLevelContainer);
        return topLevelContainer;
    }
}
