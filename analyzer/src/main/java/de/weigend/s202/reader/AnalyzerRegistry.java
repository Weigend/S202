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
package de.weigend.s202.reader;

import de.weigend.s202.reader.c.CSourceAnalyzer;
import de.weigend.s202.reader.java.GradleProjectScanner;
import de.weigend.s202.reader.java.InputAnalyzer;
import de.weigend.s202.reader.java.MavenProjectScanner;
import de.weigend.s202.reader.python.PythonSourceAnalyzer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Public entry point for the reader component.
 */
public final class AnalyzerRegistry {

    public static final String JAVA_BYTECODE = "Java bytecode";
    public static final String PYTHON = "Python";
    public static final String C = "C";

    private final InputAnalyzer javaBytecodeAnalyzer;
    private final List<LanguageAnalyzer> analyzers;

    private AnalyzerRegistry(InputAnalyzer javaBytecodeAnalyzer, List<LanguageAnalyzer> analyzers) {
        this.javaBytecodeAnalyzer = Objects.requireNonNull(javaBytecodeAnalyzer, "javaBytecodeAnalyzer");
        this.analyzers = List.copyOf(analyzers);
    }

    public static AnalyzerRegistry createDefault() {
        InputAnalyzer javaAnalyzer = new InputAnalyzer();
        List<LanguageAnalyzer> builtIns = List.of(
                javaAnalyzer,
                new PythonSourceAnalyzer(),
                new CSourceAnalyzer());

        Map<String, LanguageAnalyzer> registered = new LinkedHashMap<>();
        for (LanguageAnalyzer analyzer : builtIns) {
            registered.put(key(analyzer), analyzer);
        }
        for (LanguageAnalyzer analyzer : ServiceLoader.load(LanguageAnalyzer.class)) {
            registered.putIfAbsent(key(analyzer), analyzer);
        }
        return new AnalyzerRegistry(javaAnalyzer, new ArrayList<>(registered.values()));
    }

    public List<LanguageAnalyzer> analyzers() {
        return analyzers;
    }

    public LanguageAnalyzer javaBytecodeAnalyzer() {
        return javaBytecodeAnalyzer;
    }

    public Optional<LanguageAnalyzer> findAnalyzer(String displayName) {
        String requested = normalize(displayName);
        return analyzers.stream()
                .filter(analyzer -> normalize(analyzer.displayName()).equals(requested))
                .findFirst();
    }

    public LanguageAnalyzer requireAnalyzer(String displayName) {
        return findAnalyzer(displayName)
                .orElseThrow(() -> new IllegalArgumentException("No analyzer registered for " + displayName));
    }

    public ProjectScanResult scanMavenProject(Path projectRoot) throws IOException {
        MavenProjectScanner.Result result = new MavenProjectScanner().scan(projectRoot.toFile());
        return toProjectScanResult(result.jars(), result.missingArtifactModules(), result.scannedModuleCount());
    }

    public ProjectScanResult scanGradleProject(Path projectRoot) throws IOException {
        GradleProjectScanner.Result result = new GradleProjectScanner().scan(projectRoot.toFile());
        return toProjectScanResult(result.jars(), result.missingArtifactModules(), result.scannedModuleCount());
    }

    private static ProjectScanResult toProjectScanResult(List<File> jars,
                                                         List<String> missingArtifactModules,
                                                         int scannedModuleCount) {
        return new ProjectScanResult(
                jars.stream().map(File::toPath).toList(),
                Collections.unmodifiableList(new ArrayList<>(missingArtifactModules)),
                scannedModuleCount);
    }

    private static String key(LanguageAnalyzer analyzer) {
        return normalize(analyzer.getClass().getName() + ":" + analyzer.displayName());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record ProjectScanResult(List<Path> jars,
                                    List<String> missingArtifactModules,
                                    int scannedModuleCount) {}
}
