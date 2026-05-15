package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.strategy.LevelCalculationStrategyContext;
import de.weigend.s202.domain.strategy.impl.HeuristicSCCBreakingStrategy;
import de.weigend.s202.graph.SCCBreaker;
import de.weigend.s202.graph.StronglyConnectedComponent;
import de.weigend.s202.graph.TarjanSCCFinder;
import de.weigend.s202.reader.DependencyModel;

import java.util.*;
import java.util.logging.Logger;

/**
 * Calculates architectural levels for classes and packages based on dependencies.
 *
 * Pipeline:
 *   Step 1  Create class objects       (all levels = 0)
 *   Step 2  Create package objects     (all levels = 0)
 *   Step 3  Compute class levels       strategy (Tarjan → SCC-break → DAG → longest-path)
 *   Step 4  Compute package levels     weighted inter-package graph → SCC-break → DAG → longest-path
 *                                      + child→parent lift for class-level alignment
 *   Step 5  Set reverse dependencies
 *   Step 6  Assign local layer index   per-parent sibling graph → SCC-break → DAG → longest-path
 *                                      (rendering position within each parent box, no global meaning)
 *
 * Package levels are computed independently from class levels using a weighted
 * inter-package dependency graph. The weight of an edge P_A → P_B is the number
 * of distinct classes in P_A that depend on at least one class in P_B (intra-subtree
 * edges excluded). This separates the containment hierarchy from the dependency
 * hierarchy and correctly handles disconnected package trees (they are levelled
 * independently, both starting at 0).
 *
 * Package SCC-breaking uses a weight-based rank score:
 *   rank(P) = (Σ outgoing weights within SCC − Σ incoming weights within SCC)
 *             / max(1, sum of both)
 * Edge P_A→P_B is a back-edge when rank(P_A) < rank(P_B) − 0.1.
 * Equal-rank pairs (symmetric dependency) are left in the same SCC and assigned
 * the same level — they are genuine peers with no dominant dependency direction.
 */
public class LevelCalculator {

    private static final Logger LOG = Logger.getLogger(LevelCalculator.class.getName());
    private static final double RANK_THRESHOLD = 0.1;

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
        for (String className : rawModel.getAllClassNames()) {
            DependencyModel.ClassInfo rawClass = rawModel.getClass(className);
            model.addClass(className, new DomainModel.CalculatedElementInfo(
                className, rawClass.simpleName, "CLASS", 0,
                new HashSet<>(rawClass.dependencies), rawClass.interfaceType));
        }

        // Step 2: Create package objects (all levels = 0)
        for (String packageName : rawModel.getAllPackageNames()) {
            DependencyModel.PackageInfo rawPkg = rawModel.getPackage(packageName);
            model.addPackage(packageName, new DomainModel.CalculatedElementInfo(
                packageName, rawPkg.simpleName, "PACKAGE", 0, new HashSet<>()));
        }

        // Step 3: Compute class levels
        calculateClassLevels(model);

        // Step 4: Compute package levels from weighted inter-package graph.
        // Class back-edges are passed so child→parent lifting excludes them.
        Set<SCCBreaker.Edge> classBackEdges = classBackEdgesFromStrategy();
        calculatePackageLevels(model, rawModel, classBackEdges);

        // Step 5: Set reverse dependencies
        updateDependentRelationships(model);

        // Step 6: Assign per-parent local layer index — independent of the
        // global architectureLevel, used by the renderer to position
        // siblings within each parent's box.
        new LocalLayerCalculator().assign(model, rawModel);

        return model;
    }

    public LevelCalculationStrategyContext getStrategyContext() {
        return strategyContext;
    }

    private Set<SCCBreaker.Edge> classBackEdgesFromStrategy() {
        var strategy = strategyContext.getClassLevelStrategy();
        if (strategy instanceof HeuristicSCCBreakingStrategy h) {
            return h.getLastIdentifiedBackEdges();
        }
        return Set.of();
    }

    // -------------------------------------------------------------------------
    // Step 3 — class levels
    // -------------------------------------------------------------------------

    private void calculateClassLevels(DomainModel model) {
        Map<String, Set<String>> classDeps = new HashMap<>();
        for (DomainModel.CalculatedElementInfo c : model.getAllClasses().values()) {
            classDeps.put(c.fullName, new HashSet<>(c.dependencies));
        }
        Map<String, Integer> levels =
            strategyContext.getClassLevelStrategy().calculateClassLevels(classDeps);
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            DomainModel.CalculatedElementInfo info = model.getClass(e.getKey());
            if (info != null) info.setArchitectureLevel(e.getValue());
        }
    }

    // -------------------------------------------------------------------------
    // Step 4 — package levels via weighted inter-package graph
    // -------------------------------------------------------------------------

    private void calculatePackageLevels(DomainModel model, DependencyModel rawModel,
                                        Set<SCCBreaker.Edge> classBackEdges) {
        if (model.getAllPackages().isEmpty()) return;
        Set<String> allPkgNames = model.getAllPackages().keySet();

        // Build weighted graph: weight[from][to] = # distinct classes in 'from'
        // with at least one dependency on a class in 'to' (subtree edges excluded).
        Map<String, Map<String, Integer>> weights = buildWeightedPackageGraph(model, rawModel);

        // Unweighted adjacency for Tarjan (direction matters, zero-weight edges excluded)
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (String pkg : allPkgNames) graph.put(pkg, new LinkedHashSet<>());
        for (Map.Entry<String, Map<String, Integer>> entry : weights.entrySet()) {
            graph.get(entry.getKey()).addAll(entry.getValue().keySet());
        }

        // Iteratively break SCCs using weight-based rank, then assign levels
        // via SCC-collapsed DAG longest-path. Remaining SCCs (equal-rank peers)
        // are collapsed to the same level without cutting any edge.
        Set<String> backEdgeKeys = new LinkedHashSet<>(); // "from\0to" — tracked for R3
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StronglyConnectedComponent scc : new TarjanSCCFinder(graph).findSCCs()) {
                if (scc.getSize() < 2) continue;
                Set<String> members = scc.getMembers();

                // Compute weight-based rank for each member within this SCC
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

                // Identify back-edges: from→to where rank(from) < rank(to) - threshold
                for (String from : new ArrayList<>(members)) {
                    for (String to : new ArrayList<>(graph.getOrDefault(from, Set.of()))) {
                        if (!members.contains(to)) continue;
                        if (rank.get(from) < rank.get(to) - RANK_THRESHOLD) {
                            String key = from + "\0" + to;
                            if (backEdgeKeys.add(key)) {
                                graph.get(from).remove(to);
                                changed = true;
                            }
                        }
                    }
                }
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
            for (String m : scc.getMembers()) {
                for (String to : graph.getOrDefault(m, Set.of())) {
                    StronglyConnectedComponent toScc = pkgToScc.get(to);
                    if (toScc != null && toScc.getId() != scc.getId()) {
                        sccDeps.get(scc.getId()).add(toScc.getId());
                    }
                }
            }
        }

        // Child→parent lift: for each package P, find the max class-level of classes
        // in P that are depended on by classes in CHILD packages of P (sub-packages).
        // SCC back-edges are excluded. This ensures figurapi.primitive ends up strictly
        // above figurapi.Rotation (class L1), while external deps are unaffected.
        Map<String, Integer> childPkgUsedLevel = new HashMap<>();
        for (DomainModel.CalculatedElementInfo cls : model.getAllClasses().values()) {
            String fromPkg = extractPackageName(cls.fullName);
            if (fromPkg == null) continue;
            for (String dep : cls.dependencies) {
                String toPkg = extractPackageName(dep);
                if (toPkg == null || fromPkg.equals(toPkg)) continue;
                // Only child→parent edges: fromPkg is a sub-package of toPkg
                if (!fromPkg.startsWith(toPkg + ".")) continue;
                // Skip class-level back-edges
                if (classBackEdges.contains(new SCCBreaker.Edge(cls.fullName, dep))) continue;
                DomainModel.CalculatedElementInfo depCls = model.getAllClasses().get(dep);
                if (depCls == null) continue;
                childPkgUsedLevel.merge(toPkg, depCls.architectureLevel, Math::max);
            }
        }

        // Longest-path levels on SCC-DAG.
        // For child→parent package edges, effective dep height =
        //   max(pkgLevel, childPkgUsedLevel) so the dependent lands above the classes
        //   it uses from its parent package. External cross-package deps use pkgLevel only.
        Map<Integer, Integer> sccLevels = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) sccLevels.put(scc.getId(), 0);
        boolean lvlChanged = true;
        while (lvlChanged) {
            lvlChanged = false;
            for (StronglyConnectedComponent scc : sccs) {
                int maxDep = -1;
                for (int depId : sccDeps.getOrDefault(scc.getId(), Set.of())) {
                    int depPkgLevel = sccLevels.getOrDefault(depId, 0);
                    int effectiveDep = depPkgLevel;
                    // Check if any member of this SCC is a child of any member of depSCC
                    for (String fromMember : scc.getMembers()) {
                        for (Map.Entry<String, StronglyConnectedComponent> e : pkgToScc.entrySet()) {
                            if (e.getValue().getId() != depId) continue;
                            String depMember = e.getKey();
                            if (fromMember.startsWith(depMember + ".")) {
                                effectiveDep = Math.max(effectiveDep,
                                    childPkgUsedLevel.getOrDefault(depMember, 0));
                            }
                        }
                    }
                    maxDep = Math.max(maxDep, effectiveDep);
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
     * Child-to-ancestor edges (e.g. sub-package calling into its parent package)
     * are included; parent-to-child edges are excluded.
     * Each call count is propagated to all ancestor packages up to the point where
     * the ancestor would enter the same subtree as the target.
     */
    private Map<String, Map<String, Integer>> buildWeightedPackageGraph(
            DomainModel model, DependencyModel rawModel) {
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        for (String pkg : model.getAllPackages().keySet()) weights.put(pkg, new LinkedHashMap<>());

        Set<String> allPkgNames = weights.keySet();

        for (DomainModel.CalculatedElementInfo cls : model.getAllClasses().values()) {
            String leafPkg = extractPackageName(cls.fullName);
            if (leafPkg == null || !allPkgNames.contains(leafPkg)) continue;

            // Count method calls per target package using raw model data
            Map<String, Integer> callCountPerPkg = new LinkedHashMap<>();
            DependencyModel.ClassInfo rawCls = rawModel.getClass(cls.fullName);
            if (rawCls != null) {
                for (DependencyModel.MethodInfo method : rawCls.methods.values()) {
                    for (Map.Entry<String, Integer> call : method.methodCalls.entrySet()) {
                        // Method call key format: "ownerClass.methodName"
                        // Find the target class by checking known dependencies
                        String calledKey = call.getKey();
                        for (String dep : cls.dependencies) {
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
            // Fall back to 1 per target package for structural deps with no call data
            for (String dep : cls.dependencies) {
                String toPkg = extractPackageName(dep);
                if (toPkg != null && !leafPkg.equals(toPkg) && allPkgNames.contains(toPkg)) {
                    callCountPerPkg.putIfAbsent(toPkg, 1);
                }
            }

            // Propagate each weight up to ancestor packages
            for (Map.Entry<String, Integer> entry : callCountPerPkg.entrySet()) {
                String toPkg = entry.getKey();
                int callCount = entry.getValue();
                String ancestor = leafPkg;
                while (ancestor != null && allPkgNames.contains(ancestor)) {
                    if (ancestor.equals(toPkg) || toPkg.startsWith(ancestor + ".")) break;
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
        for (DomainModel.CalculatedElementInfo cls : model.getAllClasses().values()) {
            for (String dep : cls.dependencies) {
                DomainModel.CalculatedElementInfo d = model.getClass(dep);
                if (d != null) d.addDependent(cls.fullName);
            }
        }
        for (DomainModel.CalculatedElementInfo pkg : model.getAllPackages().values()) {
            for (String dep : pkg.dependencies) {
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
