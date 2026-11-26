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
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainModelWithoutTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    private final SCCFinder finder = Lookup.lookup(SCCFinder.class);
    private final DomainModel model = model();

    @Test
    void withoutCutsItIsTheModelItself() {
        assertSame(model, model.without(Set.of(), finder));
    }

    @Test
    void aCutTakesTheDependencyTheBackEdgeAndTheTangle() {
        DependencyEdge backEdge = model.getClassBackEdges().iterator().next();
        assertEquals(1, model.getContainerTangles().size());

        DomainModel cut = model.without(Set.of(backEdge), finder);

        assertFalse(cut.getClass(backEdge.from()).dependencies.contains(backEdge.to()));
        assertFalse(cut.isClassBackEdge(backEdge.from(), backEdge.to()));
        assertEquals(0, cut.getClassEdgeWeight(backEdge.from(), backEdge.to()));
        assertTrue(cut.getContainerTangles().isEmpty());
        assertTrue(cut.getContainerBackEdges().isEmpty());
        assertFalse(cut.getClass(backEdge.to()).dependents.contains(backEdge.from()));
    }

    @Test
    void whatIsLeftStaysAsItWasAnalyzed() {
        DependencyEdge backEdge = model.getClassBackEdges().iterator().next();

        DomainModel cut = model.without(Set.of(backEdge), finder);

        model.getAllClasses().forEach((name, cls) -> {
            assertEquals(cls.localLevel, cut.getClass(name).localLevel, name);
            assertEquals(cls.architectureLevel, cut.getClass(name).architectureLevel, name);
        });
        assertEquals(1, cut.getSiblingEdgeWeight("p", "q"));
        assertTrue(model.getClass(backEdge.from()).dependencies.contains(backEdge.to()), "the model itself is untouched");
    }

    /** p uses q twice, q uses p once. */
    private static DomainModel model() {
        DependencyModel raw = new DependencyModel();
        add(raw, "p.A", "q.B");
        add(raw, "p.X", "p.A");
        add(raw, "q.B");
        add(raw, "q.C", "p.D");
        add(raw, "p.D");
        add(raw, "q.E", "q.C");
        raw.getClass("p.A").addMethod("run", "()V");
        raw.getClass("p.A").getMethod("run", "()V").methodCalls.put("q.B.go", 1);
        raw.getClass("q.C").addMethod("run", "()V");
        raw.getClass("q.C").getMethod("run", "()V").methodCalls.put("p.D.go", 1);
        add(raw, "p.Y", "q.B");
        PackageHierarchyBuilder.buildPackageHierarchy(raw);
        DomainModel model = new LevelCalculator().calculate(raw);
        // Leave one way from p to q, so the numbers in the test are easy to see.
        return model.without(Set.of(new DependencyEdge("p.Y", "q.B")), Lookup.lookup(SCCFinder.class));
    }

    private static void add(DependencyModel raw, String name, String... targets) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        for (String target : targets) {
            info.addDependency(target, EdgeKind.CALLS);
        }
        raw.addClass(name, info);
    }
}
