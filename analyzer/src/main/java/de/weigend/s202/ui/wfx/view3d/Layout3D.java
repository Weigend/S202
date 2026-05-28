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
package de.weigend.s202.ui.wfx.view3d;

import de.weigend.s202.ui.model.ArchitectureNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure-Java (no JavaFX) layout engine for the 3D architecture view.
 *
 * <p>Maps the 2D architecture layout to 3D world coordinates:
 * <ul>
 *   <li>2D X  → 3D X  (unchanged; determined by {@code horizontalLayoutOrder})</li>
 *   <li>2D Y  → 3D Z  (the "tip-horizontal" transform; determined by {@code level})</li>
 *   <li>new Y → height of box (determined by {@code architectureLevel})</li>
 * </ul>
 *
 * <p>Within each parent package the algorithm replicates the 2D behaviour:
 * children at the same {@code level} form a row (sorted by
 * {@code horizontalLayoutOrder} left-to-right), rows are ordered by ascending
 * level in the Z direction (level 0 near, highest level far). A parent
 * package's footprint is at least as large as its metric box but expands
 * to encompass all descendants.
 */
class Layout3D {

    // Tunable constants (package-visible for tests)
    static final double UNIT       = 20.0;
    static final double NODE_GAP   = 10.0;   // gap between siblings in the same row
    static final double GROUP_GAP  = 20.0;   // Z gap between level rows within a parent
    static final double PADDING    = 8.0;    // space around children inside a parent footprint

    /**
     * Computed position of one node in 3D world space.
     *
     * @param fullName    fully-qualified name of the node
     * @param centerX     X coordinate of the box centre
     * @param centerZ     Z coordinate of the box centre (mapped from 2D Y)
     * @param width       X extent of the footprint (fanin × UNIT, expanded for children)
     * @param depth       Z extent of the footprint (fanout × UNIT, expanded for children)
     * @param height      Y height of the visible node box (architectureLevel × UNIT)
     * @param hasChildren true if the node has child nodes that are placed within its footprint
     */
    record NodeLayout3D(String fullName,
                        double centerX, double centerZ,
                        double width, double depth, double height,
                        boolean hasChildren) {

        /** Left edge of this node's footprint. */
        double minX() { return centerX - width  / 2.0; }
        /** Right edge of this node's footprint. */
        double maxX() { return centerX + width  / 2.0; }
        /** Near edge of this node's footprint. */
        double minZ() { return centerZ - depth  / 2.0; }
        /** Far edge of this node's footprint. */
        double maxZ() { return centerZ + depth  / 2.0; }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Computes a flat list of {@link NodeLayout3D} entries for every displayable
     * node in the tree rooted at {@code root}. The root itself is not included
     * (it is a synthetic container with {@code architectureLevel = -1}).
     */
    List<NodeLayout3D> compute(ArchitectureNode root) {
        if (root == null) return List.of();
        List<NodeLayout3D> result = new ArrayList<>();
        layoutChildren(displayableChildren(root), 0.0, 0.0, result);
        return result;
    }

    // -----------------------------------------------------------------------
    // Recursive layout core
    // -----------------------------------------------------------------------

    /**
     * Lays out {@code children} starting at ({@code startX}, {@code startZ}).
     *
     * @return {totalWidth, totalDepth} of the bounding rectangle covering all placed nodes
     */
    private double[] layoutChildren(List<ArchitectureNode> children,
                                    double startX, double startZ,
                                    List<NodeLayout3D> result) {
        if (children.isEmpty()) return new double[]{0.0, 0.0};

        // Group by local level, ascending (level 0 → near / small Z, high level → far / large Z)
        Map<Integer, List<ArchitectureNode>> byLevel = children.stream()
                .collect(Collectors.groupingBy(ArchitectureNode::getLevel));

        List<Integer> levels = new ArrayList<>(byLevel.keySet());
        levels.sort(Comparator.naturalOrder());

        double currentZ  = startZ;
        double totalW    = 0.0;

        for (int li = 0; li < levels.size(); li++) {
            int level = levels.get(li);
            List<ArchitectureNode> row = new ArrayList<>(byLevel.get(level));
            row.sort(Comparator.comparingInt(ArchitectureNode::getHorizontalLayoutOrder));

            double currentX  = startX;
            double rowDepth  = 0.0;

            for (ArchitectureNode node : row) {
                double[] size = placeNode(node, currentX, currentZ, result);
                currentX += size[0] + NODE_GAP;
                rowDepth  = Math.max(rowDepth, size[1]);
            }

            double rowWidth = currentX - startX - NODE_GAP;
            totalW = Math.max(totalW, rowWidth);

            currentZ += rowDepth;
            if (li < levels.size() - 1) {
                currentZ += GROUP_GAP;
            }
        }

        return new double[]{totalW, currentZ - startZ};
    }

    /**
     * Places {@code node} at world position ({@code worldX}, {@code worldZ}),
     * recursively places its children inside its footprint, adds to {@code result}.
     *
     * @return {finalWidth, finalDepth} of the node's footprint (may be larger than the
     *         metric box if children require more space)
     */
    private double[] placeNode(ArchitectureNode node,
                               double worldX, double worldZ,
                               List<NodeLayout3D> result) {
        // Metric box dimensions
        double metricW = Math.max(1, node.getDependents().size())    * UNIT;
        double metricD = Math.max(1, node.getDependencies().size())  * UNIT;
        double height  = Math.max(1, node.getArchitectureLevel())    * UNIT;

        List<ArchitectureNode> kids = displayableChildren(node);
        double contentW = 0.0, contentD = 0.0;
        List<NodeLayout3D> childBatch = new ArrayList<>();

        if (!kids.isEmpty()) {
            double[] childBounds = layoutChildren(kids,
                    worldX + PADDING, worldZ + PADDING, childBatch);
            contentW = childBounds[0] + 2.0 * PADDING;
            contentD = childBounds[1] + 2.0 * PADDING;
        }

        double finalW = Math.max(metricW, contentW);
        double finalD = Math.max(metricD, contentD);

        result.add(new NodeLayout3D(
                node.getFullName(),
                worldX + finalW / 2.0,
                worldZ + finalD / 2.0,
                finalW, finalD, height,
                !kids.isEmpty()));
        result.addAll(childBatch);

        return new double[]{finalW, finalD};
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Filters children to only those with a valid (≥ 0) architectureLevel. */
    private static List<ArchitectureNode> displayableChildren(ArchitectureNode node) {
        return node.getChildren().stream()
                .filter(c -> c.getArchitectureLevel() >= 0)
                .collect(Collectors.toList());
    }
}
