package de.weigend.s202.domain;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.analysis.strategy.LevelCalculationStrategyContext;

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
                className, rawClass.simpleName, "CLASS", 0, new HashSet<>(rawClass.dependencies)
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

        // Step 5: Propagate levels to parent packages (parent inherits max child level)
        propagateLevelsToParentPackages(model, rawModel);

        // Step 6: Adjust package levels based on cross-package dependencies
        // If package A depends on package B (via class dependencies), A.level > B.level
        adjustPackageLevelsForDependencies(model, rawModel);

        // Step 7: Update dependent relationships
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

    /**
     * Adjusts package levels based on cross-package dependencies.
     * If package A has classes that depend on classes in package B (outside A's subtree),
     * then A.level must be > B.level.
     * 
     * This ensures that if ui depends on domain, ui.level > domain.level.
     */
    private void adjustPackageLevelsForDependencies(DomainModel model, DependencyModel rawModel) {
        // Step 1: Build package dependency graph (which packages depend on which)
        Map<String, Set<String>> packageDependencies = new HashMap<>();
        
        for (DomainModel.CalculatedElementInfo classInfo : model.getAllClasses().values()) {
            String sourcePackage = extractPackageName(classInfo.fullName);
            if (sourcePackage == null) continue;
            
            packageDependencies.putIfAbsent(sourcePackage, new HashSet<>());
            
            for (String depClassName : classInfo.dependencies) {
                String targetPackage = extractPackageName(depClassName);
                if (targetPackage == null) continue;
                
                // Only consider dependencies to packages OUTSIDE the source's subtree
                // i.e., not the same package and not a child/parent relationship
                if (!sourcePackage.equals(targetPackage) && 
                    !isInSameSubtree(sourcePackage, targetPackage)) {
                    packageDependencies.get(sourcePackage).add(targetPackage);
                }
            }
        }
        
        // Step 2: Iteratively adjust package levels until stable
        // If package A depends on B, then A.level must be > B.level
        boolean changed = true;
        int maxIterations = 50;
        int iterations = 0;
        
        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;
            
            for (Map.Entry<String, Set<String>> entry : packageDependencies.entrySet()) {
                String sourcePackage = entry.getKey();
                DomainModel.CalculatedElementInfo sourcePkgInfo = model.getPackage(sourcePackage);
                if (sourcePkgInfo == null) continue;
                
                for (String targetPackage : entry.getValue()) {
                    DomainModel.CalculatedElementInfo targetPkgInfo = model.getPackage(targetPackage);
                    if (targetPkgInfo == null) continue;
                    
                    // If source depends on target, source.level must be > target.level
                    if (sourcePkgInfo.level <= targetPkgInfo.level) {
                        sourcePkgInfo.setLevel(targetPkgInfo.level + 1);
                        changed = true;
                    }
                }
            }
            
            // After adjusting leaf packages, propagate to parents
            if (changed) {
                propagateLevelsToParentPackages(model, rawModel);
            }
        }
    }

    /**
     * Checks if two packages are in the same subtree (one is a parent/child of the other).
     */
    private boolean isInSameSubtree(String pkg1, String pkg2) {
        return pkg1.startsWith(pkg2 + ".") || pkg2.startsWith(pkg1 + ".");
    }
}
