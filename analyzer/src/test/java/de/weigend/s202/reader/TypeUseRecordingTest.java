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

import de.weigend.s202.reader.TypeUse.Kind;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the bytecode of one class refers to another without calling it. */
class TypeUseRecordingTest {

    private static final String PKG = "de.weigend.s202.reader.";
    private static final String TARGET_DESCRIPTOR = "Lde/weigend/s202/reader/TypeUseTarget;";

    @TempDir
    Path directory;

    @Test
    void everyPlaceIsRecordedWithItsMember() throws Exception {
        Set<TypeUse> uses = analyze().typeUses.get(PKG + "TypeUseTarget");

        assertTrue(uses.contains(new TypeUse(Kind.FIELD, "single", null, null)));
        assertTrue(uses.contains(new TypeUse(Kind.FIELD, "many", null, null)), "named by the generic signature only");
        assertTrue(uses.contains(new TypeUse(Kind.FIELD, "nested", null, null)), "a nested class counts for its outer one");
        assertTrue(uses.contains(new TypeUse(Kind.RETURN, "get", "()" + TARGET_DESCRIPTOR, null)));
        assertTrue(uses.contains(new TypeUse(Kind.PARAMETER, "set", "(" + TARGET_DESCRIPTOR + ")V", null)));
        assertTrue(uses.contains(new TypeUse(Kind.PARAMETER, "all", "(Ljava/util/List;)V", null)));
        assertTrue(uses.contains(new TypeUse(Kind.CAST, "cast", "(Ljava/lang/Object;)Z", null)));
        assertTrue(uses.contains(new TypeUse(Kind.INSTANCEOF, "cast", "(Ljava/lang/Object;)Z", null)));
        assertTrue(uses.contains(new TypeUse(Kind.CLASS_LITERAL, "literal", "()Ljava/lang/Object;", null)));
        assertTrue(uses.contains(new TypeUse(Kind.ARRAY, "array", "()Ljava/lang/Object;", null)));
        assertTrue(uses.contains(new TypeUse(Kind.NEW, "create", "()Ljava/lang/Object;", null)));
        assertTrue(uses.contains(new TypeUse(Kind.FIELD_ACCESS, "read", "()I", "TypeUseTarget.value")));
    }

    @Test
    void exceptionsAndTypeParameterBoundsAreRecorded() throws Exception {
        DependencyModel.ClassInfo source = analyze();

        assertEquals(Set.of(new TypeUse(Kind.THROWS, "fail", "()V", null)),
                source.typeUses.get(PKG + "TypeUseException"));
        assertTrue(source.typeUses.get(PKG + "TypeUseTarget").contains(
                new TypeUse(Kind.TYPE_PARAMETER, "bound", "()V", null)));
    }

    @Test
    void caughtExceptionsAreRecorded() throws Exception {
        assertEquals(Set.of(new TypeUse(Kind.CATCH, "handle", "()V", null)),
                analyze().typeUses.get(PKG + "TypeUseCaught").stream()
                        .filter(use -> use.kind() == Kind.CATCH).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void annotationsAreRecordedWhereTheyStandAndWithWhatTheirValuesName() throws Exception {
        DependencyModel.ClassInfo source = analyze();

        Set<TypeUse> annotation = source.typeUses.get(PKG + "TypeUseAnnotation");
        assertTrue(annotation.contains(new TypeUse(Kind.ANNOTATION, "", null, null)), "on the class");
        assertTrue(annotation.contains(new TypeUse(Kind.ANNOTATION, "single", null, null)), "on a field");
        assertTrue(annotation.contains(new TypeUse(Kind.ANNOTATION, "annotated", "(I)V", null)), "on a parameter");
        assertTrue(source.typeUses.get(PKG + "TypeUseInAnnotation").contains(
                new TypeUse(Kind.CLASS_LITERAL, "", null, null)), "a class as value");
        assertTrue(source.typeUses.get(PKG + "TypeUseEnum").contains(
                new TypeUse(Kind.FIELD_ACCESS, "", null, "TypeUseEnum.ONE")), "an enum constant as value");
    }

    @Test
    void typeArgumentsOfSupertypesAreRecordedButNotTheSupertypesThemselves() throws Exception {
        DependencyModel.ClassInfo source = analyze();

        assertEquals(Set.of(new TypeUse(Kind.TYPE_ARGUMENT, "", null, null)),
                source.typeUses.get(PKG + "TypeUseArgument"));
        assertEquals(Set.of(new TypeUse(Kind.TYPE_PARAMETER, "", null, null)),
                source.typeUses.get(PKG + "TypeUseBound"));
        assertFalse(source.typeUses.getOrDefault(PKG + "TypeUseSuper", Set.of()).contains(
                new TypeUse(Kind.TYPE_ARGUMENT, "", null, null)));
    }

    /** "Other references" must not name a type the model has no dependency on. */
    @Test
    void everyRecordedTypeIsAUsesDependencyAndTheOtherWayRound() throws Exception {
        DependencyModel.ClassInfo source = analyze();

        for (String target : source.typeUses.keySet()) {
            assertTrue(source.getKinds(target).contains(EdgeKind.USES), target);
        }
        for (String target : source.dependencies) {
            assertEquals(source.getKinds(target).contains(EdgeKind.USES), source.typeUses.containsKey(target), target);
        }
        assertEquals(Set.of(EdgeKind.USES), source.getKinds(PKG + "TypeUseOnlyReturned"), "no generics needed");
        assertEquals(Set.of(EdgeKind.USES), source.getKinds(PKG + "TypeUseOnlyPassed"));
    }

    @Test
    void readingAnOwnFieldIsSaidByItsDeclaration() throws Exception {
        assertTrue(analyze().typeUses.get(PKG + "TypeUseTarget").stream()
                .noneMatch(use -> use.kind() == Kind.FIELD_ACCESS && use.member().equals("get")));
    }

    @Test
    void neitherTheClassItselfNorExcludedLibrariesAppear() throws Exception {
        Set<String> targets = analyze().typeUses.keySet();

        assertFalse(targets.contains(PKG + "TypeUseSource"));
        assertTrue(targets.stream().noneMatch(target -> target.startsWith("java.")), targets.toString());
    }

    @Test
    void excludedLibrariesAreKeptApartAsExternalDependencies() throws Exception {
        DependencyModel.ClassInfo source = analyze();

        assertEquals(Set.of(EdgeKind.USES), source.externalDependencies.get("java.util.List"));
        assertFalse(source.externalDependencies.containsKey("java.lang.Object"), "every class has it");
        assertTrue(source.externalDependencies.keySet().stream().allMatch(target -> target.startsWith("java.")),
                source.externalDependencies.toString());
        assertTrue(source.dependencies.stream().noneMatch(target -> target.startsWith("java.")));
    }

    @Test
    void theOriginIsTheFileTheClassWasReadFrom() throws Exception {
        assertEquals("type-uses.jar", analyze().getOrigin());
    }

    private DependencyModel.ClassInfo analyze() throws Exception {
        Path jar = directory.resolve("type-uses.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            for (Class<?> cls : List.of(TypeUseSource.class, TypeUseTarget.class, TypeUseException.class,
                    TypeUseCaught.class, TypeUseAnnotation.class, TypeUseInAnnotation.class, TypeUseEnum.class,
                    TypeUseArgument.class, TypeUseBound.class, TypeUseSuper.class, TypeUseOnlyReturned.class,
                    TypeUseOnlyPassed.class)) {
                String path = cls.getName().replace('.', '/') + ".class";
                jarOut.putNextEntry(new JarEntry(path));
                try (InputStream in = cls.getClassLoader().getResourceAsStream(path)) {
                    in.transferTo(jarOut);
                }
                jarOut.closeEntry();
            }
        }
        return new InputAnalyzer().analyze(jar.toString()).getClass(PKG + "TypeUseSource");
    }
}

class TypeUseTarget {
    int value = Integer.parseInt("1");

    static class Nested {
    }
}

class TypeUseException extends Exception {
}

class TypeUseCaught extends RuntimeException {
}

@interface TypeUseAnnotation {
    Class<?> type() default Object.class;

    TypeUseEnum[] constants() default {};
}

class TypeUseInAnnotation {
}

enum TypeUseEnum {
    ONE
}

class TypeUseArgument {
}

class TypeUseBound {
}

class TypeUseSuper<T> {
}

class TypeUseOnlyReturned {
}

class TypeUseOnlyPassed {
}

@TypeUseAnnotation(type = TypeUseInAnnotation.class, constants = TypeUseEnum.ONE)
class TypeUseSource<B extends TypeUseBound> extends TypeUseSuper<List<TypeUseArgument>> {
    @TypeUseAnnotation
    private TypeUseTarget single;
    private List<TypeUseTarget> many;
    private TypeUseTarget.Nested nested;

    void annotated(@TypeUseAnnotation int parameter) {
    }

    void handle() {
        try {
            hashCode();
        } catch (TypeUseCaught caught) {
            toString();
        }
    }

    TypeUseOnlyReturned returned() {
        return null;
    }

    void passed(TypeUseOnlyPassed parameter) {
    }

    TypeUseTarget get() {
        return single;
    }

    void set(TypeUseTarget target) {
        single = target;
    }

    void all(List<TypeUseTarget> targets) {
        many = targets;
    }

    boolean cast(Object object) {
        return object instanceof TypeUseTarget && ((TypeUseTarget) object).hashCode() > 0;
    }

    Object literal() {
        return TypeUseTarget.class;
    }

    Object array() {
        return new TypeUseTarget[1];
    }

    Object create() {
        nested = null;
        return new TypeUseTarget();
    }

    int read() {
        return single.value;
    }

    <T extends TypeUseTarget> void bound() {
    }

    void fail() throws TypeUseException {
    }
}
