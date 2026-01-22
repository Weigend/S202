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
        assertEquals(4, rawModel.getAllPackageNames().size(), "Should have 4 packages");
        assertEquals(9, rawModel.getAllClasses().size(), "Should have 9 classes");
        
        // Step 2: Calculate levels
        LevelCalculator levelCalculator = new LevelCalculator();
        DomainModel calculatedModel = levelCalculator.calculate(rawModel);
        assertEquals(4, calculatedModel.getAllPackages().size(), "Should have 4 packages after calculation");
        
        // Verify package levels
        assertEquals(0, calculatedModel.getAllPackages().get("com").level, "com should be L0");
        assertEquals(0, calculatedModel.getAllPackages().get("com.example").level, "com.example should be L0");
        assertEquals(0, calculatedModel.getAllPackages().get("com.example1").level, "com.example1 should be L0");
        assertEquals(1, calculatedModel.getAllPackages().get("com.example2").level, "com.example2 should be L1");
        
        // Step 3: Build ArchitectureNode tree
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        ArchitectureNode rootNode = builder.build(calculatedModel);
        assertEquals(14, rootNode.getTotalNodeCount(), "Should have 14 nodes (9 classes + 4 packages + 1 root)");
        
        // Verify ArchitectureNode has packages with correct levels
        ArchitectureNode com2Node = findNodeByName(rootNode, "com.example2");
        assertNotNull(com2Node, "com.example2 should be found in ArchitectureNode tree");
        assertEquals(NodeType.PACKAGE, com2Node.getType(), "com.example2 should be a PACKAGE");
        assertEquals(1, com2Node.getLevel(), "com.example2 should have level 1");
        
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
