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
package de.weigend.s202.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests for the {@link Architecture} value types — purely about
 * invariants and immutability of the records. Behavioural tests live
 * next to the future builder.
 */
class ArchitectureTypesTest {

    @Test
    void classElementRejectsEmptyFqnAndNegativeLevel() {
        assertThrows(IllegalArgumentException.class, () -> new Element.ClassElement("", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Element.ClassElement(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Element.ClassElement("a.B", -1, 0));
    }

    @Test
    void packageElementCopiesRowsDeepAndIsImmutable() {
        List<Element> innerRow = new ArrayList<>();
        innerRow.add(new Element.ClassElement("a.b.X", 0, 0));
        List<List<Element>> rows = new ArrayList<>();
        rows.add(innerRow);

        Element.PackageElement pkg = new Element.PackageElement("a.b", 1, 0, rows);

        // Mutating the caller's lists must not affect the record's state.
        innerRow.add(new Element.ClassElement("a.b.Y", 0, 0));
        rows.add(new ArrayList<>());
        assertEquals(1, pkg.rows().size(), "outer rows snapshot is immutable");
        assertEquals(1, pkg.rows().get(0).size(), "inner row snapshot is immutable");

        assertThrows(UnsupportedOperationException.class,
                () -> pkg.rows().add(new ArrayList<>()));
        assertThrows(UnsupportedOperationException.class,
                () -> pkg.rows().get(0).add(new Element.ClassElement("a.b.Z", 0, 0)));
    }

    @Test
    void violationRejectsNullsAndEmptyFqns() {
        assertThrows(IllegalArgumentException.class,
                () -> new Violation("", "x.Y", ViolationKind.UPWARD, 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new Violation("a.X", "", ViolationKind.UPWARD, 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new Violation("a.X", "x.Y", null, 1, 2));
    }

    @Test
    void violationDefaultsToNonBackEdge() {
        Violation violation = new Violation("a.X", "b.Y", ViolationKind.UPWARD, 0, 1);

        assertFalse(violation.backEdge());
    }

    @Test
    void hierarchicalLayeredArchitectureSnapshotsRowsViolationsAndTangles() {
        List<Element> row = new ArrayList<>();
        row.add(new Element.ClassElement("a.X", 0, 0));
        List<List<Element>> rows = new ArrayList<>();
        rows.add(row);
        List<Violation> violations = new ArrayList<>();
        violations.add(new Violation("a.X", "b.Y", ViolationKind.UPWARD, 0, 1));
        List<Tangle> tangles = new ArrayList<>();
        tangles.add(new Tangle(java.util.Set.of("a", "b")));

        HierarchicalLayeredArchitecture arch =
                new HierarchicalLayeredArchitecture(rows, violations, tangles);

        rows.clear();
        violations.clear();
        tangles.clear();
        assertEquals(1, arch.rows().size());
        assertEquals(1, arch.rows().get(0).size());
        assertEquals(1, arch.violations().size());
        assertEquals(1, arch.tangles().size());
        assertEquals(java.util.Set.of("a", "b"), arch.tangles().get(0).members());
    }

    @Test
    void tangleRequiresAtLeastTwoMembers() {
        assertThrows(IllegalArgumentException.class, () -> new Tangle(java.util.Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Tangle(java.util.Set.of("only-one")));
    }

    @Test
    void architectureIsSealedToHierarchicalLayered() {
        Architecture a = new HierarchicalLayeredArchitecture(List.of(), List.of(), List.of());
        assertTrue(a instanceof HierarchicalLayeredArchitecture);
        assertSame(List.of(), a.violations());
        assertSame(List.of(), a.tangles());
    }
}
