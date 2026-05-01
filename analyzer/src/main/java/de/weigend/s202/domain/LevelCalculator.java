package de.weigend.s202.domain;

import de.weigend.s202.analysis.scc.SCCBreaker;
import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.analysis.strategy.LevelCalculationStrategyContext;
import de.weigend.s202.reader.DependencyModel;

import java.util.*;

/**
 * Calculates architectural levels for classes and packages based on dependencies.
 * Uses pluggable strategies for flexible level calculation algorithms.
 */
public class LevelCalculator {
    
    private final LevelCalculationStrategyContext strategyContext;
    
    /**
     * Create a calculator with default strategies.
     */
    public LevelCalculator() {
        this(LevelCalculationStrategyFactory.createDefault());
    }
    
    /**
     * Create a calculator with custom strategies.
     */
    public LevelCalculator(LevelCalculationStrategyContext strategyContext) {
        Objects.requireNonNull(strategyContext, "strategyContext cannot be null");
        this.strategyContext = strategyContext;
    }

    /**
     * Calculates levels for all classes and packages.
     */
    public DomainModel calculate(DependencyModel rawModel) {
        DomainModel model = new DomainModel();

        // Step 1: Create CalculatedElementInfo for all classes
        for (String className : rawModel.getAllClassNames()) {
            DependencyModel.ClassInfo rawClass = rawModel.getClass(className);
            DomainModel.CalculatedElementInfo classInfo = new DomainModel.CalculatedElementInfo(
                className, rawClass.simpleName, "CLASS", 0, new HashSet<>(rawClass.dependencies),
                rawClass.interfaceType
            );
            model.addClass(className, classInfo);
        }

        // Step 2: Create CalculatedElementInfo for all packages
        for (String packageName : rawModel.getAllPackageNames()) {
            DependencyModel.PackageInfo rawPkg = rawModel.getPackage(packageName);
            DomainModel.CalculatedElementInfo pkgInfo = new DomainModel.CalculatedElementInfo(
                packageName, rawPkg.simpleName, "PACKAGE", 0, new HashSet<>()
            );
            model.addPackage(packageName, pkgInfo);
        }

        // Step 3: Calculate class levels (SCC-aware, handles cycles correctly)
        calculateClassLevels(model);

        // Step 4: Set package levels to max class level within each package
        // This ensures Package-Level = max(Class-Levels in Package)
        setPackageLevelsFromClasses(model);

        // Step 5 / 4b alternation. Two cyclic constraints must hold simultaneously:
        //   - parent.level >= max(child.level)  — Step 5
        //   - all members of a multi-member pkg-SCC share the SCC's max level — Step 4b
        // 4b can lift a leaf package, which then forces 5 to lift its parents;
        // 5 can lift a package via its sub-packages, which then changes the
        // SCC max and forces 4b to re-equalise. Loop until both stabilise.
        // (C# original runs the same constraints in its post-loop.)
        propagateLevelsToParentPackages(model, rawModel);
        int iterations = 0;
        while (equalizePackageSccLevels(model) && iterations < 20) {
            propagateLevelsToParentPackages(model, rawModel);
            iterations++;
        }

        // Step 6: Update dependent relationships
        updateDependentRelationships(model);

        return model;
    }

    public LevelCalculationStrategyContext getStrategyContext() {
        return strategyContext;
    }

    /**
     * Calculates levels for classes using the ClassLevelCalculationStrategy.
     * Level = max(dependency levels) + 1, or 0 if no dependencies.
     */
    private void calculateClassLevels(DomainModel model) {
        Map<String, DomainModel.CalculatedElementInfo> classes = model.getAllClasses();

        // Build dependency map from the model
        Map<String, Set<String>> classDependencies = new HashMap<>();
        for (DomainModel.CalculatedElementInfo classInfo : classes.values()) {
            classDependencies.put(classInfo.fullName, new HashSet<>(classInfo.dependencies));
        }

        // Use the strategy to calculate class levels
        Map<String, Integer> calculatedLevels = 
            strategyContext.getClassLevelStrategy().calculateClassLevels(classDependencies);

        // Apply calculated levels to the model
        for (Map.Entry<String, Integer> entry : calculatedLevels.entrySet()) {
            DomainModel.CalculatedElementInfo classInfo = model.getClass(entry.getKey());
            if (classInfo != null) {
                classInfo.setLevel(entry.getValue());
            }
        }
    }

    /**
     * Sets package levels based on the maximum class level within each package.
     * Package-Level = max(Class-Levels in Package)
     */
    private void setPackageLevelsFromClasses(DomainModel model) {
        Map<String, Integer> maxClassLevelByPackage = new HashMap<>();
        
        // Find max class level for each package
        for (DomainModel.CalculatedElementInfo classInfo : model.getAllClasses().values()) {
            String packageName = extractPackageName(classInfo.fullName);
            if (packageName != null) {
                int currentMax = maxClassLevelByPackage.getOrDefault(packageName, 0);
                if (classInfo.level > currentMax) {
                    maxClassLevelByPackage.put(packageName, classInfo.level);
                }
            }
        }
        
        // Apply max class level to each package
        for (Map.Entry<String, Integer> entry : maxClassLevelByPackage.entrySet()) {
            DomainModel.CalculatedElementInfo pkgInfo = model.getPackage(entry.getKey());
            if (pkgInfo != null) {
                pkgInfo.setLevel(entry.getValue());
            }
        }
    }

    /**
     * Propagates levels from child packages to parent packages.
     * A parent package should have at least the maximum level of its children.
     */
    private void propagateLevelsToParentPackages(DomainModel model, DependencyModel rawModel) {
        Map<String, DomainModel.CalculatedElementInfo> packages = model.getAllPackages();
        
        // Iterate until no changes (handle nested hierarchies)
        boolean changed = true;
        int maxIterations = 20;
        int iterations = 0;
        
        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;
            
            for (String packageName : packages.keySet()) {
                DependencyModel.PackageInfo rawPkg = rawModel.getPackage(packageName);
                if (rawPkg == null || rawPkg.childPackages.isEmpty()) {
                    continue; // No children, skip
                }
                
                DomainModel.CalculatedElementInfo pkgInfo = packages.get(packageName);
                int currentLevel = pkgInfo.level;
                
                // Find max level among children
                int maxChildLevel = currentLevel;
                for (String childPackageName : rawPkg.childPackages) {
                    DomainModel.CalculatedElementInfo childInfo = model.getPackage(childPackageName);
                    if (childInfo != null && childInfo.level > maxChildLevel) {
                        maxChildLevel = childInfo.level;
                    }
                }
                
                // Update parent if children have higher level
                if (maxChildLevel > currentLevel) {
                    pkgInfo.setLevel(maxChildLevel);
                    changed = true;
                }
            }
        }
    }

    /**
     * Updates the reverse dependencies (dependents) for all elements.
     */
    private void updateDependentRelationships(DomainModel model) {
        // For classes
        for (DomainModel.CalculatedElementInfo classInfo : model.getAllClasses().values()) {
            for (String depName : classInfo.dependencies) {
                DomainModel.CalculatedElementInfo dep = model.getClass(depName);
                if (dep != null) {
                    dep.addDependent(classInfo.fullName);
                }
            }
        }

        // For packages
        for (DomainModel.CalculatedElementInfo pkgInfo : model.getAllPackages().values()) {
            for (String depName : pkgInfo.dependencies) {
                DomainModel.CalculatedElementInfo dep = model.getPackage(depName);
                if (dep != null) {
                    dep.addDependent(pkgInfo.fullName);
                }
            }
        }
    }

    /**
     * Equalises the level of every multi-member package-SCC to the SCC's
     * current max level. Builds the same filtered package-dep graph the
     * R2 invariant checker uses (heuristic back-edges removed, same-class-SCC
     * deps removed, intra-subtree parent↔child deps removed) so that only
     * genuine cyclic peer relationships drive equalisation. Lifts members
     * up only — never lowers — so previously-set parent levels remain valid
     * (Step 5 reruns to lift parents the rest of the way).
     *
     * @return {@code true} if any package level was raised
     */
    private boolean equalizePackageSccLevels(DomainModel model) {
        Map<String, DomainModel.CalculatedElementInfo> classes = model.getAllClasses();
        Map<String, DomainModel.CalculatedElementInfo> packages = model.getAllPackages();
        if (classes.isEmpty() || packages.isEmpty()) {
            return false;
        }

        // Class-level dependency graph (only edges to known classes).
        Map<String, Set<String>> classGraph = new HashMap<>(classes.size());
        for (DomainModel.CalculatedElementInfo cls : classes.values()) {
            Set<String> deps = new HashSet<>();
            for (String d : cls.dependencies) {
                if (classes.containsKey(d)) {
                    deps.add(d);
                }
            }
            classGraph.put(cls.fullName, deps);
        }

        // SCC-id-per-class map and heuristic back-edge set — same inputs the
        // R2 rule uses, so the equaliser fixes exactly what the rule reports.
        Map<String, Integer> classToSccId = new HashMap<>();
        for (StronglyConnectedComponent scc : new TarjanSCCFinder(classGraph).findSCCs()) {
            for (String member : scc.getMembers()) {
                classToSccId.put(member, scc.getId());
            }
        }
        Set<SCCBreaker.Edge> backEdges = new SCCBreaker(classGraph).findBackEdges();

        // Filtered package-dep graph.
        Map<String, Set<String>> pkgGraph = new HashMap<>();
        for (DomainModel.CalculatedElementInfo cls : classes.values()) {
            String fromPkg = extractPackageName(cls.fullName);
            if (fromPkg == null) continue;
            pkgGraph.computeIfAbsent(fromPkg, k -> new HashSet<>());
            Integer fromScc = classToSccId.get(cls.fullName);
            for (String dep : cls.dependencies) {
                String toPkg = extractPackageName(dep);
                if (toPkg == null) continue;
                if (fromPkg.equals(toPkg)) continue;
                if (isInSameSubtree(fromPkg, toPkg)) continue;
                if (backEdges.contains(new SCCBreaker.Edge(cls.fullName, dep))) continue;
                Integer toScc = classToSccId.get(dep);
                if (fromScc != null && toScc != null && fromScc.equals(toScc)) continue;
                pkgGraph.get(fromPkg).add(toPkg);
            }
        }
        if (pkgGraph.isEmpty()) {
            return false;
        }

        // Equalise each multi-member package-SCC to its members' max level.
        boolean changed = false;
        for (StronglyConnectedComponent pkgScc : new TarjanSCCFinder(pkgGraph).findSCCs()) {
            if (pkgScc.getSize() <= 1) continue;
            int maxLevel = Integer.MIN_VALUE;
            for (String member : pkgScc.getMembers()) {
                DomainModel.CalculatedElementInfo p = packages.get(member);
                if (p != null && p.level > maxLevel) {
                    maxLevel = p.level;
                }
            }
            if (maxLevel == Integer.MIN_VALUE) continue;
            for (String member : pkgScc.getMembers()) {
                DomainModel.CalculatedElementInfo p = packages.get(member);
                if (p != null && p.level < maxLevel) {
                    p.setLevel(maxLevel);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean isInSameSubtree(String pkgA, String pkgB) {
        return pkgA.startsWith(pkgB + ".") || pkgB.startsWith(pkgA + ".");
    }

    /**
     * Extracts the package name from a fully qualified class name.
     * @param className fully qualified class name (e.g., "de.weigend.s202.reader.InputAnalyzer")
     * @return package name (e.g., "de.weigend.s202.reader") or null if no package
     */
    private String extractPackageName(String className) {
        if (className == null || !className.contains(".")) {
            return null; // Default package or invalid
        }
        int lastDot = className.lastIndexOf('.');
        return className.substring(0, lastDot);
    }

}
