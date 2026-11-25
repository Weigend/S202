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
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A method reference depends on the class of the method it names. */
class MethodReferenceTest {

    private static final String PKG = "de.weigend.s202.reader.";

    @TempDir
    Path directory;

    private DependencyModel model;

    @BeforeEach
    void analyze() throws Exception {
        Path jar = directory.resolve("references.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            for (String name : List.of("ReferenceSource", "ReferenceSource$1", "ReferenceStatic", "ReferenceVirtual",
                    "ReferenceInterface", "ReferenceCreated", "ReferenceFromNested", "ReferenceHost",
                    "ReferenceHost$Inner")) {
                String path = (PKG + name).replace('.', '/') + ".class";
                jarOut.putNextEntry(new JarEntry(path));
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                    in.transferTo(jarOut);
                }
                jarOut.closeEntry();
            }
        }
        model = new InputAnalyzer().analyze(jar.toString());
    }

    @Test
    void everyKindOfMethodReferenceIsACall() {
        DependencyModel.ClassInfo source = model.getClass(PKG + "ReferenceSource");

        assertTrue(source.getKinds(PKG + "ReferenceStatic").contains(EdgeKind.CALLS));
        assertTrue(source.getKinds(PKG + "ReferenceVirtual").contains(EdgeKind.CALLS));
        assertTrue(source.getKinds(PKG + "ReferenceInterface").contains(EdgeKind.CALLS));
        assertTrue(source.getKinds(PKG + "ReferenceFromNested").contains(EdgeKind.CALLS), "in an anonymous class");
        assertTrue(source.getKinds(PKG + "ReferenceHost").contains(EdgeKind.CALLS), "to a nested class");
    }

    @Test
    void aConstructorReferenceInstantiates() {
        assertEquals(Set.of(EdgeKind.INSTANTIATES),
                model.getClass(PKG + "ReferenceSource").getKinds(PKG + "ReferenceCreated"));
    }

    @Test
    void aReferenceIsListedWithTheMethodThatHoldsItAndTheOneItNames() {
        DependencyDetails.Call call = DependencyDetails.of(model, PKG + "ReferenceSource", PKG + "ReferenceStatic")
                .mainCall().orElseThrow();

        assertEquals("staticReference", call.callerName());
        assertEquals("apply", call.member());
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", call.descriptor());
        assertEquals(1, call.count());
    }

    @Test
    void referencesToOwnMethodsAndLambdasAreNoDependencies() {
        DependencyModel.ClassInfo source = model.getClass(PKG + "ReferenceSource");

        assertFalse(source.dependencies.contains(PKG + "ReferenceSource"));
        assertTrue(source.methods.get("ownReference()Ljava/util/function/Supplier;").methodCalls.isEmpty());
    }
}

class ReferenceStatic {
    static String apply(String text) {
        return text;
    }
}

class ReferenceVirtual {
    String get() {
        return "";
    }
}

interface ReferenceInterface {
    String get();
}

class ReferenceCreated {
}

class ReferenceFromNested {
    static void run() {
    }
}

class ReferenceHost {
    static class Inner {
        static String apply(String text) {
            return text;
        }
    }
}

class ReferenceSource {
    Function<String, String> staticReference() {
        return ReferenceStatic::apply;
    }

    Function<ReferenceVirtual, String> virtualReference() {
        return ReferenceVirtual::get;
    }

    Function<ReferenceInterface, String> interfaceReference() {
        return ReferenceInterface::get;
    }

    Supplier<Object> constructorReference() {
        return ReferenceCreated::new;
    }

    Function<String, String> nestedReference() {
        return ReferenceHost.Inner::apply;
    }

    Supplier<String> ownReference() {
        return this::own;
    }

    String own() {
        return "";
    }

    Supplier<Runnable> anonymous() {
        return new Supplier<>() {
            @Override
            public Runnable get() {
                return ReferenceFromNested::run;
            }
        };
    }
}
