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

import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeCycleClassifierTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void aDependencyBetweenTwoClassesOfOneClassCycleIsAClassCycleEdge() {
        EdgeCycleClassifier cycles = classifier(Set.of());

        assertEquals(CycleKind.CLASS_CYCLE, cycles.cycleOf("a.X", "b.Y"));
        assertEquals(CycleKind.CLASS_CYCLE, cycles.cycleOf("b.Y", "a.X"));
        assertNotNull(cycles.classCycleId("a.X"));
        assertEquals(cycles.classCycleId("a.X"), cycles.classCycleId("b.Y"));
    }

    @Test
    void aDependencyThatOnlyClosesAPackageCycleIsAPackageCycleEdge() {
        EdgeCycleClassifier cycles = classifier(Set.of());

        // c.P -> d.Q and d.R -> c.S: the packages c and d depend on each
        // other, but no class reaches itself.
        assertEquals(CycleKind.PACKAGE_CYCLE, cycles.cycleOf("c.P", "d.Q"));
        assertEquals(CycleKind.PACKAGE_CYCLE, cycles.cycleOf("d.R", "c.S"));
        assertNull(cycles.classCycleId("c.P"));
    }

    @Test
    void aDependencyOnNoCycleIsNone() {
        EdgeCycleClassifier cycles = classifier(Set.of());

        assertEquals(CycleKind.NONE, cycles.cycleOf("a.X", "e.Leaf"));
    }

    @Test
    void aPreviewCutThatBreaksACycleDissolvesIt() {
        EdgeCycleClassifier cycles = classifier(Set.of(new DependencyEdge("b.Y", "a.X")));

        assertEquals(CycleKind.NONE, cycles.cycleOf("a.X", "b.Y"), "the class cycle is gone, and with it the package cycle a <-> b");
        assertNull(cycles.classCycleId("a.X"));
        assertEquals(CycleKind.PACKAGE_CYCLE, cycles.cycleOf("c.P", "d.Q"), "other cycles are untouched");
    }

    @Test
    void siblingsAreTangledOnlyByACycleInsideTheirContainer() {
        EdgeCycleClassifier cycles = classifier(Set.of());

        assertTrue(cycles.inTangle("a.X", "b.Y"));
        assertTrue(cycles.inTangle("c.P", "d.Q"));
        assertFalse(cycles.inTangle("a.X", "e.Leaf"));
    }

    @Test
    void backEdgesAreTheOnesTheLevelizationCut() {
        EdgeCycleClassifier cycles = classifier(Set.of());

        assertTrue(cycles.isBackEdge("b.Y", "a.X"));
        assertFalse(cycles.isBackEdge("a.X", "b.Y"));
    }

    @Test
    void theWeightIsTheOneTheLevelizationGaveAndOneWithoutIt() {
        EdgeCycleClassifier cycles = classifier(Set.of());

        assertEquals(7, cycles.weightOf("a.X", "b.Y"));
        assertEquals(1, cycles.weightOf("b.Y", "a.X"), "no weight recorded: it counts as one reference");
    }

    @Test
    void theReturnPathOfAClassCycleRunsThroughClasses() {
        CycleReturnPath path = classifier(Set.of()).returnPath("b.Y", "a.X");

        assertFalse(path.packageLevel());
        assertEquals(java.util.List.of(new CycleReturnPath.Step("a.X", "b.Y",
                java.util.List.of(new DependencyEdge("a.X", "b.Y")))), path.steps());
    }

    @Test
    void theReturnPathOfAPackageCycleRunsThroughSiblingsAndNamesTheirDependencies() {
        CycleReturnPath path = classifier(Set.of()).returnPath("c.P", "d.Q");

        assertTrue(path.packageLevel());
        assertEquals(java.util.List.of(new CycleReturnPath.Step("d", "c",
                java.util.List.of(new DependencyEdge("d.R", "c.S")))), path.steps());
    }

    @Test
    void theReturnPathIsTheShortestOne() {
        DomainModel model = new DomainModel();
        add(model, "p.A", "p.B");
        add(model, "p.B", "p.C", "p.D");
        add(model, "p.C", "p.D");
        add(model, "p.D", "p.A");
        EdgeCycleClassifier cycles = EdgeCycleClassifier.of(model, Set.of(), Lookup.lookup(SCCFinder.class));

        assertEquals(java.util.List.of("p.B>p.D", "p.D>p.A"), cycles.returnPath("p.A", "p.B").steps().stream()
                .map(step -> step.from() + ">" + step.to()).toList());
    }

    @Test
    void aDependencyOnNoCycleHasNoReturnPathAndACutTakesItAway() {
        assertFalse(classifier(Set.of()).returnPath("a.X", "e.Leaf").exists());
        assertFalse(classifier(Set.of(new DependencyEdge("a.X", "b.Y"))).returnPath("b.Y", "a.X").exists());
    }

    /**
     * a.X <-> b.Y (class cycle), c.P -> d.Q and d.R -> c.S (package cycle only),
     * a.X -> e.Leaf (no cycle). The levelization cut b.Y -> a.X.
     */
    private static EdgeCycleClassifier classifier(Set<DependencyEdge> cuts) {
        DomainModel model = new DomainModel();
        add(model, "a.X", "b.Y", "e.Leaf");
        add(model, "b.Y", "a.X");
        add(model, "c.P", "d.Q");
        add(model, "c.S");
        add(model, "d.Q");
        add(model, "d.R", "c.S");
        add(model, "e.Leaf");
        model.setClassBackEdges(Set.of("b.Y\0a.X"));
        model.setClassEdgeWeights(java.util.Map.of("a.X\0b.Y", 7));
        return EdgeCycleClassifier.of(model, cuts, Lookup.lookup(SCCFinder.class));
    }

    private static void add(DomainModel model, String fqn, String... dependencies) {
        model.addClass(fqn, new DomainModel.CalculatedElementInfo(
                fqn, fqn.substring(fqn.lastIndexOf('.') + 1), "CLASS", 0, Set.of(dependencies)));
    }
}
