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
package de.weigend.s202.reader.impl.javascript;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.LanguageAnalyzer;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Analyzes JavaScript / ES-module source trees and maps modules (files) onto
 * S202's {@link DependencyModel} — the JS sibling of
 * {@code PythonSourceAnalyzer}. Built to make the City3D web app (an ES-module
 * Three.js app under {@code city3d/src}) meaningfully analysable: each file is
 * a module, static {@code import}s form the dependency graph.
 */
@Singleton
public class JavaScriptSourceAnalyzer implements LanguageAnalyzer {

    private static final List<String> COMMON_SOURCE_ROOTS = List.of("src", "lib", "app");
    private static final List<String> SOURCE_EXTENSIONS = List.of(".js", ".mjs", ".cjs");

    private static final Set<String> EXCLUDED_DIR_NAMES = Set.of(
            ".git", ".hg", ".svn",
            "node_modules", "bower_components",
            "dist", "build", "out", "coverage",
            ".next", ".nuxt", ".cache", ".parcel-cache", "vendor");

    private final JavaScriptAstProvider astProvider;

    @Inject
    public JavaScriptSourceAnalyzer() {
        this(new JavaScriptParser());
    }

    public JavaScriptSourceAnalyzer(JavaScriptAstProvider astProvider) {
        this.astProvider = Objects.requireNonNull(astProvider, "astProvider");
    }

    @Override
    public String displayName() {
        return "JavaScript";
    }

    public DependencyModel analyze(Path sourceRootOrProjectRoot) throws IOException {
        return analyze(List.of(sourceRootOrProjectRoot));
    }

    @Override
    public DependencyModel analyze(List<Path> roots) throws IOException {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("at least one JavaScript source root is required");
        }

        List<SourceRoot> sourceRoots = discoverSourceRoots(roots);
        List<ModuleFile> modules = discoverModules(sourceRoots);

        DependencyModel model = new DependencyModel();
        for (ModuleFile module : modules) {
            model.addClass(module.moduleName(), new DependencyModel.ClassInfo(
                    module.moduleName(), module.simpleName(), module.packageName(), false));
        }

        List<JavaScriptAstProvider.ModuleSource> sources = modules.stream()
                .map(module -> new JavaScriptAstProvider.ModuleSource(module.moduleName(), module.path()))
                .toList();
        List<ParsedJavaScriptModule> parsed = astProvider.parse(sources);
        new JavaScriptDependencyResolver(model, parsed).populate();

        PackageHierarchyBuilder.buildPackageHierarchy(model);
        return model;
    }

    private static List<SourceRoot> discoverSourceRoots(List<Path> roots) throws IOException {
        LinkedHashSet<SourceRoot> result = new LinkedHashSet<>();
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path normalized = root.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized) && isSourceFile(normalized)) {
                Path parent = normalized.getParent();
                result.add(new SourceRoot(parent, parent));
                continue;
            }
            if (!Files.isDirectory(normalized)) {
                throw new IOException("JavaScript source root does not exist or is not a directory: " + normalized);
            }

            boolean foundCommonSourceRoot = false;
            for (String commonRoot : COMMON_SOURCE_ROOTS) {
                Path candidate = normalized.resolve(commonRoot);
                if (Files.isDirectory(candidate) && containsSourceFile(candidate)) {
                    Path sourceRoot = candidate.toAbsolutePath().normalize();
                    result.add(new SourceRoot(sourceRoot, sourceRoot));
                    foundCommonSourceRoot = true;
                }
            }
            if (!foundCommonSourceRoot) {
                result.add(new SourceRoot(normalized, normalized));
            }
        }
        return result.stream().toList();
    }

    private static boolean containsSourceFile(Path root) throws IOException {
        return !collectSourceFiles(root).isEmpty();
    }

    private static List<ModuleFile> discoverModules(List<SourceRoot> sourceRoots) throws IOException {
        List<ModuleFile> modules = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (SourceRoot sourceRoot : sourceRoots) {
            String syntheticRootPackage = syntheticRootPackage(sourceRoot.moduleRoot());
            for (Path file : collectSourceFiles(sourceRoot.scanRoot())) {
                Path normalized = file.toAbsolutePath().normalize();
                if (!seen.add(normalized)) {
                    continue;
                }
                modules.add(toModuleFile(sourceRoot.moduleRoot(), normalized, syntheticRootPackage));
            }
        }
        modules.sort(Comparator.comparing(ModuleFile::moduleName));
        return modules;
    }

    private static List<Path> collectSourceFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && EXCLUDED_DIR_NAMES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (isSourceFile(file) && !name.endsWith(".min.js") && !name.endsWith(".d.ts")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private static boolean isSourceFile(Path file) {
        String name = file.getFileName().toString();
        return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static ModuleFile toModuleFile(Path sourceRoot, Path file, String syntheticRootPackage) {
        Path relative = sourceRoot.toAbsolutePath().normalize().relativize(file);
        String fileName = relative.getFileName().toString();
        String moduleSimpleName = stripSourceExtension(fileName);

        Path parent = relative.getParent();
        String packageName = parent == null ? syntheticRootPackage : dottedPath(parent);

        String moduleName = packageName + "." + moduleSimpleName;
        return new ModuleFile(moduleName, packageName, moduleSimpleName, file);
    }

    private static String stripSourceExtension(String fileName) {
        for (String ext : SOURCE_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return sanitizeIdentifier(fileName.substring(0, fileName.length() - ext.length()));
            }
        }
        return sanitizeIdentifier(fileName);
    }

    private static String dottedPath(Path path) {
        List<String> parts = new ArrayList<>();
        for (Path part : path) {
            parts.add(sanitizeIdentifier(part.toString()));
        }
        return String.join(".", parts);
    }

    private static String syntheticRootPackage(Path sourceRoot) {
        Path name = sourceRoot.getFileName();
        if (name != null && !COMMON_SOURCE_ROOTS.contains(name.toString().toLowerCase())) {
            return sanitizeIdentifier(name.toString());
        }
        Path parent = sourceRoot.getParent();
        if (parent != null && parent.getFileName() != null) {
            return sanitizeIdentifier(parent.getFileName().toString());
        }
        return "javascript";
    }

    private static String sanitizeIdentifier(String raw) {
        String normalized = raw == null || raw.isBlank() ? "javascript" : raw;
        normalized = normalized.replaceAll("[^A-Za-z0-9_]", "_");
        if (normalized.isBlank()) {
            normalized = "javascript";
        }
        if (!Character.isJavaIdentifierStart(normalized.charAt(0))) {
            normalized = "mod_" + normalized;
        }
        return normalized;
    }

    private record ModuleFile(String moduleName, String packageName, String simpleName, Path path) {
    }

    private record SourceRoot(Path scanRoot, Path moduleRoot) {
    }
}
