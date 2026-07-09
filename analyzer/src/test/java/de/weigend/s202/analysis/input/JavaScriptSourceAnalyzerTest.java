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
package de.weigend.s202.analysis.input;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.javascript.JavaScriptSourceAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaScriptSourceAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsModulesToClassInfoAndPackageHierarchy() throws IOException {
        write("src/app/sky.js", "export class Atmosphere {}\n");
        write("src/app/main.js", "import { Atmosphere } from './sky.js';\n");

        DependencyModel model = new JavaScriptSourceAnalyzer().analyze(tempDir);

        DependencyModel.ClassInfo main = model.getClass("app.main");
        assertNotNull(main);
        assertEquals("app", main.packageName);
        assertEquals("main", main.simpleName);
        assertNotNull(model.getClass("app.sky"));
        assertTrue(model.getPackage("app").classNames.contains("app.main"));
    }

    @Test
    void resolvesRelativeImportsInstantiationsAndCalls() throws IOException {
        write("src/app/sky.js", "export class Atmosphere {}\n");
        write("src/app/city.js", "export function buildCity() { return 1; }\n");
        write("src/app/main.js", """
                import { Atmosphere } from './sky.js';
                import { buildCity } from './city.js';
                const scene = new Atmosphere();
                const c = buildCity();
                """);

        DependencyModel model = new JavaScriptSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo main = model.getClass("app.main");

        assertTrue(main.dependencies.contains("app.sky"));
        assertTrue(main.getKinds("app.sky").contains(EdgeKind.IMPORTS));
        assertTrue(main.getKinds("app.sky").contains(EdgeKind.INSTANTIATES));
        assertTrue(main.dependencies.contains("app.city"));
        assertTrue(main.getKinds("app.city").contains(EdgeKind.CALLS));
    }

    @Test
    void resolvesClassExtends() throws IOException {
        write("src/app/base.js", "export class Base {}\n");
        write("src/app/derived.js", """
                import { Base } from './base.js';
                export class Derived extends Base {}
                """);

        DependencyModel model = new JavaScriptSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo derived = model.getClass("app.derived");

        assertTrue(derived.getKinds("app.base").contains(EdgeKind.EXTENDS));
    }

    @Test
    void ignoresExternalPackagesAndImportsInCommentsOrStrings() throws IOException {
        write("src/app/real.js", "export const x = 1;\n");
        write("src/app/main.js", """
                import * as THREE from 'three';
                import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
                import { x } from './real.js';
                // import { fake } from './ghost.js';
                const doc = "import { alsoFake } from './ghost.js'";
                const mesh = new THREE.Mesh();
                """);

        DependencyModel model = new JavaScriptSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo main = model.getClass("app.main");

        // External npm packages never resolve to a project module.
        assertNull(model.getClass("three"));
        assertFalse(main.dependencies.stream().anyMatch(d -> d.contains("three")));
        // The commented / stringified imports must not create a ghost module edge.
        assertNull(model.getClass("app.ghost"));
        assertFalse(main.dependencies.contains("app.ghost"));
        // The one real relative import is present.
        assertTrue(main.dependencies.contains("app.real"));
        assertTrue(main.getKinds("app.real").contains(EdgeKind.IMPORTS));
    }

    @Test
    void countsTopLevelFunctionsAndClassMethodsAsMethods() throws IOException {
        write("src/app/mod.js", """
                export function first() {}
                function second() {}
                export const third = () => {};
                export class Widget {
                    build() {}
                    update(dt) {}
                }
                """);

        DependencyModel model = new JavaScriptSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo mod = model.getClass("app.mod");

        // first, second, third + Widget.build, Widget.update
        assertEquals(5, mod.methods.size());
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
