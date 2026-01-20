package de.weigend.s202.analysis.debug;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.ui.model.UIModel;
import de.weigend.s202.ui.model.UIModelBuilder;

import java.io.File;
import java.util.*;

/**
 * Debug test to verify that package levels are correctly propagated to UIModel
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
        
        // Step 3: Build UI model
        UIModelBuilder builder = new UIModelBuilder();
        UIModel uiModel = builder.build(domainModel);
        
        System.out.println("\nUIModel levels:");
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            System.out.println("  Level " + level + " (" + uiModel.getElementsAtLevel(level).size() + " elements):");
            for (UIModel.UIElementInfo elem : uiModel.getElementsAtLevel(level)) {
                if ("PACKAGE".equals(elem.type)) {
                    System.out.println("    - " + elem.fullName + " (L" + elem.level + ")");
                }
            }
        }
    }
}
