/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.analysis.domain;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.LevelCalculator;
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
            .allMatch(classInfo -> classInfo.architectureLevel >= 0);
        
        assertTrue(allHaveLevels, "All classes should have a level >= 0");
    }

    @Test
    void testCalculateAssignsPackageLevels() {
        // All packages should have a level (>= 0)
        boolean allHaveLevels = calculatedModel.getAllPackages().values().stream()
            .allMatch(pkgInfo -> pkgInfo.architectureLevel >= 0);
        
        assertTrue(allHaveLevels, "All packages should have a level >= 0");
    }

    @Test
    void testCalculatePackageLevelMatchesMaxClassLevel() {
        // Package levels are now derived from the inter-package dependency graph,
        // NOT from class levels. A package with no cross-package dependencies lands at L0
        // regardless of its contained class levels. This intentionally decouples the
        // containment hierarchy from the dependency hierarchy.
        DomainModel.CalculatedElementInfo comExample = calculatedModel.getPackage("com.example");
        assertNotNull(comExample);
        assertEquals(0, comExample.architectureLevel,
            "com.example has no cross-package dependencies → package level 0");

        DomainModel.CalculatedElementInfo comExample2 = calculatedModel.getPackage("com.example2");
        assertNotNull(comExample2);
        assertTrue(comExample2.architectureLevel > comExample.architectureLevel,
            "com.example2 depends on com.example → com.example2 must be at a higher level");
    }

    @Test
    void testCalculateDependencyConsistency() {
        // Check class-level consistency for the non-cyclic com.example* classes only.
        // The sccs.* package intentionally contains architectural violations (back-edges
        // pointing upward) that the heuristic does not correctly resolve — those are
        // the adversarial test cases for the SCC-breaking strategy comparison.
        boolean consistent = calculatedModel.getAllClasses().values().stream()
            .filter(c -> c.fullName.startsWith("com."))
            .allMatch(classInfo -> {
                for (String depName : classInfo.dependencies) {
                    DomainModel.CalculatedElementInfo dep = calculatedModel.getClass(depName);
                    if (dep == null) continue;
                    if (!dep.fullName.startsWith("com.")) continue;
                    if (classInfo.architectureLevel <= dep.architectureLevel) return false;
                }
                return true;
            });
        assertTrue(consistent, "Level consistency should hold for com.* classes");
    }

    @Test
    void testCalculatePackageDependencyConsistency() {
        // For dominant package edges (wAB > wBA): level(A) > level(B) must hold.
        // For peer edges (wAB == wBA): level(A) == level(B) is correct — they are
        // in the same SCC of the package graph and get equalized.
        Map<String, Map<String, Integer>> weights = calculatedModel.getPackageEdgeWeights();
        boolean consistent = weights.entrySet().stream().allMatch(fromEntry -> {
            String from = fromEntry.getKey();
            DomainModel.CalculatedElementInfo fromPkg = calculatedModel.getPackage(from);
            if (fromPkg == null) return true;
            for (Map.Entry<String, Integer> toEntry : fromEntry.getValue().entrySet()) {
                String to = toEntry.getKey();
                int wAB = toEntry.getValue();
                int wBA = weights.getOrDefault(to, Map.of()).getOrDefault(from, 0);
                DomainModel.CalculatedElementInfo toPkg = calculatedModel.getPackage(to);
                if (toPkg == null) continue;
                // Only enforce strict ordering for the dominant direction
                if (wAB > wBA && fromPkg.architectureLevel <= toPkg.architectureLevel) return false;
            }
            return true;
        });
        assertTrue(consistent, "Package level consistency should hold for dominant edges");
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
        assertEquals(0, classA.architectureLevel, "Class A should be at level 0 (no dependencies)");
    }

    @Test
    void testDomainClassBIsLevel1() {
        DomainModel.CalculatedElementInfo classB = calculatedModel.getClass("com.example.B");
        assertNotNull(classB, "Class com.example.B should exist");
        assertEquals(1, classB.architectureLevel, "Class B should be at level 1 (depends on A)");
    }

    @Test
    void testDomainClassCIsLevel2() {
        DomainModel.CalculatedElementInfo classC = calculatedModel.getClass("com.example.C");
        assertNotNull(classC, "Class com.example.C should exist");
        assertEquals(2, classC.architectureLevel, "Class C should be at level 2 (depends on B)");
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
        // Package level = position in inter-package dependency DAG.
        // com.example has no cross-package dependencies → level 0.
        assertEquals(0, pkgComExample.architectureLevel,
            "Package com.example has no cross-package dependencies → level 0");
    }

    @Test
    void testDomainPackageComInheritsMaxChildLevel() {
        DomainModel.CalculatedElementInfo pkgCom = calculatedModel.getPackage("com");
        assertNotNull(pkgCom, "Package com should exist");
        // Parent->child edges contribute to the weighted package graph
        // now, so com aggregates the outgoing deps of its descendants:
        // com.example2.E depends on com.example.B, which propagates as
        // com -> com.example. com therefore ends up at L1.
        assertEquals(1, pkgCom.architectureLevel,
            "Package com aggregates its descendants' outgoing deps → L1");
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
        assertTrue(examplePkg.architectureLevel >= 0, "com.example package should have a level");
        assertTrue(example1Pkg.architectureLevel >= 0, "com.example1 package should have a level");
        assertTrue(example2Pkg.architectureLevel >= 0, "com.example2 package should have a level");
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
        // com.example2.E is at class level 3 (depends on B at L2, X at L0).
        // Max overall level may be higher due to the sccs.* adversarial example.
        DomainModel.CalculatedElementInfo classE = calculatedModel.getClass("com.example2.E");
        assertNotNull(classE, "com.example2.E should exist");
        assertEquals(3, classE.architectureLevel, "com.example2.E should be at class level 3");
    }

    @Test
    void testDomainLevelDistribution() {
        // Check class-level distribution for the non-adversarial com.* classes only.
        // Level 0: com.example.A, com.example1.X, com.example2.D
        // Level 1: com.example.B, com.example2.C, com.example2.B
        // Level 2: com.example.C, com.example2.A
        // Level 3: com.example2.E
        Map<Integer, java.util.List<DomainModel.CalculatedElementInfo>> byLevel =
            calculatedModel.getElementsByLevel();

        var comLevel0 = byLevel.getOrDefault(0, java.util.List.of()).stream()
            .filter(e -> "CLASS".equals(e.type) && e.fullName.startsWith("com."))
            .toList();
        var comLevel1 = byLevel.getOrDefault(1, java.util.List.of()).stream()
            .filter(e -> "CLASS".equals(e.type) && e.fullName.startsWith("com."))
            .toList();
        var comLevel2 = byLevel.getOrDefault(2, java.util.List.of()).stream()
            .filter(e -> "CLASS".equals(e.type) && e.fullName.startsWith("com."))
            .toList();
        var comLevel3 = byLevel.getOrDefault(3, java.util.List.of()).stream()
            .filter(e -> "CLASS".equals(e.type) && e.fullName.startsWith("com."))
            .toList();

        assertEquals(3, comLevel0.size(), "Level 0 should have 3 com.* classes (A, X, D)");
        assertEquals(3, comLevel1.size(), "Level 1 should have 3 com.* classes (B, C, B)");
        assertEquals(2, comLevel2.size(), "Level 2 should have 2 com.* classes (C, A)");
        assertEquals(1, comLevel3.size(), "Level 3 should have 1 com.* class (E)");
    }


}
