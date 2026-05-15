package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.graph.StronglyConnectedComponent;
import de.weigend.s202.graph.TarjanSCCFinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
public final class HierarchicalLayeredArchitectureBuilder {

    public Architecture build(DomainModel domain) {
        Objects.requireNonNull(domain, "domain");

        Map<String, List<CalculatedElementInfo>> contentsByParent = groupChildrenByParent(domain);
        addPackagePlaceholders(contentsByParent);

        String effectiveRoot = skipTransparentPassthroughs(contentsByParent);
        List<List<Element>> rows = buildRowsForPackage(effectiveRoot, contentsByParent);
        List<Violation> violations = detectViolations(domain);
        List<Tangle> tangles = detectTangles(domain);
        List<ContainmentEdge> containmentEdges = detectContainmentEdges(domain);

        return new HierarchicalLayeredArchitecture(rows, violations, tangles, containmentEdges);
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
        // Within each parent's box, sort siblings by their local-layer
        // position (set by LocalLayerCalculator) — purely a sibling-graph
        // decision, NOT the global architectureLevel. This is the change
        // that fixes the "ArchitectureView sits below ui.rendering" bug.
        List<CalculatedElementInfo> sorted = new ArrayList<>(children);
        sorted.sort(Comparator
                .comparingInt((CalculatedElementInfo c) -> c.localLayerIndex).reversed()
                .thenComparing(c -> c.fullName));

        List<List<Element>> rows = new ArrayList<>();
        List<Element> currentRow = new ArrayList<>();
        int currentLayer = Integer.MIN_VALUE;
        for (CalculatedElementInfo child : sorted) {
            if (child.localLayerIndex != currentLayer) {
                if (!currentRow.isEmpty()) {
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
                currentLayer = child.localLayerIndex;
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
            return new Element.ClassElement(info.fullName, info.architectureLevel, info.localLayerIndex);
        }
        List<List<Element>> innerRows = buildRowsForPackage(info.fullName, contents);
        return new Element.PackageElement(info.fullName, info.architectureLevel, info.localLayerIndex, innerRows);
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
                            cls.architectureLevel, tgtClassLevel));
                }
            }
        }

        return violations;
    }

    /**
     * Group-level cycle detection. Runs Tarjan on the package adjacency
     * derived from raw class-to-class dependencies and emits one
     * {@link Tangle} per SCC of size {@literal >} 1. This is
     * intentionally <em>different</em> from the
     * {@link DomainModel#isPackageBackEdge} list: that one reports the
     * back-edges the {@code LevelCalculator} picked to break each SCC,
     * one per breakage, not one per cycle. The UI displays cycles, so the
     * model speaks the same vocabulary.
     */
    private List<Tangle> detectTangles(DomainModel domain) {
        Map<String, Set<String>> pkgAdjacency = new HashMap<>();
        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            String srcPkg = parentOf(cls.fullName);
            if (srcPkg.isEmpty()) {
                continue;
            }
            pkgAdjacency.computeIfAbsent(srcPkg, k -> new HashSet<>());
            for (String dep : cls.dependencies) {
                CalculatedElementInfo tgt = domain.getClass(dep);
                if (tgt == null) {
                    continue;
                }
                String tgtPkg = parentOf(tgt.fullName);
                if (tgtPkg.isEmpty() || srcPkg.equals(tgtPkg)) {
                    continue;
                }
                pkgAdjacency.computeIfAbsent(srcPkg, k -> new HashSet<>()).add(tgtPkg);
                pkgAdjacency.computeIfAbsent(tgtPkg, k -> new HashSet<>());
            }
        }
        TarjanSCCFinder finder = new TarjanSCCFinder(pkgAdjacency);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        List<Tangle> tangles = new ArrayList<>();
        for (StronglyConnectedComponent scc : sccs) {
            if (scc.isTangle()) {
                tangles.add(new Tangle(scc.getMembers()));
            }
        }
        return tangles;
    }

    // -------------------------------------------------- containment edges

    /**
     * Class-to-class dependencies the level calculator suppresses because
     * the source's package is a strict ancestor of the target's. Emitted
     * both at {@link EdgeScope#CLASS} (the actual pair) and at
     * {@link EdgeScope#PACKAGE} (one entry per distinct
     * source-package/target-package pair), so the renderer can pick the
     * granularity it needs and roll up to whichever level is currently
     * visible.
     */
    private List<ContainmentEdge> detectContainmentEdges(DomainModel domain) {
        List<ContainmentEdge> classEdges = new ArrayList<>();
        Map<String, Set<String>> pkgAggregate = new LinkedHashMap<>();

        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            String srcPkg = parentOf(cls.fullName);
            if (srcPkg.isEmpty()) {
                continue;
            }
            for (String dep : cls.dependencies) {
                CalculatedElementInfo target = domain.getClass(dep);
                if (target == null) {
                    continue;
                }
                String tgtPkg = parentOf(target.fullName);
                if (tgtPkg.isEmpty() || !tgtPkg.startsWith(srcPkg + ".")) {
                    continue;
                }
                classEdges.add(new ContainmentEdge(cls.fullName, target.fullName, EdgeScope.CLASS));
                pkgAggregate.computeIfAbsent(srcPkg, k -> new LinkedHashSet<>()).add(tgtPkg);
            }
        }

        classEdges.sort(Comparator
                .comparing(ContainmentEdge::sourceFqn)
                .thenComparing(ContainmentEdge::targetFqn));

        List<ContainmentEdge> all = new ArrayList<>(classEdges);
        List<String> sortedSrc = new ArrayList<>(pkgAggregate.keySet());
        sortedSrc.sort(Comparator.naturalOrder());
        for (String src : sortedSrc) {
            List<String> targets = new ArrayList<>(pkgAggregate.get(src));
            targets.sort(Comparator.naturalOrder());
            for (String tgt : targets) {
                all.add(new ContainmentEdge(src, tgt, EdgeScope.PACKAGE));
            }
        }
        return all;
    }

    private static int[] computeVisualRank(CalculatedElementInfo cls, DomainModel domain) {
        List<Integer> ancestorLevels = new ArrayList<>();
        String parent = parentOf(cls.fullName);
        while (!parent.isEmpty()) {
            CalculatedElementInfo pkg = domain.getPackage(parent);
            if (pkg != null) {
                ancestorLevels.add(pkg.architectureLevel);
            }
            parent = parentOf(parent);
        }
        // ancestorLevels is currently innermost-first; reverse to outermost-first.
        java.util.Collections.reverse(ancestorLevels);
        int[] rank = new int[ancestorLevels.size() + 1];
        for (int i = 0; i < ancestorLevels.size(); i++) {
            rank[i] = ancestorLevels.get(i);
        }
        rank[rank.length - 1] = cls.architectureLevel;
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
