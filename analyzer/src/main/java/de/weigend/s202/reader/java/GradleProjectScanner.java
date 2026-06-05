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
package de.weigend.s202.reader.java;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks a Gradle (multi-project) build rooted at a directory containing a
 * {@code settings.gradle} or {@code settings.gradle.kts} file and returns the
 * list of analyzable JARs produced under each subproject's
 * {@code build/libs/} directory.
 *
 * <p>Sub-projects are discovered by parsing top-level {@code include(…)}
 * statements in the settings script with regular expressions. A path like
 * {@code ":foo:bar"} maps to the directory {@code foo/bar} relative to the
 * root, matching Gradle's default project layout. Custom layouts set via
 * {@code project(':x').projectDir = file('…')} are not supported — those
 * are an edge case for now; callers can still add the JARs by hand in the
 * SourceSetDialog.</p>
 *
 * <p>The scanner does not invoke Gradle. Callers are responsible for having
 * run {@code gradle build} (or equivalent) beforehand; modules without a
 * {@code build/libs/*.jar} are reported via {@link Result#missingArtifactModules()}.</p>
 */
public final class GradleProjectScanner {

    /**
     * Captures {@code include} statements in both Groovy and Kotlin DSL.
     * Groovy:  {@code include 'a:b'}, {@code include 'a', 'b'}, {@code include('a')}
     * Kotlin:  {@code include("a:b")}, {@code include("a", "b")}
     * The regex grabs everything up to the next newline or semicolon; the
     * argument list is then tokenised with {@link #QUOTED}.
     */
    private static final Pattern INCLUDE_LINE = Pattern.compile(
            "(?m)^\\s*include\\s*\\(?\\s*([^\\n;]*)");

    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");

    /** Single-line comment stripping for Groovy / Kotlin DSL. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Block comment stripping (non-greedy across lines). */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    /**
     * @param jars                   JARs to analyze, in include order
     * @param missingArtifactModules subprojects whose {@code build/libs} is missing or empty
     * @param scannedModuleCount     total number of subprojects (incl. ones without artifact)
     */
    public record Result(List<File> jars,
                         List<String> missingArtifactModules,
                         int scannedModuleCount) {}

    public Result scan(File projectRoot) throws IOException {
        if (projectRoot == null || !projectRoot.isDirectory()) {
            throw new IllegalArgumentException("Gradle project root must be a directory: " + projectRoot);
        }

        File settings = settingsFile(projectRoot);
        Set<File> jars = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        int moduleCount = 0;

        // Always inspect the root project itself — single-module Gradle
        // builds frequently have no settings.gradle at all (or one that
        // only sets rootProject.name).
        moduleCount++;
        collectJarsFromBuildLibs(projectRoot, projectRoot, jars, missing);

        if (settings != null) {
            String script = stripComments(Files.readString(settings.toPath()));
            for (String includePath : extractIncludes(script)) {
                File subDir = resolveSubprojectDir(projectRoot, includePath);
                if (!subDir.isDirectory()) {
                    missing.add(includePath + " (project directory not found at "
                            + relativise(subDir, projectRoot) + ")");
                    continue;
                }
                moduleCount++;
                collectJarsFromBuildLibs(subDir, projectRoot, jars, missing);
            }
        }

        return new Result(new ArrayList<>(jars), Collections.unmodifiableList(missing), moduleCount);
    }

    private static File settingsFile(File root) {
        File groovy = new File(root, "settings.gradle");
        if (groovy.isFile()) return groovy;
        File kotlin = new File(root, "settings.gradle.kts");
        if (kotlin.isFile()) return kotlin;
        return null;
    }

    /**
     * Maps a Gradle project path like {@code :foo:bar} to {@code foo/bar}
     * relative to the root project directory. Leading colons are dropped;
     * inner colons become file separators.
     */
    static File resolveSubprojectDir(File root, String includePath) {
        String trimmed = includePath.trim();
        while (trimmed.startsWith(":")) {
            trimmed = trimmed.substring(1);
        }
        String relative = trimmed.replace(':', File.separatorChar);
        return new File(root, relative);
    }

    static List<String> extractIncludes(String script) {
        List<String> includes = new ArrayList<>();
        Matcher line = INCLUDE_LINE.matcher(script);
        while (line.find()) {
            String args = line.group(1);
            Matcher quoted = QUOTED.matcher(args);
            while (quoted.find()) {
                includes.add(quoted.group(1));
            }
        }
        return includes;
    }

    private static String stripComments(String script) {
        String noBlock = BLOCK_COMMENT.matcher(script).replaceAll("");
        return LINE_COMMENT.matcher(noBlock).replaceAll("");
    }

    private void collectJarsFromBuildLibs(File moduleDir, File rootDir,
                                          Set<File> jars, List<String> missing) {
        File libs = new File(moduleDir, "build" + File.separator + "libs");
        if (!libs.isDirectory()) {
            // Root project often has no build output (parent only). Only
            // flag *named* sub-modules; skip the root silently when it has
            // no libs/ directory.
            if (!moduleDir.equals(rootDir)) {
                missing.add(relativise(moduleDir, rootDir));
            }
            return;
        }
        File[] files = libs.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null || files.length == 0) {
            if (!moduleDir.equals(rootDir)) {
                missing.add(relativise(moduleDir, rootDir));
            }
            return;
        }
        boolean addedAny = false;
        for (File jar : files) {
            if (MavenProjectScanner.isAnalyzableJar(jar)) {
                jars.add(jar);
                addedAny = true;
            }
        }
        if (!addedAny && !moduleDir.equals(rootDir)) {
            missing.add(relativise(moduleDir, rootDir));
        }
    }

    private static String relativise(File child, File root) {
        try {
            return root.toPath().toAbsolutePath().relativize(child.toPath().toAbsolutePath()).toString();
        } catch (IllegalArgumentException ex) {
            return child.getAbsolutePath();
        }
    }
}
