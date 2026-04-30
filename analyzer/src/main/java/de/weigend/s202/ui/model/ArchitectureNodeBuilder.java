package de.weigend.s202.ui.model;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;

import java.util.*;

/**
 * Builds an ArchitectureNode tree from a DomainModel.
 * Creates a hierarchical package structure with classes as leaf nodes.
 */
public class ArchitectureNodeBuilder {

    /**
     * Builds an ArchitectureNode tree from the given DomainModel.
     * 
     * @param domainModel The calculated domain model with levels
     * @return Root node containing the full package/class hierarchy
     */
    public ArchitectureNode build(DomainModel domainModel) {
        Objects.requireNonNull(domainModel, "domainModel cannot be null");
        
        // Create synthetic root node
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        
        // Collect all elements by their parent package
        Map<String, List<DomainModel.CalculatedElementInfo>> elementsByPackage = new HashMap<>();
        
        // Add classes
        for (DomainModel.CalculatedElementInfo classInfo : domainModel.getAllClasses().values()) {
            String parentPackage = getParentPackage(classInfo.fullName);
            elementsByPackage.computeIfAbsent(parentPackage, k -> new ArrayList<>()).add(classInfo);
        }
        
        // Add packages
        for (DomainModel.CalculatedElementInfo pkgInfo : domainModel.getAllPackages().values()) {
            String parentPackage = getParentPackage(pkgInfo.fullName);
            elementsByPackage.computeIfAbsent(parentPackage, k -> new ArrayList<>()).add(pkgInfo);
        }
        
        // Ensure parent package placeholders exist
        addParentPackagePlaceholders(elementsByPackage, domainModel);
        
        // Build hierarchy recursively
        buildHierarchy(root, elementsByPackage, "", domainModel);
        
        return root;
    }
    
    /**
     * Extract parent package name from a fully qualified name.
     */
    private String getParentPackage(String fullName) {
        if (fullName == null || !fullName.contains(".")) {
            return "";
        }
        int lastDot = fullName.lastIndexOf('.');
        return fullName.substring(0, lastDot);
    }
    
    /**
     * Ensure placeholder entries exist for each parent package so that the
     * hierarchy is complete (including synthetic parent nodes).
     */
    private void addParentPackagePlaceholders(Map<String, List<DomainModel.CalculatedElementInfo>> elementsByPackage,
                                              DomainModel domainModel) {
        Set<String> existingPackages = new HashSet<>(elementsByPackage.keySet());
        
        for (String pkg : existingPackages) {
            if (pkg == null || pkg.isEmpty()) {
                continue;
            }
            
            String current = pkg;
            while (true) {
                int lastDot = current.lastIndexOf('.');
                if (lastDot <= 0) {
                    break;
                }
                current = current.substring(0, lastDot);
                elementsByPackage.computeIfAbsent(current, k -> new ArrayList<>());
            }
            
            // Ensure top-level package without dots exists
            if (!pkg.contains(".")) {
                elementsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>());
            }
        }
    }
    
    /**
     * Recursively builds package hierarchy tree.
     */
    private void buildHierarchy(ArchitectureNode parentNode,
                                Map<String, List<DomainModel.CalculatedElementInfo>> elementsByPackage,
                                String currentPackage,
                                DomainModel domainModel) {
        
        // Add classes as direct children
        List<DomainModel.CalculatedElementInfo> directElements = elementsByPackage.get(currentPackage);
        if (directElements != null) {
            for (DomainModel.CalculatedElementInfo element : directElements) {
                if ("CLASS".equals(element.type)) {
                    ArchitectureNode classNode = new ArchitectureNode(
                        element.fullName,
                        element.simpleName,
                        NodeType.CLASS,
                        false,
                        element.level,
                        element.interfaceType
                    );
                    classNode.setDependencies(element.dependencies);
                    classNode.setDependents(element.dependents);
                    parentNode.addChild(classNode);
                }
            }
        }
        
        // Find and add subpackages
        String packagePrefix = currentPackage.isEmpty() ? "" : currentPackage + ".";
        Set<String> subpackages = new TreeSet<>(); // Sorted for consistent ordering
        
        for (String pkg : elementsByPackage.keySet()) {
            if (pkg == null || pkg.isEmpty()) {
                continue;
            }
            
            if (currentPackage.isEmpty()) {
                // Top level: find packages without dots
                if (!pkg.contains(".")) {
                    subpackages.add(pkg);
                }
            } else if (pkg.startsWith(packagePrefix) && !pkg.equals(currentPackage)) {
                // Find direct subpackages
                String relativePkg = pkg.substring(packagePrefix.length());
                if (!relativePkg.contains(".")) {
                    subpackages.add(pkg);
                }
            }
        }
        
        // Recursively process subpackages
        for (String subpkg : subpackages) {
            int lastDot = subpkg.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? subpkg.substring(lastDot + 1) : subpkg;
            
            // Get package level from domain model, default to 0
            int pkgLevel = 0;
            DomainModel.CalculatedElementInfo pkgInfo = domainModel.getPackage(subpkg);
            if (pkgInfo != null) {
                pkgLevel = pkgInfo.level;
            }
            
            ArchitectureNode subpkgNode = new ArchitectureNode(
                subpkg,
                simpleName,
                NodeType.PACKAGE,
                true,
                pkgLevel
            );
            
            // Set package dependencies if available
            if (pkgInfo != null) {
                subpkgNode.setDependencies(pkgInfo.dependencies);
                subpkgNode.setDependents(pkgInfo.dependents);
            }
            
            parentNode.addChild(subpkgNode);
            buildHierarchy(subpkgNode, elementsByPackage, subpkg, domainModel);
        }
    }
}
