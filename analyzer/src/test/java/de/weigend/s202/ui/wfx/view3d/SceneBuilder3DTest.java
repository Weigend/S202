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
package de.weigend.s202.ui.wfx.view3d;

import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SceneBuilder3DTest {

    @Test
    void sceneUses2DBoundsForPackageFootprintAndStacksClassesAbovePackages() {
        assumeJavaFx3DAvailable();

        ArchitectureNode root = node("root", NodeType.PACKAGE, -1);
        ArchitectureNode pkg = node("com.example", NodeType.PACKAGE, 1);
        ArchitectureNode cls = node("com.example.Foo", NodeType.CLASS, 1);

        // Class width is fan-in encoded; depth still follows the 2D bounds.
        cls.setDependents(Set.of("a", "b", "c", "d", "e"));
        cls.setDependencies(Set.of("x", "y", "z"));

        pkg.addChild(cls);
        root.addChild(pkg);

        Map<String, Bounds> bounds = new LinkedHashMap<>();
        bounds.put("com.example", new BoundingBox(10, 20, 200, 120));
        bounds.put("com.example.Foo", new BoundingBox(40, 60, 70, 24));

        SceneBuilder3D.SceneResult result = new SceneBuilder3D().build(bounds, root, null);
        Group group = result.group();

        Box pkgBox = assertInstanceOf(Box.class, group.getChildren().get(0));
        Group clsGroup = assertInstanceOf(Group.class, group.getChildren().get(1));
        Box clsBox = assertInstanceOf(Box.class, clsGroup.getChildren().get(0));
        Box clsBorder = assertInstanceOf(Box.class, clsGroup.getChildren().get(1));

        assertEquals(200, pkgBox.getWidth(), 0.01);
        assertEquals(120, pkgBox.getDepth(), 0.01);
        assertEquals(SceneBuilder3D.PACKAGE_THICKNESS, pkgBox.getHeight(), 0.01);
        assertEquals(SceneBuilder3D.PACKAGE_BASE_COLOR, material(pkgBox).getDiffuseColor());

        assertEquals(fanInWidth(cls), clsBox.getWidth(), 0.01);
        assertEquals(24, clsBox.getDepth(), 0.01);
        assertEquals(SceneBuilder3D.classThickness(1), clsBox.getHeight(), 0.01);
        assertEquals(SceneBuilder3D.CLASS_COLOR, material(clsBox).getDiffuseColor());
        assertEquals(SceneBuilder3D.CLASS_SPECULAR_COLOR, material(clsBox).getSpecularColor());
        assertEquals(SceneBuilder3D.CLASS_BORDER_COLOR, material(clsBorder).getDiffuseColor());
        assertEquals(9, clsGroup.getChildren().size(),
                "class group consists of fill, four top border bars, and four selected corner pillars");
        assertEquals(DrawMode.FILL, clsBorder.getDrawMode());
        Box clsCornerPillar = assertInstanceOf(Box.class, clsGroup.getChildren().get(5));
        assertFalse(clsCornerPillar.isVisible(), "selection-only corner pillars are hidden when idle");

        assertEquals(110, pkgBox.getTranslateX(), 0.01);
        assertEquals(-80, pkgBox.getTranslateZ(), 0.01);
        assertEquals(75, clsBox.getTranslateX(), 0.01);
        assertEquals(-72, clsBox.getTranslateZ(), 0.01);
        assertEquals(topY(pkgBox), bottomY(clsBox), 0.01,
                "class tile must sit directly on the package slab");
        assertTrue(clsBorder.getTranslateY() < clsBox.getTranslateY(),
                "class outline bars must be above the class tile surface");

        SceneBuilder3D.CameraHint hint = result.cameraHint();
        assertEquals(110, hint.targetX(), 0.01);
        assertEquals(-80, hint.targetZ(), 0.01);
        assertTrue(hint.y() < hint.targetY(), "camera must start above the scene");
        assertTrue(hint.z() < hint.targetZ(), "camera must start in front of the scene");

        assertPickable(pkgBox, "com.example", NodeType.PACKAGE);
        assertPickable(clsBox, "com.example.Foo", NodeType.CLASS);
        assertEdgeTarget(result.edgeTargets().get("com.example"), "com.example", NodeType.PACKAGE,
                110, topY(pkgBox), -80);
        assertEdgeTarget(result.edgeTargets().get("com.example.Foo"), "com.example.Foo", NodeType.CLASS,
                75, topY(clsBox), -72);

        SceneBuilder3D.HoverTarget pkgHover = result.hoverTargets().get("com.example");
        Box pkgHoverBar = pkgHover.borderBars().get(0);
        assertFalse(pkgHoverBar.isVisible(), "package hover border is hidden until hovered");
        pkgHover.setHovered(true);
        assertTrue(pkgHoverBar.isVisible(), "package hover border becomes visible on hover");
        pkgHover.setHovered(false);
        assertFalse(pkgHoverBar.isVisible(), "package hover border hides again");
        pkgHover.setSelected(true);
        assertTrue(pkgHoverBar.isVisible(), "package selected border stays visible");
        assertEquals(SceneBuilder3D.SELECTED_BORDER_COLOR, material(pkgHoverBar).getDiffuseColor());
        pkgHover.setSelected(false);
        assertFalse(pkgHoverBar.isVisible(), "package selected border hides when deselected");

        SceneBuilder3D.HoverTarget clsHover = result.hoverTargets().get("com.example.Foo");
        clsHover.setHovered(true);
        assertEquals(SceneBuilder3D.HOVER_BORDER_COLOR, material(clsBorder).getDiffuseColor());
        clsHover.setSelected(true);
        assertEquals(SceneBuilder3D.SELECTED_BORDER_COLOR, material(clsBorder).getDiffuseColor());
        assertTrue(clsCornerPillar.isVisible(), "selection-only corner pillars become visible when selected");
        clsHover.setHovered(false);
        assertEquals(SceneBuilder3D.SELECTED_BORDER_COLOR, material(clsBorder).getDiffuseColor());
        clsHover.setSelected(false);
        assertEquals(SceneBuilder3D.CLASS_BORDER_COLOR, material(clsBorder).getDiffuseColor());
        assertFalse(clsCornerPillar.isVisible(), "selection-only corner pillars hide again when deselected");
    }

    @Test
    void curvedArrowStaysAboveElementTops() {
        assumeJavaFx3DAvailable();

        SceneBuilder3D.EdgeTarget source = new SceneBuilder3D.EdgeTarget(
                "a.A", NodeType.CLASS, 0, -20, 0);
        SceneBuilder3D.EdgeTarget target = new SceneBuilder3D.EdgeTarget(
                "b.B", NodeType.CLASS, 120, -40, -80);

        Group arrow = CurvedArrow3D.build(source, target, -120, 0, 1.5, SceneBuilder3D.SELECTED_BORDER_COLOR);

        assertEquals(2, arrow.getChildren().size(), "arrow consists of one tube mesh plus one head mesh");
        MeshView shaft = assertInstanceOf(MeshView.class, arrow.getChildren().get(0));
        TriangleMesh mesh = assertInstanceOf(TriangleMesh.class, shaft.getMesh());
        double highestY = minY(mesh);
        assertTrue(highestY < Math.min(source.topY(), target.topY()),
                "curved arrow must bow above both source and target tops");
        Point3D firstRing = ringCenter(mesh, 0);
        Point3D secondRing = ringCenter(mesh, 1);
        double firstHorizontalOffset = Math.hypot(
                secondRing.getX() - firstRing.getX(),
                secondRing.getZ() - firstRing.getZ());
        assertTrue(firstHorizontalOffset > 0.5, "arrow must not start as a vertical riser");
    }

    @Test
    void packageColourDarkensWithNestingDepthWithoutTurningRed() {
        assumeJavaFx3DAvailable();

        ArchitectureNode root = node("root", NodeType.PACKAGE, -1);
        ArchitectureNode outer = node("outer", NodeType.PACKAGE, 1);
        ArchitectureNode inner = node("outer.inner", NodeType.PACKAGE, 1);
        outer.addChild(inner);
        root.addChild(outer);

        Map<String, Bounds> bounds = new LinkedHashMap<>();
        bounds.put("outer", new BoundingBox(0, 0, 180, 120));
        bounds.put("outer.inner", new BoundingBox(20, 20, 120, 60));

        Group group = new SceneBuilder3D().build(bounds, root, null).group();
        Box outerBox = assertInstanceOf(Box.class, group.getChildren().get(0));
        Box innerBox = assertInstanceOf(Box.class, group.getChildren().get(1));

        assertEquals(SceneBuilder3D.PACKAGE_BASE_COLOR, material(outerBox).getDiffuseColor());
        assertEquals(SceneBuilder3D.PACKAGE_DEEP_COLOR, material(innerBox).getDiffuseColor());
    }

    @Test
    void classBoxHeightEncodesGlobalArchitectureLevelWithoutChangingFaninWidth() {
        assumeJavaFx3DAvailable();

        ArchitectureNode root = node("root", NodeType.PACKAGE, -1);
        ArchitectureNode pkg = node("com.example", NodeType.PACKAGE, 0);
        ArchitectureNode low = node("com.example.Low", NodeType.CLASS, 0);
        ArchitectureNode high = node("com.example.High", NodeType.CLASS, 3);
        pkg.addChild(low);
        pkg.addChild(high);
        root.addChild(pkg);

        Map<String, Bounds> bounds = new LinkedHashMap<>();
        bounds.put("com.example", new BoundingBox(0, 0, 200, 100));
        bounds.put("com.example.Low", new BoundingBox(20, 30, 70, 24));
        bounds.put("com.example.High", new BoundingBox(110, 30, 70, 24));

        Group group = new SceneBuilder3D().build(bounds, root, null).group();
        Group lowGroup = assertInstanceOf(Group.class, group.getChildren().get(1));
        Group highGroup = assertInstanceOf(Group.class, group.getChildren().get(2));
        Box lowBox = assertInstanceOf(Box.class, lowGroup.getChildren().get(0));
        Box highBox = assertInstanceOf(Box.class, highGroup.getChildren().get(0));

        assertEquals(fanInWidth(low), lowBox.getWidth(), 0.01);
        assertEquals(24, lowBox.getDepth(), 0.01);
        assertEquals(fanInWidth(high), highBox.getWidth(), 0.01);
        assertEquals(24, highBox.getDepth(), 0.01);
        assertEquals(SceneBuilder3D.classThickness(0), lowBox.getHeight(), 0.01);
        assertEquals(SceneBuilder3D.classThickness(3), highBox.getHeight(), 0.01);
        assertEquals(bottomY(lowBox), bottomY(highBox), 0.01,
                "class boxes must grow upward from the same package slab");
    }

    private static ArchitectureNode node(String fqn, NodeType type, int architectureLevel) {
        ArchitectureNode node = new ArchitectureNode(fqn, fqn, type, false);
        node.setArchitectureLevel(architectureLevel);
        return node;
    }

    private static void assumeJavaFx3DAvailable() {
        assumeTrue(javaFx3DAvailable(),
                "JavaFX 3D pipeline is not available in this test runtime");
    }

    private static boolean javaFx3DAvailable() {
        try {
            new Box(1, 1, 1);
            new PhongMaterial();
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static PhongMaterial material(Box box) {
        return assertInstanceOf(PhongMaterial.class, box.getMaterial());
    }

    private static double fanInWidth(ArchitectureNode node) {
        return Layout3D.UNIT * (1 + Math.log10(Math.max(1, node.getDependents().size())));
    }

    private static void assertPickable(Box box, String fullName, NodeType type) {
        SceneBuilder3D.PickableElement pickable = assertInstanceOf(
                SceneBuilder3D.PickableElement.class,
                box.getProperties().get(SceneBuilder3D.PICKABLE_PROPERTY));
        assertEquals(fullName, pickable.fullName());
        assertEquals(type, pickable.type());
    }

    private static void assertEdgeTarget(SceneBuilder3D.EdgeTarget target,
                                         String fullName,
                                         NodeType type,
                                         double centerX,
                                         double topY,
                                         double centerZ) {
        assertEquals(fullName, target.fullName());
        assertEquals(type, target.type());
        assertEquals(centerX, target.centerX(), 0.01);
        assertEquals(topY, target.topY(), 0.01);
        assertEquals(centerZ, target.centerZ(), 0.01);
    }

    private static double topY(Box box) {
        return box.getTranslateY() - box.getHeight() / 2.0;
    }

    private static double minY(TriangleMesh mesh) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 1; i < mesh.getPoints().size(); i += 3) {
            min = Math.min(min, mesh.getPoints().get(i));
        }
        return min;
    }

    private static Point3D ringCenter(TriangleMesh mesh, int ring) {
        double x = 0;
        double y = 0;
        double z = 0;
        int vertexOffset = ring * CurvedArrow3D.TUBE_SEGMENTS;
        for (int i = 0; i < CurvedArrow3D.TUBE_SEGMENTS; i++) {
            int p = (vertexOffset + i) * 3;
            x += mesh.getPoints().get(p);
            y += mesh.getPoints().get(p + 1);
            z += mesh.getPoints().get(p + 2);
        }
        return new Point3D(
                x / CurvedArrow3D.TUBE_SEGMENTS,
                y / CurvedArrow3D.TUBE_SEGMENTS,
                z / CurvedArrow3D.TUBE_SEGMENTS);
    }

    private static double bottomY(Box box) {
        return box.getTranslateY() + box.getHeight() / 2.0;
    }
}
