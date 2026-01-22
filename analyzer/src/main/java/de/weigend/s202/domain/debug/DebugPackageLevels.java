package de.weigend.s202.domain.debug;

import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.analysis.scc.StronglyConnectedComponent;

import java.util.*;

/**
 * Debug tool to verify that package levels are correctly calculated
 */
public class DebugPackageLevels {
    public static void main(String[] args) throws Exception {
        String jarPath = args.length > 0 ? args[0] : "../test-example/target/test-example-1.0.0.jar";
        
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
            Set<String> deps = entry.getValue().dependencies;
            System.out.println("  " + className + " -> L" + level);
            // Show dependencies for key classes
            if (className.contains("AnalyzerApplication") || className.contains("ArchitectureView")) {
                System.out.println("      dependencies: " + deps);
            }
        }
        
        // Step 3: Find SCCs in class dependencies
        System.out.println("\n=== SCC ANALYSIS ===");
        Map<String, Set<String>> classDeps = new HashMap<>();
        for (DomainModel.CalculatedElementInfo classInfo : model.getAllClasses().values()) {
            // Filter to only internal dependencies
            Set<String> internalDeps = new HashSet<>();
            for (String dep : classInfo.dependencies) {
                if (model.getClass(dep) != null) {
                    internalDeps.add(dep);
                }
            }
            classDeps.put(classInfo.fullName, internalDeps);
        }
        
        TarjanSCCFinder finder = new TarjanSCCFinder(classDeps);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        
        System.out.println("Found " + sccs.size() + " SCCs");
        for (StronglyConnectedComponent scc : sccs) {
            if (scc.getSize() > 1) {
                System.out.println("\nSCC with " + scc.getSize() + " members:");
                for (String member : scc.getMembers()) {
                    System.out.println("  - " + member);
                }
            }
        }
        
        // Debug: Show dependencies of ArchitectureView in detail
        System.out.println("\n=== DEPENDENCY CHAIN ANALYSIS ===");
        String[] keyClasses = {"de.weigend.s202.ui.AnalyzerApplication", "de.weigend.s202.ui.ArchitectureView"};
        for (String className : keyClasses) {
            DomainModel.CalculatedElementInfo info = model.getClass(className);
            if (info != null) {
                System.out.println("\n" + className + " (L" + info.level + "):");
                System.out.println("  Internal dependencies with levels:");
                for (String dep : info.dependencies) {
                    DomainModel.CalculatedElementInfo depInfo = model.getClass(dep);
                    if (depInfo != null) {
                        System.out.println("    -> " + dep + " (L" + depInfo.level + ")");
                    }
                }
            }
        }
    }
}
