package de.weigend.s202.ui;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that verifies package levels are correctly displayed in the UI
 */
public class ArchitectureViewPackageLevelsTest {

    @Test
    public void testPackageLevelsInArchitectureNode() throws Exception {
        String jarPath = "../test-example/target/test-example-1.0.0.jar";
        
        // Step 1: Analyze
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(jarPath);
        
        // Step 2: Calculate levels
        LevelCalculator calculator = new LevelCalculator();
        DomainModel domainModel = calculator.calculate(rawModel);
        
        // Verify DomainModel has correct levels
        assertEquals(0, domainModel.getPackage("com").level);
        assertEquals(0, domainModel.getPackage("com.example").level);
        assertEquals(0, domainModel.getPackage("com.example1").level);
        assertEquals(1, domainModel.getPackage("com.example2").level);
        
        // Step 3: Build architecture node tree
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        ArchitectureNode rootNode = builder.build(domainModel);
        
        // Verify ArchitectureNode has correct levels
        ArchitectureNode example2Node = findNodeByName(rootNode, "com.example2");
        assertNotNull(example2Node, "com.example2 should be found");
        assertEquals(NodeType.PACKAGE, example2Node.getType(), "com.example2 should be a PACKAGE");
        assertEquals(1, example2Node.getLevel(), "com.example2 should have level 1");
        
        System.out.println("Found com.example2 at level " + example2Node.getLevel());
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
