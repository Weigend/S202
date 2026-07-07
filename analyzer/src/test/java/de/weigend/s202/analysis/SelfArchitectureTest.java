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
package de.weigend.s202.analysis;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S202 analysiert sich selbst: die eigene Pipeline läuft über {@code target/classes}
 * und wacht darüber, dass keine neuen Paket-Tangles, Klassen-Zyklen oder
 * Back-Edges in die Codebase rutschen.
 *
 * <p>Die Budgets starten auf dem Ist-Stand des Refactoring-Konzepts
 * ({@code docs/REFACTORING_KONZEPT_CODEBASE.md}) und werden mit jedem
 * Zyklen-Fix abgesenkt, bis sie auf 0 stehen. Ein Budget darf nie wieder
 * erhöht werden — wer hier anschlägt, hat einen neuen Zyklus eingebaut.</p>
 */
public class SelfArchitectureTest {

    // Ist-Stand Juli 2026 — absenken, nie erhöhen (Ziel: 0 / 0 / 0).
    private static final int PACKAGE_TANGLE_BUDGET = 1;
    private static final int CLASS_CYCLE_BUDGET = 0;
    private static final int CLASS_BACK_EDGE_BUDGET = 0;

    private static DomainModel model;

    @BeforeAll
    static void analyzeOwnCodebase() throws IOException {
        Lookup.init();
        Path classesDir = findOwnClassesDir();
        Path tempJar = Files.createTempFile("s202-self-analysis", ".jar");
        tempJar.toFile().deleteOnExit();
        jarUp(classesDir, tempJar);
        DependencyModel raw = new InputAnalyzer().analyze(tempJar.toString());
        model = new LevelCalculator().calculate(raw);
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void packageTanglesWithinBudget() {
        List<Set<String>> tangles = model.getPackageTangles();
        String detail = tangles.stream()
                .map(t -> "  tangle(" + t.size() + "): " + String.join(", ", t.stream().sorted().toList()))
                .collect(Collectors.joining("\n"));
        assertTrue(tangles.size() <= PACKAGE_TANGLE_BUDGET,
                "Neue Paket-Tangles eingebaut! Budget " + PACKAGE_TANGLE_BUDGET
                        + ", gefunden " + tangles.size() + ":\n" + detail);
    }

    @Test
    void classCyclesWithinBudget() {
        Map<String, Set<String>> graph = new HashMap<>();
        for (Map.Entry<String, DomainModel.CalculatedElementInfo> e : model.getAllClasses().entrySet()) {
            graph.put(e.getKey(), e.getValue().dependencies.stream()
                    .filter(d -> model.getClass(d) != null)
                    .collect(Collectors.toSet()));
        }
        List<StronglyConnectedComponent> cycles = Lookup.lookup(SCCFinder.class).findSCCs(graph).stream()
                .filter(StronglyConnectedComponent::isTangle)
                .toList();
        String detail = cycles.stream()
                .map(c -> "  cycle(" + c.getSize() + "): " + String.join(", ", c.getMembers().stream().sorted().toList()))
                .collect(Collectors.joining("\n"));
        assertTrue(cycles.size() <= CLASS_CYCLE_BUDGET,
                "Neue Klassen-Zyklen eingebaut! Budget " + CLASS_CYCLE_BUDGET
                        + ", gefunden " + cycles.size() + ":\n" + detail);
    }

    @Test
    void classBackEdgesWithinBudget() {
        int backEdges = model.getClassBackEdgeCount();
        String detail = model.getClassBackEdges().stream()
                .map(e -> "  " + e)
                .sorted()
                .collect(Collectors.joining("\n"));
        assertTrue(backEdges <= CLASS_BACK_EDGE_BUDGET,
                "Neue Klassen-Back-Edges eingebaut! Budget " + CLASS_BACK_EDGE_BUDGET
                        + ", gefunden " + backEdges + ":\n" + detail);
    }

    private static Path findOwnClassesDir() {
        for (String candidate : new String[]{"target/classes", "analyzer/target/classes"}) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p.resolve("de/weigend/s202"))) {
                return p;
            }
        }
        return fail("target/classes nicht gefunden — Test braucht kompilierte eigene Klassen");
    }

    /** Packt ein Verzeichnis in ein Jar (InputAnalyzer liest ausschließlich Jars). */
    private static void jarUp(Path dir, Path jar) throws IOException {
        try (OutputStream fos = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(fos);
             Stream<Path> files = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                jos.putNextEntry(new JarEntry(dir.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jos);
                jos.closeEntry();
            }
        }
    }
}
