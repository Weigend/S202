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

import de.weigend.s202.domain.StronglyConnectedComponent;
import io.softwareecg.wfx.lookup.api.Lookup;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Renders SCC (Strongly Connected Components) visualization.
 * Detects cyclic dependencies using Tarjan's algorithm and draws red arrows.
 *
 * <p>Features:
 * <ul>
 *   <li>Detects cycles using Tarjan's SCC algorithm</li>
 *   <li>Draws red arrows for cyclic dependencies only</li>
 *   <li>Interactive hover effects with status updates</li>
 *   <li>Visibility filtering (only draws visible elements)</li>
 *   <li>Zoom-aware scaling</li>
 * </ul>
 */
public class SCCRenderer {

    private static final Color SCC_COLOR = Color.RED;
    private static final Color PKG_CYCLE_COLOR = Color.web("#ff8c00"); // orange
    private static final Color SCC_HIGHLIGHT_COLOR = Color.web("#ffeb3b"); // bright yellow
    private static final double SCC_WIDTH = 1.0;
    private static final double SCC_HIGHLIGHT_WIDTH_FACTOR = 3.0;

    private final Pane sccPane;
    private final Map<String, Node> elementRegistry;
    private final Consumer<String> statusCallback;

    /** Package tangles for the active architecture — used to draw cross-package cycle contributions. */
    private List<Set<String>> packageTangles = List.of();
    private Function<String, String> packageResolver = SCCRenderer::staticPackage;

    private boolean showClassScc = false;
    private boolean showPackageCycles = false;

    private final List<Line> sccLines = new ArrayList<>();
    private boolean sccLinesDrawn = false;

    /** Currently highlighted edge (sourceName, targetName), or null when none. */
    private String highlightFrom;
    private String highlightTo;

    /**
     * Creates a new SCCRenderer.
     *
     * @param sccPane Pane where SCC lines are drawn
     * @param elementRegistry Map from element names to UI nodes
     * @param statusCallback Callback to update status messages
     */
    public SCCRenderer(Pane sccPane, Map<String, Node> elementRegistry, Consumer<String> statusCallback) {
        this.sccPane = Objects.requireNonNull(sccPane, "sccPane cannot be null");
        this.elementRegistry = Objects.requireNonNull(elementRegistry, "elementRegistry cannot be null");
        this.statusCallback = Objects.requireNonNull(statusCallback, "statusCallback cannot be null");
    }

    public void setPackageTangles(List<Set<String>> packageTangles) {
        this.packageTangles = packageTangles == null ? List.of() : List.copyOf(packageTangles);
    }

    public void setPackageResolver(Function<String, String> packageResolver) {
        this.packageResolver = packageResolver == null ? SCCRenderer::staticPackage : packageResolver;
    }

    public void setShowClassScc(boolean show) { this.showClassScc = show; }
    public void setShowPackageCycles(boolean show) { this.showPackageCycles = show; }

    public void setCoordinateContext(Pane zoomableContent, Pane overlayPane, ScrollPane scrollPane) {
        // Kept for existing callers. SCC coordinates are derived from sccPane directly.
    }

    /**
     * Clears all SCC lines and resets the drawn flag. The highlighted edge
     * marker is preserved across clears so a subsequent re-draw (e.g. after
     * zoom) can restore it.
     */
    public void clearSccLines() {
        sccPane.getChildren().clear();
        sccLines.clear();
        sccLinesDrawn = false;
    }

    /**
     * Mark the (from → to) SCC edge as the user-selected one. Subsequent
     * draws render that line in {@link #SCC_HIGHLIGHT_COLOR} with a thicker
     * stroke. Already-drawn lines are restyled in place. Pass either argument
     * as null to clear.
     */
    public void highlightEdge(String from, String to) {
        this.highlightFrom = from;
        this.highlightTo = to;
        applyHighlightToExistingLines();
    }

    private boolean isHighlighted(String from, String to) {
        return highlightFrom != null && highlightTo != null
                && highlightFrom.equals(from) && highlightTo.equals(to);
    }

    /**
     * Restyle already-drawn lines so the currently selected edge — if any —
     * stands out. Called after {@link #highlightEdge} and after each draw.
     */
    private void applyHighlightToExistingLines() {
        double baseWidth = getScaledLineWidth();
        for (Line line : sccLines) {
            // Only mainline segments carry userData with source/target. Skip
            // the arrowhead lines (their stroke is restyled via the array).
            Object data = line.getUserData();
            if (!(data instanceof Object[] meta) || meta.length < 5) {
                continue;
            }
            String sourceName = (String) meta[3];
            String targetName = (String) meta[4];
            boolean hi = isHighlighted(sourceName, targetName);
            Color baseColor = meta[2] instanceof Color c ? c : SCC_COLOR;
            Color color = hi ? SCC_HIGHLIGHT_COLOR : baseColor;
            double width = hi ? baseWidth * SCC_HIGHLIGHT_WIDTH_FACTOR : baseWidth;
            line.setStroke(color);
            line.setStrokeWidth(width);
            // Update the arrow strokes (but not meta[2] — that holds the original base color).
            ((Line) meta[0]).setStroke(color);
            ((Line) meta[1]).setStroke(color);
            ((Line) meta[0]).setStrokeWidth(width);
            ((Line) meta[1]).setStrokeWidth(width);
        }
    }

    /**
     * Returns whether SCC lines have been drawn.
     */
    public boolean isSccLinesDrawn() {
        return sccLinesDrawn;
    }

    /**
     * Draws SCC lines connecting all visible classes that are part of the same SCC (cycle).
     */
    public void drawSccLines(ArchitectureNode rootNode) {
        if (rootNode == null) {
            return;
        }

        // Clear existing SCC lines
        sccPane.getChildren().clear();
        sccLines.clear();

        // Step 1: Collect all visible classes and their dependencies
        Map<String, Set<String>> classDependencies = new HashMap<>();
        collectVisibleClassDependencies(rootNode, classDependencies);

        if (classDependencies.isEmpty()) {
            sccLinesDrawn = true;
            statusCallback.accept("No visible classes for SCC analysis");
            return;
        }

        // Step 2: Find SCCs
        List<StronglyConnectedComponent> sccs = Lookup.lookup(SCCFinder.class).findSCCs(classDependencies);

        // Step 3: Draw red lines for strict class-level SCCs (size > 1)
        Set<String> classSccMembers = new HashSet<>();
        int sccCount = 0;
        if (showClassScc) {
            for (StronglyConnectedComponent scc : sccs) {
                if (scc.isTangle()) {
                    drawSccComponentLines(scc, classDependencies, SCC_COLOR);
                    classSccMembers.addAll(scc.getMembers());
                    sccCount++;
                }
            }
        }

        // Step 4: Draw orange lines for cross-package deps within package tangles
        // where the classes are not already covered by a red class-SCC line.
        int pkgCycleCount = 0;
        if (showPackageCycles) {
            pkgCycleCount = drawPackageCycleLines(classDependencies, classSccMembers);
        }

        // Mark as drawn
        sccLinesDrawn = true;

        // Re-apply the highlight (if any) on the freshly drawn lines.
        applyHighlightToExistingLines();

        if (sccCount > 0 || pkgCycleCount > 0) {
            statusCallback.accept("Cycles: " + sccCount + " class SCC(s) (red)"
                    + (pkgCycleCount > 0 ? ", " + pkgCycleCount + " package cycle edge(s) (orange)" : ""));
        } else {
            statusCallback.accept("No cycles found among visible classes");
        }
    }

    /**
     * Recursively collects class dependencies from visible (expanded) nodes.
     */
    private void collectVisibleClassDependencies(ArchitectureNode node, Map<String, Set<String>> result) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            // Check if this class is actually visible in the UI
            Node uiNode = elementRegistry.get(node.getFullName());
            if (uiNode != null && isNodeActuallyVisible(uiNode)) {
                // Filter dependencies to only include classes we know about
                Set<String> visibleDeps = new HashSet<>();
                for (String dep : node.getDependencies()) {
                    if (elementRegistry.containsKey(dep)) {
                        visibleDeps.add(dep);
                    }
                }
                result.put(node.getFullName(), visibleDeps);
            }
        }

        // Recurse into children
        for (ArchitectureNode child : node.getChildren()) {
            collectVisibleClassDependencies(child, result);
        }
    }

    /**
     * Draws lines connecting all members of a single SCC that are visible.
     * Only draws the actual dependency edges within the SCC, not all pairs.
     */
    private int drawPackageCycleLines(Map<String, Set<String>> classDeps, Set<String> skipClasses) {
        int count = 0;
        for (Set<String> tangle : packageTangles) {
            for (String cls : classDeps.keySet()) {
                String clsPkg = packageOf(cls);
                if (!tangle.contains(clsPkg)) continue;
                for (String dep : classDeps.getOrDefault(cls, Set.of())) {
                    String depPkg = packageOf(dep);
                    if (!tangle.contains(depPkg) || depPkg.equals(clsPkg)) continue;
                    if (skipClasses.contains(cls) && skipClasses.contains(dep)) continue;
                    Node src = elementRegistry.get(cls);
                    Node tgt = elementRegistry.get(dep);
                    if (src == null || tgt == null) continue;
                    if (!isNodeActuallyVisible(src) || !isNodeActuallyVisible(tgt)) continue;
                    drawLine(src, tgt, cls, dep, PKG_CYCLE_COLOR);
                    count++;
                }
            }
        }
        return count;
    }

    private String packageOf(String fqn) {
        String packageName = packageResolver.apply(fqn);
        return packageName == null ? staticPackage(fqn) : packageName;
    }

    private static String staticPackage(String fqn) {
        int dot = fqn == null ? -1 : fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private void drawSccComponentLines(StronglyConnectedComponent scc, Map<String, Set<String>> classDependencies) {
        drawSccComponentLines(scc, classDependencies, SCC_COLOR);
    }

    private void drawSccComponentLines(StronglyConnectedComponent scc, Map<String, Set<String>> classDependencies, Color color) {
        Set<String> members = scc.getMembers();

        // Draw dependency edges between members of this SCC
        for (String member : members) {
            Node sourceNode = elementRegistry.get(member);
            if (sourceNode == null || !isNodeActuallyVisible(sourceNode)) {
                continue;
            }

            Set<String> deps = classDependencies.getOrDefault(member, Set.of());
            for (String dep : deps) {
                if (members.contains(dep)) {
                    Node targetNode = elementRegistry.get(dep);
                    if (targetNode != null && isNodeActuallyVisible(targetNode)) {
                        createSccLine(sourceNode, targetNode, member, dep);
                    }
                }
            }
        }
    }

    /**
     * Creates a single SCC line between two elements.
     */
    private void drawLine(Node source, Node target, String sourceName, String targetName, Color color) {
        createSccLine(source, target, sourceName, targetName, color);
    }

    private void createSccLine(Node source, Node target, String sourceName, String targetName) {
        createSccLine(source, target, sourceName, targetName, SCC_COLOR);
    }

    private void createSccLine(Node source, Node target, String sourceName, String targetName, Color color) {
        try {
            double[] sourceBounds = getBoundsInPane(source);
            double[] targetBounds = getBoundsInPane(target);

            if (sourceBounds == null || targetBounds == null) {
                return;
            }

            double sourceCenterX = (sourceBounds[0] + sourceBounds[2]) / 2.0;
            double sourceCenterY = (sourceBounds[1] + sourceBounds[3]) / 2.0;
            double targetCenterX = (targetBounds[0] + targetBounds[2]) / 2.0;
            double targetCenterY = (targetBounds[1] + targetBounds[3]) / 2.0;
            double[] start = edgePoint(sourceBounds, targetCenterX, targetCenterY);
            double[] end = edgePoint(targetBounds, sourceCenterX, sourceCenterY);
            double startX = start[0];
            double startY = start[1];
            double endX = end[0];
            double endY = end[1];

            // Create the line
            double scaledWidth = getScaledLineWidth();
            Line line = new Line(startX, startY, endX, endY);
            line.setStroke(color);
            line.setStrokeWidth(scaledWidth);

            // Hover effects — restore via the stored "default" colour kept on
            // userData[2], so a highlighted edge stays yellow on mouse-out.
            line.setOnMouseEntered(e -> {
                line.setStroke(Color.DARKRED);
                line.setCursor(javafx.scene.Cursor.HAND);
            });

            line.setOnMouseExited(e -> {
                Object data = line.getUserData();
                Color restore = (data instanceof Object[] meta && meta.length > 2 && meta[2] instanceof Color c)
                        ? c : SCC_COLOR;
                line.setStroke(restore);
                line.setCursor(javafx.scene.Cursor.DEFAULT);
            });

            // Click handler
            line.setOnMouseClicked(e -> {
                String simpleSource = sourceName.contains(".") ?
                        sourceName.substring(sourceName.lastIndexOf('.') + 1) : sourceName;
                String simpleTarget = targetName.contains(".") ?
                        targetName.substring(targetName.lastIndexOf('.') + 1) : targetName;
                statusCallback.accept("SCC Edge: " + simpleSource + " ↔ " + simpleTarget);
                e.consume();
            });

            // Create arrowhead
            double arrowSize = getScaledArrowSize();
            double angle = Math.atan2(endY - startY, endX - startX);

            double x1 = endX - arrowSize * Math.cos(angle - Math.PI / 6);
            double y1 = endY - arrowSize * Math.sin(angle - Math.PI / 6);
            double x2 = endX - arrowSize * Math.cos(angle + Math.PI / 6);
            double y2 = endY - arrowSize * Math.sin(angle + Math.PI / 6);

            Line arrow1 = new Line(endX, endY, x1, y1);
            Line arrow2 = new Line(endX, endY, x2, y2);
            arrow1.setStroke(color);
            arrow2.setStroke(color);
            arrow1.setStrokeWidth(scaledWidth);
            arrow2.setStrokeWidth(scaledWidth);
            arrow1.setMouseTransparent(true);
            arrow2.setMouseTransparent(true);

            // Store metadata
            line.setUserData(new Object[]{arrow1, arrow2, color, sourceName, targetName});

            sccPane.getChildren().addAll(line, arrow1, arrow2);
            sccLines.add(line);
            sccLines.add(arrow1);
            sccLines.add(arrow2);

        } catch (Exception e) {
            // Ignore drawing errors for elements not in scene
        }
    }

    /**
     * Checks if a node is actually visible (itself and all parents are visible).
     */
    private boolean isNodeActuallyVisible(Node node) {
        if (node == null || !node.isVisible()) {
            return false;
        }

        // Check all parents up to the scene root
        Parent parent = node.getParent();
        while (parent != null) {
            if (!parent.isVisible()) {
                return false;
            }
            parent = parent.getParent();
        }

        return true;
    }

    /**
     * Returns [minX, minY, maxX, maxY] in the SCC pane's coordinate space.
     */
    private double[] getBoundsInPane(Node node) {
        try {
            if (node == null || sccPane == null || node.getScene() != sccPane.getScene()) {
                return null;
            }
            Bounds bounds = sccPane.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            if (!isUsable(bounds)) {
                return null;
            }
            return new double[]{bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()};
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isUsable(Bounds bounds) {
        return bounds != null
                && Double.isFinite(bounds.getMinX())
                && Double.isFinite(bounds.getMinY())
                && Double.isFinite(bounds.getWidth())
                && Double.isFinite(bounds.getHeight())
                && bounds.getWidth() > 1.0
                && bounds.getHeight() > 1.0;
    }

    private static double[] edgePoint(double[] box, double towardX, double towardY) {
        double minX = box[0];
        double minY = box[1];
        double maxX = box[2];
        double maxY = box[3];
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        double dx = towardX - cx;
        double dy = towardY - cy;
        if (Math.abs(dx) < 0.0001 && Math.abs(dy) < 0.0001) {
            return new double[]{cx, cy};
        }
        double halfW = (maxX - minX) / 2.0;
        double halfH = (maxY - minY) / 2.0;
        double scale = 1.0 / Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH);
        return new double[]{cx + dx * scale, cy + dy * scale};
    }

    /**
     * Returns the line width (scales automatically with content).
     */
    private double getScaledLineWidth() {
        return SCC_WIDTH;
    }

    /**
     * Returns the arrow size (scales automatically with content).
     */
    private double getScaledArrowSize() {
        return 4.0;
    }
}
