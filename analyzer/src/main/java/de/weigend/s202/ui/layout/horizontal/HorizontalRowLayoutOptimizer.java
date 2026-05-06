package de.weigend.s202.ui.layout.horizontal;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Assigns horizontal order values for elements that share the same local row.
 *
 * <p>The optimizer never mutates {@link ArchitectureNode#getChildren()}. It only
 * writes {@link ArchitectureNode#setHorizontalLayoutOrder(int)} on direct
 * siblings. Tree builders can then render each row from a sorted view while all
 * model traversal semantics stay unchanged.
 */
public final class HorizontalRowLayoutOptimizer {

    private static final int ITERATIONS = 8;

    private static final Comparator<ArchitectureNode> STABLE_NODE_ORDER =
            Comparator.comparing(ArchitectureNode::getFullName);

    /**
     * Assigns horizontal layout orders recursively for all package children.
     */
    public void assignHorizontalLayoutOrders(ArchitectureNode root) {
        Objects.requireNonNull(root, "root cannot be null");
        processParent(root);
    }

    private void processParent(ArchitectureNode parent) {
        List<ArchitectureNode> children = parent.getChildren();
        if (!children.isEmpty()) {
            assignOrdersToDirectChildren(children);
        }

        for (ArchitectureNode child : children) {
            if (child.getType() == NodeType.PACKAGE) {
                processParent(child);
            }
        }
    }

    private void assignOrdersToDirectChildren(List<ArchitectureNode> siblings) {
        Map<Integer, List<ArchitectureNode>> rows = rowsByLevel(siblings);
        Map<String, WeightedNeighbors> weights = dependencyWeights(siblings);

        for (List<ArchitectureNode> row : rows.values()) {
            row.sort(STABLE_NODE_ORDER);
        }

        if (hasAnyDependencyWeight(weights)) {
            optimizeRows(rows, weights);
        }

        for (List<ArchitectureNode> row : rows.values()) {
            for (int order = 0; order < row.size(); order++) {
                row.get(order).setHorizontalLayoutOrder(order);
            }
        }
    }

    private Map<Integer, List<ArchitectureNode>> rowsByLevel(List<ArchitectureNode> siblings) {
        Map<Integer, List<ArchitectureNode>> rows = new TreeMap<>(Comparator.reverseOrder());
        for (ArchitectureNode sibling : siblings) {
            rows.computeIfAbsent(sibling.getLevel(), ignored -> new ArrayList<>()).add(sibling);
        }
        return rows;
    }

    private void optimizeRows(Map<Integer, List<ArchitectureNode>> rows,
                              Map<String, WeightedNeighbors> weights) {
        List<List<ArchitectureNode>> rowList = new ArrayList<>(rows.values());
        // Keep one stable reference row so the graph does not freely mirror
        // itself between equivalent-cost layouts.
        List<ArchitectureNode> anchorRow = rowList.isEmpty() ? List.of() : rowList.get(0);
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            if (iteration % 2 == 0) {
                sweep(rowList, rows, weights, anchorRow);
            } else {
                List<List<ArchitectureNode>> reverseRows = new ArrayList<>(rowList);
                Collections.reverse(reverseRows);
                sweep(reverseRows, rows, weights, anchorRow);
            }
        }
    }

    private void sweep(List<List<ArchitectureNode>> rowsInSweepOrder,
                       Map<Integer, List<ArchitectureNode>> allRows,
                       Map<String, WeightedNeighbors> weights,
                       List<ArchitectureNode> anchorRow) {
        Map<String, Position> positions = currentPositions(allRows);
        for (List<ArchitectureNode> row : rowsInSweepOrder) {
            if (row == anchorRow || row.size() < 2) {
                continue;
            }

            Map<String, Double> scores = new HashMap<>();
            for (ArchitectureNode node : row) {
                scores.put(node.getFullName(), barycenter(node, positions, weights));
            }

            Map<String, Position> positionsForSort = positions;
            row.sort(Comparator
                    .comparingDouble((ArchitectureNode node) -> scores.get(node.getFullName()))
                    .thenComparingInt(node -> positionsForSort.get(node.getFullName()).index())
                    .thenComparing(ArchitectureNode::getFullName));
            positions = currentPositions(allRows);
        }
    }

    private double barycenter(ArchitectureNode node,
                              Map<String, Position> positions,
                              Map<String, WeightedNeighbors> weights) {
        WeightedNeighbors neighbors = weights.get(node.getFullName());
        Position ownPosition = positions.get(node.getFullName());
        if (neighbors == null || neighbors.isEmpty()) {
            return ownPosition.normalized();
        }

        double sum = 0.0;
        int weightSum = 0;
        for (Map.Entry<String, Integer> entry : neighbors.byName().entrySet()) {
            Position neighborPosition = positions.get(entry.getKey());
            if (neighborPosition == null) {
                continue;
            }
            int weight = entry.getValue();
            sum += neighborPosition.normalized() * weight;
            weightSum += weight;
        }

        if (weightSum == 0) {
            return ownPosition.normalized();
        }
        return sum / weightSum;
    }

    private Map<String, Position> currentPositions(Map<Integer, List<ArchitectureNode>> rows) {
        Map<String, Position> positions = new HashMap<>();
        for (List<ArchitectureNode> row : rows.values()) {
            int rowSize = row.size();
            for (int index = 0; index < rowSize; index++) {
                positions.put(row.get(index).getFullName(), new Position(index, rowSize));
            }
        }
        return positions;
    }

    private Map<String, WeightedNeighbors> dependencyWeights(List<ArchitectureNode> siblings) {
        Map<String, Set<String>> classesBySibling = new LinkedHashMap<>();
        Map<String, Set<String>> dependenciesByClass = new HashMap<>();
        Map<String, String> siblingByClass = new HashMap<>();

        for (ArchitectureNode sibling : siblings) {
            Set<String> classNames = new HashSet<>();
            collectSubtreeClasses(sibling, classNames, dependenciesByClass);
            classesBySibling.put(sibling.getFullName(), classNames);
            for (String className : classNames) {
                siblingByClass.put(className, sibling.getFullName());
            }
        }

        Map<String, WeightedNeighbors> weights = new HashMap<>();
        for (String siblingName : classesBySibling.keySet()) {
            weights.put(siblingName, new WeightedNeighbors());
        }

        for (Map.Entry<String, Set<String>> siblingClasses : classesBySibling.entrySet()) {
            String sourceSibling = siblingClasses.getKey();
            for (String sourceClass : siblingClasses.getValue()) {
                for (String dependency : dependenciesByClass.getOrDefault(sourceClass, Set.of())) {
                    String targetSibling = siblingByClass.get(dependency);
                    if (targetSibling == null || targetSibling.equals(sourceSibling)) {
                        continue;
                    }
                    weights.get(sourceSibling).add(targetSibling);
                    weights.get(targetSibling).add(sourceSibling);
                }
            }
        }

        return weights;
    }

    private void collectSubtreeClasses(ArchitectureNode node,
                                       Set<String> classNames,
                                       Map<String, Set<String>> dependenciesByClass) {
        if (node.getType() == NodeType.CLASS) {
            classNames.add(node.getFullName());
            dependenciesByClass.put(node.getFullName(), node.getDependencies());
            return;
        }

        for (ArchitectureNode child : node.getChildren()) {
            collectSubtreeClasses(child, classNames, dependenciesByClass);
        }
    }

    private boolean hasAnyDependencyWeight(Map<String, WeightedNeighbors> weights) {
        for (WeightedNeighbors neighbors : weights.values()) {
            if (!neighbors.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private record Position(int index, int rowSize) {
        double normalized() {
            if (rowSize <= 1) {
                return 0.5;
            }
            return index / (double) (rowSize - 1);
        }
    }

    private static final class WeightedNeighbors {
        private final Map<String, Integer> byName = new HashMap<>();

        void add(String fullName) {
            byName.merge(fullName, 1, Integer::sum);
        }

        Map<String, Integer> byName() {
            return byName;
        }

        boolean isEmpty() {
            return byName.isEmpty();
        }
    }
}
