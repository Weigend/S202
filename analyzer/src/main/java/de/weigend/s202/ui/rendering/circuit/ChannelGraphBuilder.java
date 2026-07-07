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
package de.weigend.s202.ui.rendering.circuit;

import de.weigend.s202.ui.graph.LevelClassBox;
import de.weigend.s202.ui.graph.LevelPackageBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ChannelGraph} from the current visible architecture layout.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Collect bounding boxes of every visible class box plus collapsed
 *       packages — these are the obstacles whose edges define track-strip
 *       boundaries.</li>
 *   <li>Sweep the Y axis: between consecutive box-edge Y events, when the
 *       sweep "active" count is zero, the strip is free of obstacles. Place
 *       up to {@code K = floor(width / TRACK_PITCH)} parallel horizontal
 *       tracks evenly distributed across the free strip. Same logic on X for
 *       vertical tracks.</li>
 *   <li>For each class, snap its four port sides to the nearest free track
 *       on that side; record stub / corner / anchor so the painter can
 *       reproduce the dog-leg from box edge into the lattice.</li>
 * </ol>
 *
 * <p>Tracks placed by step 2 are guaranteed to be obstacle-free along their
 * full length, so the resulting lattice is fully passable. A* therefore only
 * has to navigate around the "boxed" regions of the plane, never check cell
 * occupation.
 */
public final class ChannelGraphBuilder {

    /** Pitch between adjacent parallel tracks within the same strip. Smaller →
     *  more tracks per corridor → more bandwidth for parallel routing. */
    public static final double TRACK_PITCH = 2.0;
    /** Strips narrower than this carry no track at all. */
    public static final double MIN_STRIP_WIDTH = 2.0;
    /** Distance between an obstacle edge and the closest track inside the strip.
     *  Keeps lines visibly off the box border. */
    public static final double STRIP_BUFFER = 1.5;
    /** Outer margin (added above the topmost / below the bottommost / left of the leftmost / etc.). */
    public static final double OUTER_MARGIN = 14.0;
    /** Padding around obstacle bounds so visual borders / slight bound inaccuracies stay clear of tracks.
     *  Keep small — too much eats up the gaps between adjacent boxes and forces ports to anchor on the
     *  opposite side of intervening obstacles. */
    public static final double OBSTACLE_PADDING = 1.0;
    /** Diagnostic toggle — when true, dumps per-box bounds and track arrays to stderr. */
    private static final boolean DUMP_DIAGNOSTICS = false;

    /** Anchor info for one side of a class box. */
    public static final class Port {
        public enum Side { TOP, RIGHT, BOTTOM, LEFT }

        public final Side side;
        /** Graph node this port enters. */
        public final int hIdx;
        public final int vIdx;
        /** Tip on the box edge — where the arrowhead lands for incoming edges. */
        public final double stubX;
        public final double stubY;
        /** Perpendicular corner just outside the box, on the entry track. */
        public final double cornerX;
        public final double cornerY;
        /** First lattice intersection — start of the A*-routed segment. */
        public final double anchorX;
        public final double anchorY;

        public Port(Side side, int hIdx, int vIdx,
                    double stubX, double stubY,
                    double cornerX, double cornerY,
                    double anchorX, double anchorY) {
            this.side = side;
            this.hIdx = hIdx;
            this.vIdx = vIdx;
            this.stubX = stubX;
            this.stubY = stubY;
            this.cornerX = cornerX;
            this.cornerY = cornerY;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
        }
    }

    /** Four ports for a single class box. Any side may be {@code null} when no track fits. */
    public static final class BoxPorts {
        public final Port top;
        public final Port right;
        public final Port bottom;
        public final Port left;

        public BoxPorts(Port top, Port right, Port bottom, Port left) {
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.left = left;
        }
    }

    public static final class Result {
        public final ChannelGraph graph;
        public final Map<Node, BoxPorts> ports;

        public Result(ChannelGraph graph, Map<Node, BoxPorts> ports) {
            this.graph = graph;
            this.ports = ports;
        }
    }

    public static Result build(Pane overlayPane, Node contentRoot) {
        List<NodeBounds> obstacles = new ArrayList<>();
        collect(contentRoot, overlayPane, obstacles);

        if (obstacles.isEmpty()) {
            return new Result(
                    new ChannelGraph(new double[0], new int[0], new double[0], new int[0]),
                    new HashMap<>());
        }

        TrackArrays h = computeTracks(obstacles, true);
        TrackArrays v = computeTracks(obstacles, false);
        ChannelGraph graph = new ChannelGraph(h.pos, h.pref, v.pos, v.pref);
        double[] hY = h.pos;
        double[] vX = v.pos;

        // Pre-extract inflated bounds for obstacle-aware anchor selection so
        // computeBoxPorts can refuse anchors whose stub would cross another box.
        List<Bounds> allInflated = new ArrayList<>(obstacles.size());
        for (NodeBounds nb : obstacles) allInflated.add(nb.bounds);

        Map<Node, BoxPorts> portMap = new HashMap<>();
        for (NodeBounds nb : obstacles) {
            if (!nb.isClass) continue;
            // Strip the obstacle padding back off so the arrow-tip stub lands
            // on the actual box edge, not on the inflated obstacle.
            Bounds tight = inflate(nb.bounds, -OBSTACLE_PADDING);
            BoxPorts bp = computeBoxPorts(tight, nb.bounds, allInflated, graph);
            if (bp != null) portMap.put(nb.node, bp);
        }

        if (DUMP_DIAGNOSTICS) {
            dumpDiagnostics(obstacles, hY, vX);
        }

        return new Result(graph, portMap);
    }

    private static void dumpDiagnostics(List<NodeBounds> obstacles, double[] hY, double[] vX) {
        System.err.println("=== ChannelGraphBuilder diagnostics ===");
        System.err.println("Obstacles (" + obstacles.size() + "):");
        for (NodeBounds nb : obstacles) {
            String label = nb.isClass ? "CLASS" : "PKG  ";
            String name = (nb.node instanceof LevelClassBox c) ? c.toString()
                    : (nb.node instanceof LevelPackageBox p) ? p.toString()
                    : nb.node.getClass().getSimpleName();
            Bounds b = nb.bounds;
            System.err.printf("  %s y=[%.1f, %.1f]  x=[%.1f, %.1f]  %s%n",
                    label, b.getMinY(), b.getMaxY(), b.getMinX(), b.getMaxX(), name);
        }
        System.err.println("H-tracks (" + hY.length + "):");
        for (double y : hY) System.err.printf("  hY=%.1f%n", y);
        System.err.println("V-tracks (" + vX.length + "):");
        for (double x : vX) System.err.printf("  vX=%.1f%n", x);

        // Cross-check: any track inside any obstacle?
        for (NodeBounds nb : obstacles) {
            Bounds b = nb.bounds;
            for (double y : hY) {
                if (y > b.getMinY() && y < b.getMaxY()) {
                    System.err.printf("!! H-track y=%.1f falls inside obstacle y=[%.1f,%.1f]%n",
                            y, b.getMinY(), b.getMaxY());
                }
            }
            for (double x : vX) {
                if (x > b.getMinX() && x < b.getMaxX()) {
                    System.err.printf("!! V-track x=%.1f falls inside obstacle x=[%.1f,%.1f]%n",
                            x, b.getMinX(), b.getMaxX());
                }
            }
        }
        System.err.println("=== end diagnostics ===");
    }

    /**
     * Direction-aware port picker — same convention as
     * {@link GridBuilder#pickDirectional}: source exits BOTTOM/LEFT/RIGHT,
     * target enters TOP/LEFT/RIGHT.
     */
    public static Port[] pickDirectional(BoxPorts source, BoxPorts target) {
        double sY = portCenterY(source);
        double tY = portCenterY(target);
        double sX = portCenterX(source);
        double tX = portCenterX(target);
        double dy = tY - sY;
        double dx = tX - sX;

        if (Math.abs(dy) >= Math.abs(dx)) {
            if (dy >= 0 && source.bottom != null && target.top != null) {
                return new Port[]{source.bottom, target.top};
            }
            Port src = (dx >= 0) ? source.right : source.left;
            if (src != null && target.top != null) {
                return new Port[]{src, target.top};
            }
        }
        if (dx >= 0 && source.right != null && target.left != null) {
            return new Port[]{source.right, target.left};
        }
        if (source.left != null && target.right != null) {
            return new Port[]{source.left, target.right};
        }
        return null;
    }

    private static double portCenterX(BoxPorts p) {
        if (p.top != null) return p.top.stubX;
        if (p.bottom != null) return p.bottom.stubX;
        if (p.left != null) return p.left.stubX;
        return p.right != null ? p.right.stubX : 0.0;
    }

    private static double portCenterY(BoxPorts p) {
        if (p.left != null) return p.left.stubY;
        if (p.right != null) return p.right.stubY;
        if (p.top != null) return p.top.stubY;
        return p.bottom != null ? p.bottom.stubY : 0.0;
    }

    private record NodeBounds(Node node, Bounds bounds, boolean isClass) {}

    private static void collect(Node root, Pane overlayPane, List<NodeBounds> out) {
        if (root == null || !root.isVisible()) return;
        if (root instanceof LevelClassBox) {
            Bounds b = GridBuilder.overlayLocalBounds(root, overlayPane);
            if (b != null) out.add(new NodeBounds(root, inflate(b, OBSTACLE_PADDING), true));
            return;
        }
        if (root instanceof LevelPackageBox pkg) {
            if (!hasVisibleClass(pkg)) {
                Bounds b = GridBuilder.overlayLocalBounds(root, overlayPane);
                if (b != null) out.add(new NodeBounds(root, inflate(b, OBSTACLE_PADDING), false));
                return;
            }
        }
        if (root instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, overlayPane, out);
            }
        }
    }

    private static Bounds inflate(Bounds b, double pad) {
        return new javafx.geometry.BoundingBox(
                b.getMinX() - pad, b.getMinY() - pad,
                b.getWidth() + 2 * pad, b.getHeight() + 2 * pad);
    }

    private static boolean hasVisibleClass(Node node) {
        if (node == null || !node.isVisible()) return false;
        if (node instanceof LevelClassBox) return true;
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (hasVisibleClass(child)) return true;
            }
        }
        return false;
    }

    /** Position + preference (0 = strip centre, growing outward) for one track. */
    private record TrackEntry(double pos, int pref) {}

    /** Parallel arrays sorted by position. */
    private static final class TrackArrays {
        final double[] pos;
        final int[] pref;
        TrackArrays(double[] pos, int[] pref) { this.pos = pos; this.pref = pref; }
    }

    /** Sweep one axis. {@code horizontal=true} → emit Y coords for H-tracks. */
    private static TrackArrays computeTracks(List<NodeBounds> boxes, boolean horizontal) {
        if (boxes.isEmpty()) return new TrackArrays(new double[0], new int[0]);

        double[][] events = new double[boxes.size() * 2][2];
        double overallMin = Double.POSITIVE_INFINITY;
        double overallMax = Double.NEGATIVE_INFINITY;
        int w = 0;
        for (NodeBounds nb : boxes) {
            Bounds b = nb.bounds;
            double a = horizontal ? b.getMinY() : b.getMinX();
            double c = horizontal ? b.getMaxY() : b.getMaxX();
            events[w++] = new double[]{a, +1};
            events[w++] = new double[]{c, -1};
            if (a < overallMin) overallMin = a;
            if (c > overallMax) overallMax = c;
        }
        java.util.Arrays.sort(events, (x, y) -> Double.compare(x[0], y[0]));

        List<TrackEntry> tracks = new ArrayList<>();
        // Outer margin above / left of the first event
        placeTracks(tracks, overallMin - OUTER_MARGIN, overallMin);

        int active = 0;
        int i = 0;
        while (i < events.length) {
            double y0 = events[i][0];
            while (i < events.length && events[i][0] == y0) {
                active += (int) events[i][1];
                i++;
            }
            if (i >= events.length) break;
            double y1 = events[i][0];
            if (active == 0 && y1 > y0) {
                placeTracks(tracks, y0, y1);
            }
        }

        // Outer margin below / right of the last event
        placeTracks(tracks, overallMax, overallMax + OUTER_MARGIN);

        tracks.sort(java.util.Comparator.comparingDouble(TrackEntry::pos));
        double[] pos = new double[tracks.size()];
        int[] pref = new int[tracks.size()];
        for (int k = 0; k < tracks.size(); k++) {
            pos[k] = tracks.get(k).pos();
            pref[k] = tracks.get(k).pref();
        }
        return new TrackArrays(pos, pref);
    }

    /**
     * Lay tracks across a free strip {@code [a, b]} and tag each with its
     * distance from the strip centre. Tracks at index 0 sit at the strip's
     * centre (pref = 0) — the router's per-step preference cost ensures the
     * first edge through this corridor takes the central track, and later
     * edges fan out symmetrically (left-right-left-right) along {@code pref =
     * 1, 2, …}.
     */
    private static void placeTracks(List<TrackEntry> out, double a, double b) {
        double width = b - a;
        if (width < MIN_STRIP_WIDTH) return;
        if (width < 2 * STRIP_BUFFER + 1) {
            out.add(new TrackEntry(a + width / 2, 0));
            return;
        }
        double effStart = a + STRIP_BUFFER;
        double effWidth = width - 2 * STRIP_BUFFER;
        int k = Math.max(1, (int) Math.floor(effWidth / TRACK_PITCH) + 1);
        if (k == 1) {
            out.add(new TrackEntry(effStart + effWidth / 2, 0));
            return;
        }
        double centerIdx = (k - 1) / 2.0;
        for (int i = 0; i < k; i++) {
            double pos = effStart + i * effWidth / (k - 1);
            int pref = (int) Math.round(Math.abs(i - centerIdx));
            out.add(new TrackEntry(pos, pref));
        }
    }

    private static BoxPorts computeBoxPorts(Bounds tight, Bounds selfInflated,
                                            List<Bounds> obstacles, ChannelGraph graph) {
        if (graph.nH() == 0 || graph.nV() == 0) return null;
        double cx = (tight.getMinX() + tight.getMaxX()) / 2.0;
        double cy = (tight.getMinY() + tight.getMaxY()) / 2.0;

        // Each anchor finder rejects tracks whose stub-to-corner segment
        // would cross another obstacle in the same row/column.
        int hTopIdx    = anchorAbove(cx, tight.getMinY(), graph, obstacles, selfInflated);
        int hBotIdx    = anchorBelow(cx, tight.getMaxY(), graph, obstacles, selfInflated);
        int vLeftIdx   = anchorLeft (cy, tight.getMinX(), graph, obstacles, selfInflated);
        int vRightIdx  = anchorRight(cy, tight.getMaxX(), graph, obstacles, selfInflated);
        int vCenterIdx = graph.vNearest(cx);
        int hCenterIdx = graph.hNearest(cy);

        Port top = null;
        if (hTopIdx >= 0 && vCenterIdx >= 0) {
            double hY = graph.hY(hTopIdx);
            double anchorX = graph.vX(vCenterIdx);
            top = new Port(Port.Side.TOP, hTopIdx, vCenterIdx,
                    cx, tight.getMinY(), cx, hY, anchorX, hY);
        }
        Port bottom = null;
        if (hBotIdx >= 0 && vCenterIdx >= 0) {
            double hY = graph.hY(hBotIdx);
            double anchorX = graph.vX(vCenterIdx);
            bottom = new Port(Port.Side.BOTTOM, hBotIdx, vCenterIdx,
                    cx, tight.getMaxY(), cx, hY, anchorX, hY);
        }
        Port left = null;
        if (vLeftIdx >= 0 && hCenterIdx >= 0) {
            double vX = graph.vX(vLeftIdx);
            double anchorY = graph.hY(hCenterIdx);
            left = new Port(Port.Side.LEFT, hCenterIdx, vLeftIdx,
                    tight.getMinX(), cy, vX, cy, vX, anchorY);
        }
        Port right = null;
        if (vRightIdx >= 0 && hCenterIdx >= 0) {
            double vX = graph.vX(vRightIdx);
            double anchorY = graph.hY(hCenterIdx);
            right = new Port(Port.Side.RIGHT, hCenterIdx, vRightIdx,
                    tight.getMaxX(), cy, vX, cy, vX, anchorY);
        }
        if (top == null && bottom == null && left == null && right == null) {
            return null;
        }
        return new BoxPorts(top, right, bottom, left);
    }

    /**
     * Largest h with {@code hY[h] < srcMinY} that we can reach by going up
     * from the source's top edge at {@code cx} without crossing any other
     * obstacle whose X range contains {@code cx}.
     */
    private static int anchorAbove(double cx, double srcMinY, ChannelGraph graph,
                                   List<Bounds> obstacles, Bounds self) {
        double maxReachY = Double.NEGATIVE_INFINITY;
        for (Bounds o : obstacles) {
            if (o == self) continue;
            if (o.getMaxY() > srcMinY) continue;
            if (cx <= o.getMinX() || cx >= o.getMaxX()) continue;
            if (o.getMaxY() > maxReachY) maxReachY = o.getMaxY();
        }
        int h = graph.hBelow(srcMinY);
        while (h >= 0 && graph.hY(h) <= maxReachY) h--;
        return h;
    }

    private static int anchorBelow(double cx, double srcMaxY, ChannelGraph graph,
                                   List<Bounds> obstacles, Bounds self) {
        double minReachY = Double.POSITIVE_INFINITY;
        for (Bounds o : obstacles) {
            if (o == self) continue;
            if (o.getMinY() < srcMaxY) continue;
            if (cx <= o.getMinX() || cx >= o.getMaxX()) continue;
            if (o.getMinY() < minReachY) minReachY = o.getMinY();
        }
        int h = graph.hAbove(srcMaxY);
        while (h < graph.nH() && graph.hY(h) >= minReachY) h++;
        return h < graph.nH() ? h : -1;
    }

    private static int anchorLeft(double cy, double srcMinX, ChannelGraph graph,
                                  List<Bounds> obstacles, Bounds self) {
        double maxReachX = Double.NEGATIVE_INFINITY;
        for (Bounds o : obstacles) {
            if (o == self) continue;
            if (o.getMaxX() > srcMinX) continue;
            if (cy <= o.getMinY() || cy >= o.getMaxY()) continue;
            if (o.getMaxX() > maxReachX) maxReachX = o.getMaxX();
        }
        int v = graph.vLeftOf(srcMinX);
        while (v >= 0 && graph.vX(v) <= maxReachX) v--;
        return v;
    }

    private static int anchorRight(double cy, double srcMaxX, ChannelGraph graph,
                                   List<Bounds> obstacles, Bounds self) {
        double minReachX = Double.POSITIVE_INFINITY;
        for (Bounds o : obstacles) {
            if (o == self) continue;
            if (o.getMinX() < srcMaxX) continue;
            if (cy <= o.getMinY() || cy >= o.getMaxY()) continue;
            if (o.getMinX() < minReachX) minReachX = o.getMinX();
        }
        int v = graph.vRightOf(srcMaxX);
        while (v < graph.nV() && graph.vX(v) >= minReachX) v++;
        return v < graph.nV() ? v : -1;
    }

    private ChannelGraphBuilder() {}
}
