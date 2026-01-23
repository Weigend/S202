package de.weigend.s202.ui.model;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ArchitectureNode and ArchitectureNodeBuilder.
 * Tests architecture node tree construction and ensures classes A, B, C appear at correct levels.
 */
class ArchitectureNodeTest {
    
    private ArchitectureNodeBuilder builder;
    private ArchitectureNode rootNode;
    private String testJarPath;

    @BeforeEach
    void setUp() throws IOException {
        builder = new ArchitectureNodeBuilder();
        testJarPath = "../test-example/target/test-example-1.0.0.jar";
        
        // Build the full chain: InputAnalyzer -> LevelCalculator -> ArchitectureNodeBuilder
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(testJarPath);
        
        LevelCalculator calculator = new LevelCalculator();
        DomainModel domainModel = calculator.calculate(rawModel);
        
        rootNode = builder.build(domainModel);
    }

    // ===== Generic Tests =====

    @Test
    void testBuilderReturnsNonNullModel() {
        assertNotNull(rootNode, "ArchitectureNode should not be null");
    }

    @Test
    void testModelHasMultipleLevels() {
        assertTrue(rootNode.getLevelCount() > 0, "Should have at least one level");
    }

    @Test
    void testModelHasPositiveMaxLevel() {
        int maxLevel = rootNode.getMaxLevel();
        assertTrue(maxLevel >= 0, "Max level should be >= 0");
    }

    @Test
    void testModelHasTotalElements() {
        int total = rootNode.getTotalNodeCount();
        assertTrue(total > 1, "Should have more than just root node");
    }

    @Test
    void testLevel0HasElements() {
        List<ArchitectureNode> level0 = rootNode.getNodesAtLevel(0);
        assertFalse(level0.isEmpty(), "Level 0 should have elements");
    }

    @Test
    void testGetNodesAtInvalidLevelReturnsEmpty() {
        List<ArchitectureNode> invalidLevel = rootNode.getNodesAtLevel(999);
        assertTrue(invalidLevel.isEmpty(), "Invalid level should return empty list");
    }

    @Test
    void testNodesHaveValidTypes() {
        for (int level = 0; level <= rootNode.getMaxLevel(); level++) {
            for (ArchitectureNode node : rootNode.getNodesAtLevel(level)) {
                assertNotNull(node.getType(), "Node type should not be null");
                assertTrue(node.getType() == NodeType.CLASS || node.getType() == NodeType.PACKAGE,
                    "Node type should be CLASS or PACKAGE");
            }
        }
    }

    @Test
    void testStatisticsMethodWorks() {
        String stats = rootNode.getStatistics();
        assertNotNull(stats, "Statistics should not be null");
        assertTrue(stats.length() > 0, "Statistics should not be empty");
        assertTrue(stats.contains("ArchitectureNode"), "Statistics should contain model info");
    }

    // ===== Domain-Specific Tests =====
    // Verify classes A, B, C appear at correct levels

    @Test
    void testDomainClassAInLevel0() {
        List<ArchitectureNode> level0 = rootNode.getNodesAtLevel(0);
        boolean found = level0.stream()
            .anyMatch(e -> "com.example.A".equals(e.getFullName()) && e.getType() == NodeType.CLASS);
        assertTrue(found, "Class A should be in level 0");
    }

    @Test
    void testDomainClassBInLevel1() {
        List<ArchitectureNode> level1 = rootNode.getNodesAtLevel(1);
        boolean found = level1.stream()
            .anyMatch(e -> "com.example.B".equals(e.getFullName()) && e.getType() == NodeType.CLASS);
        assertTrue(found, "Class B should be in level 1");
    }

    @Test
    void testDomainClassCInLevel2() {
        List<ArchitectureNode> level2 = rootNode.getNodesAtLevel(2);
        boolean found = level2.stream()
            .anyMatch(e -> "com.example.C".equals(e.getFullName()) && e.getType() == NodeType.CLASS);
        assertTrue(found, "Class C should be in level 2");
    }

    @Test
    void testDomainClassAHasCorrectProperties() {
        ArchitectureNode classA = findNodeByName(rootNode, "com.example.A");
        
        assertNotNull(classA, "Class A should exist");
        assertEquals("A", classA.getSimpleName(), "Simple name should be A");
        assertEquals(NodeType.CLASS, classA.getType(), "Type should be CLASS");
        assertEquals(0, classA.getLevel(), "Level should be 0");
        assertNotNull(classA.getDependencies(), "Dependencies should not be null");
        assertNotNull(classA.getDependents(), "Dependents should not be null");
    }

    @Test
    void testDomainClassBHasCorrectProperties() {
        ArchitectureNode classB = findNodeByName(rootNode, "com.example.B");
        
        assertNotNull(classB, "Class B should exist");
        assertEquals("B", classB.getSimpleName(), "Simple name should be B");
        assertEquals(NodeType.CLASS, classB.getType(), "Type should be CLASS");
        assertEquals(1, classB.getLevel(), "Level should be 1");
        assertTrue(classB.getDependencies().contains("com.example.A"), "Should depend on A");
    }

    @Test
    void testDomainClassCHasCorrectProperties() {
        ArchitectureNode classC = findNodeByName(rootNode, "com.example.C");
        
        assertNotNull(classC, "Class C should exist");
        assertEquals("C", classC.getSimpleName(), "Simple name should be C");
        assertEquals(NodeType.CLASS, classC.getType(), "Type should be CLASS");
        assertEquals(2, classC.getLevel(), "Level should be 2");
        assertTrue(classC.getDependencies().contains("com.example.B"), "Should depend on B");
    }

    @Test
    void testDomainMaxLevelIs3() {
        // Max level is 3 due to example2.E depending on classes at level 2
        assertEquals(3, rootNode.getMaxLevel(), "Max level should be 3 (com.example2.E at L3)");
    }

    @Test
    void testDomainLevel0HasAtLeastOneElement() {
        assertTrue(rootNode.getNodesAtLevel(0).size() >= 1, 
            "Level 0 should have at least 1 element (Class A)");
    }

    @Test
    void testDomainLevel1HasAtLeastOneElement() {
        assertTrue(rootNode.getNodesAtLevel(1).size() >= 1, 
            "Level 1 should have at least 1 element (Class B)");
    }

    @Test
    void testDomainLevel2HasAtLeastOneElement() {
        assertTrue(rootNode.getNodesAtLevel(2).size() >= 1, 
            "Level 2 should have at least 1 element (Class C)");
    }

    @Test
    void testDomainHasCorrectLevelStructure() {
        // Verify the expected structure with SCC-aware logic:
        // Level 0: A, X, D, com.example1
        // Level 1: B (example), B (example2), C (example2)
        // Level 2: C (example), A (example2), com.example
        // Level 3: E, com.example2, com
        
        assertTrue(rootNode.getLevelCount() >= 4, 
            "Should have at least 4 levels (0, 1, 2, 3)");
        assertTrue(rootNode.getNodesAtLevel(0).size() >= 2, 
            "Level 0 should have at least 2 elements (A, X, D)");
    }

    @Test
    void testDomainPackagesCorrectlyPlaced() {
        // Package com.example should be in level 2 (max class level = C at L2)
        boolean comExampleLevel2 = rootNode.getNodesAtLevel(2).stream()
            .anyMatch(e -> "com.example".equals(e.getFullName()) && e.getType() == NodeType.PACKAGE);
        assertTrue(comExampleLevel2, "Package com.example should be in level 2 (max class level = C)");
        
        // Package com should be in level 3 (inherits from child com.example2 which is L3)
        boolean comLevel3 = rootNode.getNodesAtLevel(3).stream()
            .anyMatch(e -> "com".equals(e.getFullName()) && e.getType() == NodeType.PACKAGE);
        assertTrue(comLevel3, "Package com should be in level 3 (inherits max level of children)");
    }

    @Test
    void testDomainNoClassesInMultipleLevels() {
        // Verify class A is only at level 0
        ArchitectureNode classA = findNodeByName(rootNode, "com.example.A");
        assertNotNull(classA, "Class A should exist");
        assertEquals(0, classA.getLevel(), "Class A should be at level 0");
        
        // Verify class B is only at level 1
        ArchitectureNode classB = findNodeByName(rootNode, "com.example.B");
        assertNotNull(classB, "Class B should exist");
        assertEquals(1, classB.getLevel(), "Class B should be at level 1");
        
        // Verify class C is only at level 2
        ArchitectureNode classC = findNodeByName(rootNode, "com.example.C");
        assertNotNull(classC, "Class C should exist");
        assertEquals(2, classC.getLevel(), "Class C should be at level 2");
    }
    
    // ===== Helper Methods =====
    
    private ArchitectureNode findNodeByName(ArchitectureNode node, String fullName) {
        if (fullName.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findNodeByName(child, fullName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
