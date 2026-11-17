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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.canvas.ZoomController;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyRendererTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void selectedClassLimitsDependencyArrowsToIncomingAndOutgoingEdges() {
        Fixture fixture = fixture();

        fixture.renderer.drawDependencyArrows(fixture.root);
        assertEquals(6, fixture.dependencyPane.getChildren().size(),
                "two dependencies are rendered as line plus two arrowhead segments");

        fixture.renderer.setSelectedFullName("pkg.A");
        fixture.renderer.drawDependencyArrows(fixture.root);

        assertEquals(3, fixture.dependencyPane.getChildren().size(),
                "only pkg.A -> pkg.B remains visible when pkg.A is selected");
    }

    @Test
    void cutClassDependencyIsDrawnDashedAndStaysVisible() {
        Fixture fixture = fixture();
        RecordingActions actions = new RecordingActions();
        actions.cuts.add("pkg.A>pkg.B");
        fixture.renderer.setEdgeActions(actions);

        fixture.renderer.drawDependencyArrows(fixture.root);

        assertEquals(6, fixture.dependencyPane.getChildren().size(), "a cut arrow is still drawn, so it can be restored");
        List<Line> dashed = lines(fixture).stream().filter(l -> !l.getStrokeDashArray().isEmpty()).toList();
        assertEquals(1, dashed.size(), "only pkg.A -> pkg.B is cut");
        assertNotNull(dashed.get(0).getOnContextMenuRequested(), "a cut arrow offers Restore");
    }

    @Test
    void classArrowsOfferTheCutMenuOnlyWhenEdgeActionsAreInstalled() {
        Fixture plain = fixture();
        plain.renderer.drawDependencyArrows(plain.root);
        assertTrue(lines(plain).stream().allMatch(l -> l.getOnContextMenuRequested() == null));

        Fixture cuttable = fixture();
        cuttable.renderer.setEdgeActions(new RecordingActions());
        cuttable.renderer.drawDependencyArrows(cuttable.root);
        long withMenu = lines(cuttable).stream().filter(l -> l.getOnContextMenuRequested() != null).count();
        assertEquals(2, withMenu, "both class-to-class arrows can be cut; arrowhead segments carry no menu");
    }

    @Test
    void selectingAClassArrowReportsItsEndpointsAndDeselectingClearsThem() {
        Fixture fixture = fixture();
        RecordingActions actions = new RecordingActions();
        fixture.renderer.setEdgeActions(actions);
        fixture.renderer.setSelectedFullName("pkg.A");
        fixture.renderer.drawDependencyArrows(fixture.root);
        Line arrow = lines(fixture).stream().filter(l -> l.getOnMouseClicked() != null).findFirst().orElseThrow();

        arrow.getOnMouseClicked().handle(click(MouseButton.SECONDARY));
        assertTrue(actions.clicks.isEmpty(), "the secondary button opens the menu, it does not select");

        arrow.getOnMouseClicked().handle(click(MouseButton.PRIMARY));
        arrow.getOnMouseClicked().handle(click(MouseButton.PRIMARY));

        assertEquals(List.of("pkg.A>pkg.B", "null>null"), actions.clicks);
    }

    @Test
    void tangleViewDrawsOnlyTheDependenciesBetweenTwoMembers() {
        Fixture fixture = tangleFixture();

        fixture.renderer.drawDependencyArrows(fixture.root);

        assertEquals(3, arrows(fixture).size(), "p.A -> p.D stays inside the member p and is context");
    }

    @Test
    void tangleViewDrawsBackEdgesRedAndHeavyAndPackageOnlyCyclesDashed() {
        Fixture fixture = tangleFixture();

        fixture.renderer.drawDependencyArrows(fixture.root);

        List<Line> red = arrows(fixture).stream()
                .filter(l -> ArrowStyle.BACK_EDGE_COLOR.equals(l.getStroke())).toList();
        assertEquals(2, red.size(), "q.B -> p.A and q.C -> p.D are the back-edges");
        assertTrue(red.stream().allMatch(l -> l.getStrokeWidth() == ArrowStyle.BACK_EDGE_WIDTH));
        assertEquals(1, red.stream().filter(l -> l.getStrokeDashArray().isEmpty()).count(),
                "q.B -> p.A closes a class cycle: solid");
        assertEquals(1, red.stream().filter(l -> !l.getStrokeDashArray().isEmpty()).count(),
                "q.C -> p.D closes the package cycle only: dashed");

        List<Line> forward = arrows(fixture).stream()
                .filter(l -> ArrowStyle.FORWARD_COLOR.equals(l.getStroke())).toList();
        assertEquals(1, forward.size(), "p.A -> q.B");
        assertTrue(forward.get(0).getStrokeDashArray().isEmpty(), "it lies on the class cycle: solid");
        assertEquals(ArrowStyle.FORWARD_WIDTH, forward.get(0).getStrokeWidth());
    }

    @Test
    void tangleViewWithContextDrawsTheOtherDependenciesInTheBackgroundColour() {
        Fixture fixture = tangleFixture();
        fixture.scope.setShowContext(true);

        fixture.renderer.drawDependencyArrows(fixture.root);

        assertEquals(4, arrows(fixture).size());
        assertEquals(1, arrows(fixture).stream()
                .filter(l -> ArrowStyle.CONTEXT_COLOR.equals(l.getStroke())).count(), "p.A -> p.D");
    }

    @Test
    void highlightPushesTheOtherTangleDependenciesIntoTheBackground() {
        Fixture fixture = tangleFixture();
        fixture.scope.setHighlight(TangleEdgeScope.Highlight.BACK_EDGE, true);

        fixture.renderer.drawDependencyArrows(fixture.root);

        for (Line arrow : arrows(fixture)) {
            boolean backEdge = ArrowStyle.BACK_EDGE_COLOR.equals(arrow.getStroke());
            assertEquals(backEdge, arrow.getOpacity() == 1.0, "only back-edges keep their full strength");
        }
    }

    @Test
    void aCutThatBreaksTheCycleTurnsItsRemainingDependenciesIntoBackground() {
        Fixture fixture = tangleFixture();
        // Without q.B -> p.A the class cycle is gone; without q.C -> p.D the package cycle too.
        fixture.renderer.setEdgeCycles(EdgeCycleClassifier.of(tangleModel(),
                Set.of(new de.weigend.s202.domain.DependencyEdge("q.B", "p.A"),
                        new de.weigend.s202.domain.DependencyEdge("q.C", "p.D")),
                Lookup.lookup(SCCFinder.class)), true);

        fixture.renderer.drawDependencyArrows(fixture.root);

        Line forward = arrows(fixture).stream()
                .filter(l -> !ArrowStyle.BACK_EDGE_COLOR.equals(l.getStroke())).findFirst().orElseThrow();
        assertEquals(ArrowStyle.CONTEXT_COLOR, forward.getStroke(), "p.A -> q.B is on no cycle any more");
    }

    @Test
    void tangleViewWritesTheWeightOnEveryTangleArrow() {
        Fixture fixture = tangleFixture();

        fixture.renderer.drawDependencyArrows(fixture.root);

        List<String> weights = weights(fixture);
        assertEquals(3, weights.size(), "one number per tangle arrow");
        assertEquals(1, weights.stream().filter("5"::equals).count(), "p.A -> q.B carries five calls");
        assertEquals(2, weights.stream().filter("1"::equals).count(), "no call data: one reference each");
    }

    @Test
    void contextArrowsAndRegularViewsCarryNoWeight() {
        Fixture tangle = tangleFixture();
        tangle.scope.setShowContext(true);
        tangle.renderer.drawDependencyArrows(tangle.root);
        assertEquals(4, arrows(tangle).size());
        assertEquals(3, weights(tangle).size(), "p.A -> p.D is context: no number");

        Fixture regular = fixture();
        regular.renderer.drawDependencyArrows(regular.root);
        assertTrue(weights(regular).isEmpty());
    }

    @Test
    void theTwoArrowsOfAMutualDependencyDoNotShareOneLine() {
        Fixture fixture = tangleFixture();

        fixture.renderer.drawDependencyArrows(fixture.root);

        // p.A (x 10..30, top row) <-> q.B (x 130..150, bottom row)
        Line down = arrows(fixture).stream()
                .filter(l -> ArrowStyle.FORWARD_COLOR.equals(l.getStroke())).findFirst().orElseThrow();
        Line up = arrows(fixture).stream()
                .filter(l -> ArrowStyle.BACK_EDGE_COLOR.equals(l.getStroke()) && l.getStrokeDashArray().isEmpty())
                .findFirst().orElseThrow();
        assertEquals(down.getStartY(), up.getEndY(), "both still touch the same box edges");
        assertEquals(down.getEndY(), up.getStartY());
        assertTrue(down.getStartX() - up.getEndX() >= 6.0, "p.A -> q.B leaves beside where q.B -> p.A arrives");
        assertTrue(down.getEndX() - up.getStartX() >= 6.0);
        assertTrue(down.getStartX() <= 30 && up.getEndX() >= 10, "neither leaves the box");

        // q.C -> p.D has no reverse: it keeps the centres.
        Line single = arrows(fixture).stream()
                .filter(l -> !l.getStrokeDashArray().isEmpty()).findFirst().orElseThrow();
        assertEquals(200.0, single.getStartX(), 0.001);
        assertEquals(80.0, single.getEndX(), 0.001);
    }

    /** The numbers written on the arrows. */
    private static List<String> weights(Fixture fixture) {
        List<String> result = new ArrayList<>();
        for (javafx.scene.Node node : fixture.dependencyPane.getChildren()) {
            if (node instanceof javafx.scene.Group group) {
                for (javafx.scene.Node child : group.getChildren()) {
                    if (child instanceof javafx.scene.text.Text text) {
                        result.add(text.getText());
                    }
                }
            }
        }
        return result;
    }

    /** The arrow shafts; arrowhead segments carry no click handler. */
    private static List<Line> arrows(Fixture fixture) {
        return lines(fixture).stream().filter(l -> l.getOnMouseClicked() != null).toList();
    }

    private static List<Line> lines(Fixture fixture) {
        return fixture.dependencyPane.getChildren().stream()
                .filter(Line.class::isInstance).map(Line.class::cast).toList();
    }

    private static MouseEvent click(MouseButton button) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, button, 1,
                false, false, false, false, false, false, false, true, false, true, null);
    }

    private static final class RecordingActions implements DependencyEdgeActions {
        final Set<String> cuts = new HashSet<>();
        final List<String> clicks = new ArrayList<>();

        @Override
        public boolean isCut(String from, String to) {
            return cuts.contains(from + ">" + to);
        }

        @Override
        public void cut(String from, String to) {
            cuts.add(from + ">" + to);
        }

        @Override
        public void restore(String from, String to) {
            cuts.remove(from + ">" + to);
        }

        @Override
        public void clicked(String from, String to) {
            clicks.add(from + ">" + to);
        }
    }

    private static Fixture fixture() {
        ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode a = new ArchitectureNode("pkg.A", "A", ArchitectureNode.NodeType.CLASS, true);
        ArchitectureNode b = new ArchitectureNode("pkg.B", "B", ArchitectureNode.NodeType.CLASS, true);
        ArchitectureNode c = new ArchitectureNode("pkg.C", "C", ArchitectureNode.NodeType.CLASS, true);
        a.setDependencies(Set.of("pkg.B"));
        c.setDependencies(Set.of("pkg.B"));
        root.addChild(a);
        root.addChild(b);
        root.addChild(c);

        Rectangle aNode = new Rectangle(10, 10, 20, 20);
        Rectangle bNode = new Rectangle(100, 100, 20, 20);
        Rectangle cNode = new Rectangle(180, 10, 20, 20);
        Map<String, javafx.scene.Node> registry = new HashMap<>();
        registry.put("pkg.A", aNode);
        registry.put("pkg.B", bNode);
        registry.put("pkg.C", cNode);

        Pane dependencyPane = new Pane();
        Pane overlayPane = new Pane(dependencyPane);
        Pane zoomableContent = new Pane(aNode, bNode, cNode, overlayPane);

        DependencyRenderer renderer = new DependencyRenderer(
                dependencyPane,
                registry,
                new ZoomController(zoomableContent, null),
                ignored -> {});
        renderer.setCoordinateContext(zoomableContent, overlayPane, null);
        return new Fixture(root, dependencyPane, renderer, null);
    }

    /**
     * The tangle of the packages p and q, all four classes visible:
     * p.A <-> q.B is a class cycle with the back-edge q.B -> p.A;
     * q.C -> p.D is a back-edge that closes the package cycle only;
     * p.A -> p.D stays inside p.
     */
    private static Fixture tangleFixture() {
        ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode p = new ArchitectureNode("p", "p", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode q = new ArchitectureNode("q", "q", ArchitectureNode.NodeType.PACKAGE, true);
        root.addChild(p);
        root.addChild(q);
        Map<String, javafx.scene.Node> registry = new HashMap<>();
        Pane dependencyPane = new Pane();
        Pane overlayPane = new Pane(dependencyPane);
        Pane zoomableContent = new Pane(overlayPane);
        DomainModel model = tangleModel();
        int column = 0;
        for (DomainModel.CalculatedElementInfo info : new java.util.TreeMap<>(model.getAllClasses()).values()) {
            ArchitectureNode node = new ArchitectureNode(info.fullName, info.simpleName,
                    ArchitectureNode.NodeType.CLASS, true);
            node.setDependencies(info.dependencies);
            (info.fullName.startsWith("p.") ? p : q).addChild(node);
            Rectangle box = new Rectangle(10 + 60 * column++, info.fullName.startsWith("p.") ? 10 : 100, 20, 20);
            registry.put(info.fullName, box);
            zoomableContent.getChildren().add(box);
        }

        DependencyRenderer renderer = new DependencyRenderer(
                dependencyPane, registry, new ZoomController(zoomableContent, null), ignored -> {});
        renderer.setCoordinateContext(zoomableContent, overlayPane, null);
        renderer.setEdgeCycles(EdgeCycleClassifier.of(model, Set.of(), Lookup.lookup(SCCFinder.class)), true);
        TangleEdgeScope scope = new TangleEdgeScope(Set.of("p", "q"));
        renderer.setTangleScope(scope);
        return new Fixture(root, dependencyPane, renderer, scope);
    }

    private static DomainModel tangleModel() {
        DomainModel model = new DomainModel();
        addClass(model, "p.A", "q.B", "p.D");
        addClass(model, "p.D");
        addClass(model, "q.B", "p.A");
        addClass(model, "q.C", "p.D");
        model.setClassBackEdges(Set.of("q.B\0p.A", "q.C\0p.D"));
        model.setClassEdgeWeights(Map.of("p.A\0q.B", 5));
        return model;
    }

    private static void addClass(DomainModel model, String fqn, String... dependencies) {
        model.addClass(fqn, new DomainModel.CalculatedElementInfo(
                fqn, fqn.substring(fqn.lastIndexOf('.') + 1), "CLASS", 0, Set.of(dependencies)));
    }

    private record Fixture(ArchitectureNode root, Pane dependencyPane, DependencyRenderer renderer,
                           TangleEdgeScope scope) {}
}
