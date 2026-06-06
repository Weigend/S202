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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ArchitectureContext;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ArchitectureStyle;
import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.domain.architecture.HierarchicalLayeredArchitecture;
import de.weigend.s202.domain.architecture.Tangle;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a {@link HierarchicalLayeredArchitecture} from a finished
 * {@link DomainModel}. The resulting Rows-of-Cols structure is the
 * UI-independent twin of what
 * {@code ArchitectureNodeBuilder + ArchitectureTreeBuilder} produce in
 * the UI pipeline today: same effective root (transparent single-child
 * passthroughs skipped), same per-package row grouping (descending by
 * level), same recursive descent into packages.
 *
 * <p>Column order within a row is currently fqn-sorted. The UI applies
 * a separate {@code HorizontalRowLayoutOptimizer} to refine the order;
 * lifting that into domain is a follow-up — for the C3 consistency
 * checker we compare row sets, not row sequences within columns.
 *
 * <p>Violations follow the hierarchical-layered style:
 * <ul>
 *   <li>{@link ViolationKind#UPWARD} — every class dep A→B where
 *       {@code A.level < B.level}. Members of a class-level SCC share a
 *       level by construction so they're naturally excluded.</li>
 *   <li>{@link ViolationKind#PACKAGE_TANGLE} — every package edge the
 *       level calculator marked as a back-edge (cycle member).</li>
 * </ul>
 */
@Singleton
public final class HierarchicalLayeredArchitectureBuilder implements ArchitectureStyle {

    @Override
    public ArchitectureKind kind() {
        return ArchitectureKind.LAYERED;
    }

    @Override
    public Architecture build(ArchitectureContext context) {
        Objects.requireNonNull(context, "context");
        return build(context.domainModel());
    }

    public Architecture build(DomainModel domain) {
        Objects.requireNonNull(domain, "domain");

        Map<String, List<CalculatedElementInfo>> contentsByParent = groupChildrenByParent(domain);
        addPackagePlaceholders(contentsByParent);

        String effectiveRoot = skipTransparentPassthroughs(contentsByParent);
        List<List<Element>> rows = buildRowsForPackage(effectiveRoot, contentsByParent);
        List<Violation> violations = detectViolations(domain);
        List<Tangle> tangles = detectTangles(domain);

        return new HierarchicalLayeredArchitecture(rows, violations, tangles);
    }

    // -------------------------------------------------- structure

    private Map<String, List<CalculatedElementInfo>> groupChildrenByParent(DomainModel domain) {
        Map<String, List<CalculatedElementInfo>> result = new HashMap<>();
        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            result.computeIfAbsent(parentOf(cls.fullName), k -> new ArrayList<>()).add(cls);
        }
        for (CalculatedElementInfo pkg : domain.getAllPackages().values()) {
            result.computeIfAbsent(parentOf(pkg.fullName), k -> new ArrayList<>()).add(pkg);
        }
        return result;
    }

    /**
     * Ensure every intermediate package along an existing fqn chain has
     * at least an empty entry — mirrors the placeholder logic the UI
     * tree builder uses so we never break the recursion on sparse
     * package trees.
     */
    private void addPackagePlaceholders(Map<String, List<CalculatedElementInfo>> contents) {
        Set<String> snapshot = new HashSet<>(contents.keySet());
        for (String pkg : snapshot) {
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
                contents.computeIfAbsent(current, k -> new ArrayList<>());
            }
            if (!pkg.contains(".")) {
                contents.computeIfAbsent(pkg, k -> new ArrayList<>());
            }
        }
    }

    /**
     * Descend from the root namespace through single-child package
     * chains — these are visually transparent (no own box) and the UI
     * starts rendering only at the deepest such ancestor. Matches the
     * UI's {@code ArchitectureTreeBuilder.shouldChildrenBeTransparent}
     * rule, which counts <em>packages</em> only — sibling classes at a
     * skipped level are ignored by the UI as well, so the consistency
     * checker won't flag them either.
     */
    private String skipTransparentPassthroughs(Map<String, List<CalculatedElementInfo>> contents) {
        String current = "";
        while (true) {
            List<CalculatedElementInfo> children = contents.getOrDefault(current, List.of());
            List<CalculatedElementInfo> subPackages = children.stream()
                    .filter(c -> "PACKAGE".equals(c.type))
                    .toList();
            if (subPackages.size() != 1) {
                return current;
            }
            current = subPackages.get(0).fullName;
        }
    }

    private List<List<Element>> buildRowsForPackage(String pkgFqn,
                                                   Map<String, List<CalculatedElementInfo>> contents) {
        List<CalculatedElementInfo> children = contents.getOrDefault(pkgFqn, List.of());
        if (children.isEmpty()) {
            return List.of();
        }
        // Within each parent's box, sort siblings by their local-level
        // position (set by LocalLevelCalculator) — purely a sibling-graph
        // decision, NOT the global architectureLevel. This is the change
        // that fixes the "ArchitectureView sits below ui.rendering" bug.
        List<CalculatedElementInfo> sorted = new ArrayList<>(children);
        sorted.sort(Comparator
                .comparingInt((CalculatedElementInfo c) -> c.localLevel).reversed()
                .thenComparing(c -> c.fullName));

        List<List<Element>> rows = new ArrayList<>();
        List<Element> currentRow = new ArrayList<>();
        int currentLevel = Integer.MIN_VALUE;
        for (CalculatedElementInfo child : sorted) {
            if (child.localLevel != currentLevel) {
                if (!currentRow.isEmpty()) {
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
                currentLevel = child.localLevel;
            }
            currentRow.add(toElement(child, contents));
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }
        return rows;
    }

    private Element toElement(CalculatedElementInfo info,
                              Map<String, List<CalculatedElementInfo>> contents) {
        if ("CLASS".equals(info.type)) {
            return new Element.ClassElement(info.fullName, info.architectureLevel, info.localLevel);
        }
        List<List<Element>> innerRows = buildRowsForPackage(info.fullName, contents);
        return new Element.PackageElement(info.fullName, info.architectureLevel, info.localLevel, innerRows);
    }

    // -------------------------------------------------- violations

    private List<Violation> detectViolations(DomainModel domain) {
        List<Violation> violations = new ArrayList<>();

        // Pre-compute the visual rank of every class: a list of levels from
        // the outermost ancestor package down to the class itself. Two
        // classes' ranks compare lexicographically — the larger value at the
        // first divergence sits visually higher (closer to the top). An
        // edge whose source rank is strictly less than its target rank
        // therefore runs upward.
        Map<String, int[]> visualRank = new HashMap<>();
        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            visualRank.put(cls.fullName, computeVisualRank(cls, domain));
        }

        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            int[] srcRank = visualRank.get(cls.fullName);
            for (String dep : cls.dependencies) {
                int[] tgtRank = visualRank.get(dep);
                if (tgtRank == null) {
                    continue;
                }
                if (compareVisualRank(srcRank, tgtRank) < 0) {
                    CalculatedElementInfo target = domain.getClass(dep);
                    int tgtClassLevel = target == null ? -1 : target.architectureLevel;
                    violations.add(new Violation(
                            cls.fullName, dep, ViolationKind.UPWARD,
                            cls.architectureLevel, tgtClassLevel,
                            domain.isClassBackEdge(cls.fullName, dep)));
                }
            }
        }

        return violations;
    }

    private List<Tangle> detectTangles(DomainModel domain) {
        return domain.getPackageTangles().stream()
                .map(Tangle::new)
                .toList();
    }

    /**
     * Build the visual rank of a class: the chain of localLevel values
     * along the parent-package path, ending with the class's own
     * localLevel. Comparing two ranks lexicographically is the same
     * comparison the eye does — "whose outermost ancestor sits higher? if
     * equal, look one level deeper; …; finally look at the class layer".
     * Uses localLevel consistently, since that's what positions the
     * boxes within each parent's container.
     */
    private static int[] computeVisualRank(CalculatedElementInfo cls, DomainModel domain) {
        List<Integer> ancestorLayers = new ArrayList<>();
        String parent = parentOf(cls.fullName);
        while (!parent.isEmpty()) {
            CalculatedElementInfo pkg = domain.getPackage(parent);
            if (pkg != null) {
                ancestorLayers.add(pkg.localLevel);
            }
            parent = parentOf(parent);
        }
        // ancestorLayers is currently innermost-first; reverse to outermost-first.
        java.util.Collections.reverse(ancestorLayers);
        int[] rank = new int[ancestorLayers.size() + 1];
        for (int i = 0; i < ancestorLayers.size(); i++) {
            rank[i] = ancestorLayers.get(i);
        }
        rank[rank.length - 1] = cls.localLevel;
        return rank;
    }

    /**
     * Lexicographic compare on visual-rank tuples. Larger entries sit
     * higher visually, so the standard {@code Integer.compare} per element
     * already gives the right answer (negative = source below target).
     * When one rank is a strict prefix of the other (e.g. a flat class
     * and a class nested one level deeper at the same row), the entries
     * are treated as tied — neither is unambiguously above the other
     * without per-row layout info.
     */
    private static int compareVisualRank(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    // -------------------------------------------------- helpers

    private static String parentOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }
}
