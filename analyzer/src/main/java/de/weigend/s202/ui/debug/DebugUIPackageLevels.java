package de.weigend.s202.ui.debug;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;

import java.io.File;
import java.util.*;

/**
 * Debug test to verify that package levels are correctly propagated to ArchitectureNode tree
 */
public class DebugUIPackageLevels {
    public static void main(String[] args) throws Exception {
        String jarPath = args.length > 0 ? args[0] : "test-example/target/test-example-1.0.0.jar";
        
        System.out.println("=== TESTING UI PIPELINE WITH: " + jarPath + " ===\n");
        
        // Step 1: Analyze
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(jarPath);
        
        // Step 2: Calculate levels
        LevelCalculator calculator = new LevelCalculator();
        DomainModel domainModel = calculator.calculate(rawModel);
        
        System.out.println("DomainModel packages:");
        for (DomainModel.CalculatedElementInfo pkg : domainModel.getAllPackages().values()) {
            System.out.println("  " + pkg.fullName + " -> L" + pkg.level);
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
}
