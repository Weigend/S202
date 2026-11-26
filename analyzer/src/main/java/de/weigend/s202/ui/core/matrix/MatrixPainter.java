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
package de.weigend.s202.ui.core.matrix;

import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import de.weigend.s202.domain.architecture.DependencyMatrix;
import de.weigend.s202.ui.core.arrows.LineColors;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.function.Predicate;

/**
 * Draws a dependency matrix: names at the left and above, cells, and the frames
 * of the open packages. Knows where everything is, for the mouse as well.
 */
final class MatrixPainter {

    static final double DEFAULT_CELL = 20.0;
    static final double MIN_CELL = 4.0;
    static final double MAX_CELL = 48.0;

    private static final double BAND = 16.0;
    private static final double ICON = 10.0;
    private static final double GAP = 4.0;
    private static final double MIN_HEADER = 60.0;
    private static final double MAX_HEADER = 260.0;
    /** Below this size a cell has no room for its number and is filled instead. */
    private static final double MIN_CELL_WITH_NUMBER = 12.0;
    /** Below this size there is no room for the names. */
    private static final double MIN_CELL_WITH_NAME = 8.0;

    private static final Color GROUND = Color.web("#f5f5f0");
    private static final Color HEADER = Color.web("#ecece6");
    private static final Color GRID = Color.web("#d9d9d2");
    private static final Color DIAGONAL = Color.web("#b9bcc0");
    private static final Color FRAME = Color.web("#6b7078");
    private static final Color TEXT = Color.web("#1f2328");
    private static final Color CROSS = Color.web("#1565c0", 0.12);
    private static final Color SELECTED_ELEMENT = Color.web("#ffd9a8", 0.75);
    private static final Color BAND_COLOR = Color.web("#fff3c4");
    private static final Color PACKAGE = Color.web("#e6c46a");
    private static final Color EXTERNAL = Color.web("#a0a4aa");
    private static final Color CLASS = Color.web("#7fb3ff");
    private static final Color INTERFACE = Color.web("#4caf50");

    /** What is special about the moment; -1 for nothing. */
    record State(int hoverRow, int hoverColumn, int selectedFirst, int selectedLast,
                 int cellRow, int cellColumn, boolean calls) {
        static final State PLAIN = new State(-1, -1, -1, -1, -1, -1, false);
    }

    /** Where a point is: in a cell, a name, the band of an open package. */
    record Place(Kind kind, int row, int column, DependencyMatrix.Group group) {
        enum Kind { NOTHING, CELL, ROW, ROW_TOGGLE, COLUMN, GROUP }

        static final Place NOTHING = new Place(Kind.NOTHING, -1, -1, null);
    }

    private final DependencyMatrix matrix;
    private final Predicate<String> isInterface;
    private final double cell;
    private final int depth;
    private final double headerWidth;
    private final double headerHeight;
    private final Font font;

    MatrixPainter(DependencyMatrix matrix, double cell, Predicate<String> isInterface) {
        this.matrix = matrix;
        this.cell = cell;
        this.isInterface = isInterface;
        this.depth = matrix.groups().stream().mapToInt(group -> group.depth() + 1).max().orElse(0);
        this.font = Font.font(Math.clamp(cell * 0.6, 7.0, 13.0));
        double longest = 0;
        Text measure = new Text();
        measure.setFont(font);
        for (DependencyMatrix.Item item : cell < MIN_CELL_WITH_NAME ? List.<DependencyMatrix.Item>of() : matrix.items()) {
            measure.setText(simple(item.fqn()));
            longest = Math.max(longest, measure.getLayoutBounds().getWidth());
        }
        double names = Math.clamp(longest + ICON + 5 * GAP + BAND, MIN_HEADER, MAX_HEADER);
        this.headerWidth = depth * BAND + names;
        this.headerHeight = Math.clamp(longest + ICON + 3 * GAP, MIN_HEADER, MAX_HEADER);
    }

    double headerWidth() {
        return headerWidth;
    }

    double headerHeight() {
        return headerHeight;
    }

    /** Everything below and beside the names. */
    double contentSize() {
        return matrix.size() * cell;
    }

    /* ----- Where is what? ----------------------------------------------------- */

    Place placeAt(double x, double y, double scrollX, double scrollY) {
        int column = x < headerWidth ? -1 : index(x - headerWidth + scrollX);
        int row = y < headerHeight ? -1 : index(y - headerHeight + scrollY);
        if (x >= headerWidth && y >= headerHeight) {
            return row < 0 || column < 0 ? Place.NOTHING : new Place(Place.Kind.CELL, row, column, null);
        }
        if (x >= headerWidth) {
            return column < 0 ? Place.NOTHING : new Place(Place.Kind.COLUMN, -1, column, null);
        }
        if (y < headerHeight || row < 0) {
            return Place.NOTHING;
        }
        int band = (int) (x / BAND);
        if (band < depth) {
            DependencyMatrix.Group innermost = null;
            for (DependencyMatrix.Group group : matrix.groups()) {
                if (group.depth() == band && group.first() <= row && row <= group.last()) {
                    innermost = group;
                }
            }
            if (innermost != null) {
                return new Place(Place.Kind.GROUP, row, -1, innermost);
            }
        }
        DependencyMatrix.Item item = matrix.items().get(row);
        boolean toggle = item.isPackage() && !item.external() && x >= headerWidth - BAND - GAP;
        return new Place(toggle ? Place.Kind.ROW_TOGGLE : Place.Kind.ROW, row, -1, null);
    }

    private int index(double position) {
        int index = (int) Math.floor(position / cell);
        return index < 0 || index >= matrix.size() ? -1 : index;
    }

    /* ----- Drawing ------------------------------------------------------------- */

    void paint(GraphicsContext gc, double width, double height, double scrollX, double scrollY, State state) {
        gc.setFill(GROUND);
        gc.fillRect(0, 0, width, height);
        if (matrix.size() == 0) {
            return;
        }
        int firstRow = Math.max(0, (int) (scrollY / cell));
        int lastRow = Math.min(matrix.size() - 1, (int) ((scrollY + height - headerHeight) / cell));
        int firstColumn = Math.max(0, (int) (scrollX / cell));
        int lastColumn = Math.min(matrix.size() - 1, (int) ((scrollX + width - headerWidth) / cell));

        gc.save();
        gc.beginPath();
        gc.rect(headerWidth, headerHeight, width - headerWidth, height - headerHeight);
        gc.clip();
        gc.translate(headerWidth - scrollX, headerHeight - scrollY);
        paintCells(gc, firstRow, lastRow, firstColumn, lastColumn, state);
        gc.restore();

        paintRowNames(gc, height, scrollY, firstRow, lastRow, state);
        paintColumnNames(gc, width, scrollX, firstColumn, lastColumn, state);
        gc.setFill(HEADER);
        gc.fillRect(0, 0, headerWidth, headerHeight);
        paintCorner(gc);
    }

    private void paintCorner(GraphicsContext gc) {
        gc.setFill(FRAME);
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.fillText("columns use rows", headerWidth - GAP, headerHeight - GAP, headerWidth - 2 * GAP);
    }

    private void paintCells(GraphicsContext gc, int firstRow, int lastRow, int firstColumn, int lastColumn,
                            State state) {
        double left = firstColumn * cell;
        double top = firstRow * cell;
        double right = (lastColumn + 1) * cell;
        double bottom = (lastRow + 1) * cell;

        gc.setFill(Color.WHITE);
        gc.fillRect(left, top, right - left, bottom - top);
        if (state.selectedFirst() >= 0) {
            double start = state.selectedFirst() * cell;
            double extent = (state.selectedLast() - state.selectedFirst() + 1) * cell;
            gc.setFill(SELECTED_ELEMENT);
            gc.fillRect(left, start, right - left, extent);
            gc.fillRect(start, top, extent, bottom - top);
        }
        if (state.hoverRow() >= 0) {
            gc.setFill(CROSS);
            gc.fillRect(left, state.hoverRow() * cell, right - left, cell);
        }
        if (state.hoverColumn() >= 0) {
            gc.setFill(CROSS);
            gc.fillRect(state.hoverColumn() * cell, top, cell, bottom - top);
        }
        if (cell >= 7) {
            gc.setStroke(GRID);
            gc.setLineWidth(0.5);
            for (int row = firstRow; row <= lastRow + 1; row++) {
                gc.strokeLine(left, row * cell, right, row * cell);
            }
            for (int column = firstColumn; column <= lastColumn + 1; column++) {
                gc.strokeLine(column * cell, top, column * cell, bottom);
            }
        }
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        Font plain = Font.font(Math.clamp(cell * 0.55, 7.0, 14.0));
        Font bold = Font.font(null, FontWeight.BOLD, plain.getSize());
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                if (row == column) {
                    gc.setFill(DIAGONAL);
                    gc.fillRect(column * cell, row * cell, cell, cell);
                    continue;
                }
                DependencyMatrix.Cell content = matrix.cell(row, column);
                if (content != null) {
                    boolean selected = row == state.cellRow() && column == state.cellColumn();
                    paintCell(gc, row, column, content, selected, state.calls(), plain, bold);
                }
            }
        }
        gc.setStroke(FRAME);
        for (DependencyMatrix.Group group : matrix.groups()) {
            double start = group.first() * cell;
            double extent = (group.last() - group.first() + 1) * cell;
            gc.setLineWidth(group.depth() == 0 ? 1.8 : 1.0);
            gc.strokeRect(start, start, extent, extent);
        }
    }

    private void paintCell(GraphicsContext gc, int row, int column, DependencyMatrix.Cell content,
                           boolean selected, boolean calls, Font plain, Font bold) {
        double x = column * cell;
        double y = row * cell;
        boolean against = content.againstLayers() > 0;
        boolean onlyCut = content.edges().isEmpty();
        Color text = onlyCut ? LineColors.CUT.darker() : against ? LineColors.AGAINST_LAYERS : TEXT;
        Color ground = selected ? LineColors.SELECTED
                : content.cycle() == CycleKind.CLASS_CYCLE ? LineColors.CLASS_CYCLE.deriveColor(0, 1, 1, 0.22)
                : content.cycle() == CycleKind.PACKAGE_CYCLE ? LineColors.PACKAGE_CYCLE.deriveColor(0, 1, 1, 0.25)
                : null;
        if (cell < MIN_CELL_WITH_NUMBER) {
            gc.setFill(selected ? LineColors.SELECTED : text.deriveColor(0, 1, 1, against || onlyCut ? 1.0 : 0.55));
            gc.fillRect(x + 0.5, y + 0.5, cell - 1, cell - 1);
            return;
        }
        if (ground != null) {
            gc.setFill(ground);
            gc.fillRect(x + 0.5, y + 0.5, cell - 1, cell - 1);
        }
        gc.setFont(against ? bold : plain);
        gc.setFill(selected ? Color.WHITE : text);
        int number = onlyCut ? content.cut().size() : calls ? content.weight() : content.edges().size();
        gc.fillText(Integer.toString(number), x + cell / 2, y + cell / 2 + 0.5, cell - 2);
    }

    private void paintRowNames(GraphicsContext gc, double height, double scrollY, int firstRow, int lastRow,
                               State state) {
        gc.save();
        gc.beginPath();
        gc.rect(0, headerHeight, headerWidth, height - headerHeight);
        gc.clip();
        gc.setFill(HEADER);
        gc.fillRect(0, headerHeight, headerWidth, height - headerHeight);
        gc.translate(0, headerHeight - scrollY);
        double bands = depth * BAND;
        gc.setTextBaseline(VPos.CENTER);
        for (int row = firstRow; row <= lastRow; row++) {
            double y = row * cell;
            paintNameGround(gc, 0, y, headerWidth, cell, isSelected(row, state), row == state.hoverRow());
            DependencyMatrix.Item item = matrix.items().get(row);
            double icon = Math.min(ICON, cell - 2);
            paintIcon(gc, item, bands + GAP, y + cell / 2, icon);
            gc.setFill(TEXT);
            gc.setFont(font);
            gc.setTextAlign(TextAlignment.LEFT);
            if (cell >= MIN_CELL_WITH_NAME) {
                gc.fillText(name(item), bands + ICON + 2 * GAP, y + cell / 2,
                        headerWidth - bands - ICON - BAND - 4 * GAP);
            }
            if (item.isPackage() && !item.external() && cell >= MIN_CELL_WITH_NAME) {
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setFill(FRAME);
                gc.fillText("+", headerWidth - BAND / 2 - GAP, y + cell / 2);
            }
        }
        for (DependencyMatrix.Group group : matrix.groups()) {
            paintBand(gc, group);
        }
        gc.restore();
        gc.setStroke(FRAME);
        gc.setLineWidth(1);
        gc.strokeLine(headerWidth, headerHeight, headerWidth, Math.min(height, headerHeight + contentSize() - scrollY));
    }

    private void paintBand(GraphicsContext gc, DependencyMatrix.Group group) {
        double x = group.depth() * BAND;
        double y = group.first() * cell;
        double extent = (group.last() - group.first() + 1) * cell;
        gc.setFill(BAND_COLOR);
        gc.fillRect(x + 1, y + 1, BAND - 2, extent - 2);
        gc.setStroke(FRAME);
        gc.setLineWidth(0.8);
        gc.strokeRect(x + 1, y + 1, BAND - 2, extent - 2);
        gc.setFill(TEXT);
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        if (extent >= 12) {
            gc.fillText("−", x + BAND / 2, y + 7);
        }
        if (extent >= 34) {
            gc.save();
            gc.translate(x + BAND / 2, y + extent - GAP);
            gc.rotate(-90);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(simple(group.fqn()), 0, 0, extent - 20);
            gc.restore();
        }
    }

    private void paintColumnNames(GraphicsContext gc, double width, double scrollX, int firstColumn, int lastColumn,
                                  State state) {
        gc.save();
        gc.beginPath();
        gc.rect(headerWidth, 0, width - headerWidth, headerHeight);
        gc.clip();
        gc.setFill(HEADER);
        gc.fillRect(headerWidth, 0, width - headerWidth, headerHeight);
        gc.translate(headerWidth - scrollX, 0);
        gc.setTextBaseline(VPos.CENTER);
        for (int column = firstColumn; column <= lastColumn; column++) {
            double x = column * cell;
            paintNameGround(gc, x, 0, cell, headerHeight, isSelected(column, state), column == state.hoverColumn());
            DependencyMatrix.Item item = matrix.items().get(column);
            double icon = Math.min(ICON, cell - 2);
            paintIcon(gc, item, x + cell / 2 - icon / 2, headerHeight - GAP - ICON / 2, icon);
            if (cell >= MIN_CELL_WITH_NAME) {
                gc.save();
                gc.translate(x + cell / 2, headerHeight - ICON - 2 * GAP);
                gc.rotate(-90);
                gc.setFill(TEXT);
                gc.setFont(font);
                gc.setTextAlign(TextAlignment.LEFT);
                gc.fillText(name(item), 0, 0, headerHeight - ICON - 3 * GAP);
                gc.restore();
            }
        }
        gc.restore();
        gc.setStroke(FRAME);
        gc.setLineWidth(1);
        gc.strokeLine(headerWidth, headerHeight, Math.min(width, headerWidth + contentSize() - scrollX), headerHeight);
    }

    private static boolean isSelected(int index, State state) {
        return state.selectedFirst() <= index && index <= state.selectedLast();
    }

    private static void paintNameGround(GraphicsContext gc, double x, double y, double width, double height,
                                        boolean selected, boolean hovered) {
        if (selected || hovered) {
            gc.setFill(selected ? SELECTED_ELEMENT : CROSS);
            gc.fillRect(x, y, width, height);
        }
    }

    private void paintIcon(GraphicsContext gc, DependencyMatrix.Item item, double x, double centerY, double size) {
        double y = centerY - size / 2;
        if (item.isPackage()) {
            gc.setFill(item.external() ? EXTERNAL : PACKAGE);
            gc.fillRoundRect(x, y, size, size, 2, 2);
        } else {
            gc.setFill(isInterface.test(item.fqn()) ? INTERFACE : CLASS);
            gc.fillOval(x, y, size, size);
        }
    }

    /** What a row is called: a package outside the analysis by its full name, the rest by the simple one. */
    static String name(DependencyMatrix.Item item) {
        return item.external() ? item.fqn() : simple(item.fqn());
    }

    /** The shortest end of the name that tells the rows apart is mostly the simple one. */
    static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
