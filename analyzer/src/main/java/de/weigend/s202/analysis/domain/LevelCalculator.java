package de.weigend.s202.analysis.domain;

import de.weigend.s202.analysis.input.DependencyModel;
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
        this(LevelCalculationStrategyContext.createDefault());
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

        // Step 3: Calculate class levels
        calculateClassLevels(model);

        // Step 4: Calculate package levels (based on external dependencies only)
        calculatePackageLevels(model, rawModel);

        // Step 5: Update dependent relationships
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
     * Calculates levels for packages based on EXTERNAL class dependencies only.
     * Uses the PackageLevelCalculationStrategy from the strategy context.
     */
    private void calculatePackageLevels(DomainModel model, DependencyModel rawModel) {
        Map<String, DomainModel.CalculatedElementInfo> packages = model.getAllPackages();

        // Build external dependency map for each package
        Map<String, Set<String>> packageDependencies = new HashMap<>();
        for (String packageName : packages.keySet()) {
            DependencyModel.PackageInfo rawPkg = rawModel.getPackage(packageName);
            Set<String> externalPackageDeps = new HashSet<>();

            // Check all classes in this package
            for (String className : rawPkg.classNames) {
                DependencyModel.ClassInfo rawClass = rawModel.getClass(className);
                for (String depClassName : rawClass.dependencies) {
                    // If dependency is in a different package, add to external deps
                    DependencyModel.ClassInfo depClass = rawModel.getClass(depClassName);
                    if (depClass != null && !depClass.packageName.equals(packageName)) {
                        externalPackageDeps.add(depClass.packageName);
                    }
                }
            }

            packageDependencies.put(packageName, externalPackageDeps);
        }

        // Use the strategy to calculate package levels
        Map<String, Integer> calculatedLevels = 
            strategyContext.getPackageLevelStrategy().calculatePackageLevels(packageDependencies);

        // Apply calculated levels to the model
        for (Map.Entry<String, Integer> entry : calculatedLevels.entrySet()) {
            DomainModel.CalculatedElementInfo pkgInfo = model.getPackage(entry.getKey());
            if (pkgInfo != null) {
                pkgInfo.setLevel(entry.getValue());
                // Update dependencies for the model
                pkgInfo.dependencies.clear();
                pkgInfo.dependencies.addAll(packageDependencies.get(entry.getKey()));
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
}
