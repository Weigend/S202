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

import de.weigend.s202.domain.DependencyDetails;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A nested class counts for its outer class, as source and as target of a dependency. */
class NestedClassAttributionTest {

    private static final String PKG = "de.weigend.s202.reader.";
    private static final String OUTER = PKG + "NestedOuter";
    private static final String SERVICE = PKG + "NestedService";

    /** Nested classes first, as they come in many jars. */
    private static final List<String> CLASSES = List.of(
            "NestedOuter$Member", "NestedOuter$Member$Deep", "NestedOuter$1", "NestedOuter$1Local",
            "NestedOuter$DI", "NestedOuter$DI$1",
            "NestedHost$Listener", "NestedService$Callback",
            "NestedOuter", "NestedService", "NestedContract", "NestedBase", "NestedHost", "NestedClient",
            "NestedGenerated", "NestedGenerated2", "NestedFromAnonymous");

    @TempDir
    Path directory;

    @Test
    void nestedClassesAreNoClassesOfTheirOwn() throws Exception {
        DependencyModel model = analyze(CLASSES);

        assertTrue(model.getAllClassNames().stream().noneMatch(name -> name.contains("$")));
        assertTrue(model.getAllClasses().values().stream()
                .flatMap(info -> info.dependencies.stream()).noneMatch(name -> name.contains("$")));
    }

    @Test
    void callsOfMemberLocalAndAnonymousClassesCountForTheOuterClass() throws Exception {
        DependencyModel model = analyze(CLASSES);

        assertTrue(model.getClass(OUTER).getKinds(SERVICE).contains(EdgeKind.CALLS));
        List<String> callers = DependencyDetails.of(model, OUTER, SERVICE).calls().stream()
                .filter(call -> call.member().equals("serve"))
                .map(DependencyDetails.Call::callerName).sorted().toList();
        assertEquals(List.of("1.run", "1Local.work", "Member$Deep.deep", "Member.member"), callers);
        assertTrue(model.getClass(OUTER).getKinds(PKG + "NestedFromAnonymous").contains(EdgeKind.INSTANTIATES));
    }

    @Test
    void theWeightOfTheDependencyIncludesTheCallsOfNestedClasses() throws Exception {
        DependencyModel model = analyze(CLASSES);
        DomainModel domain = new LevelCalculator().calculate(model);

        assertEquals(DependencyDetails.of(model, OUTER, SERVICE).callCount(),
                domain.getClassEdgeWeight(OUTER, SERVICE));
    }

    @Test
    void aNestedClassAndItsOuterClassDoNotDependOnEachOther() throws Exception {
        assertFalse(analyze(CLASSES).getClass(OUTER).dependencies.contains(OUTER));
    }

    @Test
    void inheritanceOfANestedClassCountsButMakesTheOuterClassNoSubtype() throws Exception {
        DependencyModel model = analyze(CLASSES);

        Set<EdgeKind> contract = model.getClass(OUTER).getKinds(PKG + "NestedContract");
        assertTrue(contract.contains(EdgeKind.NESTED_INHERITANCE));
        assertFalse(contract.contains(EdgeKind.IMPLEMENTS));
        Set<EdgeKind> base = model.getClass(OUTER).getKinds(PKG + "NestedBase");
        assertTrue(base.contains(EdgeKind.NESTED_INHERITANCE));
        assertFalse(base.contains(EdgeKind.EXTENDS));
        assertTrue(new LevelCalculator().calculate(model).getClass(OUTER).dependencies
                .contains(PKG + "NestedContract"), "it is a dependency like any other");
    }

    @Test
    void inheritingFromANestedClassIsADependencyOnItsOuterClass() throws Exception {
        DependencyModel model = analyze(CLASSES);

        assertEquals(Set.of(EdgeKind.NESTED_INHERITANCE),
                model.getClass(PKG + "NestedClient").getKinds(PKG + "NestedHost"));
        assertTrue(new LevelCalculator().calculate(model).getClass(PKG + "NestedClient").dependencies
                .contains(PKG + "NestedHost"));
    }

    @Test
    void methodsOfANestedClassAreMethodsOfTheOuterClass() throws Exception {
        DependencyModel model = analyze(CLASSES);

        assertTrue(model.getClass(OUTER).methods.containsKey("Member.member()V"));
        DependencyDetails.Call call = DependencyDetails.of(model, PKG + "NestedClient", SERVICE).calls().stream()
                .filter(c -> c.member().equals("Callback.back")).findFirst().orElseThrow();
        assertEquals(SERVICE, call.declaringClass(), "a call into a nested class leads to its method");
    }

    @Test
    void fieldsOfANestedClassAreNamedWithIt() throws Exception {
        assertTrue(analyze(CLASSES).getClass(OUTER).typeUses.get(SERVICE).contains(
                new TypeUse(TypeUse.Kind.FIELD, "Member.service", null, null)));
    }

    @Test
    void aGeneratedTopLevelClassWithADollarIsNotNested() throws Exception {
        DependencyModel.ClassInfo outer = analyze(CLASSES).getClass(OUTER);

        assertFalse(outer.dependencies.contains(PKG + "NestedGenerated"));
        assertFalse(outer.dependencies.contains(PKG + "NestedGenerated2"), "nor is what is nested in it");
    }

    @Test
    void aNestedClassWithoutItsOuterClassIsLeftOut() throws Exception {
        DependencyModel model = analyze(List.of("NestedOuter$Member", "NestedService"));

        assertNull(model.getClass(OUTER));
        assertEquals(Set.of(SERVICE), model.getAllClassNames());
    }

    private DependencyModel analyze(List<String> classes) throws Exception {
        Path jar = directory.resolve("nested.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            for (String name : classes) {
                String path = (PKG + name).replace('.', '/') + ".class";
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                    if (in == null) {
                        throw new AssertionError("no such class: " + path);
                    }
                    jarOut.putNextEntry(new JarEntry(path));
                    in.transferTo(jarOut);
                    jarOut.closeEntry();
                }
            }
        }
        return new InputAnalyzer().analyze(jar.toString());
    }
}

interface NestedContract {
    void contract();
}

class NestedBase {
}

class NestedService {
    void serve() {
    }

    static class Callback {
        void back() {
        }
    }
}

class NestedFromAnonymous {
}

class NestedGenerated {
}

class NestedGenerated2 {
}

class NestedHost {
    interface Listener {
    }
}

class NestedClient implements NestedHost.Listener {
    void client(NestedService.Callback callback) {
        callback.back();
    }
}

class NestedOuter {
    private int counter;

    static class Member extends NestedBase {
        private NestedService service;

        void member() {
            service.serve();
        }

        class Deep implements NestedContract {
            void deep() {
                service.serve();
            }

            @Override
            public void contract() {
            }
        }
    }

    Runnable anonymous(NestedService service) {
        return new Runnable() {
            @Override
            public void run() {
                counter++;
                service.serve();
                new NestedFromAnonymous();
            }
        };
    }

    void local(NestedService service) {
        class Local {
            void work() {
                service.serve();
            }
        }
        new Local().work();
    }
}

/** Named like a nested class, but a top-level one: what code generators produce. */
class NestedOuter$DI {
    Object generated = new NestedGenerated();

    Runnable anonymous() {
        return new Runnable() {
            @Override
            public void run() {
                new NestedGenerated2();
            }
        };
    }
}
