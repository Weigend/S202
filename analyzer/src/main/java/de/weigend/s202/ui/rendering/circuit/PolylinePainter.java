package de.weigend.s202.ui.rendering.circuit;

import javafx.scene.Cursor;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.StrokeLineCap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Renders {@link RoutedEdge}s as JavaFX {@link Path} nodes with rectilinear
 * segments and half-circle "bridges" at crossings.
 *
 * <p>Bridge convention: horizontal segments hop over vertical ones. A crossing
 * is identified via the grid's usage counters — a cell that is used by at
 * least one horizontal edge AND at least one vertical edge.
 */
public final class PolylinePainter {

    public static final Color OUTGOING_COLOR = Color.rgb(90, 94, 98);
    public static final Color INCOMING_COLOR = Color.rgb(0, 128, 0);

    private static final double STROKE_WIDTH = 0.8;
    private static final double ARROW_SIZE = 5.0;

    private final Pane target;
    private final RoutingGrid grid;
    private final Consumer<String> statusCallback;

    private Path selectedPath;
    private final List<Path> allPaths = new ArrayList<>();

    public PolylinePainter(Pane target, RoutingGrid grid, Consumer<String> statusCallback) {
        this.target = target;
        this.grid = grid;
        this.statusCallback = statusCallback;
    }

    public void paint(List<RoutedEdge> edges) {
        for (RoutedEdge edge : edges) {
            Path path = buildPath(edge);
            if (path != null) {
                target.getChildren().add(path);
                allPaths.add(path);
                addArrowHead(edge);
            }
        }
    }

    private Path buildPath(RoutedEdge edge) {
        if (edge.path == null || edge.path.size() < 2) return null;

        List<int[]> cells = edge.path;
        // Collapse cells into corner points: start + each turn + end
        List<int[]> corners = new ArrayList<>();
        corners.add(cells.get(0));
        int prevDx = cells.get(1)[0] - cells.get(0)[0];
        int prevDy = cells.get(1)[1] - cells.get(0)[1];
        for (int i = 1; i < cells.size() - 1; i++) {
            int dx = cells.get(i + 1)[0] - cells.get(i)[0];
            int dy = cells.get(i + 1)[1] - cells.get(i)[1];
            if (dx != prevDx || dy != prevDy) {
                corners.add(cells.get(i));
                prevDx = dx;
                prevDy = dy;
            }
        }
        corners.add(cells.get(cells.size() - 1));

        Path path = new Path();
        path.setStroke(edge.incoming ? INCOMING_COLOR : OUTGOING_COLOR);
        path.setStrokeWidth(STROKE_WIDTH);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setFill(null);
        path.setCursor(Cursor.HAND);

        double startX = grid.toWorldX(corners.get(0)[0]);
        double startY = grid.toWorldY(corners.get(0)[1]);
        path.getElements().add(new MoveTo(startX, startY));

        for (int i = 1; i < corners.size(); i++) {
            int[] prev = corners.get(i - 1);
            int[] cur = corners.get(i);
            boolean horizontal = (prev[1] == cur[1]);
            if (horizontal) {
                emitHorizontalSegmentWithBridges(path, prev, cur, edge);
            } else {
                // Vertical segments just go straight; bridges are drawn on horizontal ones
                path.getElements().add(new LineTo(grid.toWorldX(cur[0]), grid.toWorldY(cur[1])));
            }
        }

        final Color originalColor = edge.incoming ? INCOMING_COLOR : OUTGOING_COLOR;
        path.setOnMouseEntered(e -> {
            if (path != selectedPath) path.setStroke(Color.GRAY);
        });
        path.setOnMouseExited(e -> {
            if (path != selectedPath) path.setStroke(originalColor);
        });
        path.setOnMouseClicked(e -> {
            selectPath(path, edge, originalColor);
            e.consume();
        });

        return path;
    }

    private void emitHorizontalSegmentWithBridges(Path path, int[] from, int[] to, RoutedEdge edge) {
        int y = from[1];
        int cFrom = from[0];
        int cTo = to[0];
        int step = (cTo > cFrom) ? 1 : -1;

        double r = RoutingGrid.PITCH / 2.5;
        double worldY = grid.toWorldY(y);

        int c = cFrom;
        while (c != cTo) {
            int next = c + step;
            // Check the cell we're about to cross into for a crossing (vertical edge present)
            if (grid.horizontalUse(next, y) > 0 && grid.verticalUse(next, y) > 0
                    && next != cTo) {
                // Draw up to just before the crossing, then an arc over it, then resume
                double xBeforeArc = grid.toWorldX(next) - step * r;
                path.getElements().add(new LineTo(xBeforeArc, worldY));
                double xAfterArc = grid.toWorldX(next) + step * r;
                ArcTo arc = new ArcTo();
                arc.setX(xAfterArc);
                arc.setY(worldY);
                arc.setRadiusX(r);
                arc.setRadiusY(r);
                arc.setLargeArcFlag(false);
                // Sweep direction: horizontal line arcs upward (standard schematic).
                // sweep=true for left-to-right, false for right-to-left keeps the arc above
                arc.setSweepFlag(step > 0);
                path.getElements().add(arc);
            }
            c = next;
        }
        // Final segment to the end corner
        path.getElements().add(new LineTo(grid.toWorldX(cTo), worldY));
    }

    private void addArrowHead(RoutedEdge edge) {
        if (edge.path == null || edge.path.size() < 2) return;
        int[] last = edge.path.get(edge.path.size() - 1);
        int[] prev = edge.path.get(edge.path.size() - 2);
        double ex = grid.toWorldX(last[0]);
        double ey = grid.toWorldY(last[1]);
        double px = grid.toWorldX(prev[0]);
        double py = grid.toWorldY(prev[1]);
        double angle = Math.atan2(ey - py, ex - px);

        double x1 = ex - ARROW_SIZE * Math.cos(angle - Math.PI / 6);
        double y1 = ey - ARROW_SIZE * Math.sin(angle - Math.PI / 6);
        double x2 = ex - ARROW_SIZE * Math.cos(angle + Math.PI / 6);
        double y2 = ey - ARROW_SIZE * Math.sin(angle + Math.PI / 6);

        Color color = edge.incoming ? INCOMING_COLOR : OUTGOING_COLOR;
        Line a1 = new Line(ex, ey, x1, y1);
        Line a2 = new Line(ex, ey, x2, y2);
        a1.setStroke(color);
        a2.setStroke(color);
        a1.setStrokeWidth(STROKE_WIDTH);
        a2.setStrokeWidth(STROKE_WIDTH);
        a1.setMouseTransparent(true);
        a2.setMouseTransparent(true);
        target.getChildren().addAll(a1, a2);
    }

    private void selectPath(Path path, RoutedEdge edge, Color originalColor) {
        if (selectedPath != null && selectedPath != path) {
            Object ud = selectedPath.getUserData();
            Color c = (ud instanceof Color col) ? col : OUTGOING_COLOR;
            selectedPath.setStroke(c);
            selectedPath.setStrokeWidth(STROKE_WIDTH);
        }
        if (selectedPath == path) {
            path.setStroke(originalColor);
            path.setStrokeWidth(STROKE_WIDTH);
            selectedPath = null;
            statusCallback.accept("Ready");
        } else {
            path.setStroke(Color.RED);
            path.setStrokeWidth(STROKE_WIDTH * 2);
            path.setUserData(originalColor);
            selectedPath = path;
            String s = simple(edge.sourceName);
            String t = simple(edge.targetName);
            statusCallback.accept("Dependency: " + s + " \u2192 " + t);
        }
    }

    private static String simple(String fullName) {
        int i = fullName.lastIndexOf('.');
        return i >= 0 ? fullName.substring(i + 1) : fullName;
    }
}
