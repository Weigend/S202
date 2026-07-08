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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.javascript.JavaScriptSourceAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test: the JavaScript reader on the real City3D web app it was
 * built for. Locates {@code city3d/} relative to the module directory and,
 * if present, verifies that its ES-module dependency graph comes through
 * meaningfully.
 */
class City3DJavaScriptAnalysisTest {

    @Test
    void analyzesCity3DModuleGraph() throws IOException {
        Path city3d = locateCity3D();
        assumeTrue(city3d != null, "city3d/ not found relative to module — skipping integration test");

        DependencyModel model = new JavaScriptSourceAnalyzer().analyze(city3d);

        // All 13 ES modules under city3d/src land in one "city3d" package.
        assertNotNull(model.getClass("city3d.main"), "main module missing");
        assertNotNull(model.getClass("city3d.vehicles"));
        assertNotNull(model.getClass("city3d.i18n"));
        assertNotNull(model.getPackage("city3d"));
        assertTrue(model.getPackage("city3d").classNames.size() >= 13,
                "expected >= 13 modules, got " + model.getPackage("city3d").classNames.size());

        DependencyModel.ClassInfo main = model.getClass("city3d.main");
        // main wires the app together: imports the feature modules …
        assertTrue(main.dependencies.contains("city3d.city"));
        assertTrue(main.dependencies.contains("city3d.vehicles"));
        assertTrue(main.getKinds("city3d.city").contains(EdgeKind.IMPORTS));
        // … and drives them via `new Traffic(...)` / `buildCity(...)`.
        assertTrue(main.getKinds("city3d.vehicles").contains(EdgeKind.INSTANTIATES),
                "expected main to instantiate Traffic from vehicles");
        assertTrue(main.getKinds("city3d.city").contains(EdgeKind.CALLS),
                "expected main to call buildCity from city");

        // city.js aggregates the layout/rendering helpers.
        DependencyModel.ClassInfo city = model.getClass("city3d.city");
        assertTrue(city.dependencies.contains("city3d.adapter"));

        // External Three.js is never a project module — no bare-specifier edges leak in.
        assertNull(model.getClass("three"));
        assertTrue(model.getAllClasses().keySet().stream().noneMatch(name -> name.contains("three")));

        // A real, non-trivial method signal (main.js is the largest module).
        assertTrue(main.methods.size() >= 10,
                "expected a meaningful method count for main, got " + main.methods.size());

        // End-to-end: the model must flow through the level pipeline (its real
        // consumer) and produce architecture levels for the modules.
        DomainModel domain = new LevelCalculator().calculate(model);
        assertNotNull(domain.getClass("city3d.main"));
        assertTrue(domain.getMaxLevel() >= 1,
                "expected the module graph to yield >= 2 levels, max level was " + domain.getMaxLevel());
    }

    private static Path locateCity3D() {
        for (Path candidate : new Path[]{Path.of("../city3d"), Path.of("city3d")}) {
            if (Files.isDirectory(candidate.resolve("src"))) {
                return candidate;
            }
        }
        return null;
    }
}
