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
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        Line firstLine = (Line) fixture.sccPane.getChildren().get(0);
        assertEquals(Color.web("#ff8c00"), firstLine.getStroke());
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
