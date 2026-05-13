package de.weigend.s202.ui.consistency;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.LevelCalculator;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.HierarchicalLayeredArchitectureBuilder;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that the new {@code HierarchicalLayeredArchitectureBuilder}
 * produces the same structure (rows, elements per row, levels, package
 * nesting) the existing UI pipeline assembles. Runs against the bundled
 * {@code test-example} JAR via the same path the other integration
 * tests use.
 */
class ArchitectureConsistencyCheckerTest {

    private static final String TEST_JAR = "../test-example/target/test-example-1.0.0.jar";

    @Test
    void newArchitectureMatchesArchitectureNodeTreeForTestExampleJar() throws Exception {
        File jar = new File(TEST_JAR);
        if (!jar.isFile()) {
            fail("Required test-example JAR not built at " + TEST_JAR
                    + " — run `mvn -pl test-example package` first");
        }

        DependencyModel rawModel = new InputAnalyzer().analyze(TEST_JAR);
        DomainModel domain = new LevelCalculator().calculate(rawModel);

        Architecture archFromDomain = new HierarchicalLayeredArchitectureBuilder().build(domain);
        ArchitectureNode uiRoot = new ArchitectureNodeBuilder().build(domain);

        List<ArchitectureConsistencyChecker.Discrepancy> diffs =
                new ArchitectureConsistencyChecker().check(archFromDomain, uiRoot);

        if (!diffs.isEmpty()) {
            StringBuilder report = new StringBuilder("Architecture consistency mismatches:\n");
            for (var d : diffs) {
                report.append("  ").append(d.path()).append(" — ").append(d.message()).append('\n');
            }
            fail(report.toString());
        }
        assertNotNull(archFromDomain);
        assertTrue(diffs.isEmpty(), "expected no discrepancies");
    }
}
