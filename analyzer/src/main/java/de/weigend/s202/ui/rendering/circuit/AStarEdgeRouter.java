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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Rectilinear A* router with direction-aware cost function.
 *
 * <p>Cost terms per step into a neighbour cell:
 * <ul>
 *   <li>base step: {@value #BASE}</li>
 *   <li>turn penalty: {@value #TURN} if the new step changes direction</li>
 *   <li>bundle bonus: {@value #BUNDLE} if the target cell already carries
 *       another edge in the same axis</li>
 *   <li>crossing penalty: {@value #CROSS} if the target cell already carries
 *       another edge in the orthogonal axis</li>
 * </ul>
 *
 * <p>The heuristic is Manhattan distance × {@value #BASE} (admissible because
 * the base step cost is the smallest positive contribution per move).
 */
public final class AStarEdgeRouter {

    private static final int BASE = 10;
    /** Strong penalty — bends dominate length by design. */
    private static final int TURN = 500;
    /** Penalty for stepping into a cell already occupied in the SAME axis — prevents overlap. */
    private static final int OVERLAP = 30;
    /** Bonus for stepping into a cell whose neighbour (orthogonal offset) carries
     *  an edge in the same axis — encourages adjacent parallel tracks. */
    private static final int PARALLEL = -6;
    /** Penalty for stepping into a cell already occupied in the orthogonal axis — crossing. */
    private static final int CROSS = 25;

    private static final int[][] DIRS = {
        {0, -1}, // up    (0)
        {1,  0}, // right (1)
        {0,  1}, // down  (2)
        {-1, 0}  // left  (3)
    };
    private static final int NO_DIR = 4;

    private final RoutingGrid grid;

    public AStarEdgeRouter(RoutingGrid grid) {
        this.grid = grid;
    }

    /**
     * Finds a route between two ports. Returns {@code null} if no route exists.
     * On success, marks the route's usage counters in the grid so subsequent
     * edges can bundle along the same corridor.
     */
    public List<int[]> route(GridBuilder.Port source, GridBuilder.Port target) {
        return route(source, target, null);
    }

    /**
     * Routes with a per-edge allowed-package filter. Cells whose innermost
     * package id is not present in {@code allowedPackageIds} are rejected
     * (foreign sub-packages block the route). The special id {@code -1}
     * (outside any package) is always allowed.
     */
    public List<int[]> route(GridBuilder.Port source, GridBuilder.Port target,
                              java.util.BitSet allowedPackageIds) {
        int sCol = source.col, sRow = source.row;
        int tCol = target.col, tRow = target.row;
        if (!grid.inBounds(sCol, sRow) || !grid.inBounds(tCol, tRow)) return null;

        int cols = grid.cols();
        int rows = grid.rows();
        int states = cols * rows * (NO_DIR + 1);
        int[] gScore = new int[states];
        int[] parent = new int[states];
        Arrays.fill(gScore, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);

        int initialDir = sideToOutDir(source.side);
        int startIdx = idx(sCol, sRow, initialDir, cols, rows);
        gScore[startIdx] = 0;

        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        // entry = {f, g, idx}
        open.add(new long[]{heuristic(sCol, sRow, tCol, tRow), 0, startIdx});

        int goalIdxFound = -1;

        while (!open.isEmpty()) {
            long[] cur = open.poll();
            long poppedG = cur[1];
            int curIdx = (int) cur[2];
            int curCol = (curIdx / (NO_DIR + 1)) % cols;
            int curRow = (curIdx / (NO_DIR + 1)) / cols;
            int curDir = curIdx % (NO_DIR + 1);

            // Skip stale entries
            if (poppedG > gScore[curIdx]) continue;

            if (curCol == tCol && curRow == tRow) {
                goalIdxFound = curIdx;
                break;
            }

            int curG = gScore[curIdx];
            if (curG == Integer.MAX_VALUE) continue;

            for (int d = 0; d < 4; d++) {
                int nc = curCol + DIRS[d][0];
                int nr = curRow + DIRS[d][1];
                if (!grid.inBounds(nc, nr)) continue;

                RoutingGrid.CellStatus s = grid.get(nc, nr);
                if (s == RoutingGrid.CellStatus.BLOCKED) continue;
                // Disallow passing through foreign PORT cells (only target port allowed)
                if (s == RoutingGrid.CellStatus.PORT && !(nc == tCol && nr == tRow)) continue;
                // Disallow foreign sub-packages
                if (allowedPackageIds != null) {
                    int pid = grid.packageId(nc, nr);
                    if (pid >= 0 && !allowedPackageIds.get(pid)) continue;
                }

                int step = BASE;
                if (curDir != NO_DIR && curDir != d) step += TURN;

                boolean horizontal = (DIRS[d][1] == 0);
                int hu = grid.horizontalUse(nc, nr);
                int vu = grid.verticalUse(nc, nr);
                if (horizontal) {
                    if (hu > 0) step += OVERLAP;            // another horizontal edge already here
                    if (vu > 0) step += CROSS;              // a vertical edge crosses this cell
                    // Parallel bonus: neighbouring rows carry a horizontal edge
                    if (grid.horizontalUse(nc, nr - 1) > 0) step += PARALLEL;
                    if (grid.horizontalUse(nc, nr + 1) > 0) step += PARALLEL;
                } else {
                    if (vu > 0) step += OVERLAP;
                    if (hu > 0) step += CROSS;
                    if (grid.verticalUse(nc - 1, nr) > 0) step += PARALLEL;
                    if (grid.verticalUse(nc + 1, nr) > 0) step += PARALLEL;
                }
                if (step < 1) step = 1;

                int tentative = curG + step;
                int nIdx = idx(nc, nr, d, cols, rows);
                if (tentative < gScore[nIdx]) {
                    gScore[nIdx] = tentative;
                    parent[nIdx] = curIdx;
                    long f = tentative + heuristic(nc, nr, tCol, tRow);
                    open.add(new long[]{f, tentative, nIdx});
                }
            }
        }

        if (goalIdxFound == -1) return null;

        // Reconstruct
        List<int[]> path = new ArrayList<>();
        int cursor = goalIdxFound;
        while (cursor != -1) {
            int c = (cursor / (NO_DIR + 1)) % cols;
            int r = (cursor / (NO_DIR + 1)) / cols;
            path.add(new int[]{c, r});
            cursor = parent[cursor];
        }
        Collections.reverse(path);

        // Mark usage for bundling
        for (int i = 1; i < path.size(); i++) {
            int[] prev = path.get(i - 1);
            int[] cell = path.get(i);
            boolean horizontal = (prev[1] == cell[1]);
            if (horizontal) {
                grid.addHorizontalUse(cell[0], cell[1]);
            } else {
                grid.addVerticalUse(cell[0], cell[1]);
            }
        }
        return path;
    }

    private static int sideToOutDir(GridBuilder.Port.Side side) {
        return switch (side) {
            case TOP -> 0;
            case RIGHT -> 1;
            case BOTTOM -> 2;
            case LEFT -> 3;
        };
    }

    private static int idx(int col, int row, int dir, int cols, int rows) {
        return (row * cols + col) * (NO_DIR + 1) + dir;
    }

    private static int heuristic(int c, int r, int tc, int tr) {
        return (Math.abs(c - tc) + Math.abs(r - tr)) * BASE;
    }
}
