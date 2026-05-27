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

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import javafx.geometry.Bounds;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PointLight;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;

import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JavaFX 3D scene from the already laid-out 2D element bounds.
 *
 * <p><b>Coordinate mapping</b> ("tip horizontal"):
 * <pre>
 *   2D scene X  →  3D X  (unchanged)
 *   2D scene Y  →  3D Z  (inverted, so the 2D bottom edge faces the camera)
 * </pre>
 *
 * <p><b>Geometry</b> is intentionally derived from the 2D view, not from
 * structural metrics. Width and depth are the 2D node bounds. Y is used to
 * stack thin package slabs by hierarchy depth and to encode the global
 * architecture level as class-box height.
 *
 * <p><b>Nesting</b>: each successive package depth gets a slightly higher
 * slab. Classes are placed as thin rectangles on the slab of their direct
 * package. The result is a tilted version of the 2D layout, not a metric city.
 *
 * <p><b>Colour scheme</b>: packages start with the 2D package colour
 * {@code #fffacd} and become gradually darker with nesting depth. Classes use
 * a stronger light-blue rendering of the 2D class fill and the 2D border
 * colour {@code #0066cc}.
 */
class SceneBuilder3D {

    /** Minimum visible footprint to avoid degenerate JavaFX boxes. */
    static final double MIN_FOOTPRINT = 2.0;
    /** Vertical distance between nested package slabs. */
    static final double PACKAGE_STACK_STEP = 12.0;
    /** No air gap: classes sit directly on their direct package slab. */
    static final double CLASS_LIFT = 0.0;
    /** Thickness of one package slab. */
    static final double PACKAGE_THICKNESS = 3.0;
    /** Height of a level-0 class tile. */
    static final double CLASS_THICKNESS = 8.0;
    /** Additional class-box height per global architecture level. */
    static final double CLASS_LEVEL_HEIGHT_STEP = 8.0;
    /** Package colour used by the 2D UI. */
    static final Color PACKAGE_BASE_COLOR = Color.web("#fffacd");
    /** Slightly darker yellow for deeply nested package slabs. */
    static final Color PACKAGE_DEEP_COLOR = Color.web("#d6b85a");
    /** Visible light-blue class fill; the very pale 2D fill washes out in 3D lighting. */
    static final Color CLASS_COLOR = Color.web("#7fb3ff");
    /** Class border colour used by the 2D UI. */
    static final Color CLASS_BORDER_COLOR = Color.web("#0066cc");
    /** Disable white highlights so the pale blue fill does not wash out. */
    static final Color CLASS_SPECULAR_COLOR = Color.BLACK;
    /** Width of the blue class outline on the top surface. */
    static final double CLASS_BORDER_WIDTH = 1.5;
    /** Height of the class outline bars. */
    static final double CLASS_BORDER_THICKNESS = 0.8;
    /** Small separation above the tile surface to avoid z-fighting. */
    static final double CLASS_BORDER_SURFACE_LIFT = 0.15;

    // Diagnostic: set true to print element bounds to stdout
    private static final boolean DEBUG_BOUNDS = false;

    // -----------------------------------------------------------------------

    /** Hint for the camera's initial position and look-at target. */
    record CameraHint(double x, double y, double z,
                      double targetX, double targetY, double targetZ) {}

    record SceneResult(Group group, CameraHint cameraHint) {}

    SceneResult build(Map<String, Bounds> elementBounds,
                      ArchitectureNode root,
                      Architecture architecture) {

        if (DEBUG_BOUNDS) dumpBounds(elementBounds);

        Map<String, ArchitectureNode> nodeMap     = buildNodeMap(root);
        Map<String, Integer>          depthMap    = buildDepthMap(root);

        int maxDepth = depthMap.values().stream().mapToInt(i -> i).max().orElse(1);

        Group group = new Group();

        // Package slabs first, then class tiles. Coordinates and footprint come
        // directly from the 2D view so the visual order remains identical.
        for (var e : elementBounds.entrySet()) {
            ArchitectureNode node = nodeMap.get(e.getKey());
            if (node == null || node.getType() != NodeType.PACKAGE) continue;
            int depth = depthMap.getOrDefault(e.getKey(), 0);
            group.getChildren().add(
                    buildPackageSlab(e.getValue(), depth, maxDepth));
        }

        for (var e : elementBounds.entrySet()) {
            ArchitectureNode node = nodeMap.get(e.getKey());
            if (node == null || node.getType() != NodeType.CLASS) continue;
            int depth = depthMap.getOrDefault(e.getKey(), 0);
            group.getChildren().add(
                    buildClassTile(e.getValue(), depth, node));
        }

        group.getChildren().addAll(buildLights(elementBounds));

        CameraHint hint = computeCameraHint(
                elementBounds,
                maxDepth,
                maxRenderedClassArchitectureLevel(elementBounds, nodeMap));
        return new SceneResult(group, hint);
    }

    // -----------------------------------------------------------------------
    // Tilted 2D elements
    // -----------------------------------------------------------------------

    private Box buildPackageSlab(Bounds b, int depth, int maxDepth) {
        return buildTilted2DBox(
                b,
                PACKAGE_THICKNESS,
                packageElevation(depth),
                packageMaterial(depth, maxDepth));
    }

    private Node buildClassTile(Bounds b, int depth, ArchitectureNode node) {
        double elevation = classElevation(depth);
        double thickness = classThickness(node.getArchitectureLevel());
        Box fill = buildTilted2DBox(
                b,
                thickness,
                elevation,
                classMaterial());
        Group tile = new Group(fill);
        tile.getChildren().addAll(buildClassBorderBars(b, elevation, thickness));
        tile.setMouseTransparent(true);
        return tile;
    }

    private Box buildTilted2DBox(Bounds b, double thickness, double elevation,
                                 PhongMaterial material) {
        double width = Math.max(MIN_FOOTPRINT, b.getWidth());
        double depth = Math.max(MIN_FOOTPRINT, b.getHeight());
        return buildPositionedBox(
                b.getCenterX(),
                worldZ(b.getCenterY()),
                width,
                thickness,
                depth,
                elevation,
                material);
    }

    private Box buildPositionedBox(double centerX, double centerZ,
                                   double width, double thickness, double depth,
                                   double elevation, PhongMaterial material) {
        Box box = new Box(width, thickness, depth);
        box.setTranslateX(centerX);
        box.setTranslateY(-elevation - box.getHeight() / 2.0);
        box.setTranslateZ(centerZ);
        box.setMaterial(material);
        return box;
    }

    private List<Box> buildClassBorderBars(Bounds b, double classElevation,
                                           double classThickness) {
        double width = Math.max(MIN_FOOTPRINT, b.getWidth());
        double depth = Math.max(MIN_FOOTPRINT, b.getHeight());
        double line = Math.min(CLASS_BORDER_WIDTH, Math.min(width, depth) / 3.0);
        double centerX = b.getCenterX();
        double centerZ = worldZ(b.getCenterY());
        double minX = centerX - width / 2.0;
        double maxX = centerX + width / 2.0;
        double minZ = centerZ - depth / 2.0;
        double maxZ = centerZ + depth / 2.0;
        double borderElevation = classElevation + classThickness + CLASS_BORDER_SURFACE_LIFT;
        PhongMaterial material = classBorderMaterial();

        Box front = buildPositionedBox(centerX, minZ + line / 2.0,
                width, CLASS_BORDER_THICKNESS, line, borderElevation, material);
        Box back = buildPositionedBox(centerX, maxZ - line / 2.0,
                width, CLASS_BORDER_THICKNESS, line, borderElevation, material);
        Box left = buildPositionedBox(minX + line / 2.0, centerZ,
                line, CLASS_BORDER_THICKNESS, depth, borderElevation, material);
        Box right = buildPositionedBox(maxX - line / 2.0, centerZ,
                line, CLASS_BORDER_THICKNESS, depth, borderElevation, material);
        return List.of(front, back, left, right);
    }

    private static double packageElevation(int depth) {
        return Math.max(0, depth) * PACKAGE_STACK_STEP;
    }

    private static double classElevation(int depth) {
        int parentPackageDepth = Math.max(0, depth - 1);
        return packageElevation(parentPackageDepth) + PACKAGE_THICKNESS + CLASS_LIFT;
    }

    static double classThickness(int architectureLevel) {
        return CLASS_THICKNESS + Math.max(0, architectureLevel) * CLASS_LEVEL_HEIGHT_STEP;
    }

    private static double worldZ(double sceneY) {
        return -sceneY;
    }

    // -----------------------------------------------------------------------
    // Materials / colour
    // -----------------------------------------------------------------------

    /**
     * Package slab colour follows nesting depth only. We intentionally do not
     * encode tangles here; the 3D view should first read like the 2D layout,
     * just tilted into space.
     */
    private PhongMaterial packageMaterial(int depth, int maxDepth) {
        double ratio = Math.min(1.0, Math.max(0.0, (double) depth / Math.max(1, maxDepth)));
        Color color = PACKAGE_BASE_COLOR.interpolate(PACKAGE_DEEP_COLOR, ratio);
        PhongMaterial mat = new PhongMaterial(color);
        mat.setSpecularColor(color.brighter());
        return mat;
    }

    /** Class tile colour is the same as the 2D class box for now. */
    private PhongMaterial classMaterial() {
        PhongMaterial mat = new PhongMaterial(CLASS_COLOR);
        mat.setSpecularColor(CLASS_SPECULAR_COLOR);
        mat.setSpecularPower(96.0);
        return mat;
    }

    /** Class outline uses the same blue as the 2D class border. */
    private PhongMaterial classBorderMaterial() {
        PhongMaterial mat = new PhongMaterial(CLASS_BORDER_COLOR);
        mat.setSpecularColor(CLASS_BORDER_COLOR);
        mat.setSpecularPower(96.0);
        return mat;
    }

    // -----------------------------------------------------------------------
    // Camera hint
    // -----------------------------------------------------------------------

    private CameraHint computeCameraHint(Map<String, Bounds> elementBounds,
                                         int maxDepth,
                                         int maxClassArchitectureLevel) {
        if (elementBounds.isEmpty()) return new CameraHint(500, -800, -1000, 500, 0, 0);
        DoubleSummaryStatistics xs = elementBounds.values().stream()
                .flatMapToDouble(b -> java.util.stream.DoubleStream.of(b.getMinX(), b.getMaxX()))
                .summaryStatistics();
        DoubleSummaryStatistics ys = elementBounds.values().stream()
                .flatMapToDouble(b -> java.util.stream.DoubleStream.of(worldZ(b.getMinY()), worldZ(b.getMaxY())))
                .summaryStatistics();
        double targetX = (xs.getMin() + xs.getMax()) / 2.0;
        double targetZ = (ys.getMin() + ys.getMax()) / 2.0;
        double verticalExtent = packageElevation(maxDepth)
                + PACKAGE_THICKNESS
                + classThickness(maxClassArchitectureLevel);
        double targetY = -verticalExtent / 2.0;
        double spread  = Math.max(xs.getMax() - xs.getMin(), ys.getMax() - ys.getMin());
        double distance = Math.max(700, spread * 1.10);
        double height   = Math.max(450, Math.max(spread * 0.70, verticalExtent * 2.0));
        double cameraX  = targetX;
        double cameraY  = targetY - height;
        double cameraZ  = ys.getMin() - distance;
        return new CameraHint(cameraX, cameraY, cameraZ, targetX, targetY, targetZ);
    }

    // -----------------------------------------------------------------------
    // Lighting
    // -----------------------------------------------------------------------

    private static List<javafx.scene.Node> buildLights(Map<String, Bounds> bounds) {
        double cx = 0, cz = 0;
        if (!bounds.isEmpty()) {
            cx = bounds.values().stream().mapToDouble(Bounds::getCenterX).average().orElse(0);
            cz = bounds.values().stream().mapToDouble(b -> worldZ(b.getCenterY())).average().orElse(0);
        }
        AmbientLight ambient = new AmbientLight(Color.gray(0.70));
        PointLight   point   = new PointLight(Color.gray(0.35));
        point.setTranslateX(cx);
        point.setTranslateY(-2000);
        point.setTranslateZ(cz);
        return List.of(ambient, point);
    }

    // -----------------------------------------------------------------------
    // Tree helpers
    // -----------------------------------------------------------------------

    private static Map<String, ArchitectureNode> buildNodeMap(ArchitectureNode root) {
        Map<String, ArchitectureNode> map = new HashMap<>();
        if (root != null) collectNodes(root, map);
        return map;
    }

    private static void collectNodes(ArchitectureNode n, Map<String, ArchitectureNode> map) {
        map.put(n.getFullName(), n);
        for (ArchitectureNode c : n.getChildren()) collectNodes(c, map);
    }

    /**
     * Depth of each node in the ArchitectureNode tree, where top-level packages
     * (children of the synthetic root) are at depth 0.
     */
    private static Map<String, Integer> buildDepthMap(ArchitectureNode root) {
        Map<String, Integer> map = new HashMap<>();
        if (root == null) return map;
        for (ArchitectureNode child : root.getChildren()) collectDepths(child, 0, map);
        return map;
    }

    private static void collectDepths(ArchitectureNode node, int depth,
                                       Map<String, Integer> map) {
        if (node.getArchitectureLevel() >= 0) map.put(node.getFullName(), depth);
        for (ArchitectureNode child : node.getChildren())
            collectDepths(child, depth + 1, map);
    }

    private static int maxRenderedClassArchitectureLevel(
            Map<String, Bounds> elementBounds,
            Map<String, ArchitectureNode> nodeMap) {
        return elementBounds.keySet().stream()
                .map(nodeMap::get)
                .filter(node -> node != null && node.getType() == NodeType.CLASS)
                .mapToInt(ArchitectureNode::getArchitectureLevel)
                .filter(level -> level >= 0)
                .max()
                .orElse(0);
    }

    // -----------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------

    private static void dumpBounds(Map<String, Bounds> elementBounds) {
        System.out.println("=== 3D Scene – element bounds (2D scene coords) ===");
        elementBounds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    Bounds b = e.getValue();
                    System.out.printf("  %-60s  x=%6.0f  y=%6.0f  w=%6.0f  h=%6.0f%n",
                            e.getKey(), b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
                });
        System.out.println("===================================================");
    }
}
