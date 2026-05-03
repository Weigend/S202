package de.weigend.s202.ui.rendering.circuit;

import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders a routed channel-graph edge as a JavaFX {@link Path} plus
 * {@link Polygon} arrowhead. Horizontal segments hop over vertical ones with
 * half-circle bridges at marked crossings, matching schematic convention.
 *
 * <p>Pure rendering — no event wiring. The caller adds hover / click handlers
 * after the nodes are returned, so the same painter serves the
 * dependency-arrow renderer (gray) and the tangle-edge renderer (red, with
 * selection highlight) without knowing which is which.
 */
public final class ChannelPainter {

    /** Bridge arc radius — fraction of TRACK_PITCH so it fits between adjacent tracks. */
    private static final double BRIDGE_RADIUS = ChannelGraphBuilder.TRACK_PITCH / 2.5;

    public static final class Style {
        public final Color stroke;
        public final double strokeWidth;
        public final double arrowSize;

        public Style(Color stroke, double strokeWidth, double arrowSize) {
            this.stroke = stroke;
            this.strokeWidth = strokeWidth;
            this.arrowSize = arrowSize;
        }
    }

    /** Result of painting one edge. {@code line} is clickable, {@code arrow} is decorative. */
    public static final class Painted {
        public final Path line;
        public final Polygon arrow;

        public Painted(Path line, Polygon arrow) {
            this.line = line;
            this.arrow = arrow;
        }

        public List<Node> nodes() {
            List<Node> out = new ArrayList<>(2);
            out.add(line);
            out.add(arrow);
            return out;
        }
    }

    private final ChannelGraph graph;

    public ChannelPainter(ChannelGraph graph) {
        this.graph = graph;
    }

    /**
     * Build the JavaFX shapes for one routed path. {@code path} is the list
     * of {@code (h, v)} intersection coordinates returned by
     * {@link ChannelRouter#route}.
     */
    public Painted paint(ChannelGraphBuilder.Port source,
                         ChannelGraphBuilder.Port target,
                         List<int[]> path,
                         Style style) {
        if (path == null || path.isEmpty()) return null;

        List<int[]> corners = collapseToCorners(path);

        Path line = new Path();
        line.setStroke(style.stroke);
        line.setStrokeWidth(style.strokeWidth);
        line.setStrokeLineCap(StrokeLineCap.ROUND);
        line.setStrokeLineJoin(StrokeLineJoin.MITER);
        line.setFill(null);

        // Source dog-leg: stub → corner → anchor.
        line.getElements().add(new MoveTo(source.stubX, source.stubY));
        line.getElements().add(new LineTo(source.cornerX, source.cornerY));
        line.getElements().add(new LineTo(source.anchorX, source.anchorY));

        // Walk corners. The first corner == source.anchor (already drawn);
        // skip it. Vertical legs go straight; horizontal legs hop over
        // marked vertical crossings.
        for (int i = 1; i < corners.size(); i++) {
            int[] prev = corners.get(i - 1);
            int[] cur = corners.get(i);
            boolean horizontal = (prev[0] == cur[0]);
            if (horizontal) {
                emitHorizontalWithBridges(line, prev[0], prev[1], cur[1]);
            } else {
                line.getElements().add(new LineTo(graph.vX(cur[1]), graph.hY(cur[0])));
            }
        }

        // Target dog-leg: anchor → corner → stub.
        line.getElements().add(new LineTo(target.cornerX, target.cornerY));
        line.getElements().add(new LineTo(target.stubX, target.stubY));

        // Arrowhead direction = from the corner into the stub tip.
        double dx = target.stubX - target.cornerX;
        double dy = target.stubY - target.cornerY;
        if (dx == 0 && dy == 0) {
            dx = target.stubX - target.anchorX;
            dy = target.stubY - target.anchorY;
        }
        Polygon arrow = arrowhead(target.stubX, target.stubY, dx, dy, style);

        return new Painted(line, arrow);
    }

    /** Collapse cell list to corner list (keep only direction-change points + endpoints). */
    private static List<int[]> collapseToCorners(List<int[]> cells) {
        if (cells.size() < 2) return new ArrayList<>(cells);
        List<int[]> out = new ArrayList<>();
        out.add(cells.get(0));
        int prevDh = cells.get(1)[0] - cells.get(0)[0];
        int prevDv = cells.get(1)[1] - cells.get(0)[1];
        for (int i = 1; i < cells.size() - 1; i++) {
            int dh = cells.get(i + 1)[0] - cells.get(i)[0];
            int dv = cells.get(i + 1)[1] - cells.get(i)[1];
            if (dh != prevDh || dv != prevDv) {
                out.add(cells.get(i));
                prevDh = dh;
                prevDv = dv;
            }
        }
        out.add(cells.get(cells.size() - 1));
        return out;
    }

    /**
     * Emit a horizontal segment along h-track {@code h} from v-index
     * {@code vFrom} to {@code vTo}, hopping over each intermediate cell that
     * carries a vertical crossing.
     */
    private void emitHorizontalWithBridges(Path path, int h, int vFrom, int vTo) {
        double y = graph.hY(h);
        int step = (vTo > vFrom) ? 1 : -1;

        int v = vFrom;
        while (v != vTo) {
            int next = v + step;
            // bridge if the cell we cross into carries a vertical edge AND it's not the end node
            if (next != vTo && graph.vPass(h, next) > 0 && graph.hPass(h, next) > 0) {
                double xBefore = graph.vX(next) - step * BRIDGE_RADIUS;
                path.getElements().add(new LineTo(xBefore, y));
                double xAfter = graph.vX(next) + step * BRIDGE_RADIUS;
                ArcTo arc = new ArcTo();
                arc.setX(xAfter);
                arc.setY(y);
                arc.setRadiusX(BRIDGE_RADIUS);
                arc.setRadiusY(BRIDGE_RADIUS);
                arc.setLargeArcFlag(false);
                // sweep=true for left-to-right keeps the arc above the track.
                arc.setSweepFlag(step > 0);
                path.getElements().add(arc);
            }
            v = next;
        }
        path.getElements().add(new LineTo(graph.vX(vTo), y));
    }

    private static Polygon arrowhead(double tipX, double tipY, double dx, double dy, Style style) {
        double angle = Math.atan2(dy, dx);
        double leftX = tipX - style.arrowSize * Math.cos(angle - Math.PI / 7);
        double leftY = tipY - style.arrowSize * Math.sin(angle - Math.PI / 7);
        double rightX = tipX - style.arrowSize * Math.cos(angle + Math.PI / 7);
        double rightY = tipY - style.arrowSize * Math.sin(angle + Math.PI / 7);
        Polygon p = new Polygon(tipX, tipY, leftX, leftY, rightX, rightY);
        p.setFill(style.stroke);
        p.setStroke(style.stroke);
        p.setMouseTransparent(true);
        return p;
    }

    @SuppressWarnings("unused")
    private static <T> List<T> reversed(List<T> in) {
        List<T> copy = new ArrayList<>(in);
        Collections.reverse(copy);
        return copy;
    }
}
