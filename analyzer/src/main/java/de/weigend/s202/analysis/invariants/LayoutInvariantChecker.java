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

import de.weigend.s202.domain.StronglyConnectedComponent;
import io.softwareecg.wfx.lookup.api.Lookup;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies that a generated {@link DomainModel} is internally consistent —
 * i.e. reports actual algorithm bugs in the level pipeline, not architectural
 * violations the deterministic SCC-breaker is allowed to produce by design.
 *
 * <p>Ported 1:1 from the Software City (.NET / Unity) project's
 * {@code LayoutInvariantChecker.cs}. The 2D Structure202 view uses the same
 * level integers as the 3D city, so the rule logic transfers unchanged —
 * Unity's (x, y, z) coordinates and the resulting building height are
 * irrelevant here, since every rule operates on the level value, not on
 * spatial position.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>R1 ClassDepDownward</b> — for every class dep A→B, A.level &gt; B.level
 *       unless the edge is a registered back-edge or both endpoints sit in a
 *       broken/equalised SCC.</li>
 *   <li><b>R2 PkgSccEqualLevel</b> — packages that form an SCC at the
 *       package-dep level (after filtering back-edges, same-class-SCC deps
 *       and intra-subtree edges) are architectural peers and must share a
 *       level.</li>
 *   <li><b>R3 PackageEdgeDirection</b> — for every weighted package edge
 *       P_A → P_B where weight(P_A→P_B) &gt; weight(P_B→P_A), the dominant
 *       direction means P_A depends on P_B and must be at a strictly higher
 *       level: level(P_A) &gt; level(P_B).</li>
 *   <li><b>R4 ViolationFlag</b> — every dependency's classification
 *       (NORMAL / VIOLATION / INTRA_SCC) must match what the current level
 *       and SCC state say it should be.</li>
 * </ul>
 *
 * <p>Edges removed by the level algorithm (back-edges from Fall A and Fall B)
 * are not findings — they already render as red violation edges in the view.
 * R4 mirrors that classification locally.</p>
 *
 * <p>Pure, allocation-light, thread-safe — safe to invoke from a JavaFX
 * background {@code Task}.</p>
 */
public final class LayoutInvariantChecker {

    /**
     * Run all four rules against {@code domainModel} and return a structured
     * report. {@code rawModel} is consulted for the package name of each
     * class (the 1:1 mirror of the C# {@code CityBuilding.NamespaceName}).
     *
     * @param domainModel calculated levels for classes and packages
     * @param rawModel    bytecode-level model used to look up package names
     * @param sourcePaths jar paths or similar identifiers, echoed into the
     *                    report so a finding can be turned into a reproducer
     *                    without further lookup; may be empty
     */
    public LayoutInvariantReport check(DomainModel domainModel,
                                       DependencyModel rawModel,
                                       List<String> sourcePaths) {
        Objects.requireNonNull(domainModel, "domainModel");
        Objects.requireNonNull(rawModel, "rawModel");

        Map<String, CalculatedElementInfo> classes = domainModel.getAllClasses();
        Map<String, CalculatedElementInfo> packages = domainModel.getAllPackages();

        // Reconstruct the class graph and run Tarjan once. R1 needs the SCC map
        // to distinguish "by design" level spread from real bugs.
        Map<String, Set<String>> classGraph = buildClassGraph(classes);
        Map<String, StronglyConnectedComponent> classToScc = buildClassToSccMap(classGraph);

        // An SCC is "broken" when the deterministic algorithm deliberately spread
        // its members across different levels (Fall A or Fall B cuts). This is
        // detected directly from the computed levels: if members of the same original
        // SCC have different architecture levels, the SCC was broken by design.
        // Drift between two members of a broken SCC is the algorithm's intended
        // output — not a bug R1 should flag. (Forge's ~4000-class monolith would
        // otherwise produce thousands of noise findings.)
        Set<Integer> brokenSccIds = new HashSet<>();
        for (StronglyConnectedComponent scc : new HashSet<>(classToScc.values())) {
            if (scc.getSize() < 2) continue;
            int firstLevel = -1;
            for (String m : scc.getMembers()) {
                CalculatedElementInfo info = classes.get(m);
                if (info == null) continue;
                if (firstLevel < 0) firstLevel = info.architectureLevel;
                else if (info.architectureLevel != firstLevel) {
                    brokenSccIds.add(scc.getId());
                    break;
                }
            }
        }

        List<InvariantFinding> findings = new ArrayList<>();
        int dependencyCount = checkLevelInversionAcrossNonBackEdge(
                classes, classToScc, brokenSccIds, domainModel, findings);
        checkPackageEdgeDirection(packages, domainModel.getPackageEdgeWeights(), domainModel, findings);
        checkPkgSccEqualLevel(classes, packages, rawModel, domainModel, classToScc, findings);
        checkViolationFlagConsistency(classes, classGraph, classToScc, findings);

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

    // ---------------------------------------------------------------- helpers

    private static Map<String, Set<String>> buildClassGraph(Map<String, CalculatedElementInfo> classes) {
        Map<String, Set<String>> graph = new HashMap<>(classes.size());
        for (CalculatedElementInfo cls : classes.values()) {
            Set<String> deps = new HashSet<>();
            if (cls.dependencies != null) {
                for (String d : cls.dependencies) {
                    if (classes.containsKey(d)) {
                        deps.add(d);
                    }
                }
            }
            graph.put(cls.fullName, deps);
        }
        return graph;
    }

    private static Map<String, StronglyConnectedComponent> buildClassToSccMap(
            Map<String, Set<String>> classGraph) {
        Map<String, StronglyConnectedComponent> map = new HashMap<>(classGraph.size());
        if (classGraph.isEmpty()) return map;
        List<StronglyConnectedComponent> sccs = Lookup.lookup(SCCFinder.class).findSCCs(classGraph);
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                map.put(member, scc);
            }
        }
        return map;
    }

    /** {@link DependencyModel.ClassInfo#packageName} is authoritative for class→package. */
    private static String packageOf(DependencyModel rawModel, String fqcn) {
        DependencyModel.ClassInfo info = rawModel.getClass(fqcn);
        return info == null ? null : info.packageName;
    }

    /** Parent of e.g. {@code com.foo.bar} is {@code com.foo}; root packages return null. */
    private static String parentPackageName(String packageName) {
        if (packageName == null) return null;
        int lastDot = packageName.lastIndexOf('.');
        return lastDot < 0 ? null : packageName.substring(0, lastDot);
    }

    // ---------------------------------------------------------------- R1

    /**
     * Returns the total class-to-class dependency count walked, used as the
     * "Dependencies" header in the report (mirrors {@code CityModel.Dependencies.Count}).
     */
    private static int checkLevelInversionAcrossNonBackEdge(
            Map<String, CalculatedElementInfo> classes,
            Map<String, StronglyConnectedComponent> classToScc,
            Set<Integer> brokenSccIds,
            DomainModel domainModel,
            List<InvariantFinding> findings) {
        int depCount = 0;
        for (CalculatedElementInfo from : classes.values()) {
            if (from.dependencies == null) continue;
            for (String depName : from.dependencies) {
                CalculatedElementInfo to = classes.get(depName);
                if (to == null) continue;
                depCount++;

                if (from.architectureLevel > to.architectureLevel) continue;
                if (domainModel.isClassBackEdge(from.fullName, to.fullName)) continue;

                StronglyConnectedComponent fromScc = classToScc.get(from.fullName);
                StronglyConnectedComponent toScc = classToScc.get(to.fullName);
                int fromSize = fromScc != null ? fromScc.getSize() : 0;
                int toSize = toScc != null ? toScc.getSize() : 0;
                boolean sameScc = fromScc != null && toScc != null
                        && fromScc.getId() == toScc.getId();

                // Same-SCC, equal level is by design: when no back edge is
                // picked for an SCC, the topo-sort places all its members at
                // the same DAG level. Internal edges are then "flat" — not a
                // bug. Only same-SCC inversions that actually drift apart
                // (e.g. a hoist step ran without re-syncing) get reported.
                if (sameScc && from.architectureLevel == to.architectureLevel) continue;

                // Same-SCC drift in a *broken* SCC is also by design: the
                // deterministic algorithm deliberately spread members across levels
                // via Fall-A / Fall-B edge removal. Only unbroken-SCC drift is a bug.
                if (sameScc && brokenSccIds.contains(fromScc.getId())) continue;

                String classification;
                if (sameScc) {
                    classification = "same original SCC (size=" + fromSize
                            + "); members should share level but DIFFER — likely a hoist step "
                            + "moved one without re-running topo-sort.";
                } else if (fromSize > 1 && toSize > 1) {
                    classification = "different multi-member SCCs (sizes "
                            + fromSize + "/" + toSize
                            + "); topo-sort should have lifted the source above the target.";
                } else if (fromSize <= 1 && toSize <= 1) {
                    classification = "neither class is in a multi-member SCC; "
                            + "pure topo-sort bug — source not lifted.";
                } else {
                    classification = "mixed SCC membership (from-SCC=" + fromSize
                            + ", to-SCC=" + toSize
                            + "); topo-sort should have lifted the source.";
                }

                findings.add(new InvariantFinding(
                        "R1",
                        "Level inversion across non-back-edge dependency: " + classification,
                        from.fullName, to.fullName,
                        from.architectureLevel, to.architectureLevel,
                        // Class endpoints carry their package in fromContainer/
                        // toContainer to mirror the C# CityBuilding.NamespaceName field.
                        toContainerOrFqn(from), toContainerOrFqn(to)));
            }
        }
        return depCount;
    }

    /** {@link CalculatedElementInfo} doesn't carry the package name directly;
     *  derive it from the class name (everything before the last dot). */
    private static String toContainerOrFqn(CalculatedElementInfo cls) {
        int lastDot = cls.fullName.lastIndexOf('.');
        return lastDot < 0 ? "" : cls.fullName.substring(0, lastDot);
    }

    // ---------------------------------------------------------------- R3

    // ---------------------------------------------------------------- R3

    /**
     * For every weighted package edge P_A → P_B where weight(P_A→P_B) > weight(P_B→P_A),
     * P_A is the dominant dependent and must be at a strictly higher level than P_B.
     * Equal-weight pairs are true peers and are handled by R2 (same level expected).
     * Edges where weight(P_A→P_B) < weight(P_B→P_A) are back-edges and are skipped.
     */
    private static void checkPackageEdgeDirection(
            Map<String, CalculatedElementInfo> packages,
            Map<String, Map<String, Integer>> packageWeights,
            DomainModel domainModel,
            List<InvariantFinding> findings) {

        for (Map.Entry<String, Map<String, Integer>> fromEntry : packageWeights.entrySet()) {
            String fromPkg = fromEntry.getKey();
            CalculatedElementInfo fromInfo = packages.get(fromPkg);
            if (fromInfo == null) continue;

            for (Map.Entry<String, Integer> toEntry : fromEntry.getValue().entrySet()) {
                String toPkg = toEntry.getKey();
                int wAB = toEntry.getValue();
                int wBA = packageWeights.getOrDefault(toPkg, Map.of()).getOrDefault(fromPkg, 0);
                CalculatedElementInfo toInfo = packages.get(toPkg);
                if (toInfo == null) continue;

                // Skip non-dominant and equal-weight (peer) edges
                if (wAB <= wBA) continue;

                // Skip edges cut during package SCC-breaking — these are architectural
                // violations the algorithm deliberately removed from the level DAG,
                // analogous to class-level back-edges excluded from R1.
                if (domainModel.isPackageBackEdge(fromPkg, toPkg)) continue;

                if (fromInfo.architectureLevel <= toInfo.architectureLevel) {
                    findings.add(new InvariantFinding(
                            "R3",
                            String.format("Package edge direction violated: %s (w=%d) dominates %s (w=%d) but level(%d) <= level(%d).",
                                    fromPkg, wAB, toPkg, wBA, fromInfo.architectureLevel, toInfo.architectureLevel),
                            fromPkg, toPkg,
                            fromInfo.architectureLevel, toInfo.architectureLevel,
                            fromPkg, toPkg));
                }
            }
        }
    }

    // ---------------------------------------------------------------- R2

    private static void checkPkgSccEqualLevel(
            Map<String, CalculatedElementInfo> classes,
            Map<String, CalculatedElementInfo> packages,
            DependencyModel rawModel,
            DomainModel domainModel,
            Map<String, StronglyConnectedComponent> classToScc,
            List<InvariantFinding> findings) {

        // Build the same filtered package-dep graph LevelCalculator uses:
        //   - drop class-level back-edges (registered in domainModel)
        //   - drop package-level back-edges identified during package SCC-breaking
        //   - drop deps where both classes share an SCC (handled by R1 already)
        //   - drop edges between packages in the same subtree (parent ↔ child)
        Map<String, Set<String>> pkgGraph = new HashMap<>();
        for (CalculatedElementInfo cls : classes.values()) {
            String fromPkg = packageOf(rawModel, cls.fullName);
            if (fromPkg == null || fromPkg.isEmpty()) continue;
            pkgGraph.computeIfAbsent(fromPkg, k -> new HashSet<>());
            if (cls.dependencies == null) continue;

            StronglyConnectedComponent fromScc = classToScc.get(cls.fullName);
            for (String depName : cls.dependencies) {
                CalculatedElementInfo dep = classes.get(depName);
                if (dep == null) continue;
                String toPkg = packageOf(rawModel, depName);
                if (toPkg == null || toPkg.isEmpty()) continue;
                if (fromPkg.equals(toPkg)) continue;
                if (isInSameSubtree(fromPkg, toPkg)) continue;

                if (domainModel.isClassBackEdge(cls.fullName, depName)) continue;
                StronglyConnectedComponent toScc = classToScc.get(depName);
                if (fromScc != null && toScc != null && fromScc.getId() == toScc.getId()) continue;

                // Skip package-level back-edges: LevelCalculator broke this SCC
                // and deliberately assigned different levels — not a peer violation.
                if (domainModel.isPackageBackEdge(fromPkg, toPkg)) continue;

                pkgGraph.get(fromPkg).add(toPkg);
            }
        }
        if (pkgGraph.isEmpty()) return;

        List<StronglyConnectedComponent> pkgSccs = Lookup.lookup(SCCFinder.class).findSCCs(pkgGraph);
        for (StronglyConnectedComponent scc : pkgSccs) {
            Set<String> members = scc.getMembers();
            if (members.size() <= 1) continue;

            // Multi-member package-SCC: every member must share the same level.
            // We only emit ONE finding per SCC, naming the min/max member as
            // representatives, so the report stays scannable for highly
            // cyclic codebases.
            Integer reference = null;
            boolean drift = false;
            for (String m : members) {
                CalculatedElementInfo p = packages.get(m);
                if (p == null) continue;
                if (reference == null) reference = p.architectureLevel;
                else if (p.architectureLevel != reference) { drift = true; break; }
            }
            if (!drift) continue;

            int maxLevel = Integer.MIN_VALUE;
            int minLevel = Integer.MAX_VALUE;
            String maxMember = null, minMember = null;
            for (String m : members) {
                CalculatedElementInfo p = packages.get(m);
                if (p == null) continue;
                if (p.architectureLevel > maxLevel) { maxLevel = p.architectureLevel; maxMember = m; }
                if (p.architectureLevel < minLevel) { minLevel = p.architectureLevel; minMember = m; }
            }

            findings.add(new InvariantFinding(
                    "R2",
                    "Package-SCC members at different levels — peers should share a level. "
                            + "SCC has " + members.size() + " member(s), level range "
                            + minLevel + ".." + maxLevel + ".",
                    maxMember, minMember,
                    maxLevel, minLevel,
                    maxMember, minMember));
        }
    }

    private static boolean isInSameSubtree(String pkgA, String pkgB) {
        return pkgA.startsWith(pkgB + ".") || pkgB.startsWith(pkgA + ".");
    }

    // ---------------------------------------------------------------- R4

    /**
     * Drift detector between local edge classification and the raw level/SCC
     * state. This rule asks the classifier the same question we'd derive from
     * levels + multi-member SCC membership, and reports any disagreement —
     * for instance, if a stale SCC map were ever passed in, or if the
     * classifier's logic diverged from the topo-sort convention.
     *
     * <p>The rule should never fire in healthy state — same intent as the
     * C# {@code CheckViolationFlagConsistency}, where it caught drift
     * between {@code CityModelBuilder}'s build-time tagging and post-build
     * level mutations.</p>
     */
    private static void checkViolationFlagConsistency(
            Map<String, CalculatedElementInfo> classes,
            Map<String, Set<String>> classGraph,
            Map<String, StronglyConnectedComponent> classToScc,
            List<InvariantFinding> findings) {

        // Build the maps EdgeClassification expects. nodeToSccId records ONLY
        // multi-member SCCs (size > 1) so that the classifier's "same SCC →
        // INTRA_SCC" check matches our notion of an architectural cycle —
        // singletons each form their own SCC in Tarjan but are not cycles.
        Map<String, Integer> nodeToLevel = new HashMap<>(classes.size());
        Map<String, Integer> nodeToSccId = new HashMap<>();
        for (CalculatedElementInfo cls : classes.values()) {
            nodeToLevel.put(cls.fullName, cls.architectureLevel);
            StronglyConnectedComponent scc = classToScc.get(cls.fullName);
            if (scc != null && scc.getSize() > 1) {
                nodeToSccId.put(cls.fullName, scc.getId());
            }
        }
        EdgeClassification classifier = new EdgeClassification(nodeToLevel, nodeToSccId);

        for (CalculatedElementInfo from : classes.values()) {
            if (from.dependencies == null) continue;
            for (String depName : from.dependencies) {
                CalculatedElementInfo to = classes.get(depName);
                if (to == null) continue;

                EdgeClassification.ClassifiedEdge classified =
                        classifier.classifyEdge(from.fullName, depName);

                StronglyConnectedComponent fromScc = classToScc.get(from.fullName);
                StronglyConnectedComponent toScc = classToScc.get(depName);
                boolean sameMultiScc = fromScc != null && toScc != null
                        && fromScc.getSize() > 1
                        && fromScc.getId() == toScc.getId();

                EdgeClassification.EdgeType expected;
                if (sameMultiScc) expected = EdgeClassification.EdgeType.INTRA_SCC;
                else if (from.architectureLevel <= to.architectureLevel) expected = EdgeClassification.EdgeType.VIOLATION;
                else expected = EdgeClassification.EdgeType.NORMAL;

                if (classified.type == expected) continue;

                findings.add(new InvariantFinding(
                        "R4",
                        "EdgeClassification type=" + classified.type
                                + " disagrees with current levels (expected " + expected + ").",
                        from.fullName, to.fullName,
                        from.architectureLevel, to.architectureLevel,
                        toContainerOrFqn(from), toContainerOrFqn(to)));
            }
        }
    }

    /**
     * Internal classifier used by R4 to detect drift between dependency edge
     * tagging and the current level/SCC state. Kept here because the logic is
     * only meaningful in the context of {@link LayoutInvariantChecker}.
     */
    private static final class EdgeClassification {
        private enum EdgeType {
            NORMAL,
            VIOLATION,
            INTRA_SCC
        }

        private static final class ClassifiedEdge {
            private final String from;
            private final String to;
            private final EdgeType type;

            private ClassifiedEdge(String from, String to, EdgeType type) {
                this.from = from;
                this.to = to;
                this.type = type;
            }

            @Override
            public String toString() {
                return String.format("%s -> %s [%s]", from, to, type);
            }
        }

        private final Map<String, Integer> nodeToLevel;
        private final Map<String, Integer> nodeToSccId;

        private EdgeClassification(Map<String, Integer> nodeToLevel,
                                   Map<String, Integer> nodeToSccId) {
            this.nodeToLevel = nodeToLevel;
            this.nodeToSccId = nodeToSccId;
        }

        private ClassifiedEdge classifyEdge(String from, String to) {
            int fromLevel = nodeToLevel.getOrDefault(from, -1);
            int toLevel = nodeToLevel.getOrDefault(to, -1);

            int fromSccId = nodeToSccId.getOrDefault(from, -1);
            int toSccId = nodeToSccId.getOrDefault(to, -1);

            if (fromSccId == toSccId && fromSccId >= 0) {
                return new ClassifiedEdge(from, to, EdgeType.INTRA_SCC);
            }

            if (fromLevel > toLevel) {
                return new ClassifiedEdge(from, to, EdgeType.NORMAL);
            }
            if (fromLevel < toLevel) {
                return new ClassifiedEdge(from, to, EdgeType.VIOLATION);
            }
            return new ClassifiedEdge(from, to, EdgeType.NORMAL);
        }
    }
}
