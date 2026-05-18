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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over the {@link ChannelGraph} lattice. Same cost shape as
 * {@link AStarEdgeRouter} but operates on track intersections, so memory and
 * runtime scale with the obstacle topology rather than the pixel raster.
 *
 * <p>State is the triple {@code (hIdx, vIdx, dir)} with {@code dir} ∈
 * {UP, RIGHT, DOWN, LEFT, NONE} so the {@code TURN} penalty can detect
 * direction changes. Keys are packed into a {@code long} and live in
 * {@code HashMap}s — sparse, since A* explores only the corridor toward the
 * goal. {@code gScore}/{@code parent} are dropped after each route.
 *
 * <p>Persistent state on the graph: per-cell pass counters for OVERLAP /
 * CROSS / PARALLEL costing, marked once a route succeeds so subsequent edges
 * naturally bundle along occupied corridors and avoid crossings.
 */
public final class ChannelRouter {

    private static final int BASE = 10;
    /** Bend penalty. Lower than the pixel-grid router because each lattice
     *  step covers a full corridor — TURN comparable to the grid router would
     *  reward absurd detours over a single extra bend. */
    private static final int TURN = 80;
    /** Strong penalty so two edges never share a track segment when an
     *  adjacent parallel track is available. A second edge would rather pay
     *  two extra turns + one extra step than overlap. */
    private static final int OVERLAP = 250;
    /** Crossings cost real visual clarity, but unavoidable in dense graphs;
     *  cheaper than OVERLAP so A* picks "cross orthogonal" over "fuse". */
    private static final int CROSS = 60;
    /** Bonus for routing adjacent to an existing trace — pulls bundles into
     *  visibly aligned parallel runs. */
    private static final int PARALLEL = -15;
    /** Per-step weight on a track's strip-centre distance. With pref ∈ {0, 1, 2, …},
     *  central tracks pay 0 extra, the next ring out pays {@code 1 * weight}, etc.
     *  Encourages "fill from the middle, grow outward symmetrically". */
    private static final int CENTER_WEIGHT = 6;

    private static final int DIR_UP = 0;
    private static final int DIR_RIGHT = 1;
    private static final int DIR_DOWN = 2;
    private static final int DIR_LEFT = 3;
    private static final int DIR_NONE = 4;

    /** delta in (h, v) per direction. h-axis grows downward (Y), v-axis right (X). */
    private static final int[][] DELTA = {
            {-1,  0}, // UP
            { 0,  1}, // RIGHT
            { 1,  0}, // DOWN
            { 0, -1}  // LEFT
    };

    private final ChannelGraph graph;

    public ChannelRouter(ChannelGraph graph) {
        this.graph = graph;
    }

    /**
     * Routes from {@code source.anchor} to {@code target.anchor}. Returns
     * the ordered list of {@code (h, v)} intersection coordinates the path
     * walks through, or {@code null} when no route exists.
     *
     * <p>On success, marks pass counters along the path so the next route
     * can bundle / avoid crossings.
     */
    public List<int[]> route(ChannelGraphBuilder.Port source, ChannelGraphBuilder.Port target) {
        if (graph.nH() == 0 || graph.nV() == 0) return null;
        int sH = source.hIdx, sV = source.vIdx;
        int tH = target.hIdx, tV = target.vIdx;
        if (!graph.inBounds(sH, sV) || !graph.inBounds(tH, tV)) return null;

        int initialDir = sideToOutDir(source.side);
        long startKey = key(sH, sV, initialDir);

        Map<Long, Integer> gScore = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        gScore.put(startKey, 0);

        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        open.add(new long[]{heuristic(sH, sV, tH, tV), 0L, startKey});

        long goalKey = -1L;

        while (!open.isEmpty()) {
            long[] cur = open.poll();
            long curKey = cur[2];
            int curG = (int) cur[1];
            Integer best = gScore.get(curKey);
            if (best == null || curG > best) continue;

            int curH = unpackH(curKey);
            int curV = unpackV(curKey);
            int curDir = unpackDir(curKey);

            if (curH == tH && curV == tV) {
                goalKey = curKey;
                break;
            }

            for (int d = 0; d < 4; d++) {
                int nh = curH + DELTA[d][0];
                int nv = curV + DELTA[d][1];
                if (!graph.inBounds(nh, nv)) continue;

                int step = BASE;
                if (curDir != DIR_NONE && curDir != d) step += TURN;

                boolean horizontal = (d == DIR_RIGHT || d == DIR_LEFT);
                int hp = graph.hPass(nh, nv);
                int vp = graph.vPass(nh, nv);
                if (horizontal) {
                    if (hp > 0) step += OVERLAP;
                    if (vp > 0) step += CROSS;
                    if (graph.hPass(nh - 1, nv) > 0) step += PARALLEL;
                    if (graph.hPass(nh + 1, nv) > 0) step += PARALLEL;
                    step += CENTER_WEIGHT * graph.hPref(nh);
                } else {
                    if (vp > 0) step += OVERLAP;
                    if (hp > 0) step += CROSS;
                    if (graph.vPass(nh, nv - 1) > 0) step += PARALLEL;
                    if (graph.vPass(nh, nv + 1) > 0) step += PARALLEL;
                    step += CENTER_WEIGHT * graph.vPref(nv);
                }
                if (step < 1) step = 1;

                int tentative = curG + step;
                long nKey = key(nh, nv, d);
                Integer prev = gScore.get(nKey);
                if (prev == null || tentative < prev) {
                    gScore.put(nKey, tentative);
                    parent.put(nKey, curKey);
                    long f = tentative + heuristic(nh, nv, tH, tV);
                    open.add(new long[]{f, tentative, nKey});
                }
            }
        }

        if (goalKey < 0) return null;

        List<int[]> path = new ArrayList<>();
        long cursor = goalKey;
        while (true) {
            path.add(new int[]{unpackH(cursor), unpackV(cursor)});
            Long p = parent.get(cursor);
            if (p == null) break;
            cursor = p;
        }
        java.util.Collections.reverse(path);

        // Mark pass counters so subsequent edges bundle along this corridor.
        for (int i = 1; i < path.size(); i++) {
            int[] prev = path.get(i - 1);
            int[] cell = path.get(i);
            boolean horizontal = (prev[0] == cell[0]);
            if (horizontal) {
                graph.addHPass(cell[0], cell[1]);
                graph.addHPass(prev[0], prev[1]);
            } else {
                graph.addVPass(cell[0], cell[1]);
                graph.addVPass(prev[0], prev[1]);
            }
        }
        return path;
    }

    private static int sideToOutDir(ChannelGraphBuilder.Port.Side side) {
        return switch (side) {
            case TOP -> DIR_UP;
            case RIGHT -> DIR_RIGHT;
            case BOTTOM -> DIR_DOWN;
            case LEFT -> DIR_LEFT;
        };
    }

    /** Pack (h, v, dir) into a long. Up to 24 bits per index leaves headroom for 16M tracks. */
    private static long key(int h, int v, int dir) {
        return ((long) (h & 0xFFFFFF) << 28)
             | ((long) (v & 0xFFFFFF) << 4)
             | (dir & 0xF);
    }

    private static int unpackH(long k) { return (int) ((k >> 28) & 0xFFFFFF); }
    private static int unpackV(long k) { return (int) ((k >> 4) & 0xFFFFFF); }
    private static int unpackDir(long k) { return (int) (k & 0xF); }

    private static int heuristic(int h, int v, int th, int tv) {
        return (Math.abs(h - th) + Math.abs(v - tv)) * BASE;
    }
}
