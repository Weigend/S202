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
package de.weigend.s202.ui.core.model;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeKindGroupTest {

    private final DependencyModel model = new DependencyModel();

    @Test
    void aDependencyDisappearsOnlyWhenEverythingItConsistsOfIsHidden() {
        dependency("p.OnlyUses", EdgeKind.USES);
        dependency("p.CallsAndUses", EdgeKind.CALLS, EdgeKind.USES);
        Set<EdgeKindGroup> noReferences = Set.of(EdgeKindGroup.REFERENCES);

        assertFalse(EdgeKindGroup.shows(noReferences, model, "p.A", "p.OnlyUses"));
        assertTrue(EdgeKindGroup.shows(noReferences, model, "p.A", "p.CallsAndUses"));
        assertFalse(EdgeKindGroup.shows(Set.of(EdgeKindGroup.REFERENCES, EdgeKindGroup.CALLS),
                model, "p.A", "p.CallsAndUses"));
    }

    @Test
    void nothingHiddenAndUnknownKindsShowEverything() {
        dependency("p.OnlyUses", EdgeKind.USES);

        assertTrue(EdgeKindGroup.shows(Set.of(), model, "p.A", "p.OnlyUses"));
        assertTrue(EdgeKindGroup.shows(Set.of(EdgeKindGroup.REFERENCES), model, "p.A", "p.Unknown"));
        assertTrue(EdgeKindGroup.shows(Set.of(EdgeKindGroup.REFERENCES), null, "p.A", "p.OnlyUses"));
    }

    /** A new edge kind has to find its group, or no filter can hide it. */
    @Test
    void everyEdgeKindBelongsToExactlyOneGroup() {
        for (EdgeKind kind : EdgeKind.values()) {
            dependency("p." + kind, kind);
            long hiding = EnumSet.allOf(EdgeKindGroup.class).stream()
                    .filter(group -> !EdgeKindGroup.shows(Set.of(group), model, "p.A", "p." + kind))
                    .count();
            assertEquals(1, hiding, kind.toString());
        }
    }

    private void dependency(String to, EdgeKind... kinds) {
        DependencyModel.ClassInfo source = model.getClass("p.A");
        if (source == null) {
            source = new DependencyModel.ClassInfo("p.A", "A", "p");
            model.addClass("p.A", source);
        }
        for (EdgeKind kind : kinds) {
            source.addDependency(to, kind);
        }
    }
}
