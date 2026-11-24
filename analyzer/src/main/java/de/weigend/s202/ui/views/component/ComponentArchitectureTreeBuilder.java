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

import de.weigend.s202.ui.core.canvas.ArchitectureTreeBuilder;
import de.weigend.s202.ui.core.canvas.TreeBuilderSupport;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.views.component.ComponentBox;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalLayoutOrdering;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.core.model.ArchitectureNodeLocalLevelCalculator;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Builds the component architecture projection. A component is rendered as a
 * {@link ComponentBox} wherever its root package is — at top level, inside a
 * plain package or inside another component. Packages remain nested inside both
 * the API and implementation areas.
 */
public class ComponentArchitectureTreeBuilder {

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> selectionChangeSink;
    private final DependencyModel rawModel;
    private final ComponentArchitecture componentArchitecture;
    private final ComponentProjection projection;
    private BooleanSupplier limitBoxWidth = () -> false;

    /**
     * @param componentArchitecture the components to render; {@code null} when nothing is
     *                              analyzed yet, every package is a plain one then
     */
    public ComponentArchitectureTreeBuilder(Map<String, Node> elementRegistry,
                                            Consumer<String> selectionChangeSink,
                                            ArchitectureAnnotations annotations,
                                            DependencyModel rawModel,
                                            ComponentArchitecture componentArchitecture,
                                            BiConsumer<ArchitectureAnnotations, String> apiChangeSink) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.selectionChangeSink = selectionChangeSink;
        this.rawModel = rawModel;
        this.componentArchitecture = componentArchitecture;
        this.projection = new ComponentProjection(
                annotations == null ? ArchitectureAnnotations.empty() : annotations,
                apiChangeSink != null ? apiChangeSink : (next, message) -> {});
    }

    /** Passed on to the builders of the package trees inside the components. */
    public ComponentArchitectureTreeBuilder limitBoxWidth(BooleanSupplier limit) {
        this.limitBoxWidth = Objects.requireNonNull(limit, "limit cannot be null");
        return this;
    }

    public VBox buildTree(ArchitectureNode rootNode, int maxDepth) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");
        elementRegistry.clear();

        Components components = new Components(
                componentByRootFqn(componentArchitecture),
                ComponentProjection.indexNodes(rootNode));
        VBox topLevelContainer = createTopLevelContainer();
        ArchitectureNode effectiveRoot = effectiveRoot(rootNode, components, topLevelContainer);
        TreeBuilderSupport.tagWhatIfRoot(topLevelContainer, effectiveRoot);

        Map<Integer, HBox> topLevelRows = new HashMap<>();
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot)) {
            HBox row = topLevelRows.computeIfAbsent(child.getLevel(),
                    ignored -> TreeBuilderSupport.createTopLevelRow(topLevelContainer, 10));
            ComponentArchitecture.ComponentElement component = components.byRoot().get(child.getFullName());
            Node childView = component == null
                    ? createStandardTopLevelNode(child, components, maxDepth)
                    : createComponentBox(component, components, maxDepth);
            HBox.setHgrow(childView, Priority.ALWAYS);
            row.getChildren().add(childView);
        }
        return topLevelContainer;
    }

    /**
     * Skips the transparent name prefix like the other views, but not a
     * component that happens to be the only package on its level.
     */
    private ArchitectureNode effectiveRoot(ArchitectureNode rootNode,
                                           Components components,
                                           VBox topLevelContainer) {
        ArchitectureNode current = rootNode;
        while (TreeBuilderSupport.shouldChildrenBeTransparent(current)) {
            ArchitectureNode onlyPackage = current.getChildren().stream()
                    .filter(child -> child.getType() == NodeType.PACKAGE)
                    .findFirst()
                    .orElseThrow();
            if (components.byRoot().containsKey(onlyPackage.getFullName())) {
                break;
            }
            elementRegistry.put(onlyPackage.getFullName(), topLevelContainer);
            current = onlyPackage;
        }
        return current;
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

    /** The components by root package and the nodes of the analyzed tree they are projected from. */
    private record Components(Map<String, ComponentArchitecture.ComponentElement> byRoot,
                              Map<String, ArchitectureNode> sourceNodes) {}

    private static Map<String, ComponentArchitecture.ComponentElement> componentByRootFqn(
            ComponentArchitecture architecture) {
        Map<String, ComponentArchitecture.ComponentElement> components = new HashMap<>();
        if (architecture != null) {
            for (ComponentArchitecture.ComponentElement component : architecture.allComponents()) {
                components.put(component.rootPackageFqn(), component);
            }
        }
        return components;
    }

    private Node createStandardTopLevelNode(ArchitectureNode child, Components components, int maxDepth) {
        ArchitectureNode fakeRoot = new ArchitectureNode(
                "component-standard-root-" + child.getFullName(),
                "component-standard-root",
                NodeType.PACKAGE,
                true,
                0);
        fakeRoot.addChild(child);

        Map<String, Node> nestedRegistry = new HashMap<>();
        VBox nestedTree = nestedBuilder(nestedRegistry, components).buildTree(fakeRoot, maxDepth, false);
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

    private ComponentBox createComponentBox(ComponentArchitecture.ComponentElement component,
                                            Components components,
                                            int maxDepth) {
        ComponentBox componentBox = new ComponentBox(
                component.displayName(), component.rootPackageFqn(), component.apiClassCount());
        componentBox.setSelectionChangeSink(selectionChangeSink);
        componentBox.setLimitRowWidth(limitBoxWidth.getAsBoolean());
        projection.installApiContextMenu(componentBox, component.rootPackageFqn());
        elementRegistry.put(component.rootPackageFqn(), componentBox);

        ArchitectureNode apiRoot = apiProjectionRoot(component, components.sourceNodes());
        if (apiRoot.hasChildren()) {
            new ArchitectureNodeLocalLevelCalculator().assign(apiRoot, rawModel);
            new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(apiRoot);
            componentBox.setApiContent(buildContent(
                    component.rootPackageFqn(), apiRoot, components, maxDepth,
                    projection::installApiProjectionNodes));
        }

        ArchitectureNode implementationRoot = implementationProjectionRoot(component, components.sourceNodes());
        if (implementationRoot.hasChildren()) {
            componentBox.setImplementationContent(buildContent(
                    component.rootPackageFqn(), implementationRoot, components, maxDepth,
                    projection::installApiMenus));
        }
        return componentBox;
    }

    private Node buildContent(String componentRootFqn,
                              ArchitectureNode contentRoot,
                              Components components,
                              int maxDepth,
                              Consumer<Map<String, Node>> decorator) {
        ArchitectureNode fakeRoot = new ArchitectureNode(
                "component-root-" + componentRootFqn,
                "component-root",
                NodeType.PACKAGE,
                true,
                0);
        fakeRoot.addChild(contentRoot);

        Map<String, Node> nestedRegistry = new HashMap<>();
        VBox nestedTree = nestedBuilder(nestedRegistry, components).buildTree(fakeRoot, maxDepth, false);

        Node componentPackageNode = nestedRegistry.remove(componentRootFqn);
        decorator.accept(nestedRegistry);
        elementRegistry.putAll(nestedRegistry);
        if (componentPackageNode instanceof LevelPackageBox componentPackageBox) {
            VBox content = componentPackageBox.getContentContainer();
            componentPackageBox.getChildren().remove(content);
            return content;
        }
        return nestedTree;
    }

    private ArchitectureTreeBuilder nestedBuilder(Map<String, Node> nestedRegistry, Components components) {
        return new ArchitectureTreeBuilder(nestedRegistry, selectionChangeSink)
                .limitBoxWidth(limitBoxWidth)
                .packageNodes((packageNode, remainingDepth) -> {
                    ComponentArchitecture.ComponentElement component =
                            components.byRoot().get(packageNode.getFullName());
                    if (component == null) {
                        return null;
                    }
                    ComponentBox box = createComponentBox(component, components, Math.max(1, remainingDepth));
                    if (remainingDepth <= 0) {
                        box.setExpanded(false);
                    }
                    return box;
                });
    }

    static ArchitectureNode apiProjectionRoot(ComponentArchitecture.ComponentElement component,
                                              Map<String, ArchitectureNode> sourceNodes) {
        return ComponentProjection.apiProjectionRoot(component, sourceNodes);
    }

    static ArchitectureNode implementationProjectionRoot(ComponentArchitecture.ComponentElement component,
                                                         Map<String, ArchitectureNode> sourceNodes) {
        return ComponentProjection.implementationProjectionRoot(component, sourceNodes);
    }

    private static VBox createTopLevelContainer() {
        return TreeBuilderSupport.createTopLevelContainer(10, "#eef2f6");
    }
}
