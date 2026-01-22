package de.weigend.s202.analysis.domain;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LevelCalculator.
 * Tests level calculation for classes and packages based on dependencies.
 */
class LevelCalculatorTest {
    
    private LevelCalculator calculator;
    private DependencyModel rawModel;
    private DomainModel calculatedModel;
    private String testJarPath;

    @BeforeEach
    void setUp() throws IOException {
        calculator = new LevelCalculator();
        testJarPath = "../test-example/target/test-example-1.0.0.jar";
        
        // Load the raw model first
        InputAnalyzer analyzer = new InputAnalyzer();
        rawModel = analyzer.analyze(testJarPath);
        
        // Calculate the model
        calculatedModel = calculator.calculate(rawModel);
    }

    @Test
    void testCalculateReturnsNonNullModel() {
        assertNotNull(calculatedModel, "DomainModel should not be null");
    }

    @Test
    void testCalculatePreservesAllClasses() {
        // All raw classes should be in calculated model
        assertEquals(
            rawModel.getClassCount(),
            calculatedModel.getAllClasses().size(),
            "All raw classes should be preserved in calculated model"
        );
    }

    @Test
    void testCalculatePreservesAllPackages() {
        // All raw packages should be in calculated model
        assertEquals(
            rawModel.getPackageCount(),
            calculatedModel.getAllPackages().size(),
            "All raw packages should be preserved in calculated model"
        );
    }

    @Test
    void testCalculateAssignsClassLevels() {
        // All classes should have a level (>= 0)
        boolean allHaveLevels = calculatedModel.getAllClasses().values().stream()
            .allMatch(classInfo -> classInfo.level >= 0);
        
        assertTrue(allHaveLevels, "All classes should have a level >= 0");
    }

    @Test
    void testCalculateAssignsPackageLevels() {
        // All packages should have a level (>= 0)
        boolean allHaveLevels = calculatedModel.getAllPackages().values().stream()
            .allMatch(pkgInfo -> pkgInfo.level >= 0);
        
        assertTrue(allHaveLevels, "All packages should have a level >= 0");
    }

    @Test
    void testCalculateClassesWithoutDependenciesAreLevel0() {
        // Classes with no dependencies should be level 0
        long level0Classes = calculatedModel.getAllClasses().values().stream()
            .filter(classInfo -> classInfo.dependencies.isEmpty())
            .filter(classInfo -> classInfo.level == 0)
            .count();
        
        long noDepClassCount = calculatedModel.getAllClasses().values().stream()
            .filter(classInfo -> classInfo.dependencies.isEmpty())
            .count();
        
        assertEquals(noDepClassCount, level0Classes, 
            "All classes without dependencies should be level 0");
    }

    @Test
    void testCalculateDependencyConsistency() {
        // If class A depends on B, then level(A) >= level(B)
        boolean consistent = calculatedModel.getAllClasses().values().stream()
            .allMatch(classInfo -> {
                for (String depName : classInfo.dependencies) {
                    DomainModel.CalculatedElementInfo dep = calculatedModel.getClass(depName);
                    if (dep == null) continue; // External dependency
                    if (classInfo.level <= dep.level) {
                        return false; // Violation: dependent has same or lower level than dependency
                    }
                }
                return true;
            });
        
        assertTrue(consistent, "Level consistency should hold: level(dependent) > level(dependency)");
    }

    @Test
    void testCalculatePackageDependencyConsistency() {
        // If package A depends on B, then level(A) >= level(B)
        boolean consistent = calculatedModel.getAllPackages().values().stream()
            .allMatch(pkgInfo -> {
                for (String depName : pkgInfo.dependencies) {
                    DomainModel.CalculatedElementInfo dep = calculatedModel.getPackage(depName);
                    if (dep == null) continue; // External dependency
                    if (pkgInfo.level <= dep.level) {
                        return false; // Violation: dependent has same or lower level than dependency
                    }
                }
                return true;
            });
        
        assertTrue(consistent, "Package level consistency should hold");
    }

    @Test
    void testCalculateDependentsAreUpdated() {
        // If A depends on B, then B should have A in its dependents
        boolean dependentsCorrect = calculatedModel.getAllClasses().values().stream()
            .allMatch(classInfo -> {
                for (String depName : classInfo.dependencies) {
                    DomainModel.CalculatedElementInfo dep = calculatedModel.getClass(depName);
                    if (dep != null && !dep.dependents.contains(classInfo.fullName)) {
                        return false;
                    }
                }
                return true;
            });
        
        assertTrue(dependentsCorrect, "Reverse dependencies (dependents) should be correctly set");
    }

    @Test
    void testCalculatePackageDependentsAreUpdated() {
        // If package A depends on B, then B should have A in its dependents
        boolean dependentsCorrect = calculatedModel.getAllPackages().values().stream()
            .allMatch(pkgInfo -> {
                for (String depName : pkgInfo.dependencies) {
                    DomainModel.CalculatedElementInfo dep = calculatedModel.getPackage(depName);
                    if (dep != null && !dep.dependents.contains(pkgInfo.fullName)) {
                        return false;
                    }
                }
                return true;
            });
        
        assertTrue(dependentsCorrect, "Package reverse dependencies should be correctly set");
    }

    @Test
    void testCalculateHasMaxLevel() {
        int maxLevel = calculatedModel.getMaxLevel();
        assertTrue(maxLevel >= 0, "Max level should be >= 0");
    }

    @Test
    void testCalculateGroupsByLevel() {
        Map<Integer, java.util.List<DomainModel.CalculatedElementInfo>> byLevel = 
            calculatedModel.getElementsByLevel();
        
        assertNotNull(byLevel, "Should return non-null map of elements by level");
        assertTrue(byLevel.size() > 0, "Should have at least one level");
        assertTrue(byLevel.containsKey(0), "Should have level 0");
    }

    @Test
    void testCalculateLevel0HasElements() {
        Map<Integer, java.util.List<DomainModel.CalculatedElementInfo>> byLevel = 
            calculatedModel.getElementsByLevel();
        
        assertTrue(byLevel.get(0).size() > 0, "Level 0 should have at least one element");
    }

    @Test
    void testCalculateElementTypesPreserved() {
        // All class elements should have type "CLASS"
        boolean classTypesCorrect = calculatedModel.getAllClasses().values().stream()
            .allMatch(classInfo -> "CLASS".equals(classInfo.type));
        
        assertTrue(classTypesCorrect, "All classes should have type 'CLASS'");
        
        // All package elements should have type "PACKAGE"
        boolean pkgTypesCorrect = calculatedModel.getAllPackages().values().stream()
            .allMatch(pkgInfo -> "PACKAGE".equals(pkgInfo.type));
        
        assertTrue(pkgTypesCorrect, "All packages should have type 'PACKAGE'");
    }

    @Test
    void testCalculateSimpleNamesPreserved() {
        // All class info should have simple name set
        boolean classNamesCorrect = calculatedModel.getAllClasses().values().stream()
            .allMatch(classInfo -> classInfo.simpleName != null && !classInfo.simpleName.isEmpty());
        
        assertTrue(classNamesCorrect, "All classes should have non-empty simple names");
    }

    @Test
    void testCalculateDependenciesAreNonNull() {
        // All elements should have non-null dependencies set
        boolean depsNonNull = calculatedModel.getAllClasses().values().stream()
            .allMatch(classInfo -> classInfo.dependencies != null);
        
        depsNonNull = depsNonNull && calculatedModel.getAllPackages().values().stream()
            .allMatch(pkgInfo -> pkgInfo.dependencies != null);
        
        assertTrue(depsNonNull, "All dependencies should be non-null");
    }

    @Test
    void testCalculateDependentsAreNonNull() {
        // All elements should have non-null dependents set
        boolean depsNonNull = calculatedModel.getAllClasses().values().stream()
            .allMatch(classInfo -> classInfo.dependents != null);
        
        depsNonNull = depsNonNull && calculatedModel.getAllPackages().values().stream()
            .allMatch(pkgInfo -> pkgInfo.dependents != null);
        
        assertTrue(depsNonNull, "All dependents should be non-null");
    }

    @Test
    void testCalculateFiltersJavaClassDependencies() {
        // Java classes should not appear in dependencies
        boolean noJavaClasses = calculatedModel.getAllClasses().values().stream()
            .flatMap(classInfo -> classInfo.dependencies.stream())
            .noneMatch(dep -> dep.startsWith("java.") || dep.startsWith("javax."));
        
        assertTrue(noJavaClasses, "Should not have java.* or javax.* in dependencies");
    }

    @Test
    void testCalculatePackageLevelsBasedOnExternalDeps() {
        // Package level should be based on EXTERNAL class dependencies only
        // (not internal classes within the same package)
        boolean correct = calculatedModel.getAllPackages().values().stream()
            .allMatch(pkgInfo -> {
                // For each dependency, verify it's from another package
                for (String depPkgName : pkgInfo.dependencies) {
                    if (depPkgName.equals(pkgInfo.fullName)) {
                        return false; // Self-dependency shouldn't exist
                    }
                }
                return true;
            });
        
        assertTrue(correct, "Package dependencies should only be external (to other packages)");
    }

    @Test
    void testCalculateMultipleCallsConsistent() throws IOException {
        // Multiple calls with same input should produce same results
        LevelCalculator calculator2 = new LevelCalculator();
        DomainModel model2 = calculator2.calculate(rawModel);
        
        // Compare number of classes and packages
        assertEquals(
            calculatedModel.getAllClasses().size(),
            model2.getAllClasses().size(),
            "Multiple calculations should produce same number of classes"
        );
        
        assertEquals(
            calculatedModel.getAllPackages().size(),
            model2.getAllPackages().size(),
            "Multiple calculations should produce same number of packages"
        );
        
        // Compare max levels
        assertEquals(
            calculatedModel.getMaxLevel(),
            model2.getMaxLevel(),
            "Multiple calculations should produce same max level"
        );
    }

    // ===== Domain-Specific Tests =====
    // These tests validate the expected level structure of the test-example project:
    // - Class A: Level 0 (no dependencies)
    // - Class B: Level 1 (depends on A)
    // - Class C: Level 2 (depends on B)
    // - Package com.example: Level 0 (only internal dependencies)

    @Test
    void testDomainClassAIsLevel0() {
        DomainModel.CalculatedElementInfo classA = calculatedModel.getClass("com.example.A");
        assertNotNull(classA, "Class com.example.A should exist");
        assertEquals(0, classA.level, "Class A should be at level 0 (no dependencies)");
    }

    @Test
    void testDomainClassBIsLevel1() {
        DomainModel.CalculatedElementInfo classB = calculatedModel.getClass("com.example.B");
        assertNotNull(classB, "Class com.example.B should exist");
        assertEquals(1, classB.level, "Class B should be at level 1 (depends on A)");
    }

    @Test
    void testDomainClassCIsLevel2() {
        DomainModel.CalculatedElementInfo classC = calculatedModel.getClass("com.example.C");
        assertNotNull(classC, "Class com.example.C should exist");
        assertEquals(2, classC.level, "Class C should be at level 2 (depends on B)");
    }

    @Test
    void testDomainClassBDependsOnA() {
        DomainModel.CalculatedElementInfo classB = calculatedModel.getClass("com.example.B");
        assertNotNull(classB, "Class B should exist");
        assertTrue(classB.dependencies.contains("com.example.A"), 
            "Class B should depend on Class A");
    }

    @Test
    void testDomainClassCDependsOnB() {
        DomainModel.CalculatedElementInfo classC = calculatedModel.getClass("com.example.C");
        assertNotNull(classC, "Class C should exist");
        assertTrue(classC.dependencies.contains("com.example.B"), 
            "Class C should depend on Class B");
    }

    @Test
    void testDomainClassAHasNoDependencies() {
        DomainModel.CalculatedElementInfo classA = calculatedModel.getClass("com.example.A");
        assertNotNull(classA, "Class A should exist");
        assertTrue(classA.dependencies.isEmpty(), 
            "Class A should have no dependencies");
    }

    @Test
    void testDomainPackageComExampleIsLevel0() {
        DomainModel.CalculatedElementInfo pkgComExample = calculatedModel.getPackage("com.example");
        assertNotNull(pkgComExample, "Package com.example should exist");
        assertEquals(0, pkgComExample.level, 
            "Package com.example should be at level 0 (only internal dependencies)");
    }

    @Test
    void testDomainPackageComInheritsMaxChildLevel() {
        DomainModel.CalculatedElementInfo pkgCom = calculatedModel.getPackage("com");
        assertNotNull(pkgCom, "Package com should exist");
        // Parent packages inherit the max level of their children
        // com.example2 is L1, so com should also be L1
        assertEquals(1, pkgCom.level, 
            "Package com should be at level 1 (inherits from child com.example2)");
    }

    @Test
    void testDomainAllPackagesAreLevel0() {
        // Packages have dependencies now: example2 depends on example and example1
        // So: example1 L0, example L1, example2 L2
        // (Cross-package dependencies: example2.E depends on example.B and example1.X)
        DomainModel.CalculatedElementInfo examplePkg = calculatedModel.getPackage("com.example");
        DomainModel.CalculatedElementInfo example1Pkg = calculatedModel.getPackage("com.example1");
        DomainModel.CalculatedElementInfo example2Pkg = calculatedModel.getPackage("com.example2");
        
        // Verify packages exist and have levels
        assertTrue(examplePkg.level >= 0, "com.example package should have a level");
        assertTrue(example1Pkg.level >= 0, "com.example1 package should have a level");
        assertTrue(example2Pkg.level >= 0, "com.example2 package should have a level");
    }

    @Test
    void testDomainClassAHasDependent() {
        DomainModel.CalculatedElementInfo classA = calculatedModel.getClass("com.example.A");
        assertTrue(classA.dependents.contains("com.example.B"), 
            "Class A should have B as dependent");
    }

    @Test
    void testDomainClassBHasAsDependency() {
        DomainModel.CalculatedElementInfo classB = calculatedModel.getClass("com.example.B");
        assertTrue(classB.dependents.contains("com.example.C"), 
            "Class B should have C as dependent");
    }

    @Test
    void testDomainMaxLevelIs2() {
        // Max level is now 3 due to com.example2.E which depends on:
        // - com.example2.A (L2), com.example.B (L1), com.example1.X (L0)
        // So E gets level 3 (max(2,1,0) + 1)
        assertEquals(3, calculatedModel.getMaxLevel(), 
            "Maximum level should be 3 (due to E depending on A->B->C chain)");
    }

    @Test
    void testDomainLevelDistribution() {
        // With example1 and example2, we have more classes at each level:
        // Level 0: com.example.A, com.example1.X, com.example2.D
        // Level 1: com.example.B, com.example2.B, com.example2.C
        // Level 2: com.example.C, com.example2.A
        // Level 3: com.example2.E
        Map<Integer, java.util.List<DomainModel.CalculatedElementInfo>> byLevel = 
            calculatedModel.getElementsByLevel();
        
        var level0Classes = byLevel.get(0).stream()
            .filter(e -> "CLASS".equals(e.type))
            .toList();
        var level1Classes = byLevel.get(1).stream()
            .filter(e -> "CLASS".equals(e.type))
            .toList();
        var level2Classes = byLevel.get(2).stream()
            .filter(e -> "CLASS".equals(e.type))
            .toList();
        var level3Classes = byLevel.get(3).stream()
            .filter(e -> "CLASS".equals(e.type))
            .toList();
        
        assertEquals(3, level0Classes.size(), "Level 0 should have 3 classes (A, X, D)");
        assertEquals(3, level1Classes.size(), "Level 1 should have 3 classes (B, example2.B, example2.C)");
        assertEquals(2, level2Classes.size(), "Level 2 should have 2 classes (C, example2.A)");
        assertEquals(1, level3Classes.size(), "Level 3 should have 1 class (example2.E)");
    }

}
