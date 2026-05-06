package de.weigend.s202.domain;

import de.weigend.s202.analysis.scc.SCCBreaker;
import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.analysis.strategy.LevelCalculationStrategyContext;
import de.weigend.s202.reader.DependencyModel;

import java.util.*;
import java.util.logging.Logger;

/**
 * Calculates architectural levels for classes and packages based on dependencies.
 *
 * Phase 1 pipeline:
 *   Step 1  Create class objects             (all levels = 0)
 *   Step 2  Create package objects           (all levels = 0)
 *   Step 3  Compute class levels             Tarjan → SCCBreaker (iterative) → SCC-DAG → longest-path
 *   Step 4  Package level = max(contained class levels)
 *   Step 5a Lift parent package levels       single bottom-up pass (deepest-first sort)
 *   Step 5b Cross-package dependency order   DFS post-order on simple cross-package graph
 *   Step 5c Equalize package SCCs (R2)       Tarjan on filtered graph → lift to max
 *   Step 6  Set reverse dependencies
 *
 * Step 5b uses a simple cross-package graph (intra-subtree edges removed only) for
 * one DFS post-order ordering pass. Step 5c uses the fully filtered graph
 * (back-edges and shared-class-SCC edges also removed) to equalize only genuine cyclic
 * peer packages — the same graph the R2 invariant checker uses.
 *
 * Step 5c and the parent lift are coupled: SCC equalization can lift a child package,
 * and parent lifting can then lift an SCC member again. They therefore run to a monotonic
 * fixpoint: the loop stops when neither operation raises any package level.
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

        // Step 3: Compute class levels (iterative SCCBreaker → SCC-DAG → longest-path)
        calculateClassLevels(model);

        // Step 4: Package level = max(level of contained classes)
        setPackageLevelsFromClasses(model);

        // Pre-compute both package graphs once — Step 5b and 5c use different graphs.
        Map<String, Set<String>> simplePkgGraph   = buildSimplePackageGraph(model);
        Map<String, Set<String>> filteredPkgGraph = buildFilteredPackageGraph(model);

        // Step 5a: Lift parent package levels — single bottom-up pass (deepest-first)
        liftParentPackageLevels(model, rawModel);

        // Step 5b: Cross-package dependency order — single DFS post-order pass.
        // Uses the simple cross-package graph (intra-subtree edges removed only).
        // Cycles in this graph are handled gracefully by the DFS (in-stack skip);
        // the resulting levels may be approximate for cyclic packages, which Step 5c corrects.
        applyPackageDependencyOrdering(model, simplePkgGraph);

        // Steps 5c + lift: equalize package SCCs (R2) then re-propagate to parents.
        // The fixpoint is reached only when neither operation lifts any package.
        // Both operations are monotonic and only copy existing maximum levels, so
        // the loop terminates without an arbitrary iteration cap.
        boolean changed;
        do {
            changed = equalizePackageSccLevels(model, filteredPkgGraph);
            changed |= liftParentPackageLevels(model, rawModel);
        } while (changed);

        // Step 6: Set reverse dependencies
        updateDependentRelationships(model);

        return model;
    }

    public LevelCalculationStrategyContext getStrategyContext() {
        return strategyContext;
    }

    // -------------------------------------------------------------------------
    // Step 3
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
    // Step 4
    // -------------------------------------------------------------------------

    private void setPackageLevelsFromClasses(DomainModel model) {
        Map<String, Integer> max = new HashMap<>();
        for (DomainModel.CalculatedElementInfo c : model.getAllClasses().values()) {
            String pkg = extractPackageName(c.fullName);
            if (pkg != null) max.merge(pkg, c.level, Math::max);
        }
        for (Map.Entry<String, Integer> e : max.entrySet()) {
            DomainModel.CalculatedElementInfo info = model.getPackage(e.getKey());
            if (info != null) info.setLevel(e.getValue());
        }
    }

    // -------------------------------------------------------------------------
    // Step 5a — single bottom-up pass
    // -------------------------------------------------------------------------

    /**
     * Lifts each parent package to the maximum level of its direct children.
     * Packages sorted deepest-first (most dots) guarantee children are finalized
     * before their parent is visited — single pass, no loop needed.
     *
     * @return true if at least one package level was raised
     */
    private boolean liftParentPackageLevels(DomainModel model, DependencyModel rawModel) {
        List<String> pkgNames = new ArrayList<>(model.getAllPackages().keySet());
        pkgNames.sort((a, b) -> packageDepth(b) - packageDepth(a));

        Map<String, DomainModel.CalculatedElementInfo> packages = model.getAllPackages();
        boolean changed = false;
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
            if (maxChild > pkgInfo.level) {
                pkgInfo.setLevel(maxChild);
                changed = true;
            }
        }
        return changed;
    }

    private static int packageDepth(String name) {
        int d = 0;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '.') d++;
        return d;
    }

    // -------------------------------------------------------------------------
    // Step 5b — single DFS post-order pass on simple cross-package graph
    // -------------------------------------------------------------------------

    /**
     * Applies cross-package dependency ordering via DFS post-order.
     * Uses the simple package graph (intra-subtree edges removed only) so that the
     * pass is O(V+E). Cycles are skipped by the DFS (in-stack detection); any
     * remaining level inconsistencies for cyclic packages are corrected in Step 5c.
     */
    private void applyPackageDependencyOrdering(DomainModel model,
                                                 Map<String, Set<String>> pkgGraph) {
        if (pkgGraph.isEmpty()) return;
        Map<String, DomainModel.CalculatedElementInfo> packages = model.getAllPackages();

        for (String pkg : topoSortDfsPostOrder(pkgGraph)) {
            DomainModel.CalculatedElementInfo pkgInfo = packages.get(pkg);
            if (pkgInfo == null) continue;
            for (String dep : pkgGraph.getOrDefault(pkg, Collections.emptySet())) {
                DomainModel.CalculatedElementInfo depInfo = packages.get(dep);
                if (depInfo != null && pkgInfo.level <= depInfo.level) {
                    pkgInfo.setLevel(depInfo.level + 1);
                }
            }
        }
    }

    /**
     * Iterative DFS post-order traversal (dependencies before dependents).
     * Cycles are handled by skipping nodes already on the current DFS stack.
     */
    private static List<String> topoSortDfsPostOrder(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<String> result = new ArrayList<>(graph.size());

        for (String start : graph.keySet()) {
            if (visited.contains(start)) continue;

            // Explicit stack of (node, dep-iterator) pairs — avoids recursion depth limits.
            Deque<Object[]> stack = new ArrayDeque<>();
            Iterator<String> startDeps = graph.getOrDefault(start, Collections.emptySet()).iterator();
            stack.push(new Object[]{start, startDeps});
            inStack.add(start);

            while (!stack.isEmpty()) {
                Object[] frame = stack.peek();
                String node = (String) frame[0];
                @SuppressWarnings("unchecked")
                Iterator<String> deps = (Iterator<String>) frame[1];

                boolean pushed = false;
                while (deps.hasNext()) {
                    String dep = deps.next();
                    if (!visited.contains(dep) && !inStack.contains(dep)) {
                        Iterator<String> depDeps =
                            graph.getOrDefault(dep, Collections.emptySet()).iterator();
                        stack.push(new Object[]{dep, depDeps});
                        inStack.add(dep);
                        pushed = true;
                        break;
                    }
                }

                if (!pushed) {
                    stack.pop();
                    inStack.remove(node);
                    visited.add(node);
                    result.add(node);
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Step 5c — single Tarjan pass for R2
    // -------------------------------------------------------------------------

    /**
     * Equalizes every multi-member package-SCC to its members' maximum level.
     * Uses the filtered graph (back-edges and shared-class-SCC edges removed) so
     * that only genuine cyclic peer packages are equalized — consistent with R2.
     *
     * @return true if any level was changed (expected when cycles exist only in
     *         the simple graph used by Step 5b; logged at FINE level)
     */
    private boolean equalizePackageSccLevels(DomainModel model,
                                              Map<String, Set<String>> filteredPkgGraph) {
        if (filteredPkgGraph.isEmpty()) return false;
        Map<String, DomainModel.CalculatedElementInfo> packages = model.getAllPackages();
        boolean changed = false;
        for (StronglyConnectedComponent scc : new TarjanSCCFinder(filteredPkgGraph).findSCCs()) {
            if (scc.getSize() <= 1) continue;
            int maxLevel = Integer.MIN_VALUE;
            for (String m : scc.getMembers()) {
                DomainModel.CalculatedElementInfo p = packages.get(m);
                if (p != null && p.level > maxLevel) maxLevel = p.level;
            }
            if (maxLevel == Integer.MIN_VALUE) continue;
            for (String m : scc.getMembers()) {
                DomainModel.CalculatedElementInfo p = packages.get(m);
                if (p != null && p.level < maxLevel) { p.setLevel(maxLevel); changed = true; }
            }
        }
        return changed;
    }

    // -------------------------------------------------------------------------
    // Package graph builders
    // -------------------------------------------------------------------------

    /**
     * Simple cross-package graph: removes only intra-subtree edges (parent↔child).
     * Used by Step 5b for the topo-sort ordering pass.
     */
    private Map<String, Set<String>> buildSimplePackageGraph(DomainModel model) {
        Map<String, Set<String>> pkgGraph = new HashMap<>();
        for (String pkg : model.getAllPackages().keySet()) pkgGraph.put(pkg, new HashSet<>());
        for (DomainModel.CalculatedElementInfo cls : model.getAllClasses().values()) {
            String fromPkg = extractPackageName(cls.fullName);
            if (fromPkg == null) continue;
            for (String dep : cls.dependencies) {
                String toPkg = extractPackageName(dep);
                if (toPkg == null || fromPkg.equals(toPkg)) continue;
                if (isInSameSubtree(fromPkg, toPkg)) continue;
                pkgGraph.get(fromPkg).add(toPkg);
            }
        }
        return pkgGraph;
    }

    /**
     * Filtered cross-package graph: additionally removes heuristic back-edges and
     * edges caused by shared class SCCs. Used by Step 5c (R2 equalization) — the
     * same filtering the LayoutInvariantChecker applies for its R2 check.
     */
    private Map<String, Set<String>> buildFilteredPackageGraph(DomainModel model) {
        Map<String, DomainModel.CalculatedElementInfo> classes = model.getAllClasses();
        if (classes.isEmpty()) return Collections.emptyMap();

        Map<String, Set<String>> classGraph = new HashMap<>(classes.size());
        for (DomainModel.CalculatedElementInfo cls : classes.values()) {
            Set<String> deps = new HashSet<>();
            for (String d : cls.dependencies) if (classes.containsKey(d)) deps.add(d);
            classGraph.put(cls.fullName, deps);
        }

        Map<String, Integer> classToSccId = new HashMap<>();
        for (StronglyConnectedComponent scc : new TarjanSCCFinder(classGraph).findSCCs()) {
            for (String m : scc.getMembers()) classToSccId.put(m, scc.getId());
        }
        Set<SCCBreaker.Edge> backEdges = new SCCBreaker(classGraph).findBackEdges();

        Map<String, Set<String>> pkgGraph = new HashMap<>();
        for (String pkg : model.getAllPackages().keySet()) pkgGraph.put(pkg, new HashSet<>());
        for (DomainModel.CalculatedElementInfo cls : classes.values()) {
            String fromPkg = extractPackageName(cls.fullName);
            if (fromPkg == null) continue;
            Integer fromScc = classToSccId.get(cls.fullName);
            for (String dep : cls.dependencies) {
                String toPkg = extractPackageName(dep);
                if (toPkg == null || fromPkg.equals(toPkg)) continue;
                if (isInSameSubtree(fromPkg, toPkg)) continue;
                if (backEdges.contains(new SCCBreaker.Edge(cls.fullName, dep))) continue;
                Integer toScc = classToSccId.get(dep);
                if (fromScc != null && toScc != null && fromScc.equals(toScc)) continue;
                pkgGraph.get(fromPkg).add(toPkg);
            }
        }
        return pkgGraph;
    }

    // -------------------------------------------------------------------------
    // Step 6
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
