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
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.graph.LevelPackageBox;
import de.weigend.s202.ui.component.ComponentBox;
import de.weigend.s202.ui.layout.horizontal.HorizontalLayoutOrdering;
import de.weigend.s202.ui.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeLocalLevelCalculator;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds the component architecture projection. Components are packages with a
 * detectable API surface. They are rendered as top-level boxes; packages remain
 * nested inside both the API and implementation areas.
 */
public class ComponentArchitectureTreeBuilder {

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> selectionChangeSink;
    private final DependencyModel rawModel;
    private final ComponentArchitecture componentArchitecture;
    private final ComponentProjection projection;

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
        this(elementRegistry, selectionChangeSink, annotations, rawModel, null, apiChangeSink);
    }

    public ComponentArchitectureTreeBuilder(Map<String, Node> elementRegistry,
                                            Consumer<String> selectionChangeSink,
                                            ArchitectureAnnotations annotations,
                                            DependencyModel rawModel,
                                            ComponentArchitecture componentArchitecture,
                                            java.util.function.BiConsumer<ArchitectureAnnotations, String> apiChangeSink) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.selectionChangeSink = selectionChangeSink;
        this.rawModel = rawModel;
        this.componentArchitecture = componentArchitecture;
        this.projection = new ComponentProjection(
                annotations == null ? ArchitectureAnnotations.empty() : annotations,
                rawModel == null ? Set.of() : Set.copyOf(rawModel.getExportedPackageNames()),
                apiChangeSink != null ? apiChangeSink : (next, message) -> {});
    }

    public VBox buildTree(ArchitectureNode rootNode, int maxDepth) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        if (componentArchitecture != null) {
            return buildTreeFromArchitecture(rootNode, maxDepth);
        }
        return buildTreeFromArchitectureNode(rootNode, maxDepth);
    }

    private VBox buildTreeFromArchitecture(ArchitectureNode rootNode, int maxDepth) {
        elementRegistry.clear();

        VBox topLevelContainer = createTopLevelContainer();
        ArchitectureNode effectiveRoot = TreeBuilderSupport.effectiveRoot(rootNode, true, skipped ->
                elementRegistry.put(skipped.getFullName(), topLevelContainer));
        TreeBuilderSupport.tagWhatIfRoot(topLevelContainer, effectiveRoot);

        Map<String, ArchitectureNode> sourceNodes = ComponentProjection.indexNodes(rootNode);
        Map<String, ComponentArchitecture.ComponentElement> componentByRoot = componentByRootFqn(componentArchitecture);
        Set<String> renderedComponents = new HashSet<>();
        Map<Integer, HBox> topLevelRows = new HashMap<>();
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot)) {
            HBox row = topLevelRows.computeIfAbsent(child.getLevel(),
                    ignored -> TreeBuilderSupport.createTopLevelRow(topLevelContainer, 10));
            ComponentArchitecture.ComponentElement component = componentByRoot.get(child.getFullName());
            Node childView;
            if (component == null) {
                childView = createStandardTopLevelNode(child, maxDepth);
            } else {
                renderedComponents.add(component.rootPackageFqn());
                childView = createComponentBox(component, sourceNodes, maxDepth);
            }
            HBox.setHgrow(childView, Priority.ALWAYS);
            row.getChildren().add(childView);
        }
        for (ComponentArchitecture.ComponentElement component : componentArchitecture.components()) {
            if (renderedComponents.contains(component.rootPackageFqn())) {
                continue;
            }
            ArchitectureNode sourcePackage = sourceNodes.get(component.rootPackageFqn());
            if (sourcePackage != null && isCoveredByTopLevelChild(sourcePackage.getFullName(), effectiveRoot)) {
                continue;
            }
            int level = sourcePackage == null ? 0 : sourcePackage.getLevel();
            HBox row = topLevelRows.computeIfAbsent(level,
                    ignored -> TreeBuilderSupport.createTopLevelRow(topLevelContainer, 10));
            Node childView = createComponentBox(component, sourceNodes, maxDepth);
            HBox.setHgrow(childView, Priority.ALWAYS);
            row.getChildren().add(childView);
        }
        return topLevelContainer;
    }

    private VBox buildTreeFromArchitectureNode(ArchitectureNode rootNode, int maxDepth) {
        elementRegistry.clear();

        VBox topLevelContainer = createTopLevelContainer();
        ArchitectureNode effectiveRoot = TreeBuilderSupport.effectiveRoot(rootNode, true, skipped ->
                elementRegistry.put(skipped.getFullName(), topLevelContainer));
        TreeBuilderSupport.tagWhatIfRoot(topLevelContainer, effectiveRoot);

        Map<Integer, HBox> topLevelRows = new HashMap<>();
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot)) {
            HBox row = topLevelRows.computeIfAbsent(child.getLevel(),
                    ignored -> TreeBuilderSupport.createTopLevelRow(topLevelContainer, 10));
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
                int total = componentArchitecture == null
                        ? rootNode.getTotalNodeCount()
                        : Math.max(rootNode.getTotalNodeCount(),
                                ComponentProjection.componentNodeCount(componentArchitecture));
                progressSink.accept(total, total, rootNode.getFullName());
            }
            onComplete.accept(tree);
        };
        if (Platform.isFxApplicationThread()) {
            build.run();
        } else {
            Platform.runLater(build);
        }
    }

    private static Map<String, ComponentArchitecture.ComponentElement> componentByRootFqn(
            ComponentArchitecture architecture) {
        Map<String, ComponentArchitecture.ComponentElement> components = new HashMap<>();
        if (architecture == null) {
            return components;
        }
        for (ComponentArchitecture.ComponentElement component : architecture.components()) {
            components.put(component.rootPackageFqn(), component);
        }
        return components;
    }

    private static boolean isCoveredByTopLevelChild(String fqn, ArchitectureNode effectiveRoot) {
        for (ArchitectureNode child : effectiveRoot.getChildren()) {
            if (fqn.equals(child.getFullName()) || fqn.startsWith(child.getFullName() + ".")) {
                return true;
            }
        }
        return false;
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
        projection.installApiMenus(nestedRegistry);
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
        projection.installApiContextMenu(componentBox, component.getFullName());
        elementRegistry.put(component.getFullName(), componentBox);

        ArchitectureNode apiRoot = cloneApi(component, apiFullNames, true);
        if (apiRoot != null && apiRoot.hasChildren()) {
            new ArchitectureNodeLocalLevelCalculator().assign(apiRoot, rawModel);
            new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(apiRoot);
            componentBox.setApiContent(buildApiContent(component.getFullName(), apiRoot, maxDepth));
        }

        ArchitectureNode implementationRoot = cloneImplementation(component, apiFullNames, true);
        if (implementationRoot == null || !implementationRoot.hasChildren()) {
            return componentBox;
        }
        componentBox.setImplementationContent(
                buildImplementationContent(component.getFullName(), implementationRoot, maxDepth));
        return componentBox;
    }

    private ComponentBox createComponentBox(ComponentArchitecture.ComponentElement component,
                                            Map<String, ArchitectureNode> sourceNodes,
                                            int maxDepth) {
        ComponentBox componentBox = new ComponentBox(
                component.displayName(), component.rootPackageFqn(), component.api().size());
        componentBox.setSelectionChangeSink(selectionChangeSink);
        projection.installApiContextMenu(componentBox, component.rootPackageFqn());
        elementRegistry.put(component.rootPackageFqn(), componentBox);

        ArchitectureNode apiRoot = apiProjectionRoot(component, sourceNodes);
        if (apiRoot.hasChildren()) {
            new ArchitectureNodeLocalLevelCalculator().assign(apiRoot, rawModel);
            new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(apiRoot);
            componentBox.setApiContent(buildApiContent(component.rootPackageFqn(), apiRoot, maxDepth));
        }

        ArchitectureNode implementationRoot = implementationProjectionRoot(component, sourceNodes);
        if (implementationRoot.hasChildren()) {
            componentBox.setImplementationContent(
                    buildImplementationContent(component.rootPackageFqn(), implementationRoot, maxDepth));
        }
        return componentBox;
    }

    private Node buildApiContent(String componentRootFqn,
                                 ArchitectureNode apiRoot,
                                 int maxDepth) {
        ArchitectureNode fakeRoot = new ArchitectureNode(
                "component-api-root-" + componentRootFqn,
                "component-api-root",
                NodeType.PACKAGE,
                true,
                0);
        fakeRoot.addChild(apiRoot);

        Map<String, Node> nestedRegistry = new HashMap<>();
        ArchitectureTreeBuilder nestedBuilder = new ArchitectureTreeBuilder(nestedRegistry, selectionChangeSink);
        VBox nestedTree = nestedBuilder.buildTree(fakeRoot, maxDepth, false);

        Node componentPackageNode = nestedRegistry.remove(componentRootFqn);
        projection.installApiProjectionNodes(nestedRegistry);
        elementRegistry.putAll(nestedRegistry);
        if (componentPackageNode instanceof LevelPackageBox componentPackageBox) {
            VBox content = componentPackageBox.getContentContainer();
            componentPackageBox.getChildren().remove(content);
            return content;
        }
        return nestedTree;
    }

    private Node buildImplementationContent(String componentRootFqn,
                                            ArchitectureNode implementationRoot,
                                            int maxDepth) {
        ArchitectureNode fakeRoot = new ArchitectureNode(
                "component-root-" + componentRootFqn,
                "component-root",
                NodeType.PACKAGE,
                true,
                0);
        fakeRoot.addChild(implementationRoot);

        Map<String, Node> nestedRegistry = new HashMap<>();
        ArchitectureTreeBuilder nestedBuilder = new ArchitectureTreeBuilder(nestedRegistry, selectionChangeSink);
        VBox nestedTree = nestedBuilder.buildTree(fakeRoot, maxDepth, false);
        projection.installApiMenus(nestedRegistry);

        Node componentPackageNode = nestedRegistry.remove(componentRootFqn);
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
        return projection.selectedApiClasses(component);
    }

    static ArchitectureNode apiProjectionRoot(ComponentArchitecture.ComponentElement component,
                                              Map<String, ArchitectureNode> sourceNodes) {
        return ComponentProjection.apiProjectionRoot(component, sourceNodes);
    }

    static ArchitectureNode implementationProjectionRoot(ComponentArchitecture.ComponentElement component,
                                                         Map<String, ArchitectureNode> sourceNodes) {
        return ComponentProjection.implementationProjectionRoot(component, sourceNodes);
    }

    static List<ArchitectureNode> topLevelElements(ArchitectureNode rootNode) {
        return ComponentProjection.topLevelElements(rootNode);
    }

    static boolean isComponentRoot(ArchitectureNode node) {
        return ComponentProjection.isComponentRoot(node);
    }

    static List<ArchitectureNode> apiClasses(ArchitectureNode component) {
        return ComponentProjection.apiClasses(component);
    }

    static ArchitectureNode cloneImplementation(ArchitectureNode node,
                                                Set<String> apiFullNames,
                                                boolean keepEmptyPackage) {
        return ComponentProjection.cloneImplementation(node, apiFullNames, keepEmptyPackage);
    }

    static ArchitectureNode cloneApi(ArchitectureNode node,
                                     Set<String> apiFullNames,
                                     boolean keepEmptyPackage) {
        return ComponentProjection.cloneApi(node, apiFullNames, keepEmptyPackage);
    }

    private static VBox createTopLevelContainer() {
        return TreeBuilderSupport.createTopLevelContainer(10, "#eef2f6");
    }
}
