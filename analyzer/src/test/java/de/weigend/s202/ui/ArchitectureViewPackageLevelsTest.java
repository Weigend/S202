package de.weigend.s202.ui;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.ui.model.UIModel;
import de.weigend.s202.ui.model.UIModelBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that verifies package levels are correctly displayed in the UI
 */
public class ArchitectureViewPackageLevelsTest {

    @Test
    public void testPackageLevelsInUIModel() throws Exception {
        String jarPath = "../test-example/target/test-example-1.0.0.jar";
        
        // Step 1: Analyze
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(jarPath);
        
        // Step 2: Calculate levels
        LevelCalculator calculator = new LevelCalculator();
        DomainModel domainModel = calculator.calculate(rawModel);
        
        // Verify DomainModel has correct levels
        assertEquals(0, domainModel.getPackage("com").level);
        assertEquals(0, domainModel.getPackage("com.example").level);
        assertEquals(0, domainModel.getPackage("com.example1").level);
        assertEquals(1, domainModel.getPackage("com.example2").level);
        
        // Step 3: Build UI model
        UIModelBuilder builder = new UIModelBuilder();
        UIModel uiModel = builder.build(domainModel);
        
        // Verify UIModel has correct levels
        // Find com.example2 and verify it's at level 1
        boolean found_example2_at_level1 = false;
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            for (UIModel.UIElementInfo elem : uiModel.getElementsAtLevel(level)) {
                if ("com.example2".equals(elem.fullName)) {
                    System.out.println("Found com.example2 at UIModel level " + level + " with elem.level=" + elem.level);
                    assertEquals(1, elem.level, "com.example2 should have level 1");
                    assertEquals(1, level, "com.example2 should be at UIModel level 1");
                    found_example2_at_level1 = true;
                }
            }
        }
        assertTrue(found_example2_at_level1, "com.example2 should be found at level 1");
    }
}
