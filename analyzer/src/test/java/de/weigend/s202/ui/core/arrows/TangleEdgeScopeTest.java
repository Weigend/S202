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
package de.weigend.s202.ui.core.arrows;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.ui.core.arrows.TangleEdgeScope.Highlight;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TangleEdgeScopeTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void aTangleEdgeConnectsTwoDifferentMembers() {
        TangleEdgeScope scope = new TangleEdgeScope(Set.of("app.ui", "app.Main"));

        assertEquals("app.ui", scope.memberOf("app.ui.dialog.About"));
        assertNull(scope.memberOf("app.util.Text"));
        assertTrue(scope.isTangleEdge("app.Main", "app.ui.View"));
        assertFalse(scope.isTangleEdge("app.ui.View", "app.ui.Helper"), "inside one member");
        assertFalse(scope.isTangleEdge("app.Main", "app.util.Text"), "leaves the tangle");
    }

    @Test
    void dependenciesInsideAMemberAreDrawnOnlyWithContext() {
        TangleEdgeScope scope = new TangleEdgeScope(Set.of("app.ui", "app.Main"));

        assertFalse(scope.draws("app.ui.View", "app.ui.Helper"));
        scope.setShowContext(true);
        assertTrue(scope.draws("app.ui.View", "app.ui.Helper"));
    }

    @Test
    void aHighlightDimsEveryDependencyItDoesNotSelect() {
        TangleEdgeScope scope = new TangleEdgeScope(Set.of("app.ui", "app.Main"));

        assertFalse(scope.dims(false, true, false), "nothing is dimmed without a highlight");
        scope.setHighlight(Highlight.BACK_EDGE, true);
        assertFalse(scope.dims(true, true, false));
        assertTrue(scope.dims(false, true, false));
        scope.setHighlight(Highlight.CLASS_CYCLE, true);
        assertFalse(scope.dims(false, true, false), "highlights add up");
        assertTrue(scope.dims(false, false, true));
    }

    @Test
    void statsCountTheTangleDependenciesByPropertyAndLeaveOutCuts() {
        // Main <-> View is a class cycle whose back-edge is View -> Main;
        // View -> Helper stays inside the member app.ui.
        DomainModel model = new DomainModel();
        add(model, "app.Main", "app.ui.View");
        add(model, "app.ui.View", "app.Main", "app.ui.Helper");
        add(model, "app.ui.Helper");
        model.setClassBackEdges(Set.of("app.ui.View\0app.Main"));
        EdgeCycleClassifier cycles = EdgeCycleClassifier.of(model, Set.of(), Lookup.lookup(SCCFinder.class));

        ArchitectureNode root = new ArchitectureNode("app", "app", NodeType.PACKAGE, true);
        ArchitectureNode ui = new ArchitectureNode("app.ui", "ui", NodeType.PACKAGE, true);
        root.addChild(cls("app.Main", "app.ui.View"));
        root.addChild(ui);
        ui.addChild(cls("app.ui.View", "app.Main", "app.ui.Helper"));
        ui.addChild(cls("app.ui.Helper"));

        TangleEdgeScope scope = new TangleEdgeScope(Set.of("app.ui", "app.Main"));

        assertEquals(new TangleEdgeScope.Stats(2, 1, 2, 0, 2), scope.stats(root, cycles, null));
        assertEquals(new TangleEdgeScope.Stats(1, 0, 1, 0, 1),
                scope.stats(root, cycles, (from, to) -> from.equals("app.ui.View") && to.equals("app.Main")));
    }

    @Test
    void aTangleIsResolvedOnceNoDependencyConnectsTangledMembersEvenOnAClassCycleThatRunsOutside() {
        // app.Main -> app.ui.View -> lib.Hub -> app.Main is a class cycle, and
        // View -> Main closes it inside app as well. Once View -> Main is cut,
        // Main and ui are no longer tangled: the cycle that is left leaves app.
        DomainModel model = new DomainModel();
        add(model, "app.Main", "app.ui.View");
        add(model, "app.ui.View", "app.Main", "lib.Hub");
        add(model, "lib.Hub", "app.Main");
        ArchitectureNode root = new ArchitectureNode("app", "app", NodeType.PACKAGE, true);
        ArchitectureNode ui = new ArchitectureNode("app.ui", "ui", NodeType.PACKAGE, true);
        root.addChild(cls("app.Main", "app.ui.View"));
        root.addChild(ui);
        ui.addChild(cls("app.ui.View", "app.Main", "lib.Hub"));
        TangleEdgeScope scope = new TangleEdgeScope(Set.of("app.ui", "app.Main"));

        EdgeCycleClassifier uncut = EdgeCycleClassifier.of(model, Set.of(), Lookup.lookup(SCCFinder.class));
        assertFalse(scope.stats(root, uncut, null).resolved());

        DependencyEdge cut = new DependencyEdge("app.ui.View", "app.Main");
        EdgeCycleClassifier afterCut = EdgeCycleClassifier.of(model, Set.of(cut), Lookup.lookup(SCCFinder.class));
        TangleEdgeScope.Stats stats = scope.stats(root, afterCut,
                (from, to) -> from.equals(cut.from()) && to.equals(cut.to()));

        assertEquals(new TangleEdgeScope.Stats(1, 0, 1, 0, 0), stats, "Main -> View is still on the class cycle through lib.Hub");
        assertTrue(stats.resolved());
        assertFalse(TangleEdgeScope.Stats.EMPTY.resolved(), "nothing counted is not a resolved tangle");
    }

    private static void add(DomainModel model, String fqn, String... dependencies) {
        model.addClass(fqn, new DomainModel.CalculatedElementInfo(
                fqn, fqn.substring(fqn.lastIndexOf('.') + 1), "CLASS", 0, Set.of(dependencies)));
    }

    private static ArchitectureNode cls(String fqn, String... dependencies) {
        ArchitectureNode node = new ArchitectureNode(fqn, fqn.substring(fqn.lastIndexOf('.') + 1), NodeType.CLASS, true);
        node.setDependencies(Set.of(dependencies));
        return node;
    }
}
