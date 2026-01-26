package de.weigend.s202.analysis.domain;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.domain.LevelCalculationStrategyFactory;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that the level calculation algorithm produces consistent results:
 * All outgoing dependencies from elements within a package must point to elements
 * with a LOWER level (dependencies flow downward in the visualization).
 * 
 * Exception: Cyclic dependencies (SCCs) - elements in the same SCC get the same level,
 * so same-level dependencies within an SCC are allowed.
 */
class LevelConsistencyValidationTest {
    
    private static final String SWCITY_JAR_PATH = "src/test/resources/swcity-1.0.jar";
    
    private DomainModel domainModel;
    private ArchitectureNode rootNode;
    private List<String> violations;
    private Map<String, Integer> classToSccId;  // Maps class name to SCC ID for cycle detection
    
    @BeforeEach
    void setUp() throws IOException {
        // Step 1: Analyze the JAR
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(SWCITY_JAR_PATH);
        
        // Step 2: Calculate levels using BasicClassLevelCalculationStrategy
        // (The heuristic strategy intentionally creates back-edges that appear as upward deps)
        LevelCalculator calculator = new LevelCalculator(
            LevelCalculationStrategyFactory.createWithBasicStrategy()
        );
        domainModel = calculator.calculate(rawModel);
        
        // Step 3: Build the ArchitectureNode tree
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        rootNode = builder.build(domainModel);
        
        violations = new ArrayList<>();
        
        // Step 4: Build SCC map for cycle detection
        classToSccId = buildSccMap();
    }
    
    /**
     * Builds a map from class name to SCC ID using Tarjan's algorithm.
     * Classes in the same SCC (cycle) have the same SCC ID.
     */
    private Map<String, Integer> buildSccMap() {
        // Build dependency graph for SCC analysis
        Map<String, Set<String>> classDependencies = new HashMap<>();
        for (DomainModel.CalculatedElementInfo classInfo : domainModel.getAllClasses().values()) {
            Set<String> internalDeps = new HashSet<>();
            for (String dep : classInfo.dependencies) {
                if (domainModel.getClass(dep) != null) {
                    internalDeps.add(dep);
                }
            }
            classDependencies.put(classInfo.fullName, internalDeps);
        }
        
        // Find SCCs
        TarjanSCCFinder finder = new TarjanSCCFinder(classDependencies);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();
        
        // Build map
        Map<String, Integer> sccMap = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                sccMap.put(member, scc.getId());
            }
        }
        return sccMap;
    }
    
    /**
     * Test that all outgoing dependencies point to elements with lower levels.
     * This validates the core invariant: dependencies flow downward.
     */
    @Test
    void testAllDependenciesPointDownward() {
        // Recursively validate the entire tree
        validateNodeRecursively(rootNode);
        
        // Report all violations if any
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(violations.size()).append(" level consistency violations:\n\n");
            for (String violation : violations) {
                sb.append("  - ").append(violation).append("\n");
            }
            fail(sb.toString());
        }
    }
    
    /**
     * Recursively validates that all elements in the tree have only downward dependencies.
     */
    private void validateNodeRecursively(ArchitectureNode node) {
        // Skip the synthetic root node
        if (!"root".equals(node.getFullName())) {
            validateNodeDependencies(node);
        }
        
        // Recursively validate all children
        for (ArchitectureNode child : node.getChildren()) {
            validateNodeRecursively(child);
        }
    }
    
    /**
     * Validates that all outgoing dependencies of a node point to elements with lower levels.
     * For classes: checks dependencies to other classes
     * For packages: checks dependencies to subpackages within the same parent
     * 
     * Exception: Same-level dependencies within the same SCC (cycle) are allowed.
     */
    private void validateNodeDependencies(ArchitectureNode node) {
        int sourceLevel = node.getLevel();
        String sourceName = node.getFullName();
        
        // Get the dependencies
        Set<String> dependencies = node.getDependencies();
        
        for (String depName : dependencies) {
            // Find the target element's level
            Integer targetLevel = findElementLevel(depName);
            
            if (targetLevel == null) {
                // External dependency (not in our analyzed code) - skip
                continue;
            }
            
            // Check: source level must be > target level (dependencies point downward)
            if (sourceLevel <= targetLevel) {
                // Exception: Same-level dependencies within the same SCC are allowed (cycles)
                if (sourceLevel == targetLevel && isInSameScc(sourceName, depName)) {
                    // This is a cyclic dependency within an SCC - allowed
                    continue;
                }
                
                // Check if source and target are in the same package context
                // (we only care about violations within the same package view)
                if (isRelevantDependency(sourceName, depName)) {
                    violations.add(String.format(
                        "%s (L%d) -> %s (L%d) [UPWARD or SAME LEVEL dependency!]",
                        sourceName, sourceLevel, depName, targetLevel
                    ));
                }
            }
        }
    }
    
    /**
     * Checks if two classes are in the same Strongly Connected Component (cycle).
     */
    private boolean isInSameScc(String class1, String class2) {
        Integer scc1 = classToSccId.get(class1);
        Integer scc2 = classToSccId.get(class2);
        
        if (scc1 == null || scc2 == null) {
            return false;
        }
        
        return scc1.equals(scc2);
    }
    
    /**
     * Determines if a dependency is relevant for level consistency checking.
     * We check dependencies where:
     * 1. Both elements are classes in the same package
     * 2. A class depends on a class in a subpackage (must be higher than subpackage level)
     */
    private boolean isRelevantDependency(String sourceName, String targetName) {
        String sourcePackage = extractPackageName(sourceName);
        String targetPackage = extractPackageName(targetName);
        
        if (sourcePackage == null || targetPackage == null) {
            return false;
        }
        
        // Case 1: Same package - class to class dependency
        if (sourcePackage.equals(targetPackage)) {
            return true;
        }
        
        // Case 2: Source class is in parent package, target is in subpackage
        // e.g., source: com.example.App, target: com.example.util.Helper
        if (targetPackage.startsWith(sourcePackage + ".")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Finds the level of an element (class or package) by name.
     */
    private Integer findElementLevel(String elementName) {
        // Try to find as class first
        DomainModel.CalculatedElementInfo classInfo = domainModel.getClass(elementName);
        if (classInfo != null) {
            return classInfo.level;
        }
        
        // Try to find as package
        DomainModel.CalculatedElementInfo pkgInfo = domainModel.getPackage(elementName);
        if (pkgInfo != null) {
            return pkgInfo.level;
        }
        
        return null; // External element
    }
    
    /**
     * Extracts the package name from a fully qualified class name.
     */
    private String extractPackageName(String className) {
        if (className == null || !className.contains(".")) {
            return null;
        }
        int lastDot = className.lastIndexOf('.');
        return className.substring(0, lastDot);
    }
    
    /**
     * Additional test: Print summary of the analyzed structure for debugging.
     */
    @Test
    void testPrintAnalysisSummary() {
        System.out.println("=== SWCITY JAR ANALYSIS SUMMARY ===\n");
        
        System.out.println("Total Classes: " + domainModel.getAllClasses().size());
        System.out.println("Total Packages: " + domainModel.getAllPackages().size());
        System.out.println("Max Level: " + domainModel.getMaxLevel());
        
        System.out.println("\n--- Package Levels ---");
        domainModel.getAllPackages().values().stream()
            .sorted((a, b) -> Integer.compare(b.level, a.level))
            .forEach(pkg -> System.out.println("  " + pkg.fullName + " -> L" + pkg.level));
        
        System.out.println("\n--- Class Level Distribution ---");
        for (int level = domainModel.getMaxLevel(); level >= 0; level--) {
            final int l = level;
            long count = domainModel.getAllClasses().values().stream()
                .filter(c -> c.level == l)
                .count();
            if (count > 0) {
                System.out.println("  Level " + level + ": " + count + " classes");
            }
        }
    }
}
