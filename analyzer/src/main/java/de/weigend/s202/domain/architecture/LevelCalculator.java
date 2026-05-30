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
package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.strategy.LevelCalculationStrategyContext;
import de.weigend.s202.graph.StronglyConnectedComponent;
import de.weigend.s202.graph.TarjanSCCFinder;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Calculates architectural levels for classes and packages based on dependencies.
 *
 * Pipeline:
 *   Step 1  Create class objects       (all levels = 0)
 *   Step 2  Create package objects     (all levels = 0)
 *   Step 3  Compute package levels     weighted inter-package graph → SCC-break → DAG → longest-path
 *   Step 4  Compute class levels       package hypothesis → SCC-break → degree fallback → longest-path
 *   Step 5  Set reverse dependencies
 *   Step 6  Assign local layer index   per-parent sibling graph → SCC-break → DAG → longest-path
 *                                      (rendering position within each parent box, no global meaning)
 *
 * Package levels (Step 3) are computed first from a weighted inter-package dependency graph.
 * The weight of an edge P_A → P_B is the aggregated method-call count from classes in
 * P_A's subtree to classes in P_B, with a fallback weight of 1 for structural references
 * without method-call data.
 *
 * Package SCC-breaking uses a weight-based rank score:
 *   rank(P) = (Σ outgoing weights within SCC − Σ incoming weights within SCC)
 *             / max(1, sum of both)
 * Edge P_A→P_B is a back-edge when rank(P_A) < rank(P_B) − 0.1.
 * Equal-rank pairs (symmetric dependency) are left in the same SCC and assigned
 * the same level — they are genuine peers with no dominant dependency direction.
 *
 * Class SCC-breaking (Step 4) uses the package hypothesis computed in Step 3:
 * a class edge A→B is cut when A and B are in an SCC AND pkgLevel(A.pkg) &lt; pkgLevel(B.pkg),
 * i.e. the edge runs against the architecture hypothesis. For SCCs confined to
 * equal-level packages (genuine intra-level tangles), the in/out-degree heuristic
 * is used as a fallback.
 */
public class LevelCalculator {

    private static final Logger LOG = Logger.getLogger(LevelCalculator.class.getName());

    private final LevelCalculationStrategyContext strategyContext;

    public LevelCalculator() {
        this(LevelCalculationStrategyFactory.createDefault());
    }

    public LevelCalculator(LevelCalculationStrategyContext strategyContext) {
        Objects.requireNonNull(strategyContext, "strategyContext cannot be null");
        this.strategyContext = strategyContext;
    }

    public DomainModel calculate(DependencyModel rawModel) {
        DomainModel model = new DomainModel();

        // Step 1: Create class objects (all levels = 0)
        for (String className : sorted(rawModel.getAllClassNames())) {
            DependencyModel.ClassInfo rawClass = rawModel.getClass(className);
            Set<String> structuralDeps = rawClass.dependencies.stream()
                .filter(dep -> rawClass.getKinds(dep).stream().anyMatch(k -> k != EdgeKind.USES))
                .collect(Collectors.toSet());
            model.addClass(className, new DomainModel.CalculatedElementInfo(
                className, rawClass.simpleName, "CLASS", 0,
                structuralDeps, rawClass.interfaceType));
        }

        // Step 2: Create package objects (all levels = 0)
        for (String packageName : sorted(rawModel.getAllPackageNames())) {
            DependencyModel.PackageInfo rawPkg = rawModel.getPackage(packageName);
            model.addPackage(packageName, new DomainModel.CalculatedElementInfo(
                packageName, rawPkg.simpleName, "PACKAGE", 0, new HashSet<>()));
        }

        // Step 3: Compute package levels first — the result is the architecture hypothesis
        // that guides class SCC-breaking in Step 4.
        calculatePackageLevels(model, rawModel);

        // Step 4: Compute class levels using the package hypothesis from Step 3.
        // SCCs whose edges cross package-level boundaries are cut along the hypothesis;
        // same-level-package SCCs fall back to the degree heuristic.
        Map<String, Integer> packageLevels = new HashMap<>();
        for (Map.Entry<String, DomainModel.CalculatedElementInfo> e : model.getAllPackages().entrySet()) {
            packageLevels.put(e.getKey(), e.getValue().architectureLevel);
        }
        calculateClassLevels(model, packageLevels);

        // Step 5: Set reverse dependencies
        updateDependentRelationships(model);

        // Step 6: Assign per-parent local layer index — independent of the
        // global architectureLevel, used by the renderer to position
        // siblings within each parent's box.
        new LocalLevelCalculator().assign(model, rawModel);

        // ---- DEMO_ALERT_INJECTION (review demo for M. Philippsen, 2026-05-19) ----
        // When system property s202.demo.injectAlert=true, deliberately corrupt
        // both the architecture level and the local level of com.example.A so
        // the bug is visible in the UI (A is rendered above C, even though
        // B depends on A and C depends on B) AND the layout-invariant checker
        // catches the inconsistency, popping up the implausibility alert
        // dialog. Tests run without the property and stay green. Remove this
        // block once the demo session is over.
        if (Boolean.getBoolean("s202.demo.injectAlert")) {
            DomainModel.CalculatedElementInfo demoA = model.getClass("com.example.A");
            DomainModel.CalculatedElementInfo demoC = model.getClass("com.example.C");
            if (demoA != null && demoC != null) {
                demoA.setArchitectureLevel(demoC.architectureLevel);
                demoA.setLocalLevel(demoC.localLevel + 1);
            }
        }
        // ---- END DEMO_ALERT_INJECTION ----

        return model;
    }

    public LevelCalculationStrategyContext getStrategyContext() {
        return strategyContext;
    }

    private static List<String> sorted(Collection<String> c) {
        return c.stream().sorted().collect(Collectors.toList());
    }

    /** Filter to members first, then sort — mirrors C#'s .Where(members.Contains).OrderBy(). */
    private static List<String> sortedFiltered(Collection<String> c, Set<String> filter) {
        return c.stream().filter(filter::contains).sorted().collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Step 4 — class levels guided by the package hypothesis
    // -------------------------------------------------------------------------

    /**
     * Breaks class-level SCCs using the architecture hypothesis established by
     * package levels (Step 3). A class edge A→B inside an SCC is cut when
     * pkgLevel(A.pkg) &lt; pkgLevel(B.pkg) — it runs against the hypothesis.
     * SCCs whose members all belong to equal-level packages are genuine intra-level
     * tangles; their edges are cut by the in/out-degree heuristic as a fallback.
     */
    private void calculateClassLevels(DomainModel model, Map<String, Integer> packageLevels) {
        // Build class dependency graph
        Map<String, Set<String>> graph = new HashMap<>();
        for (DomainModel.CalculatedElementInfo c : model.getAllClasses().values()) {
            graph.put(c.fullName, new HashSet<>(c.dependencies));
        }

        // Phase 1: package-hypothesis-guided SCC breaking.
        // Iteratively cut edges A→B that run against the package hypothesis
        // (A is in a lower package than B) and are part of a cycle.
        Set<String> hypothesisBackEdgeKeys = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StronglyConnectedComponent scc : new TarjanSCCFinder(graph).findSCCs()) {
                if (scc.getSize() < 2) continue;
                Set<String> members = scc.getMembers();
                for (String from : members) {
                    for (String to : sortedFiltered(graph.getOrDefault(from, Set.of()), members)) {
                        int fromPkgLevel = packageLevels.getOrDefault(extractPackageName(from), 0);
                        int toPkgLevel   = packageLevels.getOrDefault(extractPackageName(to),   0);
                        if (fromPkgLevel < toPkgLevel) {
                            graph.get(from).remove(to);
                            hypothesisBackEdgeKeys.add(from + "\0" + to);
                            changed = true;
                        }
                    }
                }
            }
        }
        // Phase 2 (Fall B): residual SCCs are same-level tangles — no package context
        // provides a direction.  Remove every internal edge; class levels then derive
        // purely from dependencies outside the former cycle.  Nodes with no such deps
        // end up at the same level (correct: no justified ordering between them).
        // These edges are also recorded as back-edges so the LayoutInvariantChecker
        // does not flag the resulting level spread as an R1 violation.
        boolean fallBChanged = true;
        while (fallBChanged) {
            fallBChanged = false;
            for (StronglyConnectedComponent scc : new TarjanSCCFinder(graph).findSCCs()) {
                if (scc.getSize() < 2) continue;
                Set<String> members = scc.getMembers();
                for (String from : sortedFiltered(new ArrayList<>(graph.keySet()), members)) {
                    for (String to : sortedFiltered(new ArrayList<>(graph.getOrDefault(from, Set.of())), members)) {
                        graph.get(from).remove(to);
                        hypothesisBackEdgeKeys.add(from + "\0" + to);
                    }
                }
                fallBChanged = true;
                break; // restart Tarjan after each modification
            }
        }

        // Publish all removed edges (Phase 1 + Phase 2) as class back-edges.
        model.setClassBackEdges(hypothesisBackEdgeKeys);

        // Phase 3: longest-path on the (now near-DAG) graph via the configured strategy.
        Map<String, Integer> levels =
            strategyContext.getClassLevelStrategy().calculateClassLevels(graph);
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            DomainModel.CalculatedElementInfo info = model.getClass(e.getKey());
            if (info != null) info.setArchitectureLevel(e.getValue());
        }
    }

    // -------------------------------------------------------------------------
    // Step 4 — package levels via weighted inter-package graph
    // -------------------------------------------------------------------------

    private void calculatePackageLevels(DomainModel model, DependencyModel rawModel) {
        if (model.getAllPackages().isEmpty()) return;
        Set<String> allPkgNames = model.getAllPackages().keySet();

        // Build weighted graph: weight[from][to] = aggregated method-call
        // count from classes in 'from' to classes in 'to', with fallback
        // weight 1 for structural dependencies without method-call data.
        Map<String, Map<String, Integer>> weights = buildWeightedPackageGraph(model, rawModel);

        // Unweighted adjacency for Tarjan (direction matters, zero-weight edges excluded)
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (String pkg : allPkgNames) graph.put(pkg, new LinkedHashSet<>());
        for (Map.Entry<String, Map<String, Integer>> entry : weights.entrySet()) {
            graph.get(entry.getKey()).addAll(entry.getValue().keySet());
        }

        // Two-step deterministic cycle breaking — no heuristics, no thresholds:
        //
        // Step 1 (asymmetric): find the min-weight edge in the SCC and cut it, then
        //   restart.  Alphabetically first (from, to) breaks ties in the minimum.
        // Step 2 (symmetric): when all internal edges share the same weight there is
        //   no architecturally justified direction — remove every internal edge.
        //   Levels are then determined solely by dependencies outside the former cycle;
        //   nodes with no such deps stay at the same level.
        //
        // Both steps iterate until no SCC of size ≥ 2 remains.
        Set<String> backEdgeKeys = new LinkedHashSet<>(); // "from\0to" — tracked for R3
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StronglyConnectedComponent scc : new TarjanSCCFinder(graph).findSCCs()) {
                if (scc.getSize() < 2) continue;
                Set<String> members = scc.getMembers();

                // Compute rank per member from in/out weights within the SCC.
                // Rank captures global flow direction — a node called by many others has
                // low rank (foundation); a node calling many others has high rank (user).
                // Equal weights can still produce different ranks when topologies differ.
                Map<String, Double> rank = new HashMap<>();
                for (String m : members) {
                    int out = 0, in = 0;
                    for (String other : members) {
                        if (other.equals(m)) continue;
                        out += weights.getOrDefault(m, Map.of()).getOrDefault(other, 0);
                        in  += weights.getOrDefault(other, Map.of()).getOrDefault(m, 0);
                    }
                    rank.put(m, (out - in) / (double) Math.max(1, out + in));
                }

                // Cut all edges that run against the dependency flow (rank(to) > rank(from)).
                // If no edge qualifies (ranks are equal for all pairs → truly no direction),
                // remove every internal edge so the SCC dissolves.
                boolean anyCut = false;
                for (String from : sorted(members)) {
                    for (String to : sortedFiltered(new ArrayList<>(graph.getOrDefault(from, Set.of())), members)) {
                        if (rank.get(to) > rank.get(from)) {
                            graph.get(from).remove(to);
                            backEdgeKeys.add(from + "\0" + to);
                            anyCut = true;
                        }
                    }
                }
                if (!anyCut) {
                    // Truly symmetric — topology gives no direction. Remove all edges;
                    // levels are then determined by dependencies outside the former cycle.
                    for (String from : sorted(members)) {
                        for (String to : sortedFiltered(new ArrayList<>(graph.getOrDefault(from, Set.of())), members)) {
                            graph.get(from).remove(to);
                            backEdgeKeys.add(from + "\0" + to);
                        }
                    }
                }
                changed = true;
            }
        }

        // Assign package levels: SCC-collapsed DAG → longest-path
        List<StronglyConnectedComponent> sccs = new TarjanSCCFinder(graph).findSCCs();
        Map<String, StronglyConnectedComponent> pkgToScc = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String m : scc.getMembers()) pkgToScc.put(m, scc);
        }

        // SCC dependency graph (between SCC IDs)
        Map<Integer, Set<Integer>> sccDeps = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            sccDeps.put(scc.getId(), new HashSet<>());
            Set<String> sccMembers = scc.getMembers();
            for (String m : sccMembers) {
                for (String to : graph.getOrDefault(m, Set.of())) {
                    StronglyConnectedComponent toScc = pkgToScc.get(to);
                    if (toScc != null && toScc.getId() != scc.getId()) {
                        sccDeps.get(scc.getId()).add(toScc.getId());
                    }
                }
            }
        }

        // Longest-path levels on the SCC-collapsed DAG. The previous
        // "childPkgUsedLevel" lift (which inflated a parent's level by
        // the class levels its child packages used) is gone — layout
        // positioning lives on localLevel now, so architectureLevel
        // can be a direct dependency-chain depth.
        Map<Integer, Integer> sccLevels = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) sccLevels.put(scc.getId(), 0);
        boolean lvlChanged = true;
        while (lvlChanged) {
            lvlChanged = false;
            for (StronglyConnectedComponent scc : sccs) {
                int maxDep = -1;
                for (int depId : sccDeps.getOrDefault(scc.getId(), Set.of())) {
                    maxDep = Math.max(maxDep, sccLevels.getOrDefault(depId, 0));
                }
                int newLevel = maxDep >= 0 ? maxDep + 1 : 0;
                if (sccLevels.get(scc.getId()) != newLevel) {
                    sccLevels.put(scc.getId(), newLevel);
                    lvlChanged = true;
                }
            }
        }

        // Apply levels to all packages
        Map<String, DomainModel.CalculatedElementInfo> pkgInfos = model.getAllPackages();
        for (StronglyConnectedComponent scc : sccs) {
            int level = sccLevels.get(scc.getId());
            for (String member : scc.getMembers()) {
                DomainModel.CalculatedElementInfo pkg = pkgInfos.get(member);
                if (pkg != null) pkg.setArchitectureLevel(level);
            }
        }

        // Store the weighted graph and identified back-edges in the model
        model.setPackageEdgeWeights(weights);
        model.setPackageBackEdges(backEdgeKeys);

        // Populate package dependencies (unweighted) for reverse-dependency tracking
        for (Map.Entry<String, Map<String, Integer>> entry : weights.entrySet()) {
            DomainModel.CalculatedElementInfo pkg = pkgInfos.get(entry.getKey());
            if (pkg != null) entry.getValue().keySet().forEach(pkg::addDependency);
        }
    }

    /**
     * Builds the weighted inter-package dependency graph.
     * weight(P_A → P_B) = total method-call count from classes in P_A's subtree
     * to classes in P_B. Method calls are a stronger signal than distinct-class
     * counts: a class called hundreds of times clearly dominates one called once,
     * making SCC-breaking direction unambiguous. Intra-subtree calls are excluded.
     * Child-to-ancestor and parent-to-child edges are included.
     * Each call count is propagated to all ancestor packages up to the point where
     * the ancestor would enter the same subtree as the target.
     */
    private Map<String, Map<String, Integer>> buildWeightedPackageGraph(
            DomainModel model, DependencyModel rawModel) {
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        for (String pkg : model.getAllPackages().keySet()) weights.put(pkg, new LinkedHashMap<>());

        Set<String> allPkgNames = weights.keySet();

        List<DomainModel.CalculatedElementInfo> sortedClasses = model.getAllClasses().values()
                .stream().sorted(Comparator.comparing(c -> c.fullName)).collect(Collectors.toList());
        for (DomainModel.CalculatedElementInfo cls : sortedClasses) {
            String leafPkg = extractPackageName(cls.fullName);
            if (leafPkg == null || !allPkgNames.contains(leafPkg)) continue;

            // Count method calls per target package using raw model data
            Map<String, Integer> callCountPerPkg = new LinkedHashMap<>();
            DependencyModel.ClassInfo rawCls = rawModel.getClass(cls.fullName);
            if (rawCls != null) {
                for (DependencyModel.MethodInfo method : rawCls.methods.values()) {
                    for (Map.Entry<String, Integer> call : method.methodCalls.entrySet()) {
                        String calledKey = call.getKey();
                        for (String dep : sorted(cls.dependencies)) {
                            if (calledKey.startsWith(dep + ".")) {
                                String toPkg = extractPackageName(dep);
                                if (toPkg != null && !leafPkg.equals(toPkg)
                                        && allPkgNames.contains(toPkg)) {
                                    callCountPerPkg.merge(toPkg, call.getValue(), Integer::sum);
                                }
                                break;
                            }
                        }
                    }
                }
            }
            for (String dep : sorted(cls.dependencies)) {
                String toPkg = extractPackageName(dep);
                if (toPkg != null && !leafPkg.equals(toPkg) && allPkgNames.contains(toPkg)) {
                    callCountPerPkg.putIfAbsent(toPkg, 1);
                }
            }

            // Propagate each weight up to ancestor packages. Parent->child
            // edges are no longer filtered here — the rank-based SCC breaker
            // decides direction based on actual call-count weight. Layout
            // positioning is the LocalLevelCalculator's job (step 6), so the
            // global package level can be a direct dependency-chain count.
            for (Map.Entry<String, Integer> entry : new TreeMap<>(callCountPerPkg).entrySet()) {
                String toPkg = entry.getKey();
                int callCount = entry.getValue();
                String ancestor = leafPkg;
                while (ancestor != null && allPkgNames.contains(ancestor)) {
                    if (ancestor.equals(toPkg)) break;
                    weights.get(ancestor).merge(toPkg, callCount, Integer::sum);
                    ancestor = extractPackageName(ancestor);
                }
            }
        }
        return weights;
    }

    // -------------------------------------------------------------------------
    // Step 5 — reverse dependencies
    // -------------------------------------------------------------------------

    private void updateDependentRelationships(DomainModel model) {
        for (DomainModel.CalculatedElementInfo cls :
                model.getAllClasses().values().stream()
                    .sorted(Comparator.comparing(c -> c.fullName)).collect(Collectors.toList())) {
            for (String dep : sorted(cls.dependencies)) {
                DomainModel.CalculatedElementInfo d = model.getClass(dep);
                if (d != null) d.addDependent(cls.fullName);
            }
        }
        for (DomainModel.CalculatedElementInfo pkg :
                model.getAllPackages().values().stream()
                    .sorted(Comparator.comparing(p -> p.fullName)).collect(Collectors.toList())) {
            for (String dep : sorted(pkg.dependencies)) {
                DomainModel.CalculatedElementInfo d = model.getPackage(dep);
                if (d != null) d.addDependent(pkg.fullName);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static boolean isInSameSubtree(String a, String b) {
        return a.startsWith(b + ".") || b.startsWith(a + ".");
    }

    private static String extractPackageName(String className) {
        if (className == null || !className.contains(".")) return null;
        return className.substring(0, className.lastIndexOf('.'));
    }
}
