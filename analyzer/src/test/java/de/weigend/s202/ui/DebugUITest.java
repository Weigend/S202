package de.weigend.s202.ui;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.ui.model.ArchitectureModelBuilder;
import org.junit.jupiter.api.Test;

/**
 * Debug test to check what happens when loading test JAR
 */
public class DebugUITest {
    @Test
    public void debugUILoading() throws Exception {
        String jarPath = "../test-example/target/test-example-1.0.0.jar";
        System.out.println("\n=== DEBUG UI TEST ===");
        System.out.println("Loading: " + jarPath);
        
        // Step 1: Analyze JAR
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel depModel = analyzer.analyze(jarPath);
        System.out.println("✓ Analyzed JAR. Classes: " + depModel.getAllClasses().size());
        
        // Step 2: Calculate levels
        LevelCalculator calculator = new LevelCalculator();
        DomainModel domainModel = calculator.calculate(depModel);
        System.out.println("✓ Calculated levels. Domain model size: " + domainModel.getAllClasses().size());
        
        // Step 3: Build UIModel
        de.weigend.s202.ui.model.UIModelBuilder uiBuilder = new de.weigend.s202.ui.model.UIModelBuilder();
        de.weigend.s202.ui.model.UIModel uiModel = uiBuilder.build(domainModel);
        System.out.println("✓ Built UIModel. Levels: " + uiModel.getLevelCount());
        
        // Step 4: Trace through UIModel by level
        System.out.println("\nElements by level:");
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            java.util.List<de.weigend.s202.ui.model.UIModel.UIElementInfo> elements = uiModel.getElementsAtLevel(level);
            System.out.println("  Level " + level + ": " + elements.size() + " elements");
            for (de.weigend.s202.ui.model.UIModel.UIElementInfo elem : elements) {
                System.out.println("    - " + elem.fullName + " (type=" + elem.type + ")");
            }
        }
    }
    
    static void traceNode(ArchitectureModelBuilder.ArchitectureNode node, int depth) {
        String indent = "  ".repeat(depth);
        System.out.println(indent + "- " + node.getFullName() + " (type=" + node.getType() + ", layer=" + node.getLayer() + ", children=" + node.getChildren().size() + ")");
        
        if (depth < 4 && !node.getChildren().isEmpty()) {
            for (ArchitectureModelBuilder.ArchitectureNode child : node.getChildren()) {
                traceNode(child, depth + 1);
            }
        }
    }
}
