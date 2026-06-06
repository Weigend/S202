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

import de.weigend.s202.reader.impl.java.InputAnalyzer;
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
