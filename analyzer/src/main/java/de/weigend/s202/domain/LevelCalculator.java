package de.weigend.s202.domain;

import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.analysis.strategy.LevelCalculationStrategyContext;
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
 *   Step 5  Lift parent packages       single bottom-up pass: parent = max(child package levels)
 *   Step 6  Set reverse dependencies
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

        // Step 4: Compute package levels from weighted inter-package graph
        //         and store the graph in the model for checker + visualization
        calculatePackageLevels(model);

        // Step 5: Set reverse dependencies
        updateDependentRelationships(model);

        return model;
    }

    public LevelCalculationStrategyContext getStrategyContext() {
        return strategyContext;
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
            if (info != null) info.setLevel(e.getValue());
        }
    }

    // -------------------------------------------------------------------------
    // Step 4 — package levels via weighted inter-package graph
    // -------------------------------------------------------------------------

    private void calculatePackageLevels(DomainModel model) {
        if (model.getAllPackages().isEmpty()) return;
        Set<String> allPkgNames = model.getAllPackages().keySet();

        // Build weighted graph: weight[from][to] = # distinct classes in 'from'
        // with at least one dependency on a class in 'to' (subtree edges excluded).
        Map<String, Map<String, Integer>> weights = buildWeightedPackageGraph(model);

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

        // Longest-path levels on SCC-DAG.
        // Package levels are derived purely from the package dependency graph.
        // Class levels are a separate coordinate system and are not mixed in here.
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
                if (pkg != null) pkg.setLevel(level);
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
     * weight(P_A → P_B) = number of distinct classes anywhere in the subtree of P_A
     * that have at least one dependency on any class in P_B (intra-subtree edges excluded).
     *
     * Each class vote is propagated up to all ancestor packages of the class's direct
     * parent, stopping when the ancestor enters the same subtree as the target package.
     * This ensures parent packages like {@code de.weigend.s202.ui} correctly reflect
     * the cumulative dependencies of all their sub-packages.
     */
    private Map<String, Map<String, Integer>> buildWeightedPackageGraph(DomainModel model) {
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        for (String pkg : model.getAllPackages().keySet()) weights.put(pkg, new LinkedHashMap<>());

        for (DomainModel.CalculatedElementInfo cls : model.getAllClasses().values()) {
            String leafPkg = extractPackageName(cls.fullName);
            if (leafPkg == null || !weights.containsKey(leafPkg)) continue;

            // Distinct target packages for this class
            Set<String> targetPkgs = new LinkedHashSet<>();
            for (String dep : cls.dependencies) {
                String toPkg = extractPackageName(dep);
                if (toPkg != null && !leafPkg.equals(toPkg) && weights.containsKey(toPkg)) {
                    targetPkgs.add(toPkg);
                }
            }

            // Propagate each vote up to all ancestor packages.
            // Stop when toPkg is a descendant of the current ancestor (ancestor → child
            // edge — a package depending on its own sub-package) or when ancestor == toPkg.
            // Do NOT stop when toPkg is an ancestor of leafPkg: that edge (child depending
            // on ancestor) is a valid upward dependency that determines ordering.
            for (String toPkg : targetPkgs) {
                String ancestor = leafPkg;
                while (ancestor != null && weights.containsKey(ancestor)) {
                    if (ancestor.equals(toPkg) || toPkg.startsWith(ancestor + ".")) break;
                    weights.get(ancestor).merge(toPkg, 1, Integer::sum);
                    ancestor = extractPackageName(ancestor);
                }
            }
        }
        return weights;
    }

    // -------------------------------------------------------------------------
    // Step 5 — lift parent package levels
    // -------------------------------------------------------------------------

    /**
     * Sets each parent package's level to the maximum of its direct children's levels.
     * Packages sorted deepest-first guarantee children are finalised before parents.
     */
    private void liftParentPackageLevels(DomainModel model, DependencyModel rawModel) {
        List<String> pkgNames = new ArrayList<>(model.getAllPackages().keySet());
        pkgNames.sort((a, b) -> packageDepth(b) - packageDepth(a));

        Map<String, DomainModel.CalculatedElementInfo> packages = model.getAllPackages();
        for (String pkgName : pkgNames) {
            DependencyModel.PackageInfo rawPkg = rawModel.getPackage(pkgName);
            if (rawPkg == null || rawPkg.childPackages.isEmpty()) continue;
            DomainModel.CalculatedElementInfo pkgInfo = packages.get(pkgName);
            if (pkgInfo == null) continue;
            int maxChild = pkgInfo.level;
            for (String child : rawPkg.childPackages) {
                DomainModel.CalculatedElementInfo childInfo = packages.get(child);
                if (childInfo != null && childInfo.level > maxChild) maxChild = childInfo.level;
            }
            if (maxChild > pkgInfo.level) pkgInfo.setLevel(maxChild);
        }
    }

    private static int packageDepth(String name) {
        int d = 0;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '.') d++;
        return d;
    }

    // -------------------------------------------------------------------------
    // Step 6 — reverse dependencies
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
