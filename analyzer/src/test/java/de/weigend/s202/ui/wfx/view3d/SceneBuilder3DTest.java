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

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.DrawMode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneBuilder3DTest {

    @Test
    void sceneUses2DBoundsForPackageFootprintAndStacksClassesAbovePackages() {
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
        assertEquals(5, clsGroup.getChildren().size());
        assertEquals(DrawMode.FILL, clsBorder.getDrawMode());

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
        clsHover.setHovered(false);
        assertEquals(SceneBuilder3D.SELECTED_BORDER_COLOR, material(clsBorder).getDiffuseColor());
        clsHover.setSelected(false);
        assertEquals(SceneBuilder3D.CLASS_BORDER_COLOR, material(clsBorder).getDiffuseColor());
    }

    @Test
    void packageColourDarkensWithNestingDepthWithoutTurningRed() {
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

    private static double topY(Box box) {
        return box.getTranslateY() - box.getHeight() / 2.0;
    }

    private static double bottomY(Box box) {
        return box.getTranslateY() + box.getHeight() / 2.0;
    }
}
