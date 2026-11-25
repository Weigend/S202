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
package de.weigend.s202.domain;

import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.TypeUse;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyDetailsTest {

    private final DependencyModel model = new DependencyModel();

    @Test
    void everyCallerAndEveryCalledMethodIsListedMostFrequentFirst() {
        addClass("w.ViewArea", "getParent()Lw/ViewArea;");
        addClass("w.TabArea", "remove(Lw/ViewStatus;)V").addDependency("w.ViewArea", EdgeKind.EXTENDS);
        addClass("w.ViewStatus", "setDividerPositions()V", "lambda$initTab$1()V");
        call("w.ViewStatus", "setDividerPositions()V", "w.TabArea.getParent", 2, "()Lw/ViewArea;");
        call("w.ViewStatus", "lambda$initTab$1()V", "w.TabArea.remove", 1, "(Lw/ViewStatus;)V");

        DependencyDetails details = DependencyDetails.of(model, "w.ViewStatus", "w.TabArea");

        assertEquals(List.of(
                new DependencyDetails.Call("setDividerPositions", "()V", "getParent",
                        List.of("()Lw/ViewArea;"), "w.ViewArea", 2),
                new DependencyDetails.Call("lambda$initTab$1", "()V", "remove",
                        List.of("(Lw/ViewStatus;)V"), "w.TabArea", 1)), details.calls());
        assertEquals(3, details.callCount());
    }

    @Test
    void theMainCallIsTheMostFrequentOneWithAKnownDeclaration() {
        addClass("p.B", "known()V");
        addClass("p.A", "source()V");
        call("p.A", "source()V", "p.B.fromALibrary", 5, "()V");
        call("p.A", "source()V", "p.B.known", 1, "()V");

        DependencyDetails details = DependencyDetails.of(model, "p.A", "p.B");

        assertEquals("known", details.mainCall().orElseThrow().member());
        assertNull(details.calls().get(0).declaringClass());
    }

    @Test
    void theCallsOfSeveralOverloadsAreCountedOnce() {
        addClass("p.B", "called(I)V", "called()V");
        addClass("p.A", "source()V");
        call("p.A", "source()V", "p.B.called", 3, "(I)V", "()V");

        DependencyDetails details = DependencyDetails.of(model, "p.A", "p.B");

        assertEquals(3, details.callCount(), "the reader counts per name, not per overload");
        assertEquals(List.of("()V", "(I)V"), details.calls().get(0).descriptors());
        assertNull(details.calls().get(0).descriptor(), "no single overload is meant");
    }

    @Test
    void theMethodNameDoesWhenNoDescriptorWasRecorded() {
        addClass("p.B", "called()V");
        addClass("p.A", "source()V");
        call("p.A", "source()V", "p.B.called", 1);

        DependencyDetails.Call call = DependencyDetails.of(model, "p.A", "p.B").mainCall().orElseThrow();

        assertEquals("p.B", call.declaringClass());
        assertNull(call.descriptor());
    }

    @Test
    void nestedClassesCountForTheirOuterClassAndSimilarNamesDoNot() {
        addClass("p.B", "run()V");
        addClass("p.Bigger", "run()V");
        addClass("p.A", "source()V");
        call("p.A", "source()V", "p.B$Inner.run", 1, "()V");
        call("p.A", "source()V", "p.Bigger.run", 1, "()V");

        DependencyDetails details = DependencyDetails.of(model, "p.A", "p.B");

        assertEquals(List.of("Inner.run"), details.calls().stream().map(DependencyDetails.Call::member).toList());
    }

    @Test
    void aClassInAPackageNamedLikeTheTargetKeepsItsCalls() {
        addClass("pkg", "f()V");
        addClass("pkg.mod", "Cls.f()V");
        addClass("p.A", "source()V");
        call("p.A", "source()V", "pkg.mod.Cls.f", 1, "()V");

        assertTrue(DependencyDetails.of(model, "p.A", "pkg").calls().isEmpty());
        DependencyDetails.Call call = DependencyDetails.of(model, "p.A", "pkg.mod").mainCall().orElseThrow();
        assertEquals("Cls.f", call.member(), "a method of a class inside a Python module");
        assertEquals("pkg.mod", call.declaringClass());
    }

    @Test
    void otherKindsAreTheOnesTheCallsDoNotAccountFor() {
        addClass("p.B", "run()V", "<init>()V");
        DependencyModel.ClassInfo source = addClass("p.A", "source()V");
        source.addDependency("p.B", EdgeKind.CALLS);
        source.addDependency("p.B", EdgeKind.INSTANTIATES);
        source.addDependency("p.B", EdgeKind.USES);

        assertEquals(Set.of(EdgeKind.CALLS, EdgeKind.INSTANTIATES, EdgeKind.USES),
                DependencyDetails.of(model, "p.A", "p.B").otherKinds(), "no methods recorded");

        call("p.A", "source()V", "p.B.run", 1, "()V");
        call("p.A", "source()V", "p.B.<init>", 1, "()V");

        assertEquals(Set.of(EdgeKind.USES), DependencyDetails.of(model, "p.A", "p.B").otherKinds());
    }

    @Test
    void recordedPlacesAccountForTheUsesOfATypeAndComeFieldsFirst() {
        addClass("p.B");
        DependencyModel.ClassInfo source = addClass("p.A");
        source.addDependency("p.B", EdgeKind.EXTENDS);
        source.addDependency("p.B", EdgeKind.USES);
        TypeUse parameter = new TypeUse(TypeUse.Kind.PARAMETER, "set", "(Lp/B;)V", null);
        TypeUse field = new TypeUse(TypeUse.Kind.FIELD, "b", null, null);
        source.addTypeUse("p.B", parameter);
        source.addTypeUse("p.B", new TypeUse(TypeUse.Kind.NEW, "set", "(Lp/B;)V", null));
        source.addTypeUse("p.B", field);

        DependencyDetails details = DependencyDetails.of(model, "p.A", "p.B");

        assertEquals(List.of(field, parameter), details.references());
        assertEquals(Set.of(EdgeKind.EXTENDS), details.otherKinds());
    }

    @Test
    void inheritedMethodsAreFoundInSuperclassesBeforeInterfacesAndCyclesEnd() {
        addClass("p.Iface", "both()V", "onlyIface()V").addDependency("p.Sub", EdgeKind.IMPLEMENTS);
        addClass("p.Base", "both()V");
        DependencyModel.ClassInfo sub = addClass("p.Sub");
        sub.addDependency("p.Iface", EdgeKind.IMPLEMENTS);
        sub.addDependency("p.Base", EdgeKind.EXTENDS);

        assertEquals("p.Base", MethodDeclarations.declaringClass(model, "p.Sub", "both", "()V"));
        assertEquals("p.Iface", MethodDeclarations.declaringClass(model, "p.Sub", "onlyIface", "()V"));
        assertNull(MethodDeclarations.declaringClass(model, "p.Sub", "nowhere", "()V"));
    }

    @Test
    void anUnknownDependencyHasNoDetails() {
        assertTrue(DependencyDetails.of(null, "p.A", "p.B").calls().isEmpty());
        assertTrue(DependencyDetails.of(model, "p.A", "p.B").kinds().isEmpty());
    }

    /** The details must explain exactly the number the tangle view writes on the arrow. */
    @Test
    void theCallsOfRealBytecodeAddUpToTheWeightOfTheDependency() throws Exception {
        Lookup.init();
        try {
            DependencyModel raw = new InputAnalyzer().analyze("../test-example/target/test-example-1.6.0.jar");
            DomainModel domain = new LevelCalculator().calculate(raw);
            int checked = 0;
            for (DomainModel.CalculatedElementInfo source : domain.getAllClasses().values()) {
                for (String target : source.dependencies) {
                    if (domain.getClass(target) == null) {
                        continue;
                    }
                    int calls = DependencyDetails.of(raw, source.fullName, target).callCount();
                    assertEquals(domain.getClassEdgeWeight(source.fullName, target), Math.max(1, calls),
                            source.fullName + " -> " + target);
                    checked++;
                }
            }
            assertTrue(checked > 10, "only " + checked + " dependencies checked");
        } finally {
            Lookup.shutdown();
        }
    }

    private DependencyModel.ClassInfo addClass(String fqn, String... methods) {
        int dot = fqn.lastIndexOf('.');
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                fqn, fqn.substring(dot + 1), dot < 0 ? "" : fqn.substring(0, dot));
        for (String method : methods) {
            int paren = method.indexOf('(');
            info.addMethod(method.substring(0, paren), method.substring(paren));
        }
        model.addClass(fqn, info);
        return info;
    }

    private void call(String from, String caller, String call, int count, String... descriptors) {
        DependencyModel.MethodInfo method = model.getClass(from).methods.get(caller);
        method.methodCalls.put(call, count);
        if (descriptors.length > 0) {
            method.methodCallDescriptors.put(call, new HashSet<>(List.of(descriptors)));
        }
    }
}
