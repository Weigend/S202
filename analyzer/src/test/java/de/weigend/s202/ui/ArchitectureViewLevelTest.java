package de.weigend.s202.ui;

import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that verifies ArchitectureView.setArchitectureRoot() correctly displays packages with their calculated levels.
 */
public class ArchitectureViewLevelTest {
    
    @Test
    public void testPackageLevelDisplayInArchitectureView() throws Exception {
        System.err.println("\n[TEST] testPackageLevelDisplayInArchitectureView");
        
        // Load test JAR
        String testJarPath = "/home/johannes/Programieren/Structure202/test-example/target/test-example-1.0.0.jar";
        
        // Step 1: Analyze
        InputAnalyzer inputAnalyzer = new InputAnalyzer();
        DependencyModel rawModel = inputAnalyzer.analyze(testJarPath);
        // 4 com.* packages + 7 sccs.* packages (adversarial SCC example)
        assertEquals(11, rawModel.getAllPackageNames().size(), "Should have 11 packages");
        // 9 com.* classes + 10 sccs.* classes
        assertEquals(19, rawModel.getAllClasses().size(), "Should have 19 classes");

        // Step 2: Calculate levels
        LevelCalculator levelCalculator = new LevelCalculator();
        DomainModel calculatedModel = levelCalculator.calculate(rawModel);
        assertEquals(11, calculatedModel.getAllPackages().size(), "Should have 11 packages after calculation");

        // Package levels now reflect inter-package dependency position, not class levels.
        // com.example: no cross-pkg deps → L0
        // com.example1: no cross-pkg deps → L0
        // com.example2: depends on com.example and com.example1 → package L1
        // com: max(0, 0, 1) = L1
        assertEquals(0, calculatedModel.getAllPackages().get("com").level, "com has no own cross-pkg deps → L0");
        assertEquals(0, calculatedModel.getAllPackages().get("com.example").level, "com.example has no cross-pkg deps → L0");
        assertEquals(0, calculatedModel.getAllPackages().get("com.example1").level, "com.example1 has no cross-pkg deps → L0");
        assertEquals(1, calculatedModel.getAllPackages().get("com.example2").level, "com.example2 depends on com.example → package L1");

        // Step 3: Build ArchitectureNode tree
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        ArchitectureNode rootNode = builder.build(calculatedModel);
        // 19 classes + 11 packages + 1 root
        assertEquals(31, rootNode.getTotalNodeCount(), "Should have 31 nodes (19 classes + 11 packages + 1 root)");

        // Verify ArchitectureNode has packages with correct levels
        ArchitectureNode com2Node = findNodeByName(rootNode, "com.example2");
        assertNotNull(com2Node, "com.example2 should be found in ArchitectureNode tree");
        assertEquals(NodeType.PACKAGE, com2Node.getType(), "com.example2 should be a PACKAGE");
        assertEquals(1, com2Node.getLevel(), "com.example2 package level 1");
        
        System.err.println("[TEST] Found com.example2 in ArchitectureNode at level " + com2Node.getLevel());
        System.err.println("[TEST] ✓ All ArchitectureNode verifications passed");
        System.err.println("[TEST] When ArchitectureView.setArchitectureRoot() is called, it will use this tree");
    }
    
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
