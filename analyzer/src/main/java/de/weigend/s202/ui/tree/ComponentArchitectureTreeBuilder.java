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
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.graph.ArchitectureDragController;
import de.weigend.s202.ui.graph.LevelClassBox;
import de.weigend.s202.ui.graph.LevelPackageBox;
import de.weigend.s202.ui.component.ComponentBox;
import de.weigend.s202.ui.layout.horizontal.HorizontalLayoutOrdering;
import de.weigend.s202.ui.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.model.ArchitectureNodeLocalLevelCalculator;
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
 * detectable API surface. They are rendered as top-level boxes; packages remain
 * nested inside both the API and implementation areas.
 */
public class ComponentArchitectureTreeBuilder {

    private static final double TOP_LEVEL_HORIZONTAL_PADDING = 60.0;
    private static final double TOP_LEVEL_VERTICAL_PADDING = 52.0;
    private static final String API_CLASS_STYLE =
            "; -fx-border-color: #1f5e9d; -fx-border-width: 2; -fx-background-color: #ffffff;";

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> selectionChangeSink;
    private final ArchitectureAnnotations annotations;
    private final Set<String> exportedPackages;
    private final DependencyModel rawModel;
    private final ComponentArchitecture componentArchitecture;
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
        this.annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        this.rawModel = rawModel;
        this.exportedPackages = rawModel == null ? Set.of() : Set.copyOf(rawModel.getExportedPackageNames());
        this.componentArchitecture = componentArchitecture;
        this.apiChangeSink = apiChangeSink != null ? apiChangeSink : (next, message) -> {};
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
        ArchitectureNode effectiveRoot = effectiveRoot(rootNode, skipped ->
                elementRegistry.put(skipped.getFullName(), topLevelContainer));
        topLevelContainer.getProperties().put("s202.whatif.rootFqcn",
                effectiveRoot.getFullName() == null ? "" : effectiveRoot.getFullName());

        Map<String, ArchitectureNode> sourceNodes = indexNodes(rootNode);
        Map<String, ComponentArchitecture.ComponentElement> componentByRoot = componentByRootFqn(componentArchitecture);
        Set<String> renderedComponents = new HashSet<>();
        Map<Integer, HBox> topLevelRows = new HashMap<>();
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot)) {
            HBox row = topLevelRows.computeIfAbsent(child.getLevel(), ignored -> createTopLevelRow(topLevelContainer));
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
            HBox row = topLevelRows.computeIfAbsent(level, ignored -> createTopLevelRow(topLevelContainer));
            Node childView = createComponentBox(component, sourceNodes, maxDepth);
            HBox.setHgrow(childView, Priority.ALWAYS);
            row.getChildren().add(childView);
        }
        return topLevelContainer;
    }

    private VBox buildTreeFromArchitectureNode(ArchitectureNode rootNode, int maxDepth) {
        elementRegistry.clear();

        VBox topLevelContainer = createTopLevelContainer();
        ArchitectureNode effectiveRoot = effectiveRoot(rootNode, skipped ->
                elementRegistry.put(skipped.getFullName(), topLevelContainer));
        topLevelContainer.getProperties().put("s202.whatif.rootFqcn",
                effectiveRoot.getFullName() == null ? "" : effectiveRoot.getFullName());

        Map<Integer, HBox> topLevelRows = new HashMap<>();
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot)) {
            HBox row = topLevelRows.computeIfAbsent(child.getLevel(), ignored -> createTopLevelRow(topLevelContainer));
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
                        : Math.max(rootNode.getTotalNodeCount(), componentNodeCount(componentArchitecture));
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

    private static HBox createTopLevelRow(VBox topLevelContainer) {
        HBox hbox = new HBox(10);
        hbox.setMaxWidth(Double.MAX_VALUE);
        hbox.setAlignment(Pos.CENTER);
        VBox.setVgrow(hbox, Priority.ALWAYS);
        ArchitectureDragController.markAsRow(hbox);
        topLevelContainer.getChildren().add(hbox);
        return hbox;
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

        ArchitectureNode apiRoot = cloneApi(component, apiFullNames, true);
        if (apiRoot != null && apiRoot.hasChildren()) {
            new ArchitectureNodeLocalLevelCalculator().assign(apiRoot, rawModel);
            new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(apiRoot);
            componentBox.setApiContent(buildApiContent(component, apiRoot, maxDepth));
        }

        ArchitectureNode implementationRoot = cloneImplementation(component, apiFullNames, true);
        if (implementationRoot == null || !implementationRoot.hasChildren()) {
            return componentBox;
        }
        componentBox.setImplementationContent(buildImplementationContent(component, implementationRoot, maxDepth));
        return componentBox;
    }

    private ComponentBox createComponentBox(ComponentArchitecture.ComponentElement component,
                                            Map<String, ArchitectureNode> sourceNodes,
                                            int maxDepth) {
        ComponentBox componentBox = new ComponentBox(
                component.displayName(), component.rootPackageFqn(), component.api().size());
        componentBox.setSelectionChangeSink(selectionChangeSink);
        installApiContextMenu(componentBox, component.rootPackageFqn());
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

    private Node buildApiContent(ArchitectureNode component,
                                 ArchitectureNode apiRoot,
                                 int maxDepth) {
        return buildApiContent(component.getFullName(), apiRoot, maxDepth);
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
        installApiProjectionNodes(nestedRegistry);
        elementRegistry.putAll(nestedRegistry);
        if (componentPackageNode instanceof LevelPackageBox componentPackageBox) {
            VBox content = componentPackageBox.getContentContainer();
            componentPackageBox.getChildren().remove(content);
            return content;
        }
        return nestedTree;
    }

    private Node buildImplementationContent(ArchitectureNode component,
                                            ArchitectureNode implementationRoot,
                                            int maxDepth) {
        return buildImplementationContent(component.getFullName(), implementationRoot, maxDepth);
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
        installApiMenus(nestedRegistry);

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
                    parentOf(cls.fqn()), sourceNodes, packages);
            addChildInProjectionOrder(parent, toArchitectureNode(cls, sourceNodes));
        } else if (element instanceof Element.PackageElement pkg) {
            ArchitectureNode parent = ensureApiPackagePath(root, rootPackageFqn,
                    parentOf(pkg.fqn()), sourceNodes, packages);
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
                    source == null ? simpleName(cls.fqn()) : source.getSimpleName(),
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
                source == null ? simpleName(fqn) : source.getSimpleName(),
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

    private static Map<String, ArchitectureNode> indexNodes(ArchitectureNode root) {
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

    private static int componentNodeCount(ComponentArchitecture architecture) {
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
        return source == null ? simpleName(fqn) : source.getSimpleName();
    }

    private static String simpleName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String parentOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }

    private void installApiProjectionNodes(Map<String, Node> registry) {
        for (Map.Entry<String, Node> entry : registry.entrySet()) {
            Node node = entry.getValue();
            installApiContextMenu(node, entry.getKey());
            ComponentBox.markApiElement(node);
            if (node instanceof LevelClassBox apiBox) {
                apiBox.setStyle(apiBox.getStyle() + API_CLASS_STYLE);
            }
        }
    }

    private boolean isSelectedComponentRoot(ArchitectureNode node) {
        return node.getType() == NodeType.PACKAGE
                && !"root".equals(node.getFullName())
                && !selectedApiClasses(node).isEmpty();
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
        if (exportedPackages.contains(parentOf(node.getFullName()))) {
            return true;
        }
        if (inImplementationPackage) {
            return false;
        }
        return inApiPackage || isApiClass(node);
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
