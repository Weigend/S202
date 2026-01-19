package de.weigend.s202.analysis.calculated;

import de.weigend.s202.analysis.raw.DependencyModel;

import java.util.*;

/**
 * Calculates architectural levels for classes and packages based on dependencies.
 */
public class LevelCalculator {

    /**
     * Calculates levels for all classes and packages.
     */
    public CalculatedModel calculate(DependencyModel rawModel) {
        CalculatedModel model = new CalculatedModel();

        // Step 1: Create CalculatedElementInfo for all classes
        for (String className : rawModel.getAllClassNames()) {
            DependencyModel.ClassInfo rawClass = rawModel.getClass(className);
            CalculatedModel.CalculatedElementInfo classInfo = new CalculatedModel.CalculatedElementInfo(
                className, rawClass.simpleName, "CLASS", 0, new HashSet<>(rawClass.dependencies)
            );
            model.addClass(className, classInfo);
        }

        // Step 2: Create CalculatedElementInfo for all packages
        for (String packageName : rawModel.getAllPackageNames()) {
            DependencyModel.PackageInfo rawPkg = rawModel.getPackage(packageName);
            CalculatedModel.CalculatedElementInfo pkgInfo = new CalculatedModel.CalculatedElementInfo(
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

    /**
     * Calculates levels for classes using iterative refinement.
     * Level = max(dependency levels) + 1, or 0 if no dependencies.
     */
    private void calculateClassLevels(CalculatedModel model) {
        Map<String, CalculatedModel.CalculatedElementInfo> classes = model.getAllClasses();

        // Max 3 iterations per class to avoid infinite loops
        int maxIterations = classes.size() + 1;
        boolean changed = true;
        int iteration = 0;

        while (changed && iteration < maxIterations) {
            changed = false;
            iteration++;

            for (CalculatedModel.CalculatedElementInfo classInfo : classes.values()) {
                if (classInfo.dependencies.isEmpty()) {
                    continue; // Already level 0
                }

                int maxDependencyLevel = -1;
                for (String depName : classInfo.dependencies) {
                    CalculatedModel.CalculatedElementInfo dep = model.getClass(depName);
                    if (dep != null) {
                        maxDependencyLevel = Math.max(maxDependencyLevel, dep.level);
                    }
                }

                if (maxDependencyLevel >= 0) {
                    int newLevel = maxDependencyLevel + 1;
                    if (newLevel != classInfo.level) {
                        classInfo.setLevel(newLevel);
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * Calculates levels for packages based on EXTERNAL class dependencies only.
     */
    private void calculatePackageLevels(CalculatedModel model, DependencyModel rawModel) {
        Map<String, CalculatedModel.CalculatedElementInfo> packages = model.getAllPackages();

        // Build external dependency map for each package
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

            // Update package dependencies with external ones
            CalculatedModel.CalculatedElementInfo pkgInfo = model.getPackage(packageName);
            pkgInfo.dependencies.clear();
            pkgInfo.dependencies.addAll(externalPackageDeps);
        }

        // Calculate package levels using same algorithm as classes
        boolean changed = true;
        int iteration = 0;
        int maxIterations = packages.size() + 1;

        while (changed && iteration < maxIterations) {
            changed = false;
            iteration++;

            for (CalculatedModel.CalculatedElementInfo pkgInfo : packages.values()) {
                if (pkgInfo.dependencies.isEmpty()) {
                    continue;
                }

                int maxDependencyLevel = -1;
                for (String depName : pkgInfo.dependencies) {
                    CalculatedModel.CalculatedElementInfo dep = model.getPackage(depName);
                    if (dep != null) {
                        maxDependencyLevel = Math.max(maxDependencyLevel, dep.level);
                    }
                }

                if (maxDependencyLevel >= 0) {
                    int newLevel = maxDependencyLevel + 1;
                    if (newLevel != pkgInfo.level) {
                        pkgInfo.setLevel(newLevel);
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * Updates the reverse dependencies (dependents) for all elements.
     */
    private void updateDependentRelationships(CalculatedModel model) {
        // For classes
        for (CalculatedModel.CalculatedElementInfo classInfo : model.getAllClasses().values()) {
            for (String depName : classInfo.dependencies) {
                CalculatedModel.CalculatedElementInfo dep = model.getClass(depName);
                if (dep != null) {
                    dep.addDependent(classInfo.fullName);
                }
            }
        }

        // For packages
        for (CalculatedModel.CalculatedElementInfo pkgInfo : model.getAllPackages().values()) {
            for (String depName : pkgInfo.dependencies) {
                CalculatedModel.CalculatedElementInfo dep = model.getPackage(depName);
                if (dep != null) {
                    dep.addDependent(pkgInfo.fullName);
                }
            }
        }
    }
}
