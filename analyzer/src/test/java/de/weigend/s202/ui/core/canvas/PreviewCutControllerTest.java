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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.domain.DependencyEdge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCutControllerTest {

    private final List<String> events = new ArrayList<>();
    private int redraws;
    private final PreviewCutController cuts = new PreviewCutController(status -> {}, () -> redraws++);

    @Test
    void cuttingAnArrowMarksItRedrawsAndTellsTheShell() {
        cuts.setOnEdgeCut((from, to) -> events.add("cut " + from + ">" + to));

        cuts.cut("a.A", "b.B");

        assertTrue(cuts.isCut("a.A", "b.B"));
        assertFalse(cuts.isCut("b.B", "a.A"), "a cut has a direction");
        assertEquals(Set.of(new DependencyEdge("a.A", "b.B")), cuts.appliedCutEdges());
        assertEquals(1, redraws);
        assertEquals(List.of("cut a.A>b.B"), events);
    }

    @Test
    void restoringACutArrowUnmarksItRedrawsAndTellsTheShell() {
        cuts.setOnEdgeRestore((from, to) -> events.add("restore " + from + ">" + to));
        cuts.applyEdgeCut("a.A", "b.B");
        redraws = 0;

        cuts.restore("a.A", "b.B");

        assertFalse(cuts.isCut("a.A", "b.B"));
        assertEquals(1, redraws);
        assertEquals(List.of("restore a.A>b.B"), events);
    }

    @Test
    void cutsArrivingFromOtherViewsRedrawWithoutEchoingBackToTheShell() {
        cuts.setOnEdgeCut((from, to) -> events.add("cut " + from + ">" + to));

        cuts.applyEdgeCut("a.A", "b.B");
        cuts.applyEdgeCut("a.A", "b.B");
        cuts.applyEdgeCuts(List.of(new DependencyEdge("a.A", "b.B"), new DependencyEdge("c.C", "d.D")));

        assertTrue(cuts.isCut("c.C", "d.D"));
        assertEquals(2, redraws, "a cut that is already applied changes nothing");
        assertTrue(events.isEmpty(), "only the user's own action on an arrow is published");
    }

    @Test
    void methodLevelCutDoesNotMarkTheWholeClassArrowAsCut() {
        // The Top Tangles list cuts single methods as (from, "Target|method").
        cuts.applyEdgeCut("a.A", "b.B|run");

        assertFalse(cuts.isCut("a.A", "b.B"), "other calls on the same class pair remain");
    }

    @Test
    void selectingAnArrowIsForwardedToTheShell() {
        cuts.setOnEdgeClicked((from, to) -> events.add("clicked " + from + ">" + to));

        cuts.clicked("a.A", "b.B");
        cuts.clicked(null, null);

        assertEquals(List.of("clicked a.A>b.B", "clicked null>null"), events);
    }
}
