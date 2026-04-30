package de.weigend.s202.ui.tree;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private final Map<String, Node> elementRegistry;

    /**
     * Creates a new ArchitectureTreeBuilder.
     *
     * @param elementRegistry Shared registry for element lookup (will be cleared and populated)
     */
    public ArchitectureTreeBuilder(Map<String, Node> elementRegistry) {
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
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
        if (rootNode == null) {
            throw new IllegalArgumentException("rootNode cannot be null");
        }

        // Clear registry for new tree
        elementRegistry.clear();

        // Track package containers for hierarchy building
        Map<String, LevelPackageBox> packageContainers = new HashMap<>();
        Set<String> elementsAddedToParent = new HashSet<>();

        // Container for top-level packages (children of root)
        VBox topLevelContainer = new VBox(8);
        topLevelContainer.setPadding(new Insets(10));
        topLevelContainer.setStyle("-fx-background-color: #f5f5f0;");

        // Skip transparent top-level packages (de, weigend, s202, etc.)
        // Follow the chain of single-child packages until we reach the first "real" package
        ArchitectureNode effectiveRoot = rootNode;
        while (shouldChildrenBeTransparent(effectiveRoot)) {
            ArchitectureNode singleChild = effectiveRoot.getChildren().stream()
                    .filter(c -> c.getType() == NodeType.PACKAGE)
                    .findFirst().orElse(null);
            if (singleChild == null) break;
            // Register the skipped package so dependency lookups still work
            elementRegistry.put(singleChild.getFullName(), topLevelContainer);
            effectiveRoot = singleChild;
        }

        // Process children of effective root, sorted by level descending (higher levels at top)
        List<ArchitectureNode> sortedChildren = new ArrayList<>(effectiveRoot.getChildren());
        sortedChildren.sort((a, b) -> Integer.compare(b.getLevel(), a.getLevel()));

        // Group top-level children by level into HBox rows (same level = side by side)
        Map<Integer, HBox> topLevelRows = new HashMap<>();
        for (ArchitectureNode child : sortedChildren) {
            int level = child.getLevel();
            HBox levelRow = topLevelRows.computeIfAbsent(level, l -> {
                HBox hbox = new HBox(8);
                hbox.setMaxWidth(Double.MAX_VALUE);
                VBox.setVgrow(hbox, Priority.ALWAYS);
                topLevelContainer.getChildren().add(hbox);
                return hbox;
            });

            if (child.getType() == NodeType.PACKAGE) {
                LevelPackageBox packageBox = new LevelPackageBox(child.getSimpleName(), child.getLevel(), false, child.getFullName());
                packageContainers.put(child.getFullName(), packageBox);
                elementRegistry.put(child.getFullName(), packageBox);

                packageBox.setMaxWidth(Double.MAX_VALUE);
                packageBox.setMaxHeight(Double.MAX_VALUE);
                HBox.setHgrow(packageBox, Priority.ALWAYS);
                levelRow.getChildren().add(packageBox);

                // Depth 1 = this level visible, collapse if beyond maxDepth
                if (maxDepth < 1) {
                    packageBox.setExpanded(false);
                }

                processArchitectureNode(child, packageContainers, packageBox, elementsAddedToParent, effectiveRoot, false, 1, maxDepth);
            } else if (child.getType() == NodeType.CLASS) {
                LevelClassBox classBox = new LevelClassBox(child.getSimpleName(), child.getLevel(), child.getFullName());
                elementRegistry.put(child.getFullName(), classBox);
                levelRow.getChildren().add(classBox);
            }
            elementsAddedToParent.add(child.getFullName());
        }

        return topLevelContainer;
    }

    /**
     * Checks if a package should be displayed as transparent.
     * A package is transparent if it is the ONLY sub-package of its parent.
     * This visually de-emphasizes "pass-through" packages like de.weigend.s202.
     */
    private boolean shouldChildrenBeTransparent(ArchitectureNode parentNode) {
        // Count how many sub-packages the parent has
        long packageCount = parentNode.getChildren().stream()
                .filter(c -> c.getType() == NodeType.PACKAGE)
                .count();
        // Children are transparent only if there's exactly one sub-package
        return packageCount == 1;
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

        for (ArchitectureNode child : node.getChildren()) {
            // Skip if already processed
            if (elementsAddedToParent.contains(child.getFullName())) {
                continue;
            }

            // Determine parent package
            String parentPackage = getParentPackage(child.getFullName());
            if (parentPackage == null) {
                parentPackage = "";
            }

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
                    LevelPackageBox packageBox = new LevelPackageBox(child.getSimpleName(), child.getLevel(), false, child.getFullName());
                    packageContainers.put(child.getFullName(), packageBox);
                    elementRegistry.put(child.getFullName(), packageBox);
                    parentContainer.addToLevel(child.getLevel(), packageBox);

                    // Collapse if beyond max depth
                    if (currentDepth >= maxDepth) {
                        packageBox.setExpanded(false);
                    }
                }
                // Recursively process children
                processArchitectureNode(child, packageContainers, rootLevel, elementsAddedToParent, archRoot, false, currentDepth + 1, maxDepth);
            } else if (child.getType() == NodeType.CLASS) {
                // Create class element
                LevelClassBox classBox = new LevelClassBox(child.getSimpleName(), child.getLevel(), child.getFullName());
                elementRegistry.put(child.getFullName(), classBox);
                parentContainer.addToLevel(child.getLevel(), classBox);
            }

            elementsAddedToParent.add(child.getFullName());
        }
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
                int packageLevel = findPackageLevelInTree(currentPkg, rootNode);

                LevelPackageBox packageBox = new LevelPackageBox(part, packageLevel, false, currentPkg);
                packageContainers.put(currentPkg, packageBox);

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
     * Look up a package's level in the architecture tree.
     */
    private int findPackageLevelInTree(String packageName, ArchitectureNode node) {
        if (node.getFullName().equals(packageName) && node.getType() == NodeType.PACKAGE) {
            return node.getLevel();
        }
        for (ArchitectureNode child : node.getChildren()) {
            int level = findPackageLevelInTree(packageName, child);
            if (level >= 0) {
                return level;
            }
        }
        return 0; // Default if not found
    }

    /**
     * Extract parent package name from a fully qualified name.
     */
    private String getParentPackage(String fullName) {
        if (!fullName.contains(".")) return "";

        int lastDot = fullName.lastIndexOf('.');
        return fullName.substring(0, lastDot);
    }
}
