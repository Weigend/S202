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
package de.weigend.s202.domain.impl;

import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.domain.architecture.LayeredArchitecture;
import de.weigend.s202.domain.architecture.Tangle;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.SCCFinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable What-If counterpart to {@link HierarchicalLayeredArchitecture}.
 * Starts as a deep copy of an immutable original; the user can then move
 * classes and packages around via {@link #moveElement} and the
 * {@link #violations()} list reflects the new visual layout immediately —
 * for every static class edge, the source's current row position is
 * compared against the target's lexicographically. An edge whose source
 * sits below its target (larger row index at the first divergent depth)
 * is reported as {@link ViolationKind#UPWARD}.
 *
 * <p>The static class-edge graph is captured once at construction and never
 * changes: moving a box doesn't alter the underlying source code. Package
 * tangles are derived from that static graph but use the current virtual
 * class-to-package placement, so moving a class between packages immediately
 * changes the package-cycle view.
 *
 * <p>The internal representation uses a small private {@code Node} tree
 * to keep mutation cheap; rows are exposed as immutable
 * {@code List<List<Element>>} snapshots so external consumers keep the
 * existing API contract.
 */
public final class WhatIfArchitecture implements LayeredArchitecture {

    private final HierarchicalLayeredArchitecture original;
    /** Snapshot of class-to-class edges captured at construction. */
    private final List<StaticEdge> staticEdges;

    /** Mutable root — its {@code rows} are the top-level rows of the architecture. */
    private final Node root = new Node("", true);

    public WhatIfArchitecture(HierarchicalLayeredArchitecture original, DomainModel domain) {
        this.original = Objects.requireNonNull(original, "original");
        Objects.requireNonNull(domain, "domain");
        this.staticEdges = extractStaticEdges(domain);
        rebuildFromOriginal();
    }

    /** Restore the architecture to its initial (post-analysis) state. */
    public void reset() {
        rebuildFromOriginal();
    }

    /**
     * Move the element identified by {@code fqn} into {@code targetParentFqn}'s
     * inner rows at the given row and column index. Pass an empty target
     * parent to move into the top-level rows. Missing intermediate rows
     * (e.g. a stack-gap drop into an index past the last existing row)
     * are created on demand. No-op when the element or target container
     * can't be located. Empty rows left behind in the source container
     * are pruned so subsequent row indices stay in sync with the visual
     * scene graph.
     */
    public void moveElement(String fqn, String targetParentFqn, int targetRowIndex, int targetColumnIndex) {
        Node moved = prepareMove(fqn, targetParentFqn);
        if (moved == null) {
            return;
        }
        // Prune BEFORE insertion so the model's row indices line up with the
        // visual scene graph (which already cleaned up the source row).
        pruneEmptyRows(root);
        Node targetParent = resolveTargetParent(targetParentFqn);
        if (targetParent == null) {
            return;
        }
        while (targetParent.rows.size() <= targetRowIndex) {
            targetParent.rows.add(new ArrayList<>());
        }
        List<Node> row = targetParent.rows.get(targetRowIndex);
        int col = Math.max(0, Math.min(targetColumnIndex, row.size()));
        row.add(col, moved);
    }

    /**
     * Move the element identified by {@code fqn} into {@code targetParentFqn}
     * by inserting a brand-new row at {@code targetRowIndex} that contains
     * only the moved element. Mirrors the visual stack-gap drop: an HBox
     * row is inserted in the row stack at the requested position and shifts
     * the existing rows down.
     */
    public void moveElementAsNewRow(String fqn, String targetParentFqn, int targetRowIndex) {
        Node moved = prepareMove(fqn, targetParentFqn);
        if (moved == null) {
            return;
        }
        // Prune BEFORE insertion so the model's row indices line up with the
        // visual scene graph (which already cleaned up the source row).
        pruneEmptyRows(root);
        Node targetParent = resolveTargetParent(targetParentFqn);
        if (targetParent == null) {
            return;
        }
        List<Node> newRow = new ArrayList<>();
        newRow.add(moved);
        int idx = Math.max(0, Math.min(targetRowIndex, targetParent.rows.size()));
        targetParent.rows.add(idx, newRow);
    }

    private Node prepareMove(String fqn, String targetParentFqn) {
        if (fqn == null || fqn.isEmpty()) {
            return null;
        }
        return removeNode(fqn);
    }

    private Node resolveTargetParent(String targetParentFqn) {
        return (targetParentFqn == null || targetParentFqn.isEmpty())
                ? root
                : findPackage(targetParentFqn, root);
    }

    /**
     * The structural snapshot the renderer and side panel render. Rebuilt
     * fresh on every call so it never gets out of sync with the mutable
     * internal tree, but cheap enough at the scale of an architecture
     * view.
     */
    @Override
    public List<Violation> violations() {
        Map<String, int[]> positions = new HashMap<>();
        collectPositions(root, new int[0], positions);
        List<Violation> result = new ArrayList<>();
        for (StaticEdge edge : staticEdges) {
            int[] srcPos = positions.get(edge.source());
            int[] tgtPos = positions.get(edge.target());
            if (srcPos == null || tgtPos == null) {
                continue;
            }
            if (compareLex(srcPos, tgtPos) > 0) {
                result.add(new Violation(edge.source(), edge.target(), ViolationKind.UPWARD,
                        depthValue(srcPos), depthValue(tgtPos), edge.backEdge()));
            }
        }
        return result;
    }

    @Override
    public List<Tangle> tangles() {
        Map<String, String> classPackages = currentClassPackages();
        Map<String, Set<String>> packageGraph = new LinkedHashMap<>();
        collectPackages(root, packageGraph);

        for (StaticEdge edge : staticEdges) {
            String sourcePackage = classPackages.get(edge.source());
            String targetPackage = classPackages.get(edge.target());
            if (sourcePackage == null
                    || targetPackage == null
                    || sourcePackage.isEmpty()
                    || targetPackage.isEmpty()
                    || sourcePackage.equals(targetPackage)) {
                continue;
            }
            packageGraph.computeIfAbsent(sourcePackage, ignored -> new LinkedHashSet<>())
                    .add(targetPackage);
            packageGraph.computeIfAbsent(targetPackage, ignored -> new LinkedHashSet<>());
        }

        return SCCFinder.defaultFinder().findSCCs(packageGraph).stream()
                .filter(StronglyConnectedComponent::isTangle)
                .map(scc -> new Tangle(scc.getMembers()))
                .toList();
    }

    /**
     * Current virtual package for a class after What-If moves. Falls back to
     * the static FQN parent if the class is not part of the mutable view tree.
     */
    public String packageOf(String classFqn) {
        String currentPackage = currentClassPackages().get(classFqn);
        return currentPackage != null ? currentPackage : parentOf(classFqn);
    }

    public Map<String, String> classPackages() {
        return Map.copyOf(currentClassPackages());
    }

    /**
     * Same Rows-of-Cols shape {@link HierarchicalLayeredArchitecture}
     * exposes. Element levels are filled with the element's depth in the
     * Rows-of-Cols structure — useful for sorting, not for cross-element
     * comparison.
     */
    public List<List<Element>> rows() {
        return toElementRows(root.rows);
    }

    // ---------------------------------------------------------------- internals

    private void rebuildFromOriginal() {
        root.rows.clear();
        copyRowsIntoNode(original.rows(), root);
    }

    private static void pruneEmptyRows(Node parent) {
        parent.rows.removeIf(List::isEmpty);
        for (List<Node> row : parent.rows) {
            for (Node child : row) {
                if (child.isPackage) {
                    pruneEmptyRows(child);
                }
            }
        }
    }

    private static void copyRowsIntoNode(List<List<Element>> source, Node target) {
        for (List<Element> row : source) {
            List<Node> newRow = new ArrayList<>(row.size());
            for (Element e : row) {
                Node child = elementToNode(e);
                newRow.add(child);
            }
            target.rows.add(newRow);
        }
    }

    private static Node elementToNode(Element e) {
        if (e instanceof Element.ClassElement c) {
            return new Node(c.fqn(), false);
        }
        Element.PackageElement pkg = (Element.PackageElement) e;
        Node node = new Node(pkg.fqn(), true);
        copyRowsIntoNode(pkg.rows(), node);
        return node;
    }

    private Node removeNode(String fqn) {
        return removeNodeRecursive(fqn, root);
    }

    private static Node removeNodeRecursive(String fqn, Node parent) {
        for (List<Node> row : parent.rows) {
            for (int i = 0; i < row.size(); i++) {
                Node child = row.get(i);
                if (fqn.equals(child.fqn)) {
                    row.remove(i);
                    return child;
                }
                if (child.isPackage) {
                    Node hit = removeNodeRecursive(fqn, child);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    private static Node findPackage(String fqn, Node parent) {
        for (List<Node> row : parent.rows) {
            for (Node child : row) {
                if (child.isPackage) {
                    if (fqn.equals(child.fqn)) {
                        return child;
                    }
                    Node hit = findPackage(fqn, child);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    private static void collectPositions(Node parent, int[] prefix, Map<String, int[]> out) {
        for (int rowIdx = 0; rowIdx < parent.rows.size(); rowIdx++) {
            List<Node> row = parent.rows.get(rowIdx);
            int[] rowPrefix = new int[prefix.length + 1];
            System.arraycopy(prefix, 0, rowPrefix, 0, prefix.length);
            rowPrefix[prefix.length] = rowIdx;
            for (Node child : row) {
                out.put(child.fqn, rowPrefix);
                if (child.isPackage) {
                    collectPositions(child, rowPrefix, out);
                }
            }
        }
    }

    private Map<String, String> currentClassPackages() {
        Map<String, String> result = new HashMap<>();
        collectClassPackages(root, "", result);
        return result;
    }

    private static void collectClassPackages(Node parent, String currentPackage, Map<String, String> out) {
        for (List<Node> row : parent.rows) {
            for (Node child : row) {
                if (child.isPackage) {
                    collectClassPackages(child, child.fqn, out);
                } else {
                    out.put(child.fqn, currentPackage);
                }
            }
        }
    }

    private static void collectPackages(Node parent, Map<String, Set<String>> out) {
        for (List<Node> row : parent.rows) {
            for (Node child : row) {
                if (child.isPackage) {
                    if (!child.fqn.isEmpty()) {
                        out.computeIfAbsent(child.fqn, ignored -> new LinkedHashSet<>());
                    }
                    collectPackages(child, out);
                }
            }
        }
    }

    private static int compareLex(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static int depthValue(int[] position) {
        return position.length == 0 ? 0 : position[position.length - 1];
    }

    private static String parentOf(String fqn) {
        int dot = fqn == null ? -1 : fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static List<StaticEdge> extractStaticEdges(DomainModel domain) {
        List<StaticEdge> edges = new ArrayList<>();
        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            String src = cls.fullName;
            for (String dep : cls.dependencies) {
                edges.add(new StaticEdge(src, dep, domain.isClassBackEdge(src, dep)));
            }
        }
        return edges;
    }

    private List<List<Element>> toElementRows(List<List<Node>> source) {
        List<List<Element>> out = new ArrayList<>(source.size());
        for (List<Node> row : source) {
            List<Element> elems = new ArrayList<>(row.size());
            for (Node child : row) {
                elems.add(toElement(child, 0));
            }
            out.add(elems);
        }
        return out;
    }

    private Element toElement(Node node, int depth) {
        if (!node.isPackage) {
            return new Element.ClassElement(node.fqn, depth, 0);
        }
        return new Element.PackageElement(node.fqn, depth, 0, toElementRows(node.rows));
    }

    /** Internal mutable tree node. */
    private static final class Node {
        final String fqn;
        final boolean isPackage;
        final List<List<Node>> rows = new ArrayList<>();

        Node(String fqn, boolean isPackage) {
            this.fqn = fqn;
            this.isPackage = isPackage;
        }
    }

    private record StaticEdge(String source, String target, boolean backEdge) {}
}
