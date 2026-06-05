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
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.c.CSourceAnalyzer;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CSourceAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsCFilesToClassInfoAndPackageHierarchy() throws IOException {
        write("include/list.h", """
                typedef struct list list;
                void list_init(list * l);
                """);
        write("src/util/list/list.c", """
                #include "list.h"

                void list_init(list * l) {
                }
                """);

        DependencyModel model = new CSourceAnalyzer().analyze(tempDir);

        assertNotNull(model.getClass("util.list.list"));
        assertEquals("util.list", model.getClass("util.list.list").packageName);
        assertEquals("list", model.getClass("util.list.list").simpleName);
        assertNotNull(model.getPackage("util"));
        assertTrue(model.getPackage("util").childPackages.contains("util.list"));
        assertTrue(model.getPackage("util.list").classNames.contains("util.list.list"));
        assertNotNull(model.getClass("util.list.list").getMethod("list_init", "(list * l)"));
    }

    @Test
    void resolvesProjectIncludesAndDirectFunctionCalls() throws IOException {
        write("include/list.h", """
                typedef struct list list;
                void list_init(list * l);
                """);
        write("include/knde.h", """
                typedef struct knde knde;
                void knde_init(knde * k);
                """);
        write("src/util/list/list.c", """
                #include "list.h"

                void list_init(list * l) {
                }
                """);
        write("src/adt/knde/knde.c", """
                #include "knde.h"
                #include "list.h"

                void knde_init(knde * k) {
                    list_init((list *) k);
                    list_init((list *) k);
                }
                """);

        DependencyModel model = new CSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo knde = model.getClass("adt.knde.knde");

        assertTrue(knde.dependencies.contains("util.list.list"));
        assertTrue(knde.getKinds("util.list.list").contains(EdgeKind.IMPORTS));
        assertTrue(knde.getKinds("util.list.list").contains(EdgeKind.CALLS));

        DependencyModel.MethodInfo init = knde.getMethod("knde_init", "(knde * k)");
        assertNotNull(init);
        assertEquals(2, init.methodCalls.get("util.list.list.list_init"));
        assertEquals(Set.of("(list * l)"), init.methodCallDescriptors.get("util.list.list.list_init"));
    }

    @Test
    void resolvesHeaderOwnerByDeclaredFunctionWhenHeaderNameDiffers() throws IOException {
        write("include/repository_api.h", "void repo_save(void);\n");
        write("src/repo/repo.c", """
                #include "repository_api.h"

                void repo_save(void) {
                }
                """);
        write("src/service/service.c", """
                #include "repository_api.h"

                void service_run(void) {
                    repo_save();
                }
                """);

        DependencyModel model = new CSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo service = model.getClass("service.service");

        assertTrue(service.dependencies.contains("repo.repo"));
        assertTrue(service.getKinds("repo.repo").contains(EdgeKind.IMPORTS));
        assertTrue(service.getKinds("repo.repo").contains(EdgeKind.CALLS));
        assertEquals(1, service.getMethod("service_run", "()")
                .methodCalls.get("repo.repo.repo_save"));
    }

    @Test
    void prefersLocalStaticFunctionsOverSameNamedGlobalDefinitions() throws IOException {
        write("src/a/a.c", """
                static void helper(void) {
                }

                void run(void) {
                    helper();
                }
                """);
        write("src/b/helper.c", """
                void helper(void) {
                }
                """);

        DependencyModel model = new CSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo a = model.getClass("a.a");

        assertFalse(a.dependencies.contains("b.helper"));
        assertTrue(a.getMethod("run", "()").methodCalls.isEmpty());
    }

    @Test
    void producedModelRunsThroughLevelCalculator() throws IOException {
        write("include/core.h", "void core_do(void);\n");
        write("src/core/core.c", """
                #include "core.h"

                void core_do(void) {
                }
                """);
        write("src/app/app.c", """
                #include "core.h"

                void app_run(void) {
                    core_do();
                }
                """);

        DependencyModel raw = new CSourceAnalyzer().analyze(tempDir);
        DomainModel calculated = new LevelCalculator().calculate(raw);

        assertNotNull(calculated.getClass("app.app"));
        assertNotNull(calculated.getPackage("app"));
        assertEquals(1, calculated.getClass("app.app").architectureLevel);
        assertEquals(0, calculated.getClass("core.core").architectureLevel);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
