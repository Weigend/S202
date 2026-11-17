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
package de.weigend.s202.ui.core.model;

import de.weigend.s202.domain.architecture.Tangle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a pruned {@link ArchitectureNode} tree containing only the given
 * tangle members — classes, or packages with their subtree — and the
 * package chain that holds them. Per-node
 * dependencies/dependents are copied through unchanged — the existing
 * dependency renderer skips any edge whose target box wasn't built, so
 * cycles inside the tangle render correctly while edges leaving the tangle
 * are silently dropped from the visualisation.
 */
public final class TangleFilter {

    private TangleFilter() {}

    /**
     * @param root          the full architecture root from a focused
     *                      {@code ArchitectureCanvas}
     * @param keepClasses   member full-names to retain (a tangle's members:
     *                      classes and/or packages)
     * @return a synthetic root whose subtree only includes the kept classes
     *         and their containing packages, or {@code null} if no member
     *         was found in the tree
     */
    public static ArchitectureNode filter(ArchitectureNode root, Set<String> keepClasses) {
        return filterNode(root, keepClasses, null, true);
    }

    /**
     * The tree a tangle view shows: rooted at the container of the members —
     * the deepest package holding all of them — so the members are its direct
     * content and no namespace chain surrounds them.
     *
     * <p>Without context a package member is reduced to its
     * {@link #participants participants}: the classes that carry a dependency
     * to or from another member. Everything else in the package plays no part
     * in the tangle. With context every package member keeps its whole subtree.
     *
     * @return the container node, or {@code null} if no member was found
     */
    public static ArchitectureNode scope(ArchitectureNode root, Set<String> members, boolean withContext) {
        Set<String> participants = withContext ? null : participants(root, members);
        ArchitectureNode filtered = filterNode(root, members, participants, true);
        if (filtered == null || filtered.getChildren().isEmpty()) {
            return null;
        }
        ArchitectureNode container = filtered;
        while (container.getChildren().size() == 1) {
            ArchitectureNode only = container.getChildren().get(0);
            if (only.getType() != ArchitectureNode.NodeType.PACKAGE || members.contains(only.getFullName())) {
                break;
            }
            container = only;
        }
        return container;
    }

    /**
     * The classes that realise the tangle: both ends of every class dependency
     * that connects two different members.
     */
    public static Set<String> participants(ArchitectureNode root, Set<String> members) {
        Set<String> result = new HashSet<>();
        collectParticipants(root, members, result);
        return result;
    }

    private static void collectParticipants(ArchitectureNode node, Set<String> members, Set<String> out) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            String member = Tangle.memberContaining(members, node.getFullName());
            if (member == null) {
                return;
            }
            for (String dep : node.getDependencies()) {
                String depMember = Tangle.memberContaining(members, dep);
                if (depMember != null && !depMember.equals(member)) {
                    out.add(node.getFullName());
                    out.add(dep);
                }
            }
            return;
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectParticipants(child, members, out);
        }
    }

    /**
     * @param participants when non-null, a package member keeps only these
     *                     classes; {@code null} keeps its whole subtree
     */
    private static ArchitectureNode filterNode(ArchitectureNode node, Set<String> keep,
                                               Set<String> participants, boolean isRoot) {
        if (!isRoot && keep.contains(node.getFullName())) {
            // a member: class, or package with its subtree
            if (participants == null || node.getType() == ArchitectureNode.NodeType.CLASS) {
                return cloneDeep(node);
            }
            ArchitectureNode reduced = cloneParticipants(node, participants);
            return reduced != null ? reduced : cloneDeep(node);
        }
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            return null;
        }
        // PACKAGE (or synthetic root) that is not itself a member
        List<ArchitectureNode> filtered = new ArrayList<>();
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode kept = filterNode(child, keep, participants, false);
            if (kept != null) {
                filtered.add(kept);
            }
        }
        if (filtered.isEmpty() && !isRoot) {
            return null;
        }
        ArchitectureNode pkg = ArchitectureNodeCloner.cloneShallow(node);
        for (ArchitectureNode child : filtered) {
            pkg.addChild(child);
        }
        return pkg;
    }

    /** Copy of a package holding only its participant classes; {@code null} when it has none. */
    private static ArchitectureNode cloneParticipants(ArchitectureNode src, Set<String> participants) {
        if (src.getType() == ArchitectureNode.NodeType.CLASS) {
            return participants.contains(src.getFullName()) ? ArchitectureNodeCloner.cloneShallow(src) : null;
        }
        List<ArchitectureNode> kept = new ArrayList<>();
        for (ArchitectureNode child : src.getChildren()) {
            ArchitectureNode copy = cloneParticipants(child, participants);
            if (copy != null) {
                kept.add(copy);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        ArchitectureNode pkg = ArchitectureNodeCloner.cloneShallow(src);
        for (ArchitectureNode child : kept) {
            pkg.addChild(child);
        }
        return pkg;
    }

    private static ArchitectureNode cloneDeep(ArchitectureNode src) {
        return ArchitectureNodeCloner.cloneTree(src);
    }
}
