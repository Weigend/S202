package de.weigend.s202.ui.tree;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.HashSet;
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

        // Check if top-level packages should be transparent (only one top-level package)
        boolean topLevelTransparent = shouldChildrenBeTransparent(rootNode);

        // Process children of root directly (skip the root node itself)
        for (ArchitectureNode child : rootNode.getChildren()) {
            if (child.getType() == NodeType.PACKAGE) {
                // Create top-level package box
                LevelPackageBox packageBox = new LevelPackageBox(child.getSimpleName(), child.getLevel(), topLevelTransparent);
                packageContainers.put(child.getFullName(), packageBox);
                elementRegistry.put(child.getFullName(), packageBox);
                topLevelContainer.getChildren().add(packageBox);

                // Recursively process children
                processArchitectureNode(child, packageContainers, packageBox, elementsAddedToParent, rootNode);
            } else if (child.getType() == NodeType.CLASS) {
                // Top-level class (rare but possible)
                LevelClassBox classBox = new LevelClassBox(child.getSimpleName(), child.getLevel(), child.getFullName());
                elementRegistry.put(child.getFullName(), classBox);
                topLevelContainer.getChildren().add(classBox);
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
     */
    private void processArchitectureNode(ArchitectureNode node,
                                         Map<String, LevelPackageBox> packageContainers,
                                         LevelPackageBox rootLevel,
                                         Set<String> elementsAddedToParent,
                                         ArchitectureNode archRoot) {
        // Check if child packages should be transparent (node has only one sub-package)
        boolean childrenTransparent = shouldChildrenBeTransparent(node);

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
                    LevelPackageBox packageBox = new LevelPackageBox(child.getSimpleName(), child.getLevel(), childrenTransparent);
                    packageContainers.put(child.getFullName(), packageBox);
                    elementRegistry.put(child.getFullName(), packageBox);
                    parentContainer.addToLevel(child.getLevel(), packageBox);
                }
                // Recursively process children
                processArchitectureNode(child, packageContainers, rootLevel, elementsAddedToParent, archRoot);
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
                // Look up package level and check if transparent from architecture tree
                int packageLevel = findPackageLevelInTree(currentPkg, rootNode);

                // Find the parent node to determine if this package should be transparent
                ArchitectureNode parentNode = previousPkg.isEmpty() ? rootNode : findNodeInTree(previousPkg, rootNode);
                boolean isTransparent = parentNode != null && shouldChildrenBeTransparent(parentNode);

                LevelPackageBox packageBox = new LevelPackageBox(part, packageLevel, isTransparent);
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
     * Find a node by full name in the architecture tree.
     */
    private ArchitectureNode findNodeInTree(String fullName, ArchitectureNode node) {
        if (node.getFullName().equals(fullName)) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findNodeInTree(fullName, child);
            if (found != null) {
                return found;
            }
        }
        return null;
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
