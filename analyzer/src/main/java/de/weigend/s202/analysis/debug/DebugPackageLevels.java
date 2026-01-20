package de.weigend.s202.analysis.debug;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.domain.DomainModel;

import java.util.*;

/**
 * Debug tool to verify that package levels are correctly calculated
 */
public class DebugPackageLevels {
    public static void main(String[] args) throws Exception {
        String jarPath = "../test-example/target/test-example-1.0.0.jar";
        
        System.out.println("=== ANALYZING JAR: " + jarPath + " ===\n");
        
        // Step 1: Analyze bytecode
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(jarPath);
        
        // Step 2: Calculate levels
        LevelCalculator calculator = new LevelCalculator();
        System.out.println("[DEBUG] Before level calculation...");
        DomainModel model = calculator.calculate(rawModel);
        
        System.out.println("\n=== FINAL RESULTS ===\n");
        System.out.println("Package Levels:");
        for (Map.Entry<String, DomainModel.CalculatedElementInfo> entry : model.getAllPackages().entrySet()) {
            String packageName = entry.getKey();
            int level = entry.getValue().level;
            Set<String> deps = entry.getValue().dependencies;
            System.out.println("  " + packageName + " -> L" + level + " (depends on: " + deps + ")");
        }
        
        System.out.println("\nClass Levels:");
        TreeMap<String, DomainModel.CalculatedElementInfo> sortedClasses = 
            new TreeMap<>(model.getAllClasses());
        for (Map.Entry<String, DomainModel.CalculatedElementInfo> entry : sortedClasses.entrySet()) {
            String className = entry.getKey();
            int level = entry.getValue().level;
            System.out.println("  " + className + " -> L" + level);
        }
    }
}
