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

        // Step 5: Propagate levels to parent packages (parent inherits max child level)
        propagateLevelsToParentPackages(model, rawModel);

        // Note: Cross-package and mixed-package level adjustments (formerly steps 6-7) have been
        // removed. The DistrictRowLevelCalculator (Phase 2) now handles all final level assignment
        // locally per package, making those adjustments redundant.

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
