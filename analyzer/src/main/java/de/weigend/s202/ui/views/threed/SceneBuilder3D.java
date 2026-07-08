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
package de.weigend.s202.ui.views.threed;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
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
 *   2D layout X →  3D X  (unchanged)
 *   2D layout Y →  3D Z  (inverted, so the 2D bottom edge faces the camera)
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
    /** Property key used by the interaction layer to identify selectable 3D nodes. */
    static final String PICKABLE_PROPERTY = SceneBuilder3D.class.getName() + ".pickable";
    /** Subtle border colour for the element currently under the pointer. */
    static final Color HOVER_BORDER_COLOR = Color.web("#f8fafc");
    /** Same orange used by the 2D selected class/package border. */
    static final Color SELECTED_BORDER_COLOR = Color.web("#ff6600");
    /** Width of the hover outline on package slabs. */
    static final double HOVER_BORDER_WIDTH = 2.0;
    /** Height of hover outline bars. */
    static final double HOVER_BORDER_THICKNESS = 1.0;
    /** Small separation above the hovered surface to avoid z-fighting. */
    static final double HOVER_BORDER_SURFACE_LIFT = 0.35;

    // Diagnostic: set true to print element bounds + class levels to stdout
    private static final boolean DEBUG_BOUNDS = false;

    // -----------------------------------------------------------------------

    /** Hint for the camera's initial position and look-at target. */
    record CameraHint(double x, double y, double z,
                      double targetX, double targetY, double targetZ) {}

    record PickableElement(String fullName, NodeType type) {}

    record EdgeTarget(String fullName, NodeType type,
                      double centerX, double topY, double centerZ) {}

    static final class HoverTarget {
        private final String fullName;
        private final NodeType type;
        private final List<Box> borderBars;
        private final List<Box> selectionOnlyBars;
        private final PhongMaterial idleMaterial;
        private final PhongMaterial hoverMaterial;
        private final PhongMaterial selectedMaterial;
        private final boolean hiddenWhenIdle;
        private boolean hovered;
        private boolean selected;

        HoverTarget(String fullName,
                    NodeType type,
                    List<Box> borderBars,
                    List<Box> selectionOnlyBars,
                    PhongMaterial idleMaterial,
                    PhongMaterial hoverMaterial,
                    PhongMaterial selectedMaterial,
                    boolean hiddenWhenIdle) {
            this.fullName = fullName;
            this.type = type;
            this.borderBars = borderBars;
            this.selectionOnlyBars = selectionOnlyBars;
            this.idleMaterial = idleMaterial;
            this.hoverMaterial = hoverMaterial;
            this.selectedMaterial = selectedMaterial;
            this.hiddenWhenIdle = hiddenWhenIdle;
        }

        String fullName() {
            return fullName;
        }

        NodeType type() {
            return type;
        }

        List<Box> borderBars() {
            return borderBars;
        }

        void setHovered(boolean hovered) {
            this.hovered = hovered;
            refresh();
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            refresh();
        }

        boolean isSelected() {
            return selected;
        }

        private void refresh() {
            for (Box bar : borderBars) {
                bar.setVisible(selected || hovered || !hiddenWhenIdle);
                if (selected) {
                    bar.setMaterial(selectedMaterial);
                } else if (hovered) {
                    bar.setMaterial(hoverMaterial);
                } else {
                    bar.setMaterial(idleMaterial);
                }
            }
            for (Box bar : selectionOnlyBars) {
                bar.setVisible(selected);
            }
        }
    }

    record RenderedElement(Node node, HoverTarget hoverTarget, EdgeTarget edgeTarget) {}

    record SceneResult(Group group,
                       CameraHint cameraHint,
                       Map<String, HoverTarget> hoverTargets,
                       Map<String, EdgeTarget> edgeTargets) {}

    SceneResult build(Map<String, Bounds> elementBounds,
                      ArchitectureNode root,
                      Architecture architecture) {

        Map<String, ArchitectureNode> nodeMap  = buildNodeMap(root);
        Map<String, Integer>          depthMap = buildDepthMap(root);

        if (DEBUG_BOUNDS) {
            dumpBounds(elementBounds);
            dumpClassLevels(elementBounds, nodeMap);
        }

        int maxDepth = depthMap.values().stream().mapToInt(i -> i).max().orElse(1);

        // ── Bottom-up footprint computation ──────────────────────────────────
        // 1. Compute each class tile's actual 3D XZ footprint (fanin-expanded).
        Map<String, double[]> classFootprints = computeClassFootprints(elementBounds, nodeMap);
        // 2. Each package slab must cover all descendant class footprints.
        Map<String, double[]> slabFootprints  = computeSlabFootprints(elementBounds, nodeMap, classFootprints);

        Group group = new Group();
        Group hoverLayer = new Group();
        hoverLayer.setMouseTransparent(true);
        Map<String, HoverTarget> hoverTargets = new HashMap<>();
        Map<String, EdgeTarget> edgeTargets = new HashMap<>();

        // Package slabs first (behind class tiles).
        for (var e : elementBounds.entrySet()) {
            ArchitectureNode node = nodeMap.get(e.getKey());
            if (node == null || node.getType() != NodeType.PACKAGE) continue;
            int depth = depthMap.getOrDefault(e.getKey(), 0);
            double[] fp = slabFootprints.getOrDefault(e.getKey(), rawFootprint(e.getValue()));
            RenderedElement rendered = buildPackageSlabFromFootprint(e.getKey(), fp, depth, maxDepth);
            group.getChildren().add(rendered.node());
            hoverLayer.getChildren().addAll(rendered.hoverTarget().borderBars());
            hoverTargets.put(e.getKey(), rendered.hoverTarget());
            edgeTargets.put(e.getKey(), rendered.edgeTarget());
        }

        for (var e : elementBounds.entrySet()) {
            ArchitectureNode node = nodeMap.get(e.getKey());
            if (node == null || node.getType() != NodeType.CLASS) continue;
            int depth = depthMap.getOrDefault(e.getKey(), 0);
            RenderedElement rendered = buildClassTile(e.getValue(), depth, node);
            group.getChildren().add(rendered.node());
            hoverTargets.put(e.getKey(), rendered.hoverTarget());
            edgeTargets.put(e.getKey(), rendered.edgeTarget());
        }

        group.getChildren().add(hoverLayer);
        group.getChildren().addAll(buildLights(elementBounds));

        CameraHint hint = computeCameraHint(
                elementBounds,
                maxDepth,
                maxRenderedClassArchitectureLevel(elementBounds, nodeMap));
        return new SceneResult(group, hint, hoverTargets, edgeTargets);
    }

    // -----------------------------------------------------------------------
    // Tilted 2D elements
    // -----------------------------------------------------------------------

    /** Builds a package slab from an already-expanded [minX,maxX,minZ,maxZ] footprint. */
    private RenderedElement buildPackageSlabFromFootprint(String fullName, double[] fp, int depth, int maxDepth) {
        double centerX = (fp[0] + fp[1]) / 2.0;
        double centerZ = (fp[2] + fp[3]) / 2.0;
        double width   = Math.max(MIN_FOOTPRINT, fp[1] - fp[0]);
        double slabD   = Math.max(MIN_FOOTPRINT, fp[3] - fp[2]);
        Box slab = buildPositionedBox(centerX, centerZ, width, PACKAGE_THICKNESS, slabD,
                packageElevation(depth), packageMaterial(depth, maxDepth));
        installPickable(slab, fullName, NodeType.PACKAGE);

        PhongMaterial hoverMaterial = hoverBorderMaterial();
        List<Box> hoverBars = buildFootprintBorderBars(
                centerX,
                centerZ,
                width,
                slabD,
                packageElevation(depth) + PACKAGE_THICKNESS + HOVER_BORDER_SURFACE_LIFT,
                HOVER_BORDER_WIDTH,
                HOVER_BORDER_THICKNESS,
                hoverMaterial);
        for (Box bar : hoverBars) {
            bar.setVisible(false);
            bar.setMouseTransparent(true);
        }
        HoverTarget hoverTarget = new HoverTarget(
                fullName,
                NodeType.PACKAGE,
                hoverBars,
                List.of(),
                hoverMaterial,
                hoverMaterial,
                selectedBorderMaterial(),
                true);
        EdgeTarget edgeTarget = new EdgeTarget(
                fullName,
                NodeType.PACKAGE,
                centerX,
                -packageElevation(depth) - PACKAGE_THICKNESS,
                centerZ);
        return new RenderedElement(slab, hoverTarget, edgeTarget);
    }

    // ── Footprint computation (bottom-up) ────────────────────────────────────

    /**
     * For every class in the registry, compute its actual 3D XZ footprint as
     * [minX, maxX, minZ, maxZ] using the fanin-expanded width.
     */
    private static Map<String, double[]> computeClassFootprints(
            Map<String, Bounds> elementBounds,
            Map<String, ArchitectureNode> nodeMap) {
        Map<String, double[]> result = new HashMap<>();
        for (var e : elementBounds.entrySet()) {
            ArchitectureNode node = nodeMap.get(e.getKey());
            if (node == null || node.getType() != NodeType.CLASS) continue;
            Bounds b = e.getValue();
            double w  = Layout3D.UNIT * (1 + Math.log10(Math.max(1, node.getDependents().size())));
            double d  = Math.max(MIN_FOOTPRINT, b.getHeight());
            double cx = b.getCenterX();
            double cz = worldZ(b.getCenterY());
            result.put(e.getKey(), new double[]{cx - w / 2, cx + w / 2, cz - d / 2, cz + d / 2});
        }
        return result;
    }

    /**
     * For every package, start from its raw 2D footprint and expand outward
     * to cover every descendant class tile. Returns [minX,maxX,minZ,maxZ] per FQN.
     */
    private static Map<String, double[]> computeSlabFootprints(
            Map<String, Bounds> elementBounds,
            Map<String, ArchitectureNode> nodeMap,
            Map<String, double[]> classFootprints) {
        Map<String, double[]> result = new HashMap<>();
        for (var e : elementBounds.entrySet()) {
            ArchitectureNode node = nodeMap.get(e.getKey());
            if (node == null || node.getType() != NodeType.PACKAGE) continue;
            double[] fp = rawFootprint(e.getValue());
            fp = expandWithDescendants(node, classFootprints, fp);
            result.put(e.getKey(), fp);
        }
        return result;
    }

    /** Converts a JavaFX {@link Bounds} to [minX,maxX,minZ,maxZ] in 3D world coords. */
    private static double[] rawFootprint(Bounds b) {
        return new double[]{b.getMinX(), b.getMaxX(), worldZ(b.getMaxY()), worldZ(b.getMinY())};
    }

    /** Recursively expands fp to cover all descendant class tiles. */
    private static double[] expandWithDescendants(ArchitectureNode node,
                                                   Map<String, double[]> classFootprints,
                                                   double[] fp) {
        for (ArchitectureNode child : node.getChildren()) {
            double[] cf = classFootprints.get(child.getFullName());
            if (cf != null) {
                fp[0] = Math.min(fp[0], cf[0]);
                fp[1] = Math.max(fp[1], cf[1]);
                fp[2] = Math.min(fp[2], cf[2]);
                fp[3] = Math.max(fp[3], cf[3]);
            }
            fp = expandWithDescendants(child, classFootprints, fp);
        }
        return fp;
    }

    private RenderedElement buildClassTile(Bounds b, int depth, ArchitectureNode node) {
        double elevation = classElevation(depth);
        double thickness = classThickness(node.getArchitectureLevel());

        // Phase 1: logarithmic fanin width — 2× at fanin=10, 3× at fanin=100
        double fanInWidth = Layout3D.UNIT * (1 + Math.log10(Math.max(1, node.getDependents().size())));

        Box fill = buildPositionedBox(
                b.getCenterX(),
                worldZ(b.getCenterY()),
                fanInWidth,
                thickness,
                Math.max(MIN_FOOTPRINT, b.getHeight()),
                elevation,
                classMaterial());
        installPickable(fill, node.getFullName(), NodeType.CLASS);

        Group tile = new Group(fill);
        installPickable(tile, node.getFullName(), NodeType.CLASS);
        List<Box> borderBars = buildClassBorderBars(b, fanInWidth, elevation, thickness);
        for (Box borderBar : borderBars) {
            installPickable(borderBar, node.getFullName(), NodeType.CLASS);
        }
        tile.getChildren().addAll(borderBars);
        List<Box> cornerPillars = buildClassCornerPillars(b, fanInWidth, elevation, thickness);
        for (Box pillar : cornerPillars) {
            pillar.setVisible(false);
            installPickable(pillar, node.getFullName(), NodeType.CLASS);
        }
        tile.getChildren().addAll(cornerPillars);
        HoverTarget hoverTarget = new HoverTarget(
                node.getFullName(),
                NodeType.CLASS,
                borderBars,
                cornerPillars,
                classBorderMaterial(),
                hoverBorderMaterial(),
                selectedBorderMaterial(),
                false);
        EdgeTarget edgeTarget = new EdgeTarget(
                node.getFullName(),
                NodeType.CLASS,
                b.getCenterX(),
                -elevation - thickness,
                worldZ(b.getCenterY()));
        return new RenderedElement(tile, hoverTarget, edgeTarget);
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

    private List<Box> buildClassBorderBars(Bounds b, double width, double classElevation,
                                           double classThickness) {
        double depth = Math.max(MIN_FOOTPRINT, b.getHeight());
        double line = Math.min(CLASS_BORDER_WIDTH, Math.min(width, depth) / 3.0);
        double centerX = b.getCenterX();
        double centerZ = worldZ(b.getCenterY());
        return buildFootprintBorderBars(
                centerX,
                centerZ,
                width,
                depth,
                classElevation + classThickness + CLASS_BORDER_SURFACE_LIFT,
                line,
                CLASS_BORDER_THICKNESS,
                classBorderMaterial());
    }

    private List<Box> buildClassCornerPillars(Bounds b, double width, double elevation, double thickness) {
        double depth = Math.max(MIN_FOOTPRINT, b.getHeight());
        double line = Math.min(CLASS_BORDER_WIDTH, Math.min(width, depth) / 3.0);
        double minX = b.getCenterX() - width / 2.0;
        double maxX = b.getCenterX() + width / 2.0;
        double minZ = worldZ(b.getCenterY()) - depth / 2.0;
        double maxZ = worldZ(b.getCenterY()) + depth / 2.0;
        PhongMaterial mat = selectedBorderMaterial();
        // 4 vertical corner pillars spanning the full class height
        Box fl = buildPositionedBox(minX + line / 2, minZ + line / 2, line, thickness, line, elevation, mat);
        Box fr = buildPositionedBox(maxX - line / 2, minZ + line / 2, line, thickness, line, elevation, mat);
        Box bl = buildPositionedBox(minX + line / 2, maxZ - line / 2, line, thickness, line, elevation, mat);
        Box br = buildPositionedBox(maxX - line / 2, maxZ - line / 2, line, thickness, line, elevation, mat);
        return List.of(fl, fr, bl, br);
    }

    private List<Box> buildFootprintBorderBars(double centerX,
                                               double centerZ,
                                               double width,
                                               double depth,
                                               double borderElevation,
                                               double line,
                                               double thickness,
                                               PhongMaterial material) {
        double minX = centerX - width / 2.0;
        double maxX = centerX + width / 2.0;
        double minZ = centerZ - depth / 2.0;
        double maxZ = centerZ + depth / 2.0;

        Box front = buildPositionedBox(centerX, minZ + line / 2.0,
                width, thickness, line, borderElevation, material);
        Box back = buildPositionedBox(centerX, maxZ - line / 2.0,
                width, thickness, line, borderElevation, material);
        Box left = buildPositionedBox(minX + line / 2.0, centerZ,
                line, thickness, depth, borderElevation, material);
        Box right = buildPositionedBox(maxX - line / 2.0, centerZ,
                line, thickness, depth, borderElevation, material);
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

    private PhongMaterial hoverBorderMaterial() {
        PhongMaterial mat = new PhongMaterial(HOVER_BORDER_COLOR);
        mat.setSpecularColor(HOVER_BORDER_COLOR);
        mat.setSpecularPower(128.0);
        return mat;
    }

    private PhongMaterial selectedBorderMaterial() {
        PhongMaterial mat = new PhongMaterial(SELECTED_BORDER_COLOR);
        mat.setSpecularColor(SELECTED_BORDER_COLOR);
        mat.setSpecularPower(128.0);
        return mat;
    }

    private static void installPickable(Node node, String fullName, NodeType type) {
        node.getProperties().put(PICKABLE_PROPERTY, new PickableElement(fullName, type));
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
        System.out.println("=== 3D Scene - element bounds (2D layout coords) ===");
        elementBounds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    Bounds b = e.getValue();
                    System.out.printf("  %-60s  x=%6.0f  y=%6.0f  w=%6.0f  h=%6.0f%n",
                            e.getKey(), b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
                });
        System.out.println("===================================================");
    }

    private static void dumpClassLevels(Map<String, Bounds> elementBounds,
                                         Map<String, ArchitectureNode> nodeMap) {
        System.out.println("=== Class architectureLevel → 3D height ===");
        elementBounds.keySet().stream().sorted().forEach(fqn -> {
            ArchitectureNode n = nodeMap.get(fqn);
            if (n == null || n.getType() != NodeType.CLASS) return;
            int lvl = n.getArchitectureLevel();
            System.out.printf("  %-60s  archLevel=%3d  height=%.1f%n",
                    fqn, lvl, classThickness(lvl));
        });
        System.out.println("===========================================");
    }
}
