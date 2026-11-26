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

import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void withoutMovesEverythingSitsWhereItsNameSays() {
        Placement code = Placement.of(Map.of());

        assertSame(Placement.OF_THE_CODE, code);
        assertEquals(List.of("a", "a.b"), code.containersOf("a.b.C"));
        assertEquals(ContainerProjection.project("a.b.C", "a.d.E"), code.project("a.b.C", "a.d.E"));
        assertTrue(code.isIn("a.b.C", "a.b"));
        assertFalse(code.isIn("a.bb.C", "a.b"));
        assertTrue(code.isIn("a.b.C", ""));
    }

    @Test
    void aMovedClassIsWhereItWasPut() {
        Placement plan = Placement.of(Map.of("a.b.C", "a.d"));

        assertEquals(List.of("a", "a.d"), plan.containersOf("a.b.C"));
        assertNull(plan.memberContaining(Set.of("a.b"), "a.b.C"));
        assertEquals("a.d", plan.memberContaining(Set.of("a.b", "a.d"), "a.b.C"));
        assertEquals(new ContainerProjection.Projection("a.d", "a.b.C", "a.d.E"), plan.project("a.b.C", "a.d.E"));
        assertEquals(new ContainerProjection.Projection("a", "a.b", "a.d"), plan.project("a.b.X", "a.b.C"));
    }

    @Test
    void aMovedPackageTakesItsContentAlong() {
        Placement plan = Placement.of(Map.of("a.b", "x"));

        assertEquals(List.of("x", "a.b", "a.b.c"), plan.containersOf("a.b.c.D"));
        assertTrue(plan.isIn("a.b.c.D", "x"));
        assertFalse(plan.isIn("a.b.c.D", "a"));
    }

    @Test
    void anElementMovedIntoItselfIsNoEndlessWay() {
        Placement plan = Placement.of(Map.of("a", "a.b"));

        assertEquals(2, plan.containersOf("a.b.C").size());
    }

    @Test
    void aMoveResolvesATangleOfPackages() {
        // p.A -> q.B and q.C -> p.D: no circle among the classes.
        Map<String, Set<String>> classes = Map.of(
                "p.A", Set.of("q.B"), "q.B", Set.of(), "q.C", Set.of("p.D"), "p.D", Set.of());
        SCCFinder finder = Lookup.lookup(SCCFinder.class);

        assertEquals(List.of(Set.of("p", "q")), Placement.OF_THE_CODE.tangles(classes, finder));
        assertTrue(Placement.of(Map.of("q.C", "p")).tangles(classes, finder).isEmpty());
    }

    @Test
    void theTopLevelIsBelowTheSinglePackages() {
        assertEquals("com.shop", Placement.topLevel(Set.of("com", "com.shop", "com.shop.a", "com.shop.b")));
        assertEquals("", Placement.topLevel(Set.of("a", "b")));
    }
}
