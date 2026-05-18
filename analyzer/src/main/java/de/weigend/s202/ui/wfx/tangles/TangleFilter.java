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
package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.ui.model.ArchitectureNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds a pruned {@link ArchitectureNode} tree containing only the given
 * class members and the package chain that holds them. Per-node
 * dependencies/dependents are copied through unchanged — the existing
 * dependency renderer skips any edge whose target box wasn't built, so
 * cycles inside the tangle render correctly while edges leaving the tangle
 * are silently dropped from the visualisation.
 */
public final class TangleFilter {

    private TangleFilter() {}

    /**
     * @param root          the full architecture root from a focused
     *                      {@code ArchitectureView}
     * @param keepClasses   class full-names to retain (typically a tangle's
     *                      members)
     * @return a synthetic root whose subtree only includes the kept classes
     *         and their containing packages, or {@code null} if no member
     *         was found in the tree
     */
    public static ArchitectureNode filter(ArchitectureNode root, Set<String> keepClasses) {
        return filterNode(root, keepClasses, true);
    }

    private static ArchitectureNode filterNode(ArchitectureNode node, Set<String> keep, boolean isRoot) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            return keep.contains(node.getFullName()) ? clone(node) : null;
        }
        // PACKAGE (or synthetic root)
        List<ArchitectureNode> filtered = new ArrayList<>();
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode kept = filterNode(child, keep, false);
            if (kept != null) {
                filtered.add(kept);
            }
        }
        if (filtered.isEmpty() && !isRoot) {
            return null;
        }
        ArchitectureNode pkg = clone(node);
        for (ArchitectureNode child : filtered) {
            pkg.addChild(child);
        }
        return pkg;
    }

    private static ArchitectureNode clone(ArchitectureNode src) {
        ArchitectureNode n = new ArchitectureNode(
                src.getFullName(), src.getSimpleName(), src.getType(),
                src.isAutoExpanded(), src.getLevel(), src.isInterfaceType());
        n.setDependencies(src.getDependencies());
        n.setDependents(src.getDependents());
        return n;
    }
}
