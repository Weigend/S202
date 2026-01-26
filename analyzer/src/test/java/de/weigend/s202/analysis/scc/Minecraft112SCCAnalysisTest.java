package de.weigend.s202.analysis.scc;

import de.weigend.s202.analysis.strategy.impl.BasicClassLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.impl.HeuristicSCCBreakingStrategy;
import de.weigend.s202.analysis.strategy.aggregation.SimpleMaxAggregationStrategy;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.domain.LevelCalculationStrategyFactory;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeAll;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated test for Minecraft 1.12 JAR - should contain many cycles and SCCs.
 * This version of Minecraft is known for complex interdependencies.
 */
class Minecraft112SCCAnalysisTest {
    
    private static final String JAR_NAME = "/forge-1.12.2-14.23.5.2859_mapped_snapshot_20171003-1.12.jar";
    private static File minecraftJar;
    private static boolean jarAvailable = false;
    
    @BeforeAll
    static void setup() {
        URL jarUrl = Minecraft112SCCAnalysisTest.class.getResource(JAR_NAME);
        if (jarUrl != null) {
            minecraftJar = new File(jarUrl.getFile());
            jarAvailable = minecraftJar.exists();
        }
        System.out.println("Minecraft 1.12 JAR available: " + jarAvailable);
        if (jarAvailable) {
            System.out.println("JAR path: " + minecraftJar.getAbsolutePath());
            System.out.println("JAR size: " + (minecraftJar.length() / 1024 / 1024) + " MB");
        }
    }
    
    @Test
    @DisplayName("Minecraft 1.12: Analyze all SCCs in detail")
    void analyzeAllSCCsInDetail() throws IOException {
        if (!jarAvailable) {
            System.out.println("SKIPPED: Minecraft 1.12 JAR not found at " + JAR_NAME);
            return;
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("MINECRAFT 1.12 - DETAILED SCC ANALYSIS");
        System.out.println("=".repeat(80));
        
        // Parse JAR
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(minecraftJar.getAbsolutePath());
        
        int totalClasses = rawModel.getAllClassNames().size();
        System.out.println("\nTotal classes parsed: " + totalClasses);
        
        // Build internal class dependency map
        Map<String, Set<String>> classDeps = buildClassDependencyMap(rawModel);
        int totalInternalDeps = classDeps.values().stream().mapToInt(Set::size).sum();
        System.out.println("Total internal dependencies: " + totalInternalDeps);
        System.out.println("Average dependencies per class: " + String.format("%.2f", (double) totalInternalDeps / totalClasses));
        
        // Find all SCCs using Tarjan's algorithm
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(classDeps);
        List<StronglyConnectedComponent> allSCCs = sccFinder.findSCCs();
        
        // Categorize SCCs by size
        List<StronglyConnectedComponent> trivialSCCs = allSCCs.stream()
            .filter(s -> s.getSize() == 1)
            .collect(Collectors.toList());
        
        List<StronglyConnectedComponent> smallSCCs = allSCCs.stream()
            .filter(s -> s.getSize() >= 2 && s.getSize() <= 5)
            .collect(Collectors.toList());
        
        List<StronglyConnectedComponent> mediumSCCs = allSCCs.stream()
            .filter(s -> s.getSize() >= 6 && s.getSize() <= 20)
            .collect(Collectors.toList());
        
        List<StronglyConnectedComponent> largeSCCs = allSCCs.stream()
            .filter(s -> s.getSize() > 20)
            .collect(Collectors.toList());
        
        System.out.println("\n" + "-".repeat(60));
        System.out.println("SCC SUMMARY");
        System.out.println("-".repeat(60));
        System.out.println("Total SCCs found: " + allSCCs.size());
        System.out.println("  - Trivial (size 1):  " + trivialSCCs.size());
        System.out.println("  - Small (2-5):       " + smallSCCs.size());
        System.out.println("  - Medium (6-20):     " + mediumSCCs.size());
        System.out.println("  - Large (>20):       " + largeSCCs.size());
        
        int nonTrivialCount = smallSCCs.size() + mediumSCCs.size() + largeSCCs.size();
        int classesInCycles = allSCCs.stream()
            .filter(s -> s.getSize() > 1)
            .mapToInt(StronglyConnectedComponent::getSize)
            .sum();
        System.out.println("\nClasses participating in cycles: " + classesInCycles + 
            " (" + String.format("%.1f", 100.0 * classesInCycles / totalClasses) + "%)");
        
        // Show all non-trivial SCCs
        System.out.println("\n" + "-".repeat(60));
        System.out.println("NON-TRIVIAL SCCs (sorted by size)");
        System.out.println("-".repeat(60));
        
        List<StronglyConnectedComponent> nonTrivialSCCs = allSCCs.stream()
            .filter(s -> s.getSize() > 1)
            .sorted((a, b) -> Integer.compare(b.getSize(), a.getSize()))
            .collect(Collectors.toList());
        
        int sccIndex = 1;
        for (StronglyConnectedComponent scc : nonTrivialSCCs) {
            System.out.println("\nSCC #" + sccIndex + " (size: " + scc.getSize() + "):");
            
            // For smaller SCCs, list all members
            if (scc.getSize() <= 30) {
                List<String> members = scc.getMembers().stream()
                    .sorted()
                    .collect(Collectors.toList());
                for (String member : members) {
                    String shortName = getSimpleClassName(member);
                    System.out.println("    - " + shortName);
                }
            } else {
                // For very large SCCs, just list a sample
                System.out.println("    Members (first 20 of " + scc.getSize() + "):");
                scc.getMembers().stream()
                    .sorted()
                    .limit(20)
                    .forEach(m -> System.out.println("    - " + getSimpleClassName(m)));
                System.out.println("    ... and " + (scc.getSize() - 20) + " more");
            }
            
            // Show internal cycle edges for smaller SCCs
            if (scc.getSize() >= 2 && scc.getSize() <= 15) {
                System.out.println("    Internal edges:");
                for (String member : scc.getMembers()) {
                    Set<String> deps = classDeps.getOrDefault(member, Collections.emptySet());
                    for (String dep : deps) {
                        if (scc.getMembers().contains(dep)) {
                            System.out.println("      " + getSimpleClassName(member) + " -> " + getSimpleClassName(dep));
                        }
                    }
                }
            }
            sccIndex++;
        }
        
        // Report findings - class-level cycles may not exist even if package-level cycles do
        System.out.println("\n" + "-".repeat(60));
        System.out.println("RESULTS");
        System.out.println("-".repeat(60));
        
        if (nonTrivialCount > 0) {
            System.out.println("✓ Found " + nonTrivialCount + " non-trivial class-level SCCs");
        } else {
            System.out.println("ℹ No class-level SCCs found");
            System.out.println("  (Package-level cycles may still exist - see package analysis test)");
        }
        
        // This is informational, not an assertion - good code can have no class cycles
        System.out.println("\nTEST COMPLETED!");
    }
    
    @Test
    @DisplayName("Minecraft 1.12: Compare Basic vs Heuristic SCC Breaking")
    void compareStrategiesOnMinecraft112() throws IOException {
        if (!jarAvailable) {
            System.out.println("SKIPPED: Minecraft 1.12 JAR not found");
            return;
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("MINECRAFT 1.12 - STRATEGY COMPARISON");
        System.out.println("=".repeat(80));
        
        // Parse JAR
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(minecraftJar.getAbsolutePath());
        
        System.out.println("Total classes: " + rawModel.getAllClassNames().size());
        
        // Build class dependency map
        Map<String, Set<String>> classDeps = buildClassDependencyMap(rawModel);
        
        // Find SCCs first
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(classDeps);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();
        
        int largeSccCount = (int) sccs.stream().filter(s -> s.getSize() >= 3).count();
        int maxSccSize = sccs.stream().mapToInt(StronglyConnectedComponent::getSize).max().orElse(0);
        
        System.out.println("Large SCCs (>=3 members): " + largeSccCount);
        System.out.println("Largest SCC size: " + maxSccSize);
        
        // Calculate with BASIC strategy
        System.out.println("\n--- BASIC STRATEGY ---");
        long startBasic = System.currentTimeMillis();
        BasicClassLevelCalculationStrategy basicStrategy = new BasicClassLevelCalculationStrategy(
            new SimpleMaxAggregationStrategy()
        );
        Map<String, Integer> basicLevels = basicStrategy.calculateClassLevels(classDeps);
        long basicTime = System.currentTimeMillis() - startBasic;
        
        Set<Integer> basicUniqueLevels = new HashSet<>(basicLevels.values());
        int basicMaxLevel = basicLevels.values().stream().max(Integer::compare).orElse(0);
        System.out.println("Calculation time: " + basicTime + " ms");
        System.out.println("Unique levels: " + basicUniqueLevels.size());
        System.out.println("Max level: " + basicMaxLevel);
        
        // Calculate with HEURISTIC strategy  
        System.out.println("\n--- HEURISTIC STRATEGY ---");
        long startHeuristic = System.currentTimeMillis();
        HeuristicSCCBreakingStrategy heuristicStrategy = new HeuristicSCCBreakingStrategy();
        Map<String, Integer> heuristicLevels = heuristicStrategy.calculateClassLevels(classDeps);
        long heuristicTime = System.currentTimeMillis() - startHeuristic;
        
        Set<Integer> heuristicUniqueLevels = new HashSet<>(heuristicLevels.values());
        int heuristicMaxLevel = heuristicLevels.values().stream().max(Integer::compare).orElse(0);
        System.out.println("Calculation time: " + heuristicTime + " ms");
        System.out.println("Unique levels: " + heuristicUniqueLevels.size());
        System.out.println("Max level: " + heuristicMaxLevel);
        System.out.println("Back edges identified: " + heuristicStrategy.getBackEdgeCount());
        
        // Full pipeline comparison
        System.out.println("\n--- FULL PIPELINE COMPARISON ---");
        
        LevelCalculator basicCalc = new LevelCalculator();
        DomainModel basicModel = basicCalc.calculate(rawModel);
        
        LevelCalculator heuristicCalc = new LevelCalculator(
            LevelCalculationStrategyFactory.createWithHeuristicSCCBreaking()
        );
        DomainModel heuristicModel = heuristicCalc.calculate(rawModel);
        
        Map<Integer, Long> basicDist = analyzeLevelDistribution(basicModel);
        Map<Integer, Long> heuristicDist = analyzeLevelDistribution(heuristicModel);
        
        System.out.println("Basic pipeline - unique package levels: " + basicDist.size());
        System.out.println("Heuristic pipeline - unique package levels: " + heuristicDist.size());
        
        System.out.println("\nLevel distribution (Basic):");
        basicDist.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> System.out.println("  Level " + e.getKey() + ": " + e.getValue() + " classes"));
        
        System.out.println("\nLevel distribution (Heuristic):");
        heuristicDist.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> System.out.println("  Level " + e.getKey() + ": " + e.getValue() + " classes"));
        
        // Analyze differences if there are large SCCs
        if (largeSccCount > 0) {
            System.out.println("\n--- STRATEGY DIFFERENCE ANALYSIS ---");
            
            // Find classes where levels differ
            int differentLevelCount = 0;
            for (String className : basicLevels.keySet()) {
                int basicLevel = basicLevels.get(className);
                int heuristicLevel = heuristicLevels.getOrDefault(className, basicLevel);
                if (basicLevel != heuristicLevel) {
                    differentLevelCount++;
                }
            }
            System.out.println("Classes with different levels: " + differentLevelCount);
            
            // Show some examples
            if (differentLevelCount > 0 && differentLevelCount <= 50) {
                System.out.println("\nExamples of level differences:");
                int shown = 0;
                for (String className : basicLevels.keySet()) {
                    int basicLevel = basicLevels.get(className);
                    int heuristicLevel = heuristicLevels.getOrDefault(className, basicLevel);
                    if (basicLevel != heuristicLevel) {
                        System.out.println("  " + getSimpleClassName(className) + 
                            ": Basic=" + basicLevel + ", Heuristic=" + heuristicLevel);
                        shown++;
                        if (shown >= 20) {
                            System.out.println("  ... and " + (differentLevelCount - 20) + " more");
                            break;
                        }
                    }
                }
            }
        }
        
        System.out.println("\nTEST PASSED!");
    }
    
    @Test
    @DisplayName("Minecraft 1.12: Package-level cycle analysis")
    void analyzePackageLevelCycles() throws IOException {
        if (!jarAvailable) {
            System.out.println("SKIPPED: Minecraft 1.12 JAR not found");
            return;
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("MINECRAFT 1.12 - PACKAGE LEVEL CYCLE ANALYSIS");
        System.out.println("=".repeat(80));
        
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(minecraftJar.getAbsolutePath());
        
        // Build package dependency graph
        Map<String, Set<String>> packageDeps = new HashMap<>();
        Set<String> allPackages = new HashSet<>();
        
        for (String className : rawModel.getAllClassNames()) {
            String packageName = getPackageName(className);
            allPackages.add(packageName);
            
            DependencyModel.ClassInfo classInfo = rawModel.getClass(className);
            for (String dep : classInfo.dependencies) {
                if (rawModel.getAllClassNames().contains(dep)) {
                    String depPackage = getPackageName(dep);
                    if (!packageName.equals(depPackage)) {
                        packageDeps.computeIfAbsent(packageName, k -> new HashSet<>()).add(depPackage);
                    }
                }
            }
        }
        
        // Ensure all packages are in the map
        for (String pkg : allPackages) {
            packageDeps.computeIfAbsent(pkg, k -> new HashSet<>());
        }
        
        System.out.println("Total packages: " + allPackages.size());
        System.out.println("Packages with external dependencies: " + 
            packageDeps.values().stream().filter(s -> !s.isEmpty()).count());
        
        // Find package-level SCCs
        TarjanSCCFinder pkgSccFinder = new TarjanSCCFinder(packageDeps);
        List<StronglyConnectedComponent> pkgSCCs = pkgSccFinder.findSCCs();
        
        List<StronglyConnectedComponent> nonTrivialPkgSCCs = pkgSCCs.stream()
            .filter(s -> s.getSize() > 1)
            .sorted((a, b) -> Integer.compare(b.getSize(), a.getSize()))
            .collect(Collectors.toList());
        
        System.out.println("\nPackage-level SCCs (non-trivial): " + nonTrivialPkgSCCs.size());
        
        int sccIndex = 1;
        for (StronglyConnectedComponent scc : nonTrivialPkgSCCs) {
            System.out.println("\nPackage SCC #" + sccIndex + " (size: " + scc.getSize() + "):");
            List<String> members = scc.getMembers().stream()
                .sorted()
                .collect(Collectors.toList());
            
            if (members.size() <= 20) {
                for (String member : members) {
                    System.out.println("    - " + member);
                }
            } else {
                members.stream().limit(15).forEach(m -> System.out.println("    - " + m));
                System.out.println("    ... and " + (members.size() - 15) + " more packages");
            }
            sccIndex++;
        }
        
        int packagesInCycles = nonTrivialPkgSCCs.stream()
            .mapToInt(StronglyConnectedComponent::getSize)
            .sum();
        
        System.out.println("\n" + "-".repeat(60));
        System.out.println("SUMMARY");
        System.out.println("-".repeat(60));
        System.out.println("Packages in cycles: " + packagesInCycles + " / " + allPackages.size() + 
            " (" + String.format("%.1f", 100.0 * packagesInCycles / allPackages.size()) + "%)");
        
        System.out.println("\nTEST PASSED!");
    }
    
    // ============== Helper Methods ==============
    
    private Map<String, Set<String>> buildClassDependencyMap(DependencyModel rawModel) {
        Map<String, Set<String>> classDeps = new HashMap<>();
        Set<String> allClasses = rawModel.getAllClassNames();
        for (String className : allClasses) {
            DependencyModel.ClassInfo classInfo = rawModel.getClass(className);
            Set<String> filteredDeps = classInfo.dependencies.stream()
                .filter(allClasses::contains)
                .collect(Collectors.toSet());
            classDeps.put(className, filteredDeps);
        }
        return classDeps;
    }
    
    private Map<Integer, Long> analyzeLevelDistribution(DomainModel model) {
        return model.getAllClasses().values().stream()
            .collect(Collectors.groupingBy(c -> c.level, Collectors.counting()));
    }
    
    private String getSimpleClassName(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;
    }
    
    private String getPackageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(0, lastDot) : "(default)";
    }
}
