package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;

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
public final class HierarchicalLayeredArchitectureBuilder {

    public Architecture build(DomainModel domain) {
        Objects.requireNonNull(domain, "domain");

        Map<String, List<CalculatedElementInfo>> contentsByParent = groupChildrenByParent(domain);
        addPackagePlaceholders(contentsByParent);

        String effectiveRoot = skipTransparentPassthroughs(contentsByParent);
        List<List<Element>> rows = buildRowsForPackage(effectiveRoot, contentsByParent);
        List<Violation> violations = detectViolations(domain);

        return new HierarchicalLayeredArchitecture(rows, violations);
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
        List<CalculatedElementInfo> sorted = new ArrayList<>(children);
        sorted.sort(Comparator
                .comparingInt((CalculatedElementInfo c) -> c.level).reversed()
                .thenComparing(c -> c.fullName));

        List<List<Element>> rows = new ArrayList<>();
        List<Element> currentRow = new ArrayList<>();
        int currentLevel = Integer.MIN_VALUE;
        for (CalculatedElementInfo child : sorted) {
            if (child.level != currentLevel) {
                if (!currentRow.isEmpty()) {
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
                currentLevel = child.level;
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
            return new Element.ClassElement(info.fullName, info.level);
        }
        List<List<Element>> innerRows = buildRowsForPackage(info.fullName, contents);
        return new Element.PackageElement(info.fullName, info.level, innerRows);
    }

    // -------------------------------------------------- violations

    private List<Violation> detectViolations(DomainModel domain) {
        List<Violation> violations = new ArrayList<>();

        for (CalculatedElementInfo cls : domain.getAllClasses().values()) {
            for (String dep : cls.dependencies) {
                CalculatedElementInfo target = domain.getClass(dep);
                if (target == null) {
                    continue;
                }
                if (cls.level < target.level) {
                    violations.add(new Violation(
                            cls.fullName, target.fullName, ViolationKind.UPWARD,
                            cls.level, target.level));
                }
            }
        }

        for (Map.Entry<String, Map<String, Integer>> e : domain.getPackageEdgeWeights().entrySet()) {
            String from = e.getKey();
            for (String to : e.getValue().keySet()) {
                if (!domain.isPackageBackEdge(from, to)) {
                    continue;
                }
                CalculatedElementInfo fromPkg = domain.getPackage(from);
                CalculatedElementInfo toPkg = domain.getPackage(to);
                if (fromPkg == null || toPkg == null) {
                    continue;
                }
                violations.add(new Violation(
                        from, to, ViolationKind.PACKAGE_TANGLE,
                        fromPkg.level, toPkg.level));
            }
        }

        return violations;
    }

    // -------------------------------------------------- helpers

    private static String parentOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }
}
