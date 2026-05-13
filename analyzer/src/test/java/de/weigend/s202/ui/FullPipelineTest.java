package de.weigend.s202.ui;

import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FullPipelineTest {
    
    @Test
    public void testPackageLevelsPropagateToUI() throws Exception {
        // Step 1: Analyze bytecode
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze("../test-example/target/test-example-1.0.0.jar");
        
        System.out.println("[TEST] Raw model packages: " + rawModel.getAllPackageNames());
        
        // Step 2: Calculate levels
        LevelCalculator calculator = new LevelCalculator();
        DomainModel calculatedModel = calculator.calculate(rawModel);
        
        System.out.println("[TEST] After calculate:");
        System.out.println("  Packages in model: " + calculatedModel.getAllPackages().size());
        for (DomainModel.CalculatedElementInfo pkg : calculatedModel.getAllPackages().values()) {
            System.out.println("    " + pkg.fullName + " -> L" + pkg.level);
        }
        
        // 4 com.* packages + 7 sccs.* packages (adversarial SCC example)
        assertEquals(11, calculatedModel.getAllPackages().size(), "Should have 11 packages");
        
        // com.example2 depends on com.example(0) and com.example1(0) → package level 1
        DomainModel.CalculatedElementInfo example2 = calculatedModel.getPackage("com.example2");
        assertNotNull(example2, "com.example2 should exist");
        assertEquals(1, example2.level, "com.example2 uses com.example.B (class L1) → package L2");
        
        // Step 3: Build architecture node tree
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        ArchitectureNode rootNode = builder.build(calculatedModel);
        
        System.out.println("[TEST] After ArchitectureNodeBuilder:");
        System.out.println("  Total nodes: " + rootNode.getTotalNodeCount());
        System.out.println("  Level count: " + rootNode.getLevelCount());
        
        // Verify packages are in ArchitectureNode tree
        ArchitectureNode example2Node = findNodeByName(rootNode, "com.example2");
        assertNotNull(example2Node, "com.example2 should be in ArchitectureNode tree");
        assertEquals(NodeType.PACKAGE, example2Node.getType(), "com.example2 should be a PACKAGE");
        assertEquals(1, example2Node.getLevel(), "com.example2 package level 1");
        
        System.out.println("[TEST] Found com.example2 in ArchitectureNode at level " + example2Node.getLevel());
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
