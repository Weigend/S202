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
package de.weigend.s202.analysis.invariants;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Verifies that a computed {@link DomainModel} keeps the promises of the
 * hierarchical levelization. The rules recompute nothing; they read the
 * finished model and ask whether it is consistent with itself. Any finding is
 * an algorithm bug, never an architectural property of the analysed code.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>R1 ClassLevelDownward</b> — every class dependency A→B that is not a
 *       level cut has {@code level(A) > level(B)}, and every level cut is a
 *       class back-edge. Level cuts are the back-edges inside a class cycle;
 *       the class graph without them is a DAG, so no exception is needed.</li>
 *   <li><b>R2 SiblingOrder</b> — in every container, every sibling edge that was
 *       not cut runs from a higher to a lower {@code localLevel}. This is the
 *       picture's own promise: non-back-edges point downward.</li>
 *   <li><b>R3 BackEdgeInsideTangle</b> — every cut sibling edge connects two
 *       members of one container tangle. Back-edges exist only where a cycle
 *       forced them.</li>
 *   <li><b>R4 PackageLevelDownward</b> — for every non-back-edge class
 *       dependency between classes of two packages that do not contain each
 *       other, the source package has the higher {@code architectureLevel}.</li>
 *   <li><b>R5 UpwardImpliesBackEdge</b> — every class dependency that runs
 *       upward by nested visual rank (the localLevel chain from the outermost
 *       container down to the class) is a class back-edge. Violations and
 *       back-edges are the same set.</li>
 * </ul>
 *
 * <p>Pure, allocation-light, thread-safe — safe to invoke from a JavaFX
 * background {@code Task}.</p>
 */
public final class LayoutInvariantChecker {

    /**
     * Run all rules against {@code domainModel} and return a structured report.
     *
     * @param domainModel calculated levels for classes and packages
     * @param rawModel    bytecode-level model (kept for reproducer context)
     * @param sourcePaths jar paths or similar identifiers, echoed into the
     *                    report so a finding can be turned into a reproducer
     *                    without further lookup; may be empty
     */
    public LayoutInvariantReport check(DomainModel domainModel,
                                       DependencyModel rawModel,
                                       List<String> sourcePaths) {
        Objects.requireNonNull(domainModel, "domainModel");
        Objects.requireNonNull(rawModel, "rawModel");

        Map<String, CalculatedElementInfo> classes = new TreeMap<>(domainModel.getAllClasses());
        Map<String, CalculatedElementInfo> packages = new TreeMap<>(domainModel.getAllPackages());

        List<InvariantFinding> findings = new ArrayList<>();
        int dependencyCount = checkClassLevelDownward(classes, domainModel, findings);
        checkSiblingOrder(classes, packages, domainModel, findings);
        checkBackEdgeInsideTangle(classes, packages, domainModel, findings);
        checkPackageLevelDownward(classes, packages, domainModel, findings);
        checkUpwardImpliesBackEdge(classes, domainModel, findings);

        List<String> paths = sourcePaths != null
                ? new ArrayList<>(sourcePaths)
                : Collections.emptyList();

        return new LayoutInvariantReport(
                paths,
                domainModel.getMaxLevel(),
                packages.size(),
                classes.size(),
                dependencyCount,
                domainModel.getClassBackEdgeCount(),
                findings);
    }

    // ---------------------------------------------------------------- R1

    /** Returns the number of class-to-class dependencies walked (report header). */
    private static int checkClassLevelDownward(Map<String, CalculatedElementInfo> classes,
                                               DomainModel domainModel,
                                               List<InvariantFinding> findings) {
        int depCount = 0;
        for (CalculatedElementInfo from : classes.values()) {
            for (String depName : sorted(from.dependencies)) {
                CalculatedElementInfo to = classes.get(depName);
                if (to == null) continue;
                depCount++;
                if (domainModel.isClassLevelCut(from.fullName, depName)) {
                    if (domainModel.isClassBackEdge(from.fullName, depName)) continue;
                    findings.add(new InvariantFinding(
                            "R1",
                            "Level cut that is not a class back-edge: a dependency may only be ignored "
                                    + "for the class level where the container order cut it.",
                            from.fullName, to.fullName,
                            from.architectureLevel, to.architectureLevel,
                            parentOf(from.fullName), parentOf(to.fullName)));
                    continue;
                }
                if (from.architectureLevel > to.architectureLevel) continue;
                findings.add(new InvariantFinding(
                        "R1",
                        "Class level inversion on a dependency that is not a level cut: the class graph "
                                + "without level cuts must be a DAG with the source strictly above the target.",
                        from.fullName, to.fullName,
                        from.architectureLevel, to.architectureLevel,
                        parentOf(from.fullName), parentOf(to.fullName)));
            }
        }
        return depCount;
    }

    // ---------------------------------------------------------------- R2

    private static void checkSiblingOrder(Map<String, CalculatedElementInfo> classes,
                                          Map<String, CalculatedElementInfo> packages,
                                          DomainModel domainModel,
                                          List<InvariantFinding> findings) {
        for (Map.Entry<String, Map<String, Integer>> fromEntry : domainModel.getSiblingEdgeWeights().entrySet()) {
            String from = fromEntry.getKey();
            CalculatedElementInfo fromInfo = element(from, classes, packages);
            if (fromInfo == null) continue;
            for (Map.Entry<String, Integer> toEntry : new TreeMap<>(fromEntry.getValue()).entrySet()) {
                String to = toEntry.getKey();
                if (toEntry.getValue() <= 0) continue;
                if (domainModel.isContainerBackEdge(from, to)) continue;
                CalculatedElementInfo toInfo = element(to, classes, packages);
                if (toInfo == null) continue;
                if (fromInfo.localLevel > toInfo.localLevel) continue;
                findings.add(new InvariantFinding(
                        "R2",
                        String.format("Sibling edge %s -> %s (w=%d) was not cut but does not run downward: "
                                        + "localLevel %d <= %d.",
                                from, to, toEntry.getValue(), fromInfo.localLevel, toInfo.localLevel),
                        from, to,
                        fromInfo.localLevel, toInfo.localLevel,
                        parentOf(from), parentOf(to)));
            }
        }
    }

    // ---------------------------------------------------------------- R3

    private static void checkBackEdgeInsideTangle(Map<String, CalculatedElementInfo> classes,
                                                  Map<String, CalculatedElementInfo> packages,
                                                  DomainModel domainModel,
                                                  List<InvariantFinding> findings) {
        Map<String, Integer> tangleOf = new HashMap<>();
        int id = 0;
        for (Set<String> tangle : domainModel.getContainerTangles()) {
            for (String member : tangle) tangleOf.put(member, id);
            id++;
        }
        for (var edge : domainModel.getContainerBackEdges()) {
            Integer a = tangleOf.get(edge.from());
            Integer b = tangleOf.get(edge.to());
            if (a != null && a.equals(b)) continue;
            CalculatedElementInfo fromInfo = element(edge.from(), classes, packages);
            CalculatedElementInfo toInfo = element(edge.to(), classes, packages);
            findings.add(new InvariantFinding(
                    "R3",
                    "Container back-edge outside any tangle: an edge may only be cut to break a cycle.",
                    edge.from(), edge.to(),
                    fromInfo == null ? -1 : fromInfo.localLevel,
                    toInfo == null ? -1 : toInfo.localLevel,
                    parentOf(edge.from()), parentOf(edge.to())));
        }
    }

    // ---------------------------------------------------------------- R4

    private static void checkPackageLevelDownward(Map<String, CalculatedElementInfo> classes,
                                                  Map<String, CalculatedElementInfo> packages,
                                                  DomainModel domainModel,
                                                  List<InvariantFinding> findings) {
        for (CalculatedElementInfo from : classes.values()) {
            CalculatedElementInfo fromPkg = packages.get(parentOf(from.fullName));
            if (fromPkg == null) continue;
            for (String depName : sorted(from.dependencies)) {
                if (!classes.containsKey(depName)) continue;
                if (domainModel.isClassBackEdge(from.fullName, depName)) continue;
                CalculatedElementInfo toPkg = packages.get(parentOf(depName));
                if (toPkg == null || toPkg == fromPkg) continue;
                if (isInSameSubtree(fromPkg.fullName, toPkg.fullName)) continue;
                if (fromPkg.architectureLevel > toPkg.architectureLevel) continue;
                findings.add(new InvariantFinding(
                        "R4",
                        String.format("Package level inversion: %s -> %s is not a back-edge but level(%s)=%d <= level(%s)=%d.",
                                from.fullName, depName, fromPkg.fullName, fromPkg.architectureLevel,
                                toPkg.fullName, toPkg.architectureLevel),
                        fromPkg.fullName, toPkg.fullName,
                        fromPkg.architectureLevel, toPkg.architectureLevel,
                        fromPkg.fullName, toPkg.fullName));
            }
        }
    }

    // ---------------------------------------------------------------- R5

    private static void checkUpwardImpliesBackEdge(Map<String, CalculatedElementInfo> classes,
                                                   DomainModel domainModel,
                                                   List<InvariantFinding> findings) {
        Map<String, int[]> rank = new HashMap<>(classes.size());
        for (CalculatedElementInfo cls : classes.values()) {
            rank.put(cls.fullName, visualRank(cls, domainModel));
        }
        for (CalculatedElementInfo from : classes.values()) {
            int[] fromRank = rank.get(from.fullName);
            for (String depName : sorted(from.dependencies)) {
                int[] toRank = rank.get(depName);
                if (toRank == null) continue;
                if (compareVisualRank(fromRank, toRank) >= 0) continue;
                if (domainModel.isClassBackEdge(from.fullName, depName)) continue;
                CalculatedElementInfo to = classes.get(depName);
                findings.add(new InvariantFinding(
                        "R5",
                        "Dependency runs upward in the nested layout but is not a class back-edge: "
                                + "the picture shows a violation the model does not know about.",
                        from.fullName, depName,
                        from.localLevel, to.localLevel,
                        parentOf(from.fullName), parentOf(depName)));
            }
        }
    }

    /**
     * The localLevel chain from the outermost package down to the class — the
     * same comparison the eye makes in the nested picture.
     */
    private static int[] visualRank(CalculatedElementInfo cls, DomainModel domainModel) {
        List<Integer> chain = new ArrayList<>();
        String parent = parentOf(cls.fullName);
        while (!parent.isEmpty()) {
            CalculatedElementInfo pkg = domainModel.getPackage(parent);
            if (pkg != null) chain.add(pkg.localLevel);
            parent = parentOf(parent);
        }
        Collections.reverse(chain);
        int[] rank = new int[chain.size() + 1];
        for (int i = 0; i < chain.size(); i++) rank[i] = chain.get(i);
        rank[chain.size()] = cls.localLevel;
        return rank;
    }

    private static int compareVisualRank(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    // ---------------------------------------------------------------- helpers

    private static CalculatedElementInfo element(String fqn,
                                                 Map<String, CalculatedElementInfo> classes,
                                                 Map<String, CalculatedElementInfo> packages) {
        CalculatedElementInfo info = classes.get(fqn);
        return info != null ? info : packages.get(fqn);
    }

    private static List<String> sorted(Set<String> values) {
        if (values == null) return List.of();
        List<String> list = new ArrayList<>(values);
        Collections.sort(list);
        return list;
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static boolean isInSameSubtree(String a, String b) {
        return a.startsWith(b + ".") || b.startsWith(a + ".");
    }
}
