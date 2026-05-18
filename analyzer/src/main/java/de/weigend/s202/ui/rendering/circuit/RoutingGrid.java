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

/**
 * Discrete grid used for rectilinear (Manhattan) edge routing.
 *
 * <p>The grid is a dense 2D array of cells, each with a status and bookkeeping
 * counters used by the A* cost function:
 * <ul>
 *   <li><b>FREE</b> — empty corridor, lines may pass</li>
 *   <li><b>BLOCKED</b> — occupied by a class or collapsed package; never routable</li>
 *   <li><b>PORT</b> — reserved entry/exit cell belonging to a specific class</li>
 * </ul>
 * Each cell also tracks how many previously routed edges pass through it
 * horizontally or vertically. The router uses these counters to reward bundling
 * (same direction) and penalise crossings (orthogonal direction).
 */
public final class RoutingGrid {

    public enum CellStatus { FREE, BLOCKED, PORT }

    public static final int PITCH = 8;

    private final int cols;
    private final int rows;
    private final double originX;
    private final double originY;

    private final CellStatus[][] status;
    private final int[][] horizontalUse;
    private final int[][] verticalUse;
    /** Innermost package id per cell, or -1 if outside any package. */
    private final int[][] packageId;

    public RoutingGrid(double originX, double originY, int cols, int rows) {
        this.originX = originX;
        this.originY = originY;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.status = new CellStatus[this.cols][this.rows];
        this.horizontalUse = new int[this.cols][this.rows];
        this.verticalUse = new int[this.cols][this.rows];
        this.packageId = new int[this.cols][this.rows];
        for (int x = 0; x < this.cols; x++) {
            for (int y = 0; y < this.rows; y++) {
                this.status[x][y] = CellStatus.FREE;
                this.packageId[x][y] = -1;
            }
        }
    }

    public int packageId(int col, int row) {
        return inBounds(col, row) ? packageId[col][row] : -1;
    }

    public void setPackageRect(double x0, double y0, double x1, double y1, int id) {
        int c0 = toColClamped(Math.min(x0, x1));
        int c1 = toColClamped(Math.max(x0, x1));
        int r0 = toRowClamped(Math.min(y0, y1));
        int r1 = toRowClamped(Math.max(y0, y1));
        for (int c = c0; c <= c1; c++) {
            for (int r = r0; r <= r1; r++) {
                packageId[c][r] = id;
            }
        }
    }

    public int cols() { return cols; }
    public int rows() { return rows; }
    public double originX() { return originX; }
    public double originY() { return originY; }

    public double toWorldX(int col) { return originX + col * PITCH + PITCH / 2.0; }
    public double toWorldY(int row) { return originY + row * PITCH + PITCH / 2.0; }

    public int toColClamped(double worldX) {
        int c = (int) Math.floor((worldX - originX) / PITCH);
        return Math.max(0, Math.min(cols - 1, c));
    }

    public int toRowClamped(double worldY) {
        int r = (int) Math.floor((worldY - originY) / PITCH);
        return Math.max(0, Math.min(rows - 1, r));
    }

    public boolean inBounds(int col, int row) {
        return col >= 0 && col < cols && row >= 0 && row < rows;
    }

    public CellStatus get(int col, int row) {
        if (!inBounds(col, row)) return CellStatus.BLOCKED;
        return status[col][row];
    }

    public void set(int col, int row, CellStatus s) {
        if (inBounds(col, row)) {
            status[col][row] = s;
        }
    }

    /** Marks the axis-aligned rectangle (in world coordinates) as BLOCKED. */
    public void blockRect(double x0, double y0, double x1, double y1) {
        int c0 = toColClamped(Math.min(x0, x1));
        int c1 = toColClamped(Math.max(x0, x1));
        int r0 = toRowClamped(Math.min(y0, y1));
        int r1 = toRowClamped(Math.max(y0, y1));
        for (int c = c0; c <= c1; c++) {
            for (int r = r0; r <= r1; r++) {
                status[c][r] = CellStatus.BLOCKED;
            }
        }
    }

    /** Marks a single cell as a PORT (overrides BLOCKED). */
    public void setPort(int col, int row) {
        if (inBounds(col, row)) {
            status[col][row] = CellStatus.PORT;
        }
    }

    public int horizontalUse(int col, int row) {
        return inBounds(col, row) ? horizontalUse[col][row] : 0;
    }

    public int verticalUse(int col, int row) {
        return inBounds(col, row) ? verticalUse[col][row] : 0;
    }

    public void addHorizontalUse(int col, int row) {
        if (inBounds(col, row)) horizontalUse[col][row]++;
    }

    public void addVerticalUse(int col, int row) {
        if (inBounds(col, row)) verticalUse[col][row]++;
    }
}
