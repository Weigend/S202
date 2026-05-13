package de.weigend.s202.graph;

import de.weigend.s202.analysis.strategy.impl.BasicClassLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.impl.HeuristicSCCBreakingStrategy;
import de.weigend.s202.analysis.strategy.aggregation.SimpleMaxAggregationStrategy;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.analysis.strategy.LevelCalculator;
import de.weigend.s202.analysis.strategy.LevelCalculationStrategyFactory;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that compares Basic vs Heuristic SCC strategies.
 */
class MinecraftSCCBreakingTest {
    
    private static File minecraftJar;
    private static boolean jarAvailable = false;
    
    @BeforeAll
    static void setup() {
        URL jarUrl = MinecraftSCCBreakingTest.class.getResource("/forge-1.19.2-43.3.0_mapped_official_1.19.2.jar");
        if (jarUrl != null) {
            minecraftJar = new File(jarUrl.getFile());
            jarAvailable = minecraftJar.exists();
        }
        System.out.println("Minecraft JAR available: " + jarAvailable);
    }
    
    @Test
    @DisplayName("Synthetic test: Large SCC shows difference between strategies")
    void syntheticLargeSCCTest() {
        System.out.println("\n========================================");
        System.out.println("SYNTHETIC LARGE SCC TEST");
        System.out.println("========================================");
        
        // Create a synthetic graph with a large cycle
        Map<String, Set<String>> graph = new HashMap<>();
        
        // Create 20 classes in a complex cycle
        List<String> classes = Arrays.asList(
            "GameMain", "WorldManager", "EntityManager", "PlayerController", "InputHandler",
            "Renderer", "TextureLoader", "ModelLoader", "ShaderManager", "LightingSystem",
            "PhysicsEngine", "CollisionDetector", "SoundManager", "NetworkClient", "PacketHandler",
            "SaveManager", "ConfigLoader", "ResourceManager", "EventBus", "PluginLoader"
        );
        
        // Create complex interdependencies
        graph.put("GameMain", new HashSet<>(Arrays.asList("WorldManager", "Renderer", "NetworkClient", "SaveManager", "ConfigLoader")));
        graph.put("WorldManager", new HashSet<>(Arrays.asList("EntityManager", "PhysicsEngine", "SaveManager")));
        graph.put("EntityManager", new HashSet<>(Arrays.asList("WorldManager", "CollisionDetector", "PlayerController")));
        graph.put("PlayerController", new HashSet<>(Arrays.asList("InputHandler", "EntityManager", "NetworkClient")));
        graph.put("InputHandler", new HashSet<>(Arrays.asList("EventBus", "ConfigLoader")));
        graph.put("Renderer", new HashSet<>(Arrays.asList("TextureLoader", "ModelLoader", "ShaderManager", "LightingSystem", "WorldManager")));
        graph.put("TextureLoader", new HashSet<>(Arrays.asList("ResourceManager")));
        graph.put("ModelLoader", new HashSet<>(Arrays.asList("ResourceManager", "TextureLoader")));
        graph.put("ShaderManager", new HashSet<>(Arrays.asList("ResourceManager", "ConfigLoader")));
        graph.put("LightingSystem", new HashSet<>(Arrays.asList("ShaderManager", "WorldManager")));
        graph.put("PhysicsEngine", new HashSet<>(Arrays.asList("CollisionDetector", "EntityManager")));
        graph.put("CollisionDetector", new HashSet<>(Arrays.asList("EntityManager")));
        graph.put("SoundManager", new HashSet<>(Arrays.asList("ResourceManager", "EventBus", "WorldManager")));
        graph.put("NetworkClient", new HashSet<>(Arrays.asList("PacketHandler", "EventBus")));
        graph.put("PacketHandler", new HashSet<>(Arrays.asList("EntityManager", "WorldManager")));
        graph.put("SaveManager", new HashSet<>(Arrays.asList("WorldManager", "ConfigLoader")));
        graph.put("ConfigLoader", new HashSet<>(Arrays.asList("ResourceManager")));
        graph.put("ResourceManager", new HashSet<>(Arrays.asList("ConfigLoader")));
        graph.put("EventBus", new HashSet<>(Arrays.asList("PluginLoader")));
        graph.put("PluginLoader", new HashSet<>(Arrays.asList("EventBus", "ConfigLoader")));
        
        // Analyze SCCs
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(graph);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();
        
        int largeSccCount = (int) sccs.stream().filter(s -> s.getSize() >= 3).count();
        int maxSccSize = sccs.stream().mapToInt(StronglyConnectedComponent::getSize).max().orElse(0);
        
        System.out.println("Classes: " + classes.size());
        System.out.println("Large SCCs (>=3 members): " + largeSccCount);
        System.out.println("Largest SCC size: " + maxSccSize);
        
        if (maxSccSize >= 3) {
            StronglyConnectedComponent largestScc = sccs.stream()
                .max(Comparator.comparingInt(StronglyConnectedComponent::getSize))
                .orElse(null);
            System.out.println("Largest SCC members: " + largestScc.getMembers());
        }
        
        // Calculate levels with BASIC strategy
        BasicClassLevelCalculationStrategy basicStrategy = new BasicClassLevelCalculationStrategy(
            new SimpleMaxAggregationStrategy()
        );
        Map<String, Integer> basicLevels = basicStrategy.calculateClassLevels(graph);
        
        Set<Integer> basicUniqueLevels = new HashSet<>(basicLevels.values());
        System.out.println("\n--- BASIC STRATEGY ---");
        System.out.println("Unique levels: " + basicUniqueLevels.size());
        printLevels(basicLevels);
        
        // Calculate levels with HEURISTIC strategy
        HeuristicSCCBreakingStrategy heuristicStrategy = new HeuristicSCCBreakingStrategy();
        Map<String, Integer> heuristicLevels = heuristicStrategy.calculateClassLevels(graph);
        
        Set<Integer> heuristicUniqueLevels = new HashSet<>(heuristicLevels.values());
        System.out.println("\n--- HEURISTIC STRATEGY ---");
        System.out.println("Unique levels: " + heuristicUniqueLevels.size());
        System.out.println("Back edges identified: " + heuristicStrategy.getBackEdgeCount());
        for (SCCBreaker.Edge edge : heuristicStrategy.getLastIdentifiedBackEdges()) {
            System.out.println("  Back edge: " + edge);
        }
        printLevels(heuristicLevels);
        
        // Assertions
        System.out.println("\n--- ASSERTIONS ---");
        
        if (maxSccSize >= 3) {
            // The key difference: Basic strategy assigns SAME level to all SCC members
            // Heuristic breaks the SCC, allowing different levels within former SCC members
            
            // Find the largest SCC and check level distribution
            StronglyConnectedComponent largestScc = sccs.stream()
                .max(Comparator.comparingInt(StronglyConnectedComponent::getSize))
                .orElse(null);
            
            Set<Integer> basicSccLevels = largestScc.getMembers().stream()
                .map(basicLevels::get)
                .collect(Collectors.toSet());
            
            Set<Integer> heuristicSccLevels = largestScc.getMembers().stream()
                .map(heuristicLevels::get)
                .collect(Collectors.toSet());
            
            System.out.println("Levels of SCC members with BASIC: " + basicSccLevels + " (count: " + basicSccLevels.size() + ")");
            System.out.println("Levels of SCC members with HEURISTIC: " + heuristicSccLevels + " (count: " + heuristicSccLevels.size() + ")");
            
            // Basic: all SCC members should have the SAME level
            assertEquals(1, basicSccLevels.size(),
                "Basic strategy should assign same level to all SCC members");
            System.out.println("✓ Basic strategy: all " + largestScc.getSize() + " SCC members on level " + basicSccLevels.iterator().next());
            
            // Heuristic: SCC members should be spread across MULTIPLE levels
            assertTrue(heuristicSccLevels.size() > 1,
                "Heuristic should spread SCC members across multiple levels. Got: " + heuristicSccLevels);
            System.out.println("✓ Heuristic strategy: SCC members spread across " + heuristicSccLevels.size() + " levels");
            
            // Back edges should be identified
            assertTrue(heuristicStrategy.getBackEdgeCount() > 0,
                "Heuristic should identify back edges in large SCCs");
            System.out.println("✓ Back edges identified: " + heuristicStrategy.getBackEdgeCount());
        }
        
        System.out.println("\nTEST PASSED!");
    }
    
    @Test
    @DisplayName("Compare strategies on Forge JAR")
    void compareStrategiesOnForgeJar() throws IOException {
        if (!jarAvailable) {
            System.out.println("SKIPPED: Forge JAR not found");
            return;
        }
        
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(minecraftJar.getAbsolutePath());
        
        System.out.println("\n========================================");
        System.out.println("FORGE JAR ANALYSIS");
        System.out.println("========================================");
        System.out.println("Total classes: " + rawModel.getAllClassNames().size());
        
        // Build class dependency map
        Map<String, Set<String>> classDeps = buildClassDependencyMap(rawModel);
        int totalInternalDeps = classDeps.values().stream().mapToInt(Set::size).sum();
        System.out.println("Total internal dependencies: " + totalInternalDeps);
        
        // Analyze SCCs
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(classDeps);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();
        int largeSccCount = (int) sccs.stream().filter(s -> s.getSize() >= 3).count();
        int maxSccSize = sccs.stream().mapToInt(StronglyConnectedComponent::getSize).max().orElse(0);
        
        System.out.println("Large SCCs (>=3 members): " + largeSccCount);
        System.out.println("Largest SCC size: " + maxSccSize);
        
        // Calculate with both strategies
        LevelCalculator basicCalc = new LevelCalculator();
        DomainModel basicModel = basicCalc.calculate(rawModel);
        
        LevelCalculator heuristicCalc = new LevelCalculator(
            LevelCalculationStrategyFactory.createWithHeuristicSCCBreaking()
        );
        DomainModel heuristicModel = heuristicCalc.calculate(rawModel);
        
        Map<Integer, Long> basicDist = analyzeLevelDistribution(basicModel);
        Map<Integer, Long> heuristicDist = analyzeLevelDistribution(heuristicModel);
        
        System.out.println("\nBasic unique levels: " + basicDist.size());
        System.out.println("Heuristic unique levels: " + heuristicDist.size());
        
        if (largeSccCount == 0) {
            System.out.println("\nNOTE: No large SCCs - strategies produce identical results.");
            assertEquals(basicDist, heuristicDist);
        }
        
        System.out.println("\nTEST PASSED!");
    }
    
    private void printLevels(Map<String, Integer> levels) {
        levels.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry::getKey))
            .forEach(e -> System.out.println("  Level " + e.getValue() + ": " + e.getKey()));
    }
    
    private Map<Integer, Long> analyzeLevelDistribution(DomainModel model) {
        return model.getAllClasses().values().stream()
            .collect(Collectors.groupingBy(c -> c.level, Collectors.counting()));
    }
    
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
}
