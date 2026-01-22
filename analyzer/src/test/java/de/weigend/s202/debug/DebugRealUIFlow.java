package de.weigend.s202.debug;

import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;

/**
 * Simulates exactly what AnalyzerApplication does when you load a JAR.
 */
public class DebugRealUIFlow {
    public static void main(String[] args) throws Exception {
        System.err.println("[DEBUG] Starting real UI flow simulation...\n");
        
        // Load test JAR
        String testJarPath = "/home/johannes/Programieren/Structure202/test-example/target/test-example-1.0.0.jar";
        
        System.err.println("[DEBUG] Step 1: InputAnalyzer.analyze()");
        InputAnalyzer inputAnalyzer = new InputAnalyzer();
        DependencyModel rawModel = inputAnalyzer.analyze(testJarPath);
        System.err.println("[DEBUG]   Raw packages: " + rawModel.getAllPackageNames().size());
        System.err.println("[DEBUG]   Raw classes: " + rawModel.getAllClasses().size());
        
        System.err.println("\n[DEBUG] Step 2: LevelCalculator.calculate()");
        LevelCalculator levelCalculator = new LevelCalculator();
        DomainModel calculatedModel = levelCalculator.calculate(rawModel);
        System.err.println("[DEBUG]   Calculated packages: " + calculatedModel.getAllPackages().size());
        System.err.println("[DEBUG]   Packages with levels:");
        for (DomainModel.CalculatedElementInfo pkg : calculatedModel.getAllPackages().values()) {
            System.err.println("[DEBUG]     - " + pkg.fullName + " -> L" + pkg.level);
        }
        
        System.err.println("\n[DEBUG] Step 3: ArchitectureNodeBuilder.build()");
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        ArchitectureNode rootNode = builder.build(calculatedModel);
        System.err.println("[DEBUG]   Total nodes: " + rootNode.getTotalNodeCount());
        System.err.println("[DEBUG]   Level count: " + rootNode.getLevelCount());
        
        System.err.println("\n[DEBUG] Step 4: Check what ArchitectureView would receive");
        System.err.println("[DEBUG]   Package nodes:");
        printPackageNodes(rootNode, "");
        
        System.err.println("\n[DEBUG] FINAL RESULT:");
        System.err.println("[DEBUG]   Expected com.example2 at level 1");
        ArchitectureNode example2 = findNodeByName(rootNode, "com.example2");
        if (example2 != null) {
            System.err.println("[DEBUG]   FOUND: " + example2.getFullName() + " at level " + example2.getLevel());
        } else {
            System.err.println("[DEBUG]   ERROR: com.example2 not found in ArchitectureNode tree!");
        }
    }
    
    private static void printPackageNodes(ArchitectureNode node, String indent) {
        if (node.getType() == NodeType.PACKAGE && !"root".equals(node.getFullName())) {
            System.err.println("[DEBUG]     " + indent + node.getFullName() + " (level=" + node.getLevel() + ")");
        }
        for (ArchitectureNode child : node.getChildren()) {
            printPackageNodes(child, indent + "  ");
        }
    }
    
    private static ArchitectureNode findNodeByName(ArchitectureNode node, String fullName) {
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
