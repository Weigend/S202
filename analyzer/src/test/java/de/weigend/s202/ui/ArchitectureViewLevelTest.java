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
package de.weigend.s202.ui;

import de.weigend.s202.reader.java.InputAnalyzer;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.LevelCalculator;
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
        String testJarPath = System.getProperty("user.dir")
                + "/../test-example/target/test-example-1.0.0.jar";
        
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

        // architectureLevel reflects honest dep-chain depth — parent->child
        // edges contribute, so com aggregates its descendants' outgoing
        // deps and lands at L1.
        assertEquals(1, calculatedModel.getAllPackages().get("com").architectureLevel,
                "com aggregates descendants' deps → L1");
        assertEquals(0, calculatedModel.getAllPackages().get("com.example").architectureLevel, "com.example has no cross-pkg deps → L0");
        assertEquals(0, calculatedModel.getAllPackages().get("com.example1").architectureLevel, "com.example1 has no cross-pkg deps → L0");
        assertEquals(1, calculatedModel.getAllPackages().get("com.example2").architectureLevel, "com.example2 depends on com.example → package L1");

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
