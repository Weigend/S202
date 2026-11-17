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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.ui.core.graph.ArchitectureDragController;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalLayoutOrdering;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds the UI tree representation of the architecture hierarchy.
 * Transforms ArchitectureNode model into JavaFX UI components.
 *
 * <p>Features:
 * <ul>
 *   <li>Creates hierarchical package/class structure using LevelPackageBox and LevelClassBox</li>
 *   <li>Handles transparent packages (single-child optimization)</li>
 *   <li>Maintains element registry for lookup during rendering</li>
 *   <li>Ensures complete package hierarchy even for sparse trees</li>
 * </ul>
 */
public class ArchitectureTreeBuilder {

    private static final int ASYNC_BATCH_SIZE = 120;

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> selectionChangeSink;
    private boolean lazyCollapsedPackages;
    private boolean plainNodes;

    /**
     * Creates a new ArchitectureTreeBuilder.
     *
     * @param elementRegistry Shared registry for element lookup (will be cleared and populated)
     */
    public ArchitectureTreeBuilder(Map<String, Node> elementRegistry) {
        this(elementRegistry, null);
    }

    public ArchitectureTreeBuilder(Map<String, Node> elementRegistry, Consumer<String> selectionChangeSink) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.selectionChangeSink = selectionChangeSink;
    }

    /**
     * When enabled, a package that starts collapsed (beyond {@code maxDepth})
     * gets no child boxes at build time. They are created — one level at a
     * time — when the package is expanded for the first time, or when
     * {@link de.weigend.s202.ui.core.graph.ElementLookup#materialize} asks for
     * one of its elements. Until then such elements have no registry entry;
     * {@link de.weigend.s202.ui.core.graph.ElementLookup#pendingContainer}
     * names the collapsed package standing in for them.
     *
     * <p>Off by default: callers that copy the registry after the build
     * (nested builders) would miss boxes registered later.
     */
    public ArchitectureTreeBuilder lazyCollapsedPackages(boolean lazy) {
        this.lazyCollapsedPackages = lazy;
        return this;
    }

    /**
     * When enabled, packages and classes are drawn the way a graph draws its
     * nodes — icon and name, no surrounding box — and the levels move apart
     * to leave room for the arrows. A view that is about the dependencies
     * rather than the containment uses this.
     */
    public ArchitectureTreeBuilder plainNodes(boolean plain) {
        this.plainNodes = plain;
        return this;
    }

    /**
     * Builds the UI tree from an architecture model.
     *
     * @param rootNode Root of the architecture tree
     * @return VBox containing the complete UI hierarchy
     */
    public VBox buildTree(ArchitectureNode rootNode) {
        return buildTree(rootNode, 3);
    }

    /**
     * Builds the UI tree from an architecture model.
     *
     * @param rootNode Root of the architecture tree
     * @param maxDepth Maximum depth to expand (counted from first real package)
     * @return VBox containing the complete UI hierarchy
     */
    public VBox buildTree(ArchitectureNode rootNode, int maxDepth) {
        return buildTree(rootNode, maxDepth, true);
    }

    /**
     * Builds the UI tree from an architecture model.
     *
     * @param rootNode Root of the architecture tree
     * @param maxDepth Maximum depth to expand (counted from first real package)
     * @param skipTransparentTopLevelPackages Whether chains of single-child top-level
     *        packages should be skipped. Regular architecture views use this to hide
     *        namespace wrappers; scope views disable it so the selected package remains
     *        visible as the root of the scoped chart.
     * @return VBox containing the complete UI hierarchy
     */
    public VBox buildTree(ArchitectureNode rootNode, int maxDepth, boolean skipTransparentTopLevelPackages) {
        if (rootNode == null) {
            throw new IllegalArgumentException("rootNode cannot be null");
        }

        // Clear registry for new tree
        elementRegistry.clear();

        // Track package containers for hierarchy building
        Map<String, LevelPackageBox> packageContainers = new HashMap<>();
        Set<String> elementsAddedToParent = new HashSet<>();

        // Container for top-level packages (children of root)
        VBox topLevelContainer = createTopLevelContainer();
        // Register the skipped packages so dependency lookups still work
        ArchitectureNode effectiveRoot = TreeBuilderSupport.effectiveRoot(rootNode,
                skipTransparentTopLevelPackages,
                skipped -> elementRegistry.put(skipped.getFullName(), topLevelContainer));
        TreeBuilderSupport.tagWhatIfRoot(topLevelContainer, effectiveRoot);

        // Process children from a sorted layout view: level desc, then horizontal row order.
        List<ArchitectureNode> sortedChildren = HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot);

        // Group top-level children by level into HBox rows (same level = side by side)
        Map<Integer, HBox> topLevelRows = new HashMap<>();
        for (ArchitectureNode child : sortedChildren) {
            HBox levelRow = topLevelRows.computeIfAbsent(child.getLevel(),
                    l -> TreeBuilderSupport.createTopLevelRow(topLevelContainer, siblingGap()));

            if (child.getType() == NodeType.PACKAGE) {
                LevelPackageBox packageBox = createPackageBox(child);
                packageContainers.put(child.getFullName(), packageBox);
                elementRegistry.put(child.getFullName(), packageBox);

                packageBox.fillRow();
                levelRow.getChildren().add(packageBox);

                // Depth 1 = this level visible, collapse if beyond maxDepth
                if (maxDepth < 1) {
                    packageBox.setExpanded(false);
                }

                if (maxDepth < 1 && lazyCollapsedPackages) {
                    deferChildren(child, packageBox, packageContainers, packageBox,
                            elementsAddedToParent, effectiveRoot, 1, maxDepth);
                } else {
                    processArchitectureNode(child, packageContainers, packageBox, elementsAddedToParent, effectiveRoot, false, 1, maxDepth);
                }
            } else if (child.getType() == NodeType.CLASS) {
                LevelClassBox classBox = createClassBox(child);
                elementRegistry.put(child.getFullName(), classBox);
                levelRow.getChildren().add(classBox);
            }
            elementsAddedToParent.add(child.getFullName());
        }

        return topLevelContainer;
    }

    public void buildTreeAsync(ArchitectureNode rootNode,
                               int maxDepth,
                               ProgressSink progressSink,
                               Consumer<VBox> onComplete) {
        buildTreeAsync(rootNode, maxDepth, true, progressSink, onComplete);
    }

    public void buildTreeAsync(ArchitectureNode rootNode,
                               int maxDepth,
                               boolean skipTransparentTopLevelPackages,
                               ProgressSink progressSink,
                               Consumer<VBox> onComplete) {
        if (rootNode == null) {
            throw new IllegalArgumentException("rootNode cannot be null");
        }
        Objects.requireNonNull(onComplete, "onComplete cannot be null");

        Runnable start = () -> {
            elementRegistry.clear();

            Map<String, LevelPackageBox> packageContainers = new HashMap<>();
            Set<String> elementsAddedToParent = new HashSet<>();
            BuildProgressCounter counter = new BuildProgressCounter(Math.max(1, rootNode.getTotalNodeCount()));

            VBox topLevelContainer = createTopLevelContainer();
            ArchitectureNode effectiveRoot = TreeBuilderSupport.effectiveRoot(rootNode,
                    skipTransparentTopLevelPackages,
                    skipped -> elementRegistry.put(skipped.getFullName(), topLevelContainer));
            TreeBuilderSupport.tagWhatIfRoot(topLevelContainer, effectiveRoot);

            List<ArchitectureNode> sortedChildren = HorizontalLayoutOrdering.childrenInLayoutOrder(effectiveRoot);

            Map<Integer, HBox> topLevelRows = new HashMap<>();
            Queue<Runnable> queue = new ArrayDeque<>();
            for (ArchitectureNode child : sortedChildren) {
                queue.add(() -> processTopLevelChild(child, effectiveRoot, maxDepth, topLevelContainer,
                        topLevelRows, packageContainers, elementsAddedToParent, queue, counter, progressSink));
            }

            runAsyncBatch(queue, topLevelContainer, onComplete);
        };

        if (Platform.isFxApplicationThread()) {
            start.run();
        } else {
            Platform.runLater(start);
        }
    }

    @FunctionalInterface
    public interface ProgressSink {
        void accept(int processedNodes, int totalNodes, String currentElement);
    }

    private void runAsyncBatch(Queue<Runnable> queue, VBox topLevelContainer, Consumer<VBox> onComplete) {
        int steps = 0;
        while (!queue.isEmpty() && steps < ASYNC_BATCH_SIZE) {
            queue.poll().run();
            steps++;
        }
        if (queue.isEmpty()) {
            onComplete.accept(topLevelContainer);
            return;
        }
        Platform.runLater(() -> runAsyncBatch(queue, topLevelContainer, onComplete));
    }

    private void processTopLevelChild(ArchitectureNode child,
                                      ArchitectureNode effectiveRoot,
                                      int maxDepth,
                                      VBox topLevelContainer,
                                      Map<Integer, HBox> topLevelRows,
                                      Map<String, LevelPackageBox> packageContainers,
                                      Set<String> elementsAddedToParent,
                                      Queue<Runnable> queue,
                                      BuildProgressCounter counter,
                                      ProgressSink progressSink) {
        HBox levelRow = topLevelRows.computeIfAbsent(child.getLevel(),
                l -> TreeBuilderSupport.createTopLevelRow(topLevelContainer, siblingGap()));

        if (child.getType() == NodeType.PACKAGE) {
            LevelPackageBox packageBox = createPackageBox(child);
            packageContainers.put(child.getFullName(), packageBox);
            elementRegistry.put(child.getFullName(), packageBox);

            packageBox.fillRow();
            levelRow.getChildren().add(packageBox);

            if (maxDepth < 1) {
                packageBox.setExpanded(false);
            }

            if (maxDepth < 1 && lazyCollapsedPackages) {
                deferChildren(child, packageBox, packageContainers, packageBox,
                        elementsAddedToParent, effectiveRoot, 1, maxDepth);
                counter.processed += child.getTotalNodeCount() - 1;
            } else {
                enqueueChildren(child, packageContainers, packageBox, elementsAddedToParent,
                        effectiveRoot, 1, maxDepth, queue, counter, progressSink);
            }
        } else if (child.getType() == NodeType.CLASS) {
            LevelClassBox classBox = createClassBox(child);
            elementRegistry.put(child.getFullName(), classBox);
            levelRow.getChildren().add(classBox);
        }
        elementsAddedToParent.add(child.getFullName());
        reportProgress(counter, child, progressSink);
    }

    private void enqueueChildren(ArchitectureNode node,
                                 Map<String, LevelPackageBox> packageContainers,
                                 LevelPackageBox rootLevel,
                                 Set<String> elementsAddedToParent,
                                 ArchitectureNode archRoot,
                                 int currentDepth,
                                 int maxDepth,
                                 Queue<Runnable> queue,
                                 BuildProgressCounter counter,
                                 ProgressSink progressSink) {
        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(node)) {
            queue.add(() -> processChildNode(child, packageContainers, rootLevel, elementsAddedToParent,
                    archRoot, currentDepth, maxDepth, queue, counter, progressSink));
        }
    }

    private void processChildNode(ArchitectureNode child,
                                  Map<String, LevelPackageBox> packageContainers,
                                  LevelPackageBox rootLevel,
                                  Set<String> elementsAddedToParent,
                                  ArchitectureNode archRoot,
                                  int currentDepth,
                                  int maxDepth,
                                  Queue<Runnable> queue,
                                  BuildProgressCounter counter,
                                  ProgressSink progressSink) {
        if (elementsAddedToParent.contains(child.getFullName())) {
            return;
        }

        String parentPackage = TreeBuilderSupport.parentOf(child.getFullName());

        ensurePackageHierarchy(parentPackage, packageContainers, rootLevel, archRoot);

        LevelPackageBox parentContainer = packageContainers.get(parentPackage);
        if (parentContainer == null) {
            parentContainer = rootLevel;
        }

        if (child.getType() == NodeType.PACKAGE) {
            if (!packageContainers.containsKey(child.getFullName())) {
                LevelPackageBox packageBox = createPackageBox(child);
                packageContainers.put(child.getFullName(), packageBox);
                elementRegistry.put(child.getFullName(), packageBox);
                parentContainer.addToLevel(child.getLevel(), packageBox);

                if (currentDepth >= maxDepth) {
                    packageBox.setExpanded(false);
                    if (lazyCollapsedPackages) {
                        deferChildren(child, packageBox, packageContainers, packageBox,
                                elementsAddedToParent, archRoot, currentDepth + 1, maxDepth);
                        counter.processed += child.getTotalNodeCount() - 1;
                        elementsAddedToParent.add(child.getFullName());
                        reportProgress(counter, child, progressSink);
                        return;
                    }
                }
            }
            LevelPackageBox packageBox = packageContainers.get(child.getFullName());
            enqueueChildren(child, packageContainers, packageBox == null ? rootLevel : packageBox,
                    elementsAddedToParent, archRoot, currentDepth + 1, maxDepth, queue, counter, progressSink);
        } else if (child.getType() == NodeType.CLASS) {
            LevelClassBox classBox = createClassBox(child);
            elementRegistry.put(child.getFullName(), classBox);
            parentContainer.addToLevel(child.getLevel(), classBox);
        }

        elementsAddedToParent.add(child.getFullName());
        reportProgress(counter, child, progressSink);
    }

    private void reportProgress(BuildProgressCounter counter, ArchitectureNode node, ProgressSink sink) {
        counter.processed++;
        if (sink != null && (counter.processed == 1 || counter.processed % ASYNC_BATCH_SIZE == 0
                || counter.processed >= counter.total)) {
            sink.accept(counter.processed, counter.total, node.getFullName());
        }
    }

    private static final class BuildProgressCounter {
        final int total;
        int processed;

        BuildProgressCounter(int total) {
            this.total = total;
        }
    }

    private VBox createTopLevelContainer() {
        return TreeBuilderSupport.createTopLevelContainer(
                plainNodes ? LevelPackageBox.PLAIN_LEVEL_GAP : 8, "#f5f5f0");
    }

    /**
     * Recursively processes an ArchitectureNode and its children to build the UI hierarchy.
     *
     * @param currentNodeIsTransparent Whether the current node itself is transparent.
     *        Since we skip transparent top-level packages, this is always false after effective root.
     * @param currentDepth Current depth from the effective root (1-based)
     * @param maxDepth Maximum depth to expand
     */
    private void processArchitectureNode(ArchitectureNode node,
                                         Map<String, LevelPackageBox> packageContainers,
                                         LevelPackageBox rootLevel,
                                         Set<String> elementsAddedToParent,
                                         ArchitectureNode archRoot,
                                         boolean currentNodeIsTransparent,
                                         int currentDepth,
                                         int maxDepth) {

        for (ArchitectureNode child : HorizontalLayoutOrdering.childrenInLayoutOrder(node)) {
            // Skip if already processed
            if (elementsAddedToParent.contains(child.getFullName())) {
                continue;
            }

            // Determine parent package
            String parentPackage = TreeBuilderSupport.parentOf(child.getFullName());

            // Ensure parent hierarchy exists
            ensurePackageHierarchy(parentPackage, packageContainers, rootLevel, archRoot);

            // Get parent container
            LevelPackageBox parentContainer = packageContainers.get(parentPackage);
            if (parentContainer == null) {
                parentContainer = rootLevel;
            }

            if (child.getType() == NodeType.PACKAGE) {
                // Create package container if not already created
                if (!packageContainers.containsKey(child.getFullName())) {
                    LevelPackageBox packageBox = createPackageBox(child);
                    packageContainers.put(child.getFullName(), packageBox);
                    elementRegistry.put(child.getFullName(), packageBox);
                    parentContainer.addToLevel(child.getLevel(), packageBox);

                    // Collapse if beyond max depth
                    if (currentDepth >= maxDepth) {
                        packageBox.setExpanded(false);
                        if (lazyCollapsedPackages) {
                            deferChildren(child, packageBox, packageContainers, rootLevel,
                                    elementsAddedToParent, archRoot, currentDepth + 1, maxDepth);
                            elementsAddedToParent.add(child.getFullName());
                            continue;
                        }
                    }
                }
                // Recursively process children
                processArchitectureNode(child, packageContainers, rootLevel, elementsAddedToParent, archRoot, false, currentDepth + 1, maxDepth);
            } else if (child.getType() == NodeType.CLASS) {
                // Create class element
                LevelClassBox classBox = createClassBox(child);
                elementRegistry.put(child.getFullName(), classBox);
                parentContainer.addToLevel(child.getLevel(), classBox);
            }

            elementsAddedToParent.add(child.getFullName());
        }
    }

    /**
     * Leaves {@code packageBox} without children for now. The deferred build
     * runs the regular synchronous path for this package only: it creates the
     * direct children, and sub-packages are again collapsed and deferred
     * because they lie beyond {@code maxDepth} as well.
     */
    private void deferChildren(ArchitectureNode node,
                               LevelPackageBox packageBox,
                               Map<String, LevelPackageBox> packageContainers,
                               LevelPackageBox rootLevel,
                               Set<String> elementsAddedToParent,
                               ArchitectureNode archRoot,
                               int childDepth,
                               int maxDepth) {
        Set<String> pending = new HashSet<>();
        collectDescendantNames(node, pending);
        packageBox.setLazyContent(
                () -> processArchitectureNode(node, packageContainers, rootLevel, elementsAddedToParent,
                        archRoot, false, childDepth, maxDepth),
                pending);
    }

    private static void collectDescendantNames(ArchitectureNode node, Set<String> out) {
        for (ArchitectureNode child : node.getChildren()) {
            out.add(child.getFullName());
            collectDescendantNames(child, out);
        }
    }

    private LevelPackageBox createPackageBox(ArchitectureNode node) {
        return createPackageBox(node.getSimpleName(), node.getLevel(), node.getFullName(), node.getArchitectureLevel());
    }

    private LevelPackageBox createPackageBox(String simpleName,
                                             int level,
                                             String fullName,
                                             int architectureLevel) {
        LevelPackageBox packageBox = new LevelPackageBox(simpleName, level, false, fullName, architectureLevel);
        packageBox.setSelectionChangeSink(selectionChangeSink);
        packageBox.setPlain(plainNodes);
        return packageBox;
    }

    private LevelClassBox createClassBox(ArchitectureNode node) {
        LevelClassBox classBox = new LevelClassBox(
                node.getSimpleName(),
                node.getLevel(),
                node.getFullName(),
                node.isInterfaceType(),
                node.getArchitectureLevel());
        classBox.setSelectionChangeSink(selectionChangeSink);
        classBox.setPlain(plainNodes);
        return classBox;
    }

    private double siblingGap() {
        return plainNodes ? LevelPackageBox.PLAIN_SIBLING_GAP : 8;
    }

    /**
     * Ensures that all parent packages in a hierarchy exist.
     * Since transparent top-level packages are skipped entirely, all packages
     * created here are non-transparent.
     */
    private void ensurePackageHierarchy(String packageName,
                                        Map<String, LevelPackageBox> packageContainers,
                                        LevelPackageBox rootLevel,
                                        ArchitectureNode rootNode) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        if (packageContainers.containsKey(packageName)) {
            return;
        }

        // Split the package into parts
        String[] parts = packageName.split("\\.");
        String currentPkg = "";

        for (String part : parts) {
            String previousPkg = currentPkg;
            currentPkg = currentPkg.isEmpty() ? part : currentPkg + "." + part;

            if (!packageContainers.containsKey(currentPkg)) {
                ArchitectureNode pkgNode = findPackageNodeInTree(currentPkg, rootNode);
                int packageLevel = pkgNode != null ? pkgNode.getLevel() : 0;
                int packageArchLevel = pkgNode != null ? pkgNode.getArchitectureLevel() : -1;

                LevelPackageBox packageBox = createPackageBox(part, packageLevel, currentPkg, packageArchLevel);
                packageContainers.put(currentPkg, packageBox);
                elementRegistry.put(currentPkg, packageBox);

                // Add to parent at the correct architectural level
                LevelPackageBox parentContainer = packageContainers.get(previousPkg);
                if (parentContainer != null) {
                    parentContainer.addToLevel(packageLevel, packageBox);
                } else {
                    rootLevel.addToLevel(packageLevel, packageBox);
                }
            }
        }
    }

    /**
     * Look up a package node in the architecture tree, or {@code null} if absent.
     */
    private ArchitectureNode findPackageNodeInTree(String packageName, ArchitectureNode node) {
        if (node.getFullName().equals(packageName) && node.getType() == NodeType.PACKAGE) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findPackageNodeInTree(packageName, child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
