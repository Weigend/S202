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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The cyclomatic complexity and the lines with code that the Java reader measures. */
class ComplexityTest {

    private static final String NAME = "de.weigend.s202.reader.ComplexityFixture";

    @TempDir
    Path directory;
    private DependencyModel.ClassInfo fixture;

    @BeforeEach
    void analyze() throws Exception {
        Path jar = directory.resolve("complexity.jar");
        String path = NAME.replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry(path));
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                in.transferTo(jarOut);
            }
            jarOut.closeEntry();
        }
        fixture = new InputAnalyzer().analyze(jar.toString()).getClass(NAME);
    }

    private DependencyModel.MethodInfo method(String name) {
        return fixture.methods.values().stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aMethodWithoutBranchesHasOnePath() {
        assertEquals(1, method("straight").complexity);
        assertEquals(1, method("straight").lines);
    }

    @Test
    void everyConditionCountsOnce() {
        // if with &&: two, the loop: one, the conditional expression: one.
        assertEquals(5, method("branches").complexity);
    }

    @Test
    void everyCaseOfASwitchCounts() {
        assertEquals(4, method("cases").complexity);
    }

    @Test
    void aCatchIsAPathOfItsOwn() {
        assertEquals(2, method("caught").complexity);
    }

    @Test
    void aMethodWithoutCodeHasNone() {
        assertEquals(0, method("nothing").complexity);
        assertEquals(0, method("nothing").lines);
    }
}

@SuppressWarnings("unused")
abstract class ComplexityFixture {

    int straight(int a) {
        return a + 1;
    }

    int branches(int a, int b) {
        int r = 0;
        if (a > 0 && b > 0) {
            r = 1;
        }
        for (int i = 0; i < a; i++) {
            r += i;
        }
        return r > 10 ? r : -r;
    }

    int cases(int k) {
        switch (k) {
            case 1:
                return 10;
            case 2:
                return 20;
            case 3:
                return 30;
            default:
                return 0;
        }
    }

    int caught(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    abstract int nothing();
}
