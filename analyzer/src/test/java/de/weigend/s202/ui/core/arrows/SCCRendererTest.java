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

import de.weigend.s202.ui.core.model.ArchitectureNode;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.scene.Group;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.QuadCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SCCRendererTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void rendererDoesNotDrawClassSccByDefault() {
        Fixture fixture = fixture();

        fixture.renderer.drawSccLines(fixture.root);

        assertEquals(0, fixture.sccPane.getChildren().size());
    }

    @Test
    void packageCyclesDoNotImplicitlyEnableClassSccs() {
        Fixture fixture = fixture();
        fixture.renderer.setPackageTangles(java.util.List.of(Set.of("pkg.a", "pkg.b")));
        fixture.renderer.setShowPackageCycles(true);

        fixture.renderer.drawSccLines(fixture.root);

        assertFalse(fixture.sccPane.getChildren().isEmpty());
        Line firstLine = firstLine(fixture.sccPane);
        assertEquals(LineColors.PACKAGE_CYCLE, firstLine.getStroke());
        assertFalse(firstLine.getStrokeDashArray().isEmpty(), "on a package cycle only: dashed");
    }

    @Test
    void packageCyclesUseConfiguredPackageResolver() {
        Fixture fixture = fixture();
        fixture.renderer.setPackageTangles(java.util.List.of(Set.of("pkg.a", "pkg.b")));
        fixture.renderer.setPackageResolver(fqn -> "pkg.b");
        fixture.renderer.setShowPackageCycles(true);

        fixture.renderer.drawSccLines(fixture.root);

        assertEquals(0, fixture.sccPane.getChildren().size());
    }

    @Test
    void classCyclesComeFromTheModelNotFromTheVisibleClasses() {
        // A -> B -> C -> A, but only A and B have a box: C is collapsed away.
        de.weigend.s202.domain.DomainModel model = new de.weigend.s202.domain.DomainModel();
        addClass(model, "pkg.a.A", "pkg.b.B");
        addClass(model, "pkg.b.B", "pkg.c.C");
        addClass(model, "pkg.c.C", "pkg.a.A");

        ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode a = new ArchitectureNode("pkg.a.A", "A", ArchitectureNode.NodeType.CLASS, true);
        ArchitectureNode b = new ArchitectureNode("pkg.b.B", "B", ArchitectureNode.NodeType.CLASS, true);
        a.setDependencies(Set.of("pkg.b.B"));
        b.setDependencies(Set.of("pkg.c.C"));
        root.addChild(a);
        root.addChild(b);
        Rectangle aNode = new Rectangle(10, 10, 20, 20);
        Rectangle bNode = new Rectangle(100, 100, 20, 20);
        Map<String, javafx.scene.Node> registry = new HashMap<>();
        registry.put("pkg.a.A", aNode);
        registry.put("pkg.b.B", bNode);
        Pane sccPane = new Pane();
        new Pane(aNode, bNode, new Pane(sccPane));
        SCCRenderer renderer = new SCCRenderer(sccPane, registry, ignored -> {});
        renderer.setShowClassScc(true);

        renderer.drawSccLines(root);
        assertEquals(0, sccPane.getChildren().size(), "the visible classes alone form no cycle");

        renderer.setEdgeCycles(de.weigend.s202.domain.EdgeCycleClassifier.of(
                model, Set.of(), Lookup.lookup(de.weigend.s202.domain.SCCFinder.class)));
        renderer.drawSccLines(root);
        assertEquals(3, sccPane.getChildren().size(),
                "A -> B lies on the model's class cycle: mouse area, line and arrowhead");
        assertEquals(LineColors.CLASS_CYCLE, firstLine(sccPane).getStroke());
        assertTrue(firstLine(sccPane).getStrokeDashArray().isEmpty());
    }

    @Test
    void cyclesOfCollapsedPackagesEndAtThePackagesWithTheirNumber() {
        // A <-> C is a class cycle between the packages, B -> C only adds to their tangle.
        Collapsed view = new Collapsed(Map.of(
                "p.A", Set.of("q.C"), "p.B", Set.of("q.C"), "q.C", Set.of("p.A")));
        view.renderer.setPackageTangles(java.util.List.of(Set.of("p", "q")));
        view.renderer.setShowPackageCycles(true);

        view.renderer.drawSccLines(view.root);

        assertEquals(Map.of("2", LineColors.PACKAGE_CYCLE), view.numbers(), "a single dependency needs no number");
        assertEquals(2, view.lines().size(), "one line for each direction");

        view.renderer.setShowClassScc(true);
        view.renderer.drawSccLines(view.root);

        assertEquals(Map.of("2", LineColors.CLASS_CYCLE), view.numbers(),
                "a line that contains a class cycle shows that and counts all it stands for");
    }

    @Test
    void cyclesWithinACollapsedPackageAreALoop() {
        Collapsed view = new Collapsed(Map.of("p.A", Set.of("p.B"), "p.B", Set.of("p.A"), "q.C", Set.of()));
        view.renderer.setShowClassScc(true);

        view.renderer.drawSccLines(view.root);

        assertEquals(Map.of("2", LineColors.CLASS_CYCLE), view.numbers());
        assertEquals(0, view.lines().size());
        assertEquals(1, view.sccPane.getChildren().stream().filter(QuadCurve.class::isInstance).count());
    }

    /** The first line that is drawn, after the invisible areas that catch the mouse. */
    private static Line firstLine(Pane pane) {
        return pane.getChildren().stream().filter(Line.class::isInstance).map(Line.class::cast)
                .findFirst().orElseThrow();
    }

    /** The packages p and q, both collapsed, with the given class dependencies. */
    private static final class Collapsed {
        private final ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        private final Pane sccPane = new Pane();
        private final SCCRenderer renderer;

        private Collapsed(Map<String, Set<String>> dependencies) {
            de.weigend.s202.domain.DomainModel model = new de.weigend.s202.domain.DomainModel();
            Map<String, javafx.scene.Node> registry = new HashMap<>();
            Pane content = new Pane(new Pane(sccPane));
            for (String name : java.util.List.of("p", "q")) {
                Rectangle box = new Rectangle(10, "p".equals(name) ? 10 : 200, 80, 30);
                registry.put(name, box);
                Pane hidden = new Pane();
                hidden.setVisible(false);
                hidden.getProperties().put("s202.rollupEndpointFqn", name);
                content.getChildren().addAll(box, hidden);
                ArchitectureNode pkg = new ArchitectureNode(name, name, ArchitectureNode.NodeType.PACKAGE, false);
                root.addChild(pkg);
                dependencies.keySet().stream().filter(fqn -> fqn.startsWith(name + ".")).forEach(fqn -> {
                    ArchitectureNode cls = new ArchitectureNode(fqn, fqn, ArchitectureNode.NodeType.CLASS, false);
                    cls.setDependencies(dependencies.get(fqn));
                    pkg.addChild(cls);
                    Rectangle classBox = new Rectangle(0, 0, 20, 20);
                    hidden.getChildren().add(classBox);
                    registry.put(fqn, classBox);
                    addClass(model, fqn, dependencies.get(fqn).toArray(String[]::new));
                });
            }
            renderer = new SCCRenderer(sccPane, registry, ignored -> {});
            renderer.setCoordinateContext(content, null, null);
            renderer.setEdgeCycles(de.weigend.s202.domain.EdgeCycleClassifier.of(
                    model, Set.of(), Lookup.lookup(de.weigend.s202.domain.SCCFinder.class)));
        }

        private java.util.List<Line> lines() {
            return sccPane.getChildren().stream().filter(Line.class::isInstance).map(Line.class::cast).toList();
        }

        private Map<String, javafx.scene.paint.Paint> numbers() {
            Map<String, javafx.scene.paint.Paint> numbers = new HashMap<>();
            for (javafx.scene.Node node : sccPane.getChildren()) {
                if (node instanceof Group badge && badge.getChildren().get(0) instanceof Circle) {
                    numbers.put(((Text) badge.getChildren().get(1)).getText(),
                            ((Circle) badge.getChildren().get(0)).getFill());
                }
            }
            return numbers;
        }
    }

    private static void addClass(de.weigend.s202.domain.DomainModel model, String fqn, String... dependencies) {
        model.addClass(fqn, new de.weigend.s202.domain.DomainModel.CalculatedElementInfo(
                fqn, fqn.substring(fqn.lastIndexOf('.') + 1), "CLASS", 0, Set.of(dependencies)));
    }

    private static Fixture fixture() {
        ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode a = new ArchitectureNode("pkg.a.A", "A", ArchitectureNode.NodeType.CLASS, true);
        ArchitectureNode b = new ArchitectureNode("pkg.b.B", "B", ArchitectureNode.NodeType.CLASS, true);
        a.setDependencies(Set.of("pkg.b.B"));
        b.setDependencies(Set.of("pkg.a.A"));
        root.addChild(a);
        root.addChild(b);

        Rectangle aNode = new Rectangle(10, 10, 20, 20);
        Rectangle bNode = new Rectangle(100, 100, 20, 20);
        Map<String, javafx.scene.Node> registry = new HashMap<>();
        registry.put("pkg.a.A", aNode);
        registry.put("pkg.b.B", bNode);

        Pane sccPane = new Pane();
        Pane overlayPane = new Pane(sccPane);
        Pane zoomableContent = new Pane(aNode, bNode, overlayPane);

        SCCRenderer renderer = new SCCRenderer(sccPane, registry, ignored -> {});
        renderer.setCoordinateContext(zoomableContent, overlayPane, null);
        return new Fixture(root, sccPane, renderer);
    }

    private record Fixture(ArchitectureNode root, Pane sccPane, SCCRenderer renderer) {}
}
