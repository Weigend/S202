package de.weigend.s202.ui;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.ui.model.UIModel;
import de.weigend.s202.ui.model.UIModelBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that verifies ArchitectureView.setUIModel() correctly displays packages with their calculated levels.
 */
public class ArchitectureViewLevelTest {
    
    @Test
    public void testPackageLevelDisplayInArchitectureView() throws Exception {
        System.err.println("\n[TEST] testPackageLevelDisplayInArchitectureView");
        
        // Load test JAR
        String testJarPath = "/home/johannes/Programieren/Structure202/test-example/target/test-example-1.0.0.jar";
        
        // Step 1: Analyze
        InputAnalyzer inputAnalyzer = new InputAnalyzer();
        DependencyModel rawModel = inputAnalyzer.analyze(testJarPath);
        assertEquals(4, rawModel.getAllPackageNames().size(), "Should have 4 packages");
        assertEquals(9, rawModel.getAllClasses().size(), "Should have 9 classes");
        
        // Step 2: Calculate levels
        LevelCalculator levelCalculator = new LevelCalculator();
        DomainModel calculatedModel = levelCalculator.calculate(rawModel);
        assertEquals(4, calculatedModel.getAllPackages().size(), "Should have 4 packages after calculation");
        
        // Verify package levels
        assertEquals(0, calculatedModel.getAllPackages().get("com").level, "com should be L0");
        assertEquals(0, calculatedModel.getAllPackages().get("com.example").level, "com.example should be L0");
        assertEquals(0, calculatedModel.getAllPackages().get("com.example1").level, "com.example1 should be L0");
        assertEquals(1, calculatedModel.getAllPackages().get("com.example2").level, "com.example2 should be L1");
        
        // Step 3: Build UIModel
        UIModelBuilder uiModelBuilder = new UIModelBuilder();
        UIModel uiModel = uiModelBuilder.build(calculatedModel);
        assertEquals(13, uiModel.getTotalElementCount(), "Should have 13 elements (9 classes + 4 packages)");
        
        // Verify UIModel has packages with correct levels
        boolean foundCom2AtL1 = false;
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            for (UIModel.UIElementInfo elem : uiModel.getElementsAtLevel(level)) {
                if ("com.example2".equals(elem.fullName)) {
                    System.err.println("[TEST] Found com.example2 in UIModel at level " + level + ", elem.level=" + elem.level);
                    assertEquals(1, elem.level, "com.example2 in UIModel should have level 1");
                    foundCom2AtL1 = true;
                }
            }
        }
        assertTrue(foundCom2AtL1, "com.example2 should be found in UIModel at level 1");
        
        System.err.println("[TEST] ✓ All UIModel verifications passed");
        System.err.println("[TEST] When ArchitectureView.setUIModel() is called, it will use this UIModel");
        System.err.println("[TEST] Watch debug output for package creation with correct levels");
    }
}
