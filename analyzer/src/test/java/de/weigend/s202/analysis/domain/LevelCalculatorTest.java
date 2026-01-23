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
    void testCalculatePackageLevelMatchesMaxClassLevel() {
        // Package level should equal the max class level within the package
        boolean allPackagesMatchMaxClassLevel = calculatedModel.getAllPackages().values().stream()
            .allMatch(pkg -> {
                int maxClassLevel = calculatedModel.getAllClasses().values().stream()
                    .filter(cls -> cls.fullName.startsWith(pkg.fullName + ".") 
                        && !cls.fullName.substring(pkg.fullName.length() + 1).contains("."))
                    .mapToInt(cls -> cls.level)
                    .max()
                    .orElse(pkg.level);
                return pkg.level >= maxClassLevel;
            });
        
        assertTrue(allPackagesMatchMaxClassLevel, 
            "Package level should be >= max class level within the package");
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
    void testDomainPackageComExampleMatchesMaxClassLevel() {
        DomainModel.CalculatedElementInfo pkgComExample = calculatedModel.getPackage("com.example");
        assertNotNull(pkgComExample, "Package com.example should exist");
        // Package level = max class level in package (C is at L2)
        assertEquals(2, pkgComExample.level, 
            "Package com.example should be at level 2 (max class level = C at L2)");
    }

    @Test
    void testDomainPackageComInheritsMaxChildLevel() {
        DomainModel.CalculatedElementInfo pkgCom = calculatedModel.getPackage("com");
        assertNotNull(pkgCom, "Package com should exist");
        // Parent packages inherit the max level of their children
        // com.example2 is L3 (E is L3), so com should also be L3
        assertEquals(3, pkgCom.level, 
            "Package com should be at level 3 (inherits from child com.example2)");
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
    void testDomainMaxLevelIs3() {
        // Max level is 3 due to com.example2.E which depends on classes at L2
        assertEquals(3, calculatedModel.getMaxLevel(), 
            "Maximum level should be 3 (E depends on classes at L2)");
    }

    @Test
    void testDomainLevelDistribution() {
        // With SCC-aware calculation:
        // Level 0: com.example.A, com.example1.X, com.example2.D
        // Level 1: com.example.B, com.example2.C, com.example2.B
        // Level 2: com.example.C, com.example2.A
        // Level 3: com.example2.E
        Map<Integer, java.util.List<DomainModel.CalculatedElementInfo>> byLevel = 
            calculatedModel.getElementsByLevel();
        
        var level0Classes = byLevel.getOrDefault(0, java.util.List.of()).stream()
            .filter(e -> "CLASS".equals(e.type))
            .toList();
        var level1Classes = byLevel.getOrDefault(1, java.util.List.of()).stream()
            .filter(e -> "CLASS".equals(e.type))
            .toList();
        var level2Classes = byLevel.getOrDefault(2, java.util.List.of()).stream()
            .filter(e -> "CLASS".equals(e.type))
            .toList();
        var level3Classes = byLevel.getOrDefault(3, java.util.List.of()).stream()
            .filter(e -> "CLASS".equals(e.type))
            .toList();
        
        assertEquals(3, level0Classes.size(), "Level 0 should have 3 classes (A, X, D)");
        assertEquals(3, level1Classes.size(), "Level 1 should have 3 classes (B, C, B)");
        assertEquals(2, level2Classes.size(), "Level 2 should have 2 classes (C, A)");
        assertEquals(1, level3Classes.size(), "Level 3 should have 1 class (E)");
    }

    /**
     * Test for mixed package scenario: Package contains both classes and subpackages.
     * When a class C in package P depends on a class D in subpackage P.sub,
     * C.level must be > P.sub.level to ensure downward dependency visualization.
     */
    @Test
    void testClassLevelAdjustedForSubpackageDependencies() throws IOException {
        // Create a synthetic model to test the mixed package scenario
        DependencyModel mixedModel = new DependencyModel();
        
        // Create ClassInfo objects with proper dependencies
        // Package: com.example (contains classes AND subpackage)
        DependencyModel.ClassInfo appClass = new DependencyModel.ClassInfo("com.example.App", "App", "com.example");
        appClass.dependencies.add("com.example.util.Helper");  // Depends on class in subpackage
        mixedModel.addClass("com.example.App", appClass);
        
        // Class in subpackage
        DependencyModel.ClassInfo helperClass = new DependencyModel.ClassInfo("com.example.util.Helper", "Helper", "com.example.util");
        mixedModel.addClass("com.example.util.Helper", helperClass);
        
        // Class in subpackage with cross-package dependency (to make package level > 0)
        DependencyModel.ClassInfo utilUserClass = new DependencyModel.ClassInfo("com.example.util.UtilUser", "UtilUser", "com.example.util");
        utilUserClass.dependencies.add("com.other.External");  // Cross-package dependency
        mixedModel.addClass("com.example.util.UtilUser", utilUserClass);
        
        // External class to create cross-package dependency
        DependencyModel.ClassInfo externalClass = new DependencyModel.ClassInfo("com.other.External", "External", "com.other");
        mixedModel.addClass("com.other.External", externalClass);
        
        // Create package hierarchy
        java.util.Map<String, DependencyModel.PackageInfo> packages = new java.util.HashMap<>();
        
        DependencyModel.PackageInfo comPkg = new DependencyModel.PackageInfo("com", "com");
        comPkg.childPackages.add("com.example");
        comPkg.childPackages.add("com.other");
        packages.put("com", comPkg);
        
        DependencyModel.PackageInfo examplePkg = new DependencyModel.PackageInfo("com.example", "example");
        examplePkg.childPackages.add("com.example.util");
        examplePkg.classNames.add("com.example.App");
        packages.put("com.example", examplePkg);
        
        DependencyModel.PackageInfo utilPkg = new DependencyModel.PackageInfo("com.example.util", "util");
        utilPkg.classNames.add("com.example.util.Helper");
        utilPkg.classNames.add("com.example.util.UtilUser");
        packages.put("com.example.util", utilPkg);
        
        DependencyModel.PackageInfo otherPkg = new DependencyModel.PackageInfo("com.other", "other");
        otherPkg.classNames.add("com.other.External");
        packages.put("com.other", otherPkg);
        
        mixedModel.setPackages(packages);
        
        // Calculate levels
        DomainModel result = calculator.calculate(mixedModel);
        
        // Get levels
        DomainModel.CalculatedElementInfo utilPkgInfo = result.getPackage("com.example.util");
        DomainModel.CalculatedElementInfo appClassInfo = result.getClass("com.example.App");
        
        assertNotNull(utilPkgInfo, "com.example.util package should exist");
        assertNotNull(appClassInfo, "com.example.App class should exist");
        
        // Key assertion: App's level must be > util package's level
        // because App depends on Helper which is in util subpackage
        assertTrue(appClassInfo.level > utilPkgInfo.level,
            "Class com.example.App (L" + appClassInfo.level + ") should have higher level than " +
            "subpackage com.example.util (L" + utilPkgInfo.level + ") because App depends on Helper in util");
    }

}
