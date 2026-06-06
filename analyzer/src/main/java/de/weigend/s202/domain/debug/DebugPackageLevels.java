/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.domain.debug;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.LanguageAnalyzer;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.StronglyConnectedComponent;
import io.softwareecg.wfx.lookup.api.Lookup;

import java.nio.file.Path;
import java.util.*;

/**
 * Debug tool to verify that package levels are correctly calculated
 */
public class DebugPackageLevels {
    public static void main(String[] args) throws Exception {
        String jarPath = args.length > 0 ? args[0] : "../test-example/target/test-example-1.0.0.jar";
        
        System.out.println("=== ANALYZING JAR: " + jarPath + " ===\n");
        
        // Step 1: Analyze bytecode
        DependencyModel rawModel = javaBytecodeAnalyzer().analyze(List.of(Path.of(jarPath)));
        
        // Step 2: Calculate levels
        LevelCalculator calculator = new LevelCalculator();
        System.out.println("[DEBUG] Before level calculation...");
        DomainModel model = calculator.calculate(rawModel);
        
        System.out.println("\n=== FINAL RESULTS ===\n");
        System.out.println("Package Levels:");
        for (Map.Entry<String, DomainModel.CalculatedElementInfo> entry : model.getAllPackages().entrySet()) {
            String packageName = entry.getKey();
            int level = entry.getValue().architectureLevel;
            Set<String> deps = entry.getValue().dependencies;
            System.out.println("  " + packageName + " -> L" + level + " (depends on: " + deps + ")");
        }
        
        System.out.println("\nClass Levels:");
        TreeMap<String, DomainModel.CalculatedElementInfo> sortedClasses = 
            new TreeMap<>(model.getAllClasses());
        for (Map.Entry<String, DomainModel.CalculatedElementInfo> entry : sortedClasses.entrySet()) {
            String className = entry.getKey();
            int level = entry.getValue().architectureLevel;
            Set<String> deps = entry.getValue().dependencies;
            System.out.println("  " + className + " -> L" + level);
            // Show dependencies for key classes
            if (className.contains("S202Main") || className.contains("ArchitectureView")) {
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
        
        List<StronglyConnectedComponent> sccs = SCCFinder.defaultFinder().findSCCs(classDeps);
        
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
        String[] keyClasses = {"de.weigend.s202.ui.wfx.S202Main", "de.weigend.s202.ui.ArchitectureView"};
        for (String className : keyClasses) {
            DomainModel.CalculatedElementInfo info = model.getClass(className);
            if (info != null) {
                System.out.println("\n" + className + " (L" + info.architectureLevel + "):");
                System.out.println("  Internal dependencies with levels:");
                for (String dep : info.dependencies) {
                    DomainModel.CalculatedElementInfo depInfo = model.getClass(dep);
                    if (depInfo != null) {
                        System.out.println("    -> " + dep + " (L" + depInfo.architectureLevel + ")");
                    }
                }
            }
        }
    }

    private static LanguageAnalyzer javaBytecodeAnalyzer() {
        Lookup.init();
        return Lookup.lookupAll(LanguageAnalyzer.class).stream()
                .filter(analyzer -> "Java bytecode".equals(analyzer.displayName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Java bytecode analyzer registered"));
    }
}
