package de.weigend.s202.ui;

import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculationStrategyFactory;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.model.DistrictRowLevelCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the full pipeline including DistrictRowLevelCalculator:
 * For every class dependency A → B (internal), Level(A) > Level(B) must hold.
 * Exception: classes in the same SCC (cycle) may have equal levels.
 */
class DistrictRowLevelValidationTest {

    private static final String SWCITY_JAR_PATH = "src/test/resources/swcity-1.0.jar";

    private DomainModel domainModel;
    private ArchitectureNode rootNode;
    private Map<String, Integer> classToSccId;

    @BeforeEach
    void setUp() throws IOException {
        // Full pipeline as in AnalyzerApplication

        // Step 1: Analyze bytecode
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(SWCITY_JAR_PATH);

        // Step 2: Calculate levels (BasicStrategy keeps SCC members at same level)
        LevelCalculator calculator = new LevelCalculator(
                LevelCalculationStrategyFactory.createWithBasicStrategy()
        );
        domainModel = calculator.calculate(rawModel);

        // Step 3: Build architecture node tree
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        rootNode = builder.build(domainModel);

        // Step 3b: Assign district row-levels (the new step)
        new DistrictRowLevelCalculator().assignDistrictRowLevels(rootNode);

        // Build SCC map for cycle detection
        classToSccId = buildSccMap();
    }

    private Map<String, Integer> buildSccMap() {
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

        TarjanSCCFinder finder = new TarjanSCCFinder(classDependencies);
        List<StronglyConnectedComponent> sccs = finder.findSCCs();

        Map<String, Integer> sccMap = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                sccMap.put(member, scc.getId());
            }
        }
        return sccMap;
    }

    /**
     * For ALL internal class dependencies A → B:
     * Level(A) > Level(B) must hold, unless A and B are in the same SCC.
     */
    @Test
    void testAllClassDependenciesPointDownward() {
        List<String> violations = new ArrayList<>();
        int checkedCount = 0;
        int skippedSccCount = 0;

        for (DomainModel.CalculatedElementInfo classInfo : domainModel.getAllClasses().values()) {
            for (String depName : classInfo.dependencies) {
                DomainModel.CalculatedElementInfo depInfo = domainModel.getClass(depName);
                if (depInfo == null) {
                    continue; // external dependency
                }

                checkedCount++;

                if (classInfo.level > depInfo.level) {
                    // OK: strict downward
                    continue;
                }

                if (classInfo.level == depInfo.level && isInSameScc(classInfo.fullName, depName)) {
                    // OK: same level within SCC (cycle)
                    skippedSccCount++;
                    continue;
                }

                violations.add(String.format(
                        "%s (L%d) -> %s (L%d)%s",
                        classInfo.fullName, classInfo.level,
                        depName, depInfo.level,
                        classInfo.level == depInfo.level ? " [SAME LEVEL, different SCC]" : " [UPWARD]"
                ));
            }
        }

        System.out.println("=== Class Dependency Level Validation ===");
        System.out.println("  Total classes: " + domainModel.getAllClasses().size());
        System.out.println("  Checked dependencies: " + checkedCount);
        System.out.println("  SCC-allowed same-level: " + skippedSccCount);
        System.out.println("  Violations: " + violations.size());

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(violations.size()).append(" class-level violations (A->B but Level(A) <= Level(B)):\n\n");
            for (String v : violations) {
                sb.append("  ").append(v).append("\n");
            }
            fail(sb.toString());
        }
    }

    /**
     * For every subpackage S that is a direct child of some parent P:
     * If any class in S's subtree depends on a sibling class B (direct child of P),
     * then Level(S) > Level(B) must hold.
     *
     * This ensures subpackages are placed in a higher row than the classes they depend on.
     */
    @Test
    void testSubpackageLevelAboveDependencyTargetClasses() {
        List<String> violations = new ArrayList<>();
        validateSubpackageLevelsRecursively(rootNode, violations);

        System.out.println("=== Subpackage vs Sibling-Class Level Validation ===");
        System.out.println("  Violations: " + violations.size());

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(violations.size())
              .append(" subpackage-level violations (Subpackage(A)->B but Level(Subpackage) <= Level(B)):\n\n");
            for (String v : violations) {
                sb.append("  ").append(v).append("\n");
            }
            fail(sb.toString());
        }
    }

    private void validateSubpackageLevelsRecursively(ArchitectureNode parent, List<String> violations) {
        // Collect direct children by type
        Map<String, ArchitectureNode> siblingClasses = new HashMap<>();
        List<ArchitectureNode> siblingPackages = new ArrayList<>();

        for (ArchitectureNode child : parent.getChildren()) {
            if (child.getType() == NodeType.CLASS) {
                siblingClasses.put(child.getFullName(), child);
            } else if (child.getType() == NodeType.PACKAGE) {
                siblingPackages.add(child);
            }
        }

        // For each subpackage, check if its subtree classes depend on sibling classes
        for (ArchitectureNode pkg : siblingPackages) {
            Set<String> subtreeClassNames = new HashSet<>();
            collectAllClassNames(pkg, subtreeClassNames);

            // Find all sibling classes targeted by this subpackage's subtree
            Set<String> targetedSiblings = new HashSet<>();
            for (String className : subtreeClassNames) {
                DomainModel.CalculatedElementInfo classInfo = domainModel.getClass(className);
                if (classInfo == null) continue;
                for (String dep : classInfo.dependencies) {
                    if (siblingClasses.containsKey(dep)) {
                        targetedSiblings.add(dep);
                    }
                }
            }

            // Validate: pkg.level > each targeted sibling class level
            for (String targetName : targetedSiblings) {
                ArchitectureNode targetNode = siblingClasses.get(targetName);
                if (pkg.getLevel() <= targetNode.getLevel()) {
                    violations.add(String.format(
                            "Subpackage %s (L%d) contains class depending on sibling %s (L%d) [needs L(pkg) > L(class)]",
                            pkg.getFullName(), pkg.getLevel(),
                            targetName, targetNode.getLevel()
                    ));
                }
            }
        }

        // Recurse into subpackages
        for (ArchitectureNode child : parent.getChildren()) {
            if (child.getType() == NodeType.PACKAGE) {
                validateSubpackageLevelsRecursively(child, violations);
            }
        }
    }

    private void collectAllClassNames(ArchitectureNode node, Set<String> classNames) {
        if (node.getType() == NodeType.CLASS) {
            classNames.add(node.getFullName());
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectAllClassNames(child, classNames);
        }
    }

    private boolean isInSameScc(String class1, String class2) {
        Integer scc1 = classToSccId.get(class1);
        Integer scc2 = classToSccId.get(class2);
        return scc1 != null && scc2 != null && scc1.equals(scc2);
    }
}
