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

import de.weigend.s202.domain.DependencyEdge;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Paints tangle edges (routed, fallback, bypass and plain straight variants)
 * and installs their mouse interactions. Styling and selection live here;
 * the routes themselves are computed elsewhere and passed in. Renderer
 * callbacks are injected so this class never depends on the renderer.
 */
final class TangleEdgePainter {

    private static final Color NON_TANGLE_EDGE_COLOR = Color.web("#202020");
    private static final Color EDGE_COLOR     = Color.web("#ff5252");
    private static final Color EDGE_HOVER     = Color.web("#b71c1c");
    private static final Color SELECTED_COLOR = Color.web("#d50000");
    private static final Color CUT_EDGE_COLOR = Color.web("#ff9800");
    private static final Color APPLIED_CUT_EDGE_COLOR = Color.web("#2ecc71");
    private static final double NON_TANGLE_EDGE_WIDTH = 0.8;
    private static final double EDGE_WIDTH    = 1.2;
    private static final double SELECTED_WIDTH = 3.0;
    private static final double CUT_EDGE_WIDTH = 2.2;

    private final Pane pane;
    private final Map<String, Node> elementRegistry;
    private final Consumer<String> statusCallback;
    private final TangleCycleModel cycleModel;
    private final java.util.function.BiConsumer<String, String> selectEdge;
    private final Runnable layoutPendingSignal;

    private String selectedFrom;
    private String selectedTo;

    private java.util.function.BiConsumer<String, String> onEdgeClicked = (a, b) -> {};
    private java.util.function.BiConsumer<String, String> onEdgeCut = (a, b) -> {};
    private java.util.function.BiConsumer<String, String> onEdgeRestore = (a, b) -> {};

    TangleEdgePainter(Pane pane, Map<String, Node> elementRegistry,
                      Consumer<String> statusCallback, TangleCycleModel cycleModel,
                      java.util.function.BiConsumer<String, String> selectEdge,
                      Runnable layoutPendingSignal) {
        this.pane = pane;
        this.elementRegistry = elementRegistry;
        this.statusCallback = statusCallback;
        this.cycleModel = cycleModel;
        this.selectEdge = selectEdge;
        this.layoutPendingSignal = layoutPendingSignal;
    }

    void setOnEdgeClicked(java.util.function.BiConsumer<String, String> handler) {
        this.onEdgeClicked = handler == null ? (a, b) -> {} : handler;
    }

    void setOnEdgeCut(java.util.function.BiConsumer<String, String> handler) {
        this.onEdgeCut = handler == null ? (a, b) -> {} : handler;
    }

    void setOnEdgeRestore(java.util.function.BiConsumer<String, String> handler) {
        this.onEdgeRestore = handler == null ? (a, b) -> {} : handler;
    }

    void setSelectedEdge(String from, String to) {
        this.selectedFrom = from;
        this.selectedTo = to;
    }

    void paintRoutedEdge(TangleGeometry.RoutedTangleEdge routed, List<TangleGeometry.VerticalSegment> verticalSegments) {
        DependencyEdge edge = routed.edge;
        Color color = edgeColor(edge);
        double width = edgeWidth(edge);

        double y = routed.horizontal.y;
        TangleGeometry.Point sourceDock = TangleGeometry.dockPoint(routed.source, routed.sourceTrack.x, y);
        TangleGeometry.Point targetDock = TangleGeometry.dockPoint(routed.target, routed.targetTrack.x, y);

        Path path = new Path();
        path.setStroke(color);
        path.setStrokeWidth(width);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.MITER);
        path.setFill(null);
        path.setCursor(Cursor.HAND);
        applyEdgeDash(path, edge);

        path.getElements().add(new MoveTo(sourceDock.x(), sourceDock.y()));
        path.getElements().add(new LineTo(routed.sourceTrack.x, sourceDock.y()));
        path.getElements().add(new LineTo(routed.sourceTrack.x, y));
        TangleRouter.emitHorizontalWithBridges(path, routed.sourceTrack.x, routed.targetTrack.x, y, routed, verticalSegments);
        path.getElements().add(new LineTo(routed.targetTrack.x, targetDock.y()));
        path.getElements().add(new LineTo(targetDock.x(), targetDock.y()));

        double arrowDx = targetDock.x() - routed.targetTrack.x;
        double arrowDy = Math.abs(arrowDx) < 0.0001 ? targetDock.y() - y : 0.0;
        Polygon arrow = makeArrow(targetDock.x(), targetDock.y(), arrowDx, arrowDy, color);

        path.setOnMouseEntered(e -> {
            if (!isSelected(edge)) path.setStroke(EDGE_HOVER);
            path.setCursor(Cursor.HAND);
        });
        path.setOnMouseExited(e -> {
            path.setStroke(edgeColor(edge));
            path.setCursor(Cursor.DEFAULT);
        });
        installEdgeInteractions(path, edge);

        pane.getChildren().addAll(path, arrow);
    }

    boolean renderFallbackEdge(DependencyEdge edge, TangleGeometry.FallbackPath fallback,
                               Bounds source, Bounds target, TangleGeometry.BypassAllocator bypass) {
        if (fallback == null) {
            return renderBypassEdge(edge, source, target, bypass);
        }

        Color color = edgeColor(edge);
        double width = edgeWidth(edge);

        Path path = new Path();
        path.setStroke(color);
        path.setStrokeWidth(width);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.MITER);
        path.setFill(null);
        path.setCursor(Cursor.HAND);
        applyEdgeDash(path, edge);

        path.getElements().add(new MoveTo(fallback.sourceDock.x(), fallback.sourceDock.y()));
        path.getElements().add(new LineTo(fallback.sourceX, fallback.sourceDock.y()));
        path.getElements().add(new LineTo(fallback.sourceX, fallback.y));
        path.getElements().add(new LineTo(fallback.targetX, fallback.y));
        path.getElements().add(new LineTo(fallback.targetX, fallback.targetDock.y()));
        path.getElements().add(new LineTo(fallback.targetDock.x(), fallback.targetDock.y()));

        double arrowDx = fallback.targetDock.x() - fallback.targetX;
        double arrowDy = Math.abs(arrowDx) < 0.0001 ? fallback.targetDock.y() - fallback.y : 0.0;
        Polygon arrow = makeArrow(fallback.targetDock.x(), fallback.targetDock.y(), arrowDx, arrowDy, color);

        path.setOnMouseEntered(e -> {
            if (!isSelected(edge)) path.setStroke(EDGE_HOVER);
            path.setCursor(Cursor.HAND);
        });
        path.setOnMouseExited(e -> {
            path.setStroke(edgeColor(edge));
            path.setCursor(Cursor.DEFAULT);
        });
        installEdgeInteractions(path, edge);

        pane.getChildren().addAll(path, arrow);
        return true;
    }

    /**
     * Routes an edge around all class boxes via an outer bypass column to the left or right
     * of the entire layout. Used when Z-shaped routing finds no valid path (e.g. source and
     * target are separated by intermediate classes in the same column).
     */
    private boolean renderBypassEdge(DependencyEdge edge, Bounds source, Bounds target,
                                     TangleGeometry.BypassAllocator bypass) {
        double srcCX = source.getCenterX();
        double tgtCX = target.getCenterX();
        boolean useLeft = (srcCX + tgtCX) / 2.0 < (bypass.xLeft + bypass.xRight) / 2.0;

        double outerX = useLeft ? bypass.nextLeft() : bypass.nextRight();

        TangleGeometry.Point sourceDock = new TangleGeometry.Point(
                useLeft ? source.getMinX() : source.getMaxX(), source.getCenterY());
        TangleGeometry.Point targetDock = new TangleGeometry.Point(
                useLeft ? target.getMinX() : target.getMaxX(), target.getCenterY());

        Color color = edgeColor(edge);
        double width = edgeWidth(edge);

        Path path = new Path();
        path.setStroke(color);
        path.setStrokeWidth(width);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.MITER);
        path.setFill(null);
        path.setCursor(Cursor.HAND);
        applyEdgeDash(path, edge);

        path.getElements().add(new MoveTo(sourceDock.x(), sourceDock.y()));
        path.getElements().add(new LineTo(outerX, sourceDock.y()));
        path.getElements().add(new LineTo(outerX, targetDock.y()));
        path.getElements().add(new LineTo(targetDock.x(), targetDock.y()));

        double arrowDx = targetDock.x() - outerX;
        Polygon arrow = makeArrow(targetDock.x(), targetDock.y(), arrowDx, 0.0, color);

        path.setOnMouseEntered(e -> {
            if (!isSelected(edge)) path.setStroke(EDGE_HOVER);
            path.setCursor(Cursor.HAND);
        });
        path.setOnMouseExited(e -> {
            path.setStroke(edgeColor(edge));
            path.setCursor(Cursor.DEFAULT);
        });
        installEdgeInteractions(path, edge);

        pane.getChildren().addAll(path, arrow);
        return true;
    }

    /**
     * Draws a straight line from the centre of the source box to the centre of
     * the target box, with a filled arrowhead at the target end.
     *
     * @return {@code true} when both nodes were found and drawn.
     */
    boolean renderEdge(DependencyEdge edge, Pane overlayPane) {
        Node source = elementRegistry.get(edge.from());
        Node target = elementRegistry.get(TangleLaneLayout.targetClassOf(edge.to()));
        if (source == null || target == null) return false;
        if (!TangleGeometry.isVisible(source) || !TangleGeometry.isVisible(target)) return false;

        Bounds sb = TangleGeometry.overlayBounds(source, overlayPane);
        Bounds tb = TangleGeometry.overlayBounds(target, overlayPane);
        if (sb == null || tb == null) {
            layoutPendingSignal.run();
            return false;
        }

        TangleGeometry.Point start = TangleGeometry.edgePoint(sb, tb.getCenterX(), tb.getCenterY());
        TangleGeometry.Point end = TangleGeometry.edgePoint(tb, sb.getCenterX(), sb.getCenterY());

        Color color = edgeColor(edge);
        double width = edgeWidth(edge);

        Line line = new Line(start.x(), start.y(), end.x(), end.y());
        line.setStroke(color);
        line.setStrokeWidth(width);
        applyEdgeDash(line, edge);

        Polygon arrow = makeArrow(end.x(), end.y(), end.x() - start.x(), end.y() - start.y(), color);

        line.setOnMouseEntered(e -> {
            if (!isSelected(edge)) line.setStroke(EDGE_HOVER);
            line.setCursor(Cursor.HAND);
        });
        line.setOnMouseExited(e -> {
            line.setStroke(edgeColor(edge));
            line.setCursor(Cursor.DEFAULT);
        });
        installEdgeInteractions(line, edge);

        pane.getChildren().addAll(line, arrow);
        return true;
    }

    private void installEdgeInteractions(javafx.scene.shape.Shape shape, DependencyEdge edge) {
        shape.setOnMouseClicked(e -> {
            handleEdgeClick(edge);
            e.consume();
        });
        if (cycleModel.isAppliedCutEdge(edge)) {
            shape.setOnContextMenuRequested(e -> {
                ContextMenu menu = new ContextMenu();
                MenuItem restoreItem = new MenuItem("Restore");
                restoreItem.setOnAction(action -> onEdgeRestore.accept(edge.from(), edge.to()));
                menu.getItems().setAll(restoreItem);
                menu.show(shape, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        } else if (cycleModel.isCycleBreakEdge(edge) && cycleModel.isActiveTangleEdge(edge)) {
            shape.setOnContextMenuRequested(e -> {
                ContextMenu menu = new ContextMenu();
                MenuItem cutItem = new MenuItem("Cut");
                cutItem.setOnAction(action -> onEdgeCut.accept(edge.from(), edge.to()));
                menu.getItems().setAll(cutItem);
                menu.show(shape, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        } else {
            shape.setOnContextMenuRequested(null);
        }
    }

    private void handleEdgeClick(DependencyEdge edge) {
        if (isSelected(edge)) {
            statusCallback.accept("Tangle edge deselected");
            selectEdge.accept(null, null);
            onEdgeClicked.accept(null, null);
            return;
        }
        String label = simple(edge.from()) + " \u2192 " + simple(TangleLaneLayout.targetClassOf(edge.to()));
        if (cycleModel.isAppliedCutEdge(edge)) {
            statusCallback.accept("Refactoring Preview: " + label);
        } else {
            statusCallback.accept(cycleModel.isCycleBreakEdge(edge) && cycleModel.isActiveTangleEdge(edge)
                    ? "Recommended cut: " + label : label);
        }
        selectEdge.accept(edge.from(), edge.to());
        onEdgeClicked.accept(edge.from(), edge.to());
    }

    private Color edgeColor(DependencyEdge edge) {
        if (isSelected(edge)) {
            return SELECTED_COLOR;
        }
        if (cycleModel.isAppliedCutEdge(edge)) {
            return APPLIED_CUT_EDGE_COLOR;
        }
        if (!cycleModel.isActiveTangleEdge(edge)) {
            return NON_TANGLE_EDGE_COLOR;
        }
        return cycleModel.isCycleBreakEdge(edge) ? CUT_EDGE_COLOR : EDGE_COLOR;
    }

    private double edgeWidth(DependencyEdge edge) {
        if (isSelected(edge)) {
            return SELECTED_WIDTH;
        }
        if (cycleModel.isAppliedCutEdge(edge)) {
            return CUT_EDGE_WIDTH;
        }
        if (!cycleModel.isActiveTangleEdge(edge)) {
            return NON_TANGLE_EDGE_WIDTH;
        }
        return cycleModel.isCycleBreakEdge(edge) && cycleModel.isActiveTangleEdge(edge) ? CUT_EDGE_WIDTH : EDGE_WIDTH;
    }

    private void applyEdgeDash(javafx.scene.shape.Shape shape, DependencyEdge edge) {
        if (cycleModel.isAppliedCutEdge(edge) || (cycleModel.isCycleBreakEdge(edge) && cycleModel.isActiveTangleEdge(edge))) {
            shape.getStrokeDashArray().setAll(9.0, 5.0);
        } else {
            shape.getStrokeDashArray().clear();
        }
    }

    private boolean isSelected(DependencyEdge edge) {
        return selectedFrom != null && selectedTo != null
                && selectedFrom.equals(edge.from()) && selectedTo.equals(edge.to());
    }

    private static Polygon makeArrow(double tipX, double tipY, double dx, double dy, Color fill) {
        double angle = Math.atan2(dy, dx);
        double lx = tipX - TangleGeometry.ARROW_SIZE * Math.cos(angle - Math.PI / 7);
        double ly = tipY - TangleGeometry.ARROW_SIZE * Math.sin(angle - Math.PI / 7);
        double rx = tipX - TangleGeometry.ARROW_SIZE * Math.cos(angle + Math.PI / 7);
        double ry = tipY - TangleGeometry.ARROW_SIZE * Math.sin(angle + Math.PI / 7);
        Polygon p = new Polygon(tipX, tipY, lx, ly, rx, ry);
        p.setFill(fill);
        p.setStroke(fill);
        p.setMouseTransparent(true);
        return p;
    }

    private static String simple(String fqn) {
        if (fqn == null) return "";
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }
}
