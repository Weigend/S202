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

import de.weigend.s202.reader.impl.java.InputAnalyzer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the exclusion matcher in {@link InputAnalyzer}:
 * plain entries match as prefixes (historical behaviour), entries
 * containing {@code *} or {@code ?} are treated as glob patterns and
 * matched against the full class FQN.
 */
class InputAnalyzerExclusionTest {

    private static final Path CONFIG = Paths.get("excluded-prefixes.txt");
    private Path backup;

    @BeforeEach
    void backupConfig() throws IOException {
        if (Files.exists(CONFIG)) {
            backup = Files.createTempFile("excluded-prefixes", ".bak");
            Files.copy(CONFIG, backup, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterEach
    void restoreConfig() throws IOException {
        if (backup != null) {
            Files.copy(backup, CONFIG, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backup);
        } else if (Files.exists(CONFIG)) {
            Files.delete(CONFIG);
        }
        InputAnalyzer.reloadExcludedPrefixes();
    }

    @Test
    void plainEntryMatchesAsPrefix() throws IOException {
        writeConfig(List.of("java."));
        InputAnalyzer.reloadExcludedPrefixes();
        assertTrue(InputAnalyzer.isExcludedClass("java.util.List"));
        assertTrue(InputAnalyzer.isExcludedClass("java.lang.String"));
        assertFalse(InputAnalyzer.isExcludedClass("com.example.Foo"));
        // prefix that doesn't match
        assertFalse(InputAnalyzer.isExcludedClass("javax.servlet.Servlet"));
    }

    @Test
    void starPatternMatchesAcrossAnyPackage() throws IOException {
        writeConfig(List.of("*.WfxModule"));
        InputAnalyzer.reloadExcludedPrefixes();
        assertTrue(InputAnalyzer.isExcludedClass("de.weigend.s202.ui.wfx.WfxModule"));
        assertTrue(InputAnalyzer.isExcludedClass("foo.bar.WfxModule"));
        assertFalse(InputAnalyzer.isExcludedClass("de.weigend.s202.ui.wfx.WfxModuleHelper"));
        assertFalse(InputAnalyzer.isExcludedClass("de.weigend.s202.ui.wfx.S202Module"));
    }

    @Test
    void dollarSuffixPatternMatches() throws IOException {
        writeConfig(List.of("*$DI"));
        InputAnalyzer.reloadExcludedPrefixes();
        assertTrue(InputAnalyzer.isExcludedClass("com.example.Foo$DI"));
        assertFalse(InputAnalyzer.isExcludedClass("com.example.FooDI"));
        assertFalse(InputAnalyzer.isExcludedClass("com.example.Foo"));
    }

    @Test
    void avajeCompositionRootCanBeExcludedWithoutUiModule() throws IOException {
        writeConfig(List.of("de.weigend.s202.S202Module"));
        InputAnalyzer.reloadExcludedPrefixes();
        assertTrue(InputAnalyzer.isExcludedClass("de.weigend.s202.S202Module"));
        assertFalse(InputAnalyzer.isExcludedClass("de.weigend.s202.ui.wfx.S202Module"));
    }

    @Test
    void questionMarkMatchesExactlyOneChar() throws IOException {
        writeConfig(List.of("com.example.Foo?Bar"));
        InputAnalyzer.reloadExcludedPrefixes();
        assertTrue(InputAnalyzer.isExcludedClass("com.example.FooXBar"));
        assertFalse(InputAnalyzer.isExcludedClass("com.example.FooBar"));
        assertFalse(InputAnalyzer.isExcludedClass("com.example.FooXYBar"));
    }

    @Test
    void packageDotInPatternStaysLiteral() throws IOException {
        // a literal "." in the entry must not match arbitrary chars
        writeConfig(List.of("com.example.*.Generated"));
        InputAnalyzer.reloadExcludedPrefixes();
        assertTrue(InputAnalyzer.isExcludedClass("com.example.foo.Generated"));
        assertTrue(InputAnalyzer.isExcludedClass("com.example.foo.bar.Generated"));
        assertFalse(InputAnalyzer.isExcludedClass("comXexampleXfooXGenerated"));
    }

    private static void writeConfig(List<String> entries) throws IOException {
        Files.writeString(CONFIG, String.join("\n", entries) + "\n");
    }
}
