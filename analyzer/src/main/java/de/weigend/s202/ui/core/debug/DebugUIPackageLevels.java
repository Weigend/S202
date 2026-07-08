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
package de.weigend.s202.ui.core.debug;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.LanguageAnalyzer;
import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import io.softwareecg.wfx.lookup.api.Lookup;

import java.nio.file.Path;
import java.util.List;

/**
 * Debug test to verify that package levels are correctly propagated to ArchitectureNode tree
 */
public class DebugUIPackageLevels {
    public static void main(String[] args) throws Exception {
        String jarPath = args.length > 0 ? args[0] : "test-example/target/test-example-1.0.0.jar";
        
        System.out.println("=== TESTING UI PIPELINE WITH: " + jarPath + " ===\n");
        
        // Step 1: Analyze
        DependencyModel rawModel = javaBytecodeAnalyzer().analyze(List.of(Path.of(jarPath)));
        
        // Step 2: Calculate levels
        DomainModel domainModel = domainComputer().compute(rawModel);
        
        System.out.println("DomainModel packages:");
        for (DomainModel.CalculatedElementInfo pkg : domainModel.getAllPackages().values()) {
            System.out.println("  " + pkg.fullName + " -> L" + pkg.architectureLevel);
        }
        
        // Step 3: Build architecture node tree
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        ArchitectureNode rootNode = builder.build(domainModel);
        
        System.out.println("\nArchitectureNode tree (packages only):");
        printPackageNodes(rootNode, "");
    }
    
    private static void printPackageNodes(ArchitectureNode node, String indent) {
        if (node.getType() == NodeType.PACKAGE && !"root".equals(node.getFullName())) {
            System.out.println(indent + "- " + node.getFullName() + " (L" + node.getLevel() + ")");
        }
        for (ArchitectureNode child : node.getChildren()) {
            printPackageNodes(child, indent + "  ");
        }
    }

    private static LanguageAnalyzer javaBytecodeAnalyzer() {
        Lookup.init();
        return Lookup.lookupAll(LanguageAnalyzer.class).stream()
                .filter(analyzer -> "Java bytecode".equals(analyzer.displayName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Java bytecode analyzer registered"));
    }

    private static DomainComputer domainComputer() {
        DomainComputer computer = Lookup.lookup(DomainComputer.class);
        if (computer == null) {
            throw new IllegalStateException("No domain computer registered");
        }
        return computer;
    }
}
