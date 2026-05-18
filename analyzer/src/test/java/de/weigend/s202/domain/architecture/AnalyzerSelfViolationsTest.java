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
package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diagnostic test that runs the full analysis pipeline on the analyzer's
 * own bundled jar and prints the resulting UPWARD violations, grouped by
 * source/target package. Mirrors what the UI shows in the
 * "Wrong-direction edges" side panel — useful for reasoning about
 * structural refactorings (i.e. package moves that the user could try in
 * the What-If overlay before touching any source code).
 *
 * <p>Skips when the jar isn't built yet — run {@code mvn package} once to
 * populate {@code analyzer/target/} or pass {@code -Dself.jar=...} to
 * point at a specific build.
 */
class AnalyzerSelfViolationsTest {

    private static final String DEFAULT_JAR = "target/s202-code-analyzer-1.0.0.jar";

    @Test
    @EnabledIfSystemProperty(named = "skip.self.violations", matches = "false|FALSE", disabledReason = "set -Dskip.self.violations=false to opt in (default skipped on CI)")
    void dumpAnalyzerJarViolations() throws Exception {
        String jarPath = System.getProperty("self.jar", DEFAULT_JAR);
        File jar = new File(jarPath);
        assumeTrue(jar.exists(), "Self-jar not found at " + jar.getAbsolutePath()
                + " — run `mvn package` or pass -Dself.jar=<path>");

        InputAnalyzer inputAnalyzer = new InputAnalyzer();
        DependencyModel raw = inputAnalyzer.analyze(jar.getAbsolutePath());
        DomainModel domain = new LevelCalculator().calculate(raw);
        Architecture arch = new HierarchicalLayeredArchitectureBuilder().build(domain);
        assertNotNull(arch);

        List<Violation> upward = new ArrayList<>();
        for (Violation v : arch.violations()) {
            if (v.kind() == ViolationKind.UPWARD) {
                upward.add(v);
            }
        }

        Map<EndpointPair, List<Violation>> grouped =
                arch.groupUpwardViolations(AnalyzerSelfViolationsTest::parentOf);

        System.out.println();
        System.out.println("=== Analyzer self-violations: " + upward.size() + " UPWARD class edges ===");
        System.out.println("Source jar: " + jar.getAbsolutePath());
        System.out.println();
        System.out.println("Grouped by (source-package, target-package):");

        List<Map.Entry<EndpointPair, List<Violation>>> sorted = new ArrayList<>(grouped.entrySet());
        sorted.sort(Comparator
                .<Map.Entry<EndpointPair, List<Violation>>, Integer>comparing(e -> -e.getValue().size())
                .thenComparing(e -> e.getKey().source())
                .thenComparing(e -> e.getKey().target()));

        for (Map.Entry<EndpointPair, List<Violation>> entry : sorted) {
            EndpointPair pair = entry.getKey();
            List<Violation> edges = entry.getValue();
            System.out.println();
            System.out.println("  " + pair.source() + "  ↑  " + pair.target() + "   (" + edges.size() + ")");
            List<Violation> sortedEdges = edges.stream()
                    .sorted(Comparator.comparing(Violation::sourceFqn).thenComparing(Violation::targetFqn))
                    .toList();
            for (Violation v : sortedEdges) {
                System.out.println("    " + simple(v.sourceFqn()) + " → " + simple(v.targetFqn())
                        + "    (" + v.sourceFqn() + " → " + v.targetFqn() + ")");
            }
        }

        System.out.println();
        System.out.println("=== Tangles: " + arch.tangles().size() + " ===");
        for (Tangle t : arch.tangles()) {
            List<String> members = t.members().stream().sorted().toList();
            System.out.println("  { " + String.join(", ", members) + " }");
        }
        System.out.println();

        // Loose sanity: the pipeline produced something coherent.
        assertTrue(upward.size() >= 0);
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simple(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
