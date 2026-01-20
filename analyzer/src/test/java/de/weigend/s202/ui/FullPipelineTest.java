package de.weigend.s202.ui;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.ui.model.UIModel;
import de.weigend.s202.ui.model.UIModelBuilder;

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
        
        assertEquals(4, calculatedModel.getAllPackages().size(), "Should have 4 packages");
        
        // Verify com.example2 is at Level 1
        DomainModel.CalculatedElementInfo example2 = calculatedModel.getPackage("com.example2");
        assertNotNull(example2, "com.example2 should exist");
        assertEquals(1, example2.level, "com.example2 should be at level 1");
        
        // Step 3: Build UI model
        UIModelBuilder builder = new UIModelBuilder();
        UIModel uiModel = builder.build(calculatedModel);
        
        System.out.println("[TEST] After UIModelBuilder:");
        System.out.println("  Total elements: " + uiModel.getTotalElementCount());
        System.out.println("  Level count: " + uiModel.getLevelCount());
        
        // Verify packages are in UIModel
        boolean foundExample2 = false;
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            for (UIModel.UIElementInfo elem : uiModel.getElementsAtLevel(level)) {
                if ("PACKAGE".equals(elem.type) && elem.fullName.equals("com.example2")) {
                    System.out.println("[TEST] Found com.example2 in UIModel at level " + level + " with elem.level=" + elem.level);
                    foundExample2 = true;
                    assertEquals(1, elem.level, "com.example2 should have level 1 in UIModel");
                    assertEquals(1, level, "com.example2 should be at UIModel level 1");
                }
            }
        }
        
        assertTrue(foundExample2, "com.example2 should be in UIModel");
    }
}
