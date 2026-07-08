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
package de.weigend.s202.ui.rendering;

import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.canvas.ZoomController;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DependencyRendererTest {

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
        return new Fixture(root, dependencyPane, renderer);
    }

    private record Fixture(ArchitectureNode root, Pane dependencyPane, DependencyRenderer renderer) {}
}
