package de.weigend.s202.ui.model;

import de.weigend.s202.analysis.scc.SCCDAGBuilder;
import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;

import java.util.*;

/**
 * Computes local layout row-levels for ALL sibling elements (classes AND packages)
 * within each parent container, based on inter-sibling dependency direction.
 *
 * <p>Levels are computed locally per parent — NOT globally. A class that only has
 * dependencies to elements outside the current package is Level 0 within that package.
 *
 * <p>Each sibling (class or package) has a "subtree" of classes:
 * <ul>
 *   <li>Class node: subtree = {itself}</li>
 *   <li>Package node: subtree = all classes recursively contained</li>
 * </ul>
 *
 * <p>For each pair of siblings (A, B), the dependency direction is determined by
 * distinct-target-counting on their subtrees. SCC + DAG level assignment produces
 * the final layout levels.
 */
public class DistrictRowLevelCalculator {

    /**
     * Assigns local layout row-levels to all nodes in the tree.
     *
     * @param root the root of the ArchitectureNode tree
     */
    public void assignDistrictRowLevels(ArchitectureNode root) {
        processNode(root);
    }

    /**
     * Recursively processes a parent node: compute local levels for all its
     * children (classes + packages), then recurse into package children.
     */
    private void processNode(ArchitectureNode node) {
        List<ArchitectureNode> children = node.getChildren();

        if (children.size() >= 2) {
            assignLevelsToAllSiblings(children);
        } else if (children.size() == 1) {
            children.get(0).setLevel(0);
        }

        // Recurse into package children
        for (ArchitectureNode child : children) {
            if (child.getType() == NodeType.PACKAGE) {
                processNode(child);
            }
        }
    }

    /**
     * Computes and assigns local layout levels for all sibling elements
     * (classes and packages) within the same parent container.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>For each sibling, collect its subtree classes and their dependencies</li>
     *   <li>For each pair, count distinct targets in each direction</li>
     *   <li>Net direction determines edge in the sibling dependency graph</li>
     *   <li>SCC detection + DAG level assignment produces layout levels</li>
     * </ol>
     */
    private void assignLevelsToAllSiblings(List<ArchitectureNode> siblings) {
        // Step 1: Collect subtree classes for each sibling
        // Class: subtree = {itself}, Package: subtree = all classes recursively
        Map<String, Set<String>> subtreeClassNames = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> subtreeClassDeps = new LinkedHashMap<>();

        for (ArchitectureNode sibling : siblings) {
            Set<String> classNames = new HashSet<>();
            Map<String, Set<String>> classDeps = new HashMap<>();
            if (sibling.getType() == NodeType.CLASS) {
                classNames.add(sibling.getFullName());
                classDeps.put(sibling.getFullName(), sibling.getDependencies());
            } else {
                collectSubtreeClasses(sibling, classNames, classDeps);
            }
            subtreeClassNames.put(sibling.getFullName(), classNames);
            subtreeClassDeps.put(sibling.getFullName(), classDeps);
        }

        // Step 2: Build dependency graph among ALL siblings via distinct-target-counting
        Map<String, Set<String>> siblingGraph = new LinkedHashMap<>();
        for (ArchitectureNode sibling : siblings) {
            siblingGraph.put(sibling.getFullName(), new HashSet<>());
        }

        for (int i = 0; i < siblings.size(); i++) {
            for (int j = i + 1; j < siblings.size(); j++) {
                String nameA = siblings.get(i).getFullName();
                String nameB = siblings.get(j).getFullName();

                int countAtoB = countDistinctTargets(
                        subtreeClassDeps.get(nameA), subtreeClassNames.get(nameB));
                int countBtoA = countDistinctTargets(
                        subtreeClassDeps.get(nameB), subtreeClassNames.get(nameA));

                if (countAtoB > countBtoA) {
                    // A depends more on B → A is higher
                    siblingGraph.get(nameA).add(nameB);
                } else if (countBtoA > countAtoB) {
                    // B depends more on A → B is higher
                    siblingGraph.get(nameB).add(nameA);
                }
            }
        }

        // Step 3: SCC detection + DAG level assignment
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(siblingGraph);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();

        SCCDAGBuilder dagBuilder = new SCCDAGBuilder(sccs, siblingGraph);
        dagBuilder.buildDAG();
        dagBuilder.assignLevels();

        // Step 4: Apply computed levels to all siblings
        Map<String, Integer> nodeToLevel = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                nodeToLevel.put(member, scc.getLevel());
            }
        }

        for (ArchitectureNode sibling : siblings) {
            Integer level = nodeToLevel.get(sibling.getFullName());
            if (level != null) {
                sibling.setLevel(level);
            }
        }
    }

    /**
     * Recursively collects all CLASS nodes in a subtree.
     */
    private void collectSubtreeClasses(ArchitectureNode node,
                                       Set<String> classNames,
                                       Map<String, Set<String>> classDeps) {
        if (node.getType() == NodeType.CLASS) {
            classNames.add(node.getFullName());
            classDeps.put(node.getFullName(), node.getDependencies());
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectSubtreeClasses(child, classNames, classDeps);
        }
    }

    /**
     * Counts distinct target classes: how many classes in {@code targetClasses}
     * are referenced by any class in {@code sourceClassDeps}.
     *
     * <p>Implements: |{c ∈ targetClasses : ∃ s ∈ sourceClasses with s.dependencies ∋ c}|
     */
    private int countDistinctTargets(Map<String, Set<String>> sourceClassDeps,
                                     Set<String> targetClasses) {
        Set<String> distinctTargets = new HashSet<>();
        for (Set<String> deps : sourceClassDeps.values()) {
            for (String dep : deps) {
                if (targetClasses.contains(dep)) {
                    distinctTargets.add(dep);
                }
            }
        }
        return distinctTargets.size();
    }
}
