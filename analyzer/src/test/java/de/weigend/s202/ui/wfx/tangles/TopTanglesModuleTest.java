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
package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopTanglesModuleTest {

    @Test
    void computeTopTanglesMarksCycleBreakEdges() {
        DomainModel model = new DomainModel();
        model.addClass("a.A", new DomainModel.CalculatedElementInfo(
                "a.A", "A", "CLASS", 2, Set.of("a.B")));
        model.addClass("a.B", new DomainModel.CalculatedElementInfo(
                "a.B", "B", "CLASS", 1, Set.of("a.C")));
        model.addClass("a.C", new DomainModel.CalculatedElementInfo(
                "a.C", "C", "CLASS", 0, Set.of("a.A")));

        var tangles = TopTanglesModule.computeTopTangles(
                model, null, Set.of(new DependencyEdge("a.C", "a.A")), null, 5);

        assertEquals(1, tangles.size());
        assertTrue(tangles.get(0).edges().stream()
                .anyMatch(edge -> edge.from().equals("a.C")
                        && edge.to().equals("a.A")
                        && edge.cycleBreakEdge()));
    }

    @Test
    void computeTopTanglesRemovesAppliedCutEdgesFromSccGraph() {
        DomainModel model = new DomainModel();
        model.addClass("a.A", new DomainModel.CalculatedElementInfo(
                "a.A", "A", "CLASS", 2, Set.of("a.B")));
        model.addClass("a.B", new DomainModel.CalculatedElementInfo(
                "a.B", "B", "CLASS", 1, Set.of("a.C")));
        model.addClass("a.C", new DomainModel.CalculatedElementInfo(
                "a.C", "C", "CLASS", 0, Set.of("a.A")));

        var cutEdge = new DependencyEdge("a.C", "a.A");
        var tangles = TopTanglesModule.computeTopTangles(
                model, null, Set.of(cutEdge), Set.of(cutEdge), null, 5);

        assertTrue(tangles.isEmpty());
    }

    @Test
    void computeTopTanglesShowsCalledMethodNames() {
        DomainModel model = new DomainModel();
        model.addClass("a.A", new DomainModel.CalculatedElementInfo(
                "a.A", "A", "CLASS", 2, Set.of("a.B")));
        model.addClass("a.B", new DomainModel.CalculatedElementInfo(
                "a.B", "B", "CLASS", 1, Set.of("a.A")));

        DependencyModel rawModel = new DependencyModel();
        DependencyModel.ClassInfo a = new DependencyModel.ClassInfo("a.A", "A", "a");
        a.addDependency("a.B", EdgeKind.CALLS);
        DependencyModel.MethodInfo source = new DependencyModel.MethodInfo("source", "()V");
        source.methodCalls.put("a.B.work", 1);
        source.methodCallDescriptors.put("a.B.work", Set.of("(I)V"));
        a.methods.put("source()V", source);
        DependencyModel.ClassInfo b = new DependencyModel.ClassInfo("a.B", "B", "a");
        b.addDependency("a.A", EdgeKind.CALLS);
        rawModel.addClass("a.A", a);
        rawModel.addClass("a.B", b);

        var tangles = TopTanglesModule.computeTopTangles(
                model, rawModel, Set.of(), null, 5);

        assertEquals(1, tangles.size());
        assertTrue(tangles.get(0).edges().stream()
                .filter(edge -> edge.from().equals("a.A") && edge.to().equals("a.B"))
                .flatMap(edge -> edge.entries().stream())
                .anyMatch(entry -> entry.kind() == EdgeKind.CALLS && "work(I)V".equals(entry.detail())));
    }

    @Test
    void computeHotspotsRanksMethodsByTangleCount() {
        var callWork = new TopTanglesView.KindEntry(EdgeKind.CALLS, "work(I)V");
        var callOther = new TopTanglesView.KindEntry(EdgeKind.CALLS, "other()V");

        // cut=true edges calling work() in two tangles → should appear as hotspot
        var edge1 = new TopTanglesView.TangleEdge("a.A", "a.B", List.of(callWork), true, false);
        var tangle1 = new TopTanglesView.Tangle(2, "a.A|a.B", "T1", List.of("a.A", "a.B"), List.of(edge1));

        var edge2a = new TopTanglesView.TangleEdge("a.C", "a.B", List.of(callWork), true, false);
        // cut=false edge calling other() → must not appear in hotspot
        var edge2b = new TopTanglesView.TangleEdge("a.C", "a.D", List.of(callOther), false, false);
        var tangle2 = new TopTanglesView.Tangle(3, "a.B|a.C|a.D", "T2",
                List.of("a.B", "a.C", "a.D"), List.of(edge2a, edge2b));

        var hotspots = TopTanglesModule.computeHotspots(List.of(tangle1, tangle2), 5);

        assertEquals(1, hotspots.size());
        assertEquals("B.work()", hotspots.get(0).label());
        assertEquals(2, hotspots.get(0).edgeCount());
        assertEquals(List.of(
                new TopTanglesView.HotspotCallerRow("a.A", "a.B"),
                new TopTanglesView.HotspotCallerRow("a.C", "a.B")),
                hotspots.get(0).callerRows());
    }
}
