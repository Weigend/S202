package de.weigend.s202.domain.debug;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.reader.DependencyModel;

public class TestLevelCalculatorDebug {
    public static void main(String[] args) throws Exception {
        String jarPath = "../test-example/target/test-example-1.0.0.jar";
        
        System.out.println("=== TESTING LEVEL CALCULATOR ===\n");
        
        // Step 1: Analyze
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(jarPath);
        System.out.println("Raw packages: " + rawModel.getAllPackageNames());
        
        // Step 2: Calculate
        LevelCalculator calc = new LevelCalculator();
        DomainModel domainModel = calc.calculate(rawModel);
        
        System.out.println("\n=== FINAL RESULT ===");
        System.out.println("DomainModel packages: " + domainModel.getAllPackages().size());
        for (DomainModel.CalculatedElementInfo pkg : domainModel.getAllPackages().values()) {
            System.out.println("  " + pkg.fullName + " -> L" + pkg.level);
        }
    }
}
