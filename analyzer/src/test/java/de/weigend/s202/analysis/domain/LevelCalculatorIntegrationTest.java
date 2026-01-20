package de.weigend.s202.analysis.domain;

import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.input.InputAnalyzer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify package levels are calculated correctly
 * for the test-example JAR with cross-package dependencies
 */
public class LevelCalculatorIntegrationTest {

    @Test
    public void testPackageLevelingWithCrossPackageDependencies() throws Exception {
        // Load the test JAR
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze("../test-example/target/test-example-1.0.0.jar");
        
        // Calculate levels
        LevelCalculator calculator = new LevelCalculator();
        DomainModel model = calculator.calculate(rawModel);
        
        // Verify package levels
        System.out.println("\n=== Package Levels ===");
        for (var entry : model.getAllPackages().entrySet()) {
            System.out.println(entry.getKey() + " = L" + entry.getValue().level);
        }
        
        // Assertions
        DomainModel.CalculatedElementInfo examplePkg = model.getPackage("com.example");
        DomainModel.CalculatedElementInfo example1Pkg = model.getPackage("com.example1");
        DomainModel.CalculatedElementInfo example2Pkg = model.getPackage("com.example2");
        
        assertNotNull(examplePkg, "com.example package should exist");
        assertNotNull(example1Pkg, "com.example1 package should exist");
        assertNotNull(example2Pkg, "com.example2 package should exist");
        
        // com.example and com.example1 have no external dependencies
        assertEquals(0, examplePkg.level, "com.example should be L0 (no external deps)");
        assertEquals(0, example1Pkg.level, "com.example1 should be L0 (no external deps)");
        
        // com.example2 depends on com.example and com.example1
        assertEquals(1, example2Pkg.level, "com.example2 should be L1 (depends on L0 packages)");
    }
}
