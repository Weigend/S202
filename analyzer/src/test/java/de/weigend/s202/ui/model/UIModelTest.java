package de.weigend.s202.ui.model;

import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.domain.LevelCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UIModel and UIModelBuilder.
 * Tests UI model construction and ensures classes A, B, C appear at correct levels.
 */
class UIModelTest {
    
    private UIModelBuilder builder;
    private UIModel uiModel;
    private String testJarPath;

    @BeforeEach
    void setUp() throws IOException {
        builder = new UIModelBuilder();
        testJarPath = "../test-example/target/test-example-1.0.0.jar";
        
        // Build the full chain: InputAnalyzer -> LevelCalculator -> UIModelBuilder
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(testJarPath);
        
        LevelCalculator calculator = new LevelCalculator();
        DomainModel domainModel = calculator.calculate(rawModel);
        
        uiModel = builder.build(domainModel);
    }

    // ===== Generic Tests =====

    @Test
    void testBuilderReturnsNonNullModel() {
        assertNotNull(uiModel, "UIModel should not be null");
    }

    @Test
    void testModelHasMultipleLevels() {
        assertTrue(uiModel.getLevelCount() > 0, "Should have at least one level");
    }

    @Test
    void testModelHasPositiveMaxLevel() {
        int maxLevel = uiModel.getMaxLevel();
        assertTrue(maxLevel >= 0, "Max level should be >= 0");
    }

    @Test
    void testModelHasTotalElements() {
        int total = uiModel.getTotalElementCount();
        assertTrue(total > 0, "Should have at least one element total");
    }

    @Test
    void testLevel0HasElements() {
        List<UIModel.UIElementInfo> level0 = uiModel.getElementsAtLevel(0);
        assertFalse(level0.isEmpty(), "Level 0 should have elements");
    }

    @Test
    void testGetElementsAtInvalidLevelReturnsEmpty() {
        List<UIModel.UIElementInfo> invalidLevel = uiModel.getElementsAtLevel(999);
        assertTrue(invalidLevel.isEmpty(), "Invalid level should return empty list");
    }

    @Test
    void testGetElementCountAtLevel() {
        int level0Count = uiModel.getElementCountAtLevel(0);
        int level0ListSize = uiModel.getElementsAtLevel(0).size();
        assertEquals(level0Count, level0ListSize, "Count should match list size");
    }

    @Test
    void testGetAllLevelsReturnsNonNull() {
        List<List<UIModel.UIElementInfo>> allLevels = uiModel.getAllLevels();
        assertNotNull(allLevels, "getAllLevels should return non-null");
    }

    @Test
    void testElementsAreSortedAlphabetically() {
        // Check if elements at each level are sorted by fullName
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            List<UIModel.UIElementInfo> elements = uiModel.getElementsAtLevel(level);
            for (int i = 0; i < elements.size() - 1; i++) {
                String current = elements.get(i).fullName;
                String next = elements.get(i + 1).fullName;
                assertTrue(current.compareTo(next) <= 0, 
                    "Elements should be sorted alphabetically at level " + level);
            }
        }
    }

    @Test
    void testElementsHaveValidTypes() {
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            for (UIModel.UIElementInfo element : uiModel.getElementsAtLevel(level)) {
                assertTrue("CLASS".equals(element.type) || "PACKAGE".equals(element.type),
                    "Element type should be CLASS or PACKAGE");
            }
        }
    }

    @Test
    void testStatisticsMethodWorks() {
        String stats = uiModel.getStatistics();
        assertNotNull(stats, "Statistics should not be null");
        assertTrue(stats.length() > 0, "Statistics should not be empty");
        assertTrue(stats.contains("UIModel"), "Statistics should contain model info");
    }

    // ===== Domain-Specific Tests =====
    // Verify classes A, B, C appear at correct levels

    @Test
    void testDomainClassAInLevel0() {
        List<UIModel.UIElementInfo> level0 = uiModel.getElementsAtLevel(0);
        boolean found = level0.stream()
            .anyMatch(e -> "com.example.A".equals(e.fullName) && "CLASS".equals(e.type));
        assertTrue(found, "Class A should be in level 0");
    }

    @Test
    void testDomainClassBInLevel1() {
        List<UIModel.UIElementInfo> level1 = uiModel.getElementsAtLevel(1);
        boolean found = level1.stream()
            .anyMatch(e -> "com.example.B".equals(e.fullName) && "CLASS".equals(e.type));
        assertTrue(found, "Class B should be in level 1");
    }

    @Test
    void testDomainClassCInLevel2() {
        List<UIModel.UIElementInfo> level2 = uiModel.getElementsAtLevel(2);
        boolean found = level2.stream()
            .anyMatch(e -> "com.example.C".equals(e.fullName) && "CLASS".equals(e.type));
        assertTrue(found, "Class C should be in level 2");
    }

    @Test
    void testDomainClassAHasCorrectProperties() {
        List<UIModel.UIElementInfo> level0 = uiModel.getElementsAtLevel(0);
        UIModel.UIElementInfo classA = level0.stream()
            .filter(e -> "com.example.A".equals(e.fullName))
            .findFirst()
            .orElse(null);
        
        assertNotNull(classA, "Class A should exist");
        assertEquals("A", classA.simpleName, "Simple name should be A");
        assertEquals("CLASS", classA.type, "Type should be CLASS");
        assertEquals(0, classA.level, "Level should be 0");
        assertNotNull(classA.dependencies, "Dependencies should not be null");
        assertNotNull(classA.dependents, "Dependents should not be null");
    }

    @Test
    void testDomainClassBHasCorrectProperties() {
        List<UIModel.UIElementInfo> level1 = uiModel.getElementsAtLevel(1);
        UIModel.UIElementInfo classB = level1.stream()
            .filter(e -> "com.example.B".equals(e.fullName))
            .findFirst()
            .orElse(null);
        
        assertNotNull(classB, "Class B should exist");
        assertEquals("B", classB.simpleName, "Simple name should be B");
        assertEquals("CLASS", classB.type, "Type should be CLASS");
        assertEquals(1, classB.level, "Level should be 1");
        assertTrue(classB.dependencies.contains("com.example.A"), "Should depend on A");
    }

    @Test
    void testDomainClassCHasCorrectProperties() {
        List<UIModel.UIElementInfo> level2 = uiModel.getElementsAtLevel(2);
        UIModel.UIElementInfo classC = level2.stream()
            .filter(e -> "com.example.C".equals(e.fullName))
            .findFirst()
            .orElse(null);
        
        assertNotNull(classC, "Class C should exist");
        assertEquals("C", classC.simpleName, "Simple name should be C");
        assertEquals("CLASS", classC.type, "Type should be CLASS");
        assertEquals(2, classC.level, "Level should be 2");
        assertTrue(classC.dependencies.contains("com.example.B"), "Should depend on B");
    }

    @Test
    void testDomainMaxLevelIs2() {
        assertEquals(2, uiModel.getMaxLevel(), "Max level should be 2 (A->B->C chain)");
    }

    @Test
    void testDomainLevel0HasAtLeastOneElement() {
        assertTrue(uiModel.getElementCountAtLevel(0) >= 1, 
            "Level 0 should have at least 1 element (Class A)");
    }

    @Test
    void testDomainLevel1HasAtLeastOneElement() {
        assertTrue(uiModel.getElementCountAtLevel(1) >= 1, 
            "Level 1 should have at least 1 element (Class B)");
    }

    @Test
    void testDomainLevel2HasAtLeastOneElement() {
        assertTrue(uiModel.getElementCountAtLevel(2) >= 1, 
            "Level 2 should have at least 1 element (Class C)");
    }

    @Test
    void testDomainHasCorrectLevelStructure() {
        // Verify the expected structure: 
        // Level 0: A + package com.example + package com
        // Level 1: B + (packages)
        // Level 2: C + (packages)
        
        assertTrue(uiModel.getLevelCount() >= 3, 
            "Should have at least 3 levels (0, 1, 2)");
        assertTrue(uiModel.getElementCountAtLevel(0) >= 3, 
            "Level 0 should have at least 3 elements (A, com.example, com)");
    }

    @Test
    void testDomainPackagesCorrectlyPlaced() {
        // Package com.example should be in level 0
        boolean comExampleLevel0 = uiModel.getElementsAtLevel(0).stream()
            .anyMatch(e -> "com.example".equals(e.fullName) && "PACKAGE".equals(e.type));
        assertTrue(comExampleLevel0, "Package com.example should be in level 0");
        
        // Package com should be in level 0
        boolean comLevel0 = uiModel.getElementsAtLevel(0).stream()
            .anyMatch(e -> "com".equals(e.fullName) && "PACKAGE".equals(e.type));
        assertTrue(comLevel0, "Package com should be in level 0");
    }

    @Test
    void testDomainNoClassesInMultipleLevels() {
        // Verify class A is only in level 0
        int countA = 0;
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            long found = uiModel.getElementsAtLevel(level).stream()
                .filter(e -> "com.example.A".equals(e.fullName))
                .count();
            countA += found;
        }
        assertEquals(1, countA, "Class A should appear exactly once");
        
        // Verify class B is only in level 1
        int countB = 0;
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            long found = uiModel.getElementsAtLevel(level).stream()
                .filter(e -> "com.example.B".equals(e.fullName))
                .count();
            countB += found;
        }
        assertEquals(1, countB, "Class B should appear exactly once");
        
        // Verify class C is only in level 2
        int countC = 0;
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            long found = uiModel.getElementsAtLevel(level).stream()
                .filter(e -> "com.example.C".equals(e.fullName))
                .count();
            countC += found;
        }
        assertEquals(1, countC, "Class C should appear exactly once");
    }

}
