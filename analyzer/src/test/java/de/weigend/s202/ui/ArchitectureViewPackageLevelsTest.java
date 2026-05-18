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
        
        // architectureLevel = honest dependency-chain depth, parent->child
        // edges included. com aggregates its descendants' outgoing deps, so
        // it's no longer artificially at L0 — it sits at L1 because
        // com.example2 carries deps into com.example/com.example1 which
        // propagate up to the com node as well.
        assertEquals(1, domainModel.getPackage("com").architectureLevel,
                "com aggregates outgoing deps from its descendants → L1");
        assertEquals(0, domainModel.getPackage("com.example").architectureLevel, "com.example has no cross-pkg deps → L0");
        assertEquals(0, domainModel.getPackage("com.example1").architectureLevel);
        assertEquals(1, domainModel.getPackage("com.example2").architectureLevel, "com.example2 depends on com.example → package L1");

        // ArchitectureNode tree uses localLevel now: it's the layout
        // position within the parent box, not the global dep-chain depth.
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        ArchitectureNode rootNode = builder.build(domainModel);

        ArchitectureNode example2Node = findNodeByName(rootNode, "com.example2");
        assertNotNull(example2Node, "com.example2 should be found");
        assertEquals(NodeType.PACKAGE, example2Node.getType(), "com.example2 should be a PACKAGE");
        // Within com's box, example2 sits one layer above example/example1
        // (which it depends on) — local layer index 1.
        assertEquals(1, example2Node.getLevel(), "com.example2 local layer 1");
        
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
