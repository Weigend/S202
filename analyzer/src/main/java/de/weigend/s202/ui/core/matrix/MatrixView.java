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

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import de.weigend.s202.domain.architecture.DependencyMatrix;
import de.weigend.s202.ui.core.arrows.DependencyEdgeActions;
import de.weigend.s202.ui.core.arrows.DependencySelection;
import de.weigend.s202.ui.core.matrix.MatrixPainter.Place;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shows a {@link DependencyMatrix}. Only what is in the window is drawn, so the
 * size of the matrix does not matter. What it shows, and what a click means to
 * the rest of the workbench, is the business of its owner.
 */
public final class MatrixView extends BorderPane {

    private static final double SCROLL_BAR = 14.0;
    private static final double ZOOM_STEP = 1.15;
    /** The largest canvas that can be drawn at once, for a picture of the whole. */
    private static final double MAX_PICTURE = 8_000.0;

    private final Canvas canvas = new Canvas();
    private final ScrollBar horizontal = new ScrollBar();
    private final ScrollBar vertical = new ScrollBar();
    private final Label stats = new Label();
    private final CheckBox calls = new CheckBox("Calls");
    private final CheckBox externals = new CheckBox("Libraries");
    private final Tooltip tooltip = new Tooltip();
    private final ReadOnlyDoubleWrapper zoom = new ReadOnlyDoubleWrapper(1.0);

    private final Set<String> open = new LinkedHashSet<>();
    private DependencyMatrix matrix;
    private MatrixPainter painter;
    private Predicate<String> isInterface = fqn -> false;

    private int hoverRow = -1;
    private int hoverColumn = -1;
    private String selectedElement;
    /** The selected cell, by the names of its row and column: it outlives a rebuild. */
    private String cellRow;
    private String cellColumn;

    private Runnable onOpenChanged = () -> { };
    private Function<String, String> originOf = fqn -> null;
    private Consumer<String> onElementSelected = fqn -> { };
    private Function<String, List<MenuItem>> elementMenu = fqn -> List.of();
    private DependencyEdgeActions actions;

    public MatrixView() {
        getStyleClass().add("matrix-view");
        horizontal.setOrientation(Orientation.HORIZONTAL);
        vertical.setOrientation(Orientation.VERTICAL);
        horizontal.valueProperty().addListener((obs, was, isNow) -> paint());
        vertical.valueProperty().addListener((obs, was, isNow) -> paint());

        Pane drawing = new Pane(canvas, horizontal, vertical) {
            @Override
            protected void layoutChildren() {
                double width = Math.max(0, getWidth() - SCROLL_BAR);
                double height = Math.max(0, getHeight() - SCROLL_BAR);
                canvas.setWidth(width);
                canvas.setHeight(height);
                horizontal.resizeRelocate(0, height, width, SCROLL_BAR);
                vertical.resizeRelocate(width, 0, SCROLL_BAR, height);
                updateScrollBars();
                paint();
            }
        };
        drawing.setMinSize(0, 0);
        setTop(bar());
        setCenter(drawing);

        tooltip.setShowDelay(Duration.millis(400));
        tooltip.setShowDuration(Duration.seconds(30));
        Tooltip.install(canvas, tooltip);
        canvas.setOnMouseMoved(event -> hover(placeAt(event)));
        canvas.setOnMouseExited(event -> hover(Place.NOTHING));
        canvas.setOnMouseClicked(this::clicked);
        canvas.setOnContextMenuRequested(this::showMenu);
        canvas.setOnScroll(this::scrolled);
    }

    private Node bar() {
        Label opened = new Label("Open:");
        Button members = button("Members", "Close all packages", () -> setOpen(Set.of()));
        Button packages = button("Packages", "Open the packages that hold other packages",
                () -> setOpen(matrix == null ? Set.of() : matrix.packagesOfPackages()));
        Button classes = button("Classes", "Open all packages",
                () -> setOpen(matrix == null ? Set.of() : matrix.packages()));
        calls.setTooltip(new Tooltip("Off: the numbers count class dependencies.\n"
                + "On: they count the calls that carry them, a dependency without calls as 1."));
        calls.selectedProperty().addListener((obs, was, isNow) -> paint());
        externals.setTooltip(new Tooltip("Rows at the bottom for the packages used outside the analysis:\n"
                + "the JDK, frameworks, other libraries. They count for no layer and no cycle."));
        externals.selectedProperty().addListener((obs, was, isNow) -> onOpenChanged.run());
        Label legend = new Label("red: against the layers   ■ class cycle   ■ package cycle only");
        legend.setGraphic(null);
        HBox legendBox = legend();
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox bar = new HBox(6, opened, members, packages, classes, calls, externals, space, legend(), stats);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 8, 3, 8));
        bar.getStyleClass().add("matrix-bar");
        return bar;
    }

    private static HBox legend() {
        Label against = new Label("12");
        against.getStyleClass().add("matrix-legend-against");
        Label classCycle = new Label("   ");
        classCycle.getStyleClass().add("matrix-legend-class-cycle");
        Label packageCycle = new Label("   ");
        packageCycle.getStyleClass().add("matrix-legend-package-cycle");
        HBox box = new HBox(4, against, new Label("against the layers "),
                classCycle, new Label("class cycle "), packageCycle, new Label("package cycle only   "));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Button button(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("matrix-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(event -> action.run());
        return button;
    }

    /* ----- What is shown ------------------------------------------------------- */

    /** @param isInterface tells interfaces from other classes */
    public void show(DependencyMatrix newMatrix, Predicate<String> isInterface) {
        this.matrix = newMatrix;
        this.isInterface = isInterface;
        hoverRow = -1;
        hoverColumn = -1;
        stats.setText(newMatrix == null ? "" : "I " + newMatrix.size() + "  D " + newMatrix.dependencies()
                + "  T " + newMatrix.tangles());
        stats.setTooltip(new Tooltip("Items, dependencies between them, and tangles among them"));
        layOut();
    }

    public DependencyMatrix matrix() {
        return matrix;
    }

    /** Whether rows for what is used outside the analysis are wanted. */
    public boolean isShowingExternals() {
        return externals.isSelected();
    }

    public void setShowingExternals(boolean show) {
        externals.setSelected(show);
    }

    /** The packages whose members are shown. */
    public Set<String> getOpen() {
        return Set.copyOf(open);
    }

    public void setOpen(Set<String> packages) {
        if (open.equals(packages)) {
            return;
        }
        Set<String> now = new HashSet<>(packages);
        open.clear();
        open.addAll(now);
        onOpenChanged.run();
    }

    private void toggle(String pkg) {
        Set<String> next = new HashSet<>(open);
        if (!next.remove(pkg)) {
            next.add(pkg);
        }
        setOpen(next);
    }

    /** Marks the row and column of an element, or of the closed package it is in; {@code null} for none. */
    public void setSelectedElement(String fqn) {
        if (!Objects.equals(fqn, selectedElement)) {
            selectedElement = fqn;
            paint();
        }
    }

    /** Scrolls so that the element can be seen. */
    public void reveal(String fqn) {
        int index = matrix == null || fqn == null ? -1 : matrix.indexOf(fqn);
        if (index < 0 || painter == null) {
            return;
        }
        double position = index * cell();
        reveal(vertical, position, canvas.getHeight() - painter.headerHeight());
        reveal(horizontal, position, canvas.getWidth() - painter.headerWidth());
    }

    private void reveal(ScrollBar bar, double position, double visible) {
        if (position < bar.getValue() || position + cell() > bar.getValue() + visible) {
            bar.setValue(Math.clamp(position - visible / 2, bar.getMin(), Math.max(bar.getMin(), bar.getMax())));
        }
    }

    /* ----- Who is told ---------------------------------------------------------- */

    /** Asks for {@link #show} with the packages now open. */
    public void setOnOpenChanged(Runnable handler) {
        onOpenChanged = handler;
    }

    /** Receives the element whose name was clicked; {@code null} when it is not selected any more. */
    public void setOnElementSelected(Consumer<String> handler) {
        onElementSelected = handler;
    }

    /** Tells the file a class was read from, {@code null} if that is not known. */
    public void setOriginOf(Function<String, String> origins) {
        originOf = origins;
    }

    /** Offers what else can be done with an element. */
    public void setElementMenu(Function<String, List<MenuItem>> menu) {
        elementMenu = menu;
    }

    /** Is told which cell is selected, and cuts and restores. */
    public void setActions(DependencyEdgeActions actions) {
        this.actions = actions;
    }

    /* ----- Zoom ------------------------------------------------------------------ */

    public ReadOnlyDoubleProperty zoomProperty() {
        return zoom.getReadOnlyProperty();
    }

    public void setZoom(double factor) {
        double limited = Math.clamp(factor,
                MatrixPainter.MIN_CELL / MatrixPainter.DEFAULT_CELL, MatrixPainter.MAX_CELL / MatrixPainter.DEFAULT_CELL);
        if (limited != zoom.get()) {
            zoom.set(limited);
            layOut();
        }
    }

    public void zoomIn() {
        setZoom(zoom.get() * ZOOM_STEP);
    }

    public void zoomOut() {
        setZoom(zoom.get() / ZOOM_STEP);
    }

    /** As large as possible with all of it to be seen. */
    public void zoomToFit() {
        if (matrix == null || matrix.size() == 0) {
            return;
        }
        // The names change their size with the cells; a few rounds settle it.
        for (int round = 0; round < 4; round++) {
            double room = Math.min(canvas.getWidth() - painter.headerWidth(), canvas.getHeight() - painter.headerHeight());
            setZoom(Math.floor(room / matrix.size() * 4) / 4 / MatrixPainter.DEFAULT_CELL);
        }
    }

    private double cell() {
        return MatrixPainter.DEFAULT_CELL * zoom.get();
    }

    private void layOut() {
        painter = matrix == null ? null : new MatrixPainter(matrix, cell(), isInterface);
        updateScrollBars();
        paint();
    }

    private void updateScrollBars() {
        if (painter == null) {
            return;
        }
        update(horizontal, canvas.getWidth() - painter.headerWidth());
        update(vertical, canvas.getHeight() - painter.headerHeight());
    }

    private void update(ScrollBar bar, double visible) {
        double hidden = Math.max(0, painter.contentSize() - Math.max(0, visible));
        bar.setMin(0);
        bar.setMax(hidden);
        bar.setVisibleAmount(hidden == 0 ? 0 : hidden * Math.max(0.05, visible / painter.contentSize()));
        bar.setUnitIncrement(cell());
        bar.setBlockIncrement(Math.max(cell(), visible * 0.8));
        bar.setDisable(hidden == 0);
        bar.setValue(Math.clamp(bar.getValue(), 0, hidden));
    }

    private void scrolled(ScrollEvent event) {
        if (event.isControlDown()) {
            if (event.getDeltaY() > 0) {
                zoomIn();
            } else if (event.getDeltaY() < 0) {
                zoomOut();
            }
        } else {
            boolean sideways = event.isShiftDown() && event.getDeltaX() == 0;
            scroll(horizontal, sideways ? event.getDeltaY() : event.getDeltaX());
            scroll(vertical, sideways ? 0 : event.getDeltaY());
        }
        event.consume();
    }

    private static void scroll(ScrollBar bar, double delta) {
        bar.setValue(Math.clamp(bar.getValue() - delta, bar.getMin(), bar.getMax()));
    }

    /* ----- Mouse ------------------------------------------------------------------ */

    private Place placeAt(MouseEvent event) {
        return placeAt(event.getX(), event.getY());
    }

    private Place placeAt(double x, double y) {
        return painter == null ? Place.NOTHING : painter.placeAt(x, y, horizontal.getValue(), vertical.getValue());
    }

    private void hover(Place place) {
        canvas.setCursor(switch (place.kind()) {
            case NOTHING -> Cursor.DEFAULT;
            case CELL -> matrix.cell(place.row(), place.column()) == null ? Cursor.DEFAULT : Cursor.HAND;
            default -> Cursor.HAND;
        });
        String text = describe(place);
        tooltip.setText(text);
        tooltip.setOpacity(text.isEmpty() ? 0 : 1);
        if (place.row() != hoverRow || place.column() != hoverColumn) {
            hoverRow = place.row();
            hoverColumn = place.column();
            paint();
        }
    }

    private String describe(Place place) {
        return switch (place.kind()) {
            case ROW, ROW_TOGGLE -> describeItem(matrix.items().get(place.row()));
            case COLUMN -> describeItem(matrix.items().get(place.column()));
            case GROUP -> place.group().fqn() + "\nClick to close";
            case CELL -> describeCell(place.row(), place.column());
            case NOTHING -> "";
        };
    }

    private String describeItem(DependencyMatrix.Item item) {
        if (item.external()) {
            return item.fqn() + "\noutside the analysis";
        }
        String origin = item.isPackage() ? null : originOf.apply(item.fqn());
        return origin == null ? item.fqn() : item.fqn() + "\nread from " + origin;
    }

    private String describeCell(int row, int column) {
        DependencyMatrix.Cell content = matrix.cell(row, column);
        if (content == null) {
            return "";
        }
        String text = MatrixPainter.name(matrix.items().get(column)) + " uses "
                + MatrixPainter.name(matrix.items().get(row));
        if (!content.edges().isEmpty()) {
            text += "\n" + count(content.edges().size(), "class dependency", "class dependencies")
                    + ", weight " + content.weight();
        }
        if (content.againstLayers() > 0) {
            text += "\n" + content.againstLayers() + " against the layers";
        }
        if (content.cycle() != CycleKind.NONE) {
            text += "\n" + (content.cycle() == CycleKind.CLASS_CYCLE ? "on a class cycle" : "on a package cycle");
        }
        if (!content.cut().isEmpty()) {
            text += "\n" + content.cut().size() + " cut";
        }
        return text;
    }

    private static String count(int number, String one, String many) {
        return number + " " + (number == 1 ? one : many);
    }

    private void clicked(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
            return;
        }
        Place place = placeAt(event);
        switch (place.kind()) {
            case GROUP -> toggle(place.group().fqn());
            case ROW_TOGGLE -> toggle(matrix.items().get(place.row()).fqn());
            case ROW -> nameClicked(matrix.items().get(place.row()), event.getClickCount());
            case COLUMN -> nameClicked(matrix.items().get(place.column()), event.getClickCount());
            case CELL -> cellClicked(place.row(), place.column());
            case NOTHING -> { }
        }
    }

    private void nameClicked(DependencyMatrix.Item item, int clicks) {
        if (clicks == 2 && matrix.packages().contains(item.fqn())) {
            toggle(item.fqn());
        } else if (clicks == 1) {
            onElementSelected.accept(item.fqn().equals(selectedElement) ? null : item.fqn());
        }
    }

    private void cellClicked(int row, int column) {
        DependencyMatrix.Cell content = matrix.cell(row, column);
        String rowName = matrix.items().get(row).fqn();
        String columnName = matrix.items().get(column).fqn();
        boolean lift = content == null || rowName.equals(cellRow) && columnName.equals(cellColumn);
        boolean wasSelected = cellRow != null;
        cellRow = lift ? null : rowName;
        cellColumn = lift ? null : columnName;
        paint();
        if (actions != null && (!lift || wasSelected)) {
            actions.selected(lift ? DependencySelection.NONE
                    : new DependencySelection(columnName, rowName, content.allEdges()));
        }
    }

    private void showMenu(ContextMenuEvent event) {
        Place place = placeAt(event.getX(), event.getY());
        List<MenuItem> items = switch (place.kind()) {
            case ROW, ROW_TOGGLE -> menuOf(matrix.items().get(place.row()).fqn());
            case COLUMN -> menuOf(matrix.items().get(place.column()).fqn());
            case GROUP -> menuOf(place.group().fqn());
            case CELL -> matrix.items().get(place.row()).external() ? List.of()
                    : menuOf(matrix.cell(place.row(), place.column()));
            case NOTHING -> List.of();
        };
        if (!items.isEmpty()) {
            ContextMenu menu = new ContextMenu();
            menu.getItems().setAll(items);
            menu.show(canvas, event.getScreenX(), event.getScreenY());
        }
        event.consume();
    }

    private List<MenuItem> menuOf(String fqn) {
        List<MenuItem> items = new ArrayList<>();
        if (matrix.packages().contains(fqn)) {
            MenuItem toggle = new MenuItem(open.contains(fqn) ? "Close" : "Open");
            toggle.setOnAction(event -> toggle(fqn));
            items.add(toggle);
        }
        List<MenuItem> offered = elementMenu.apply(fqn);
        if (!items.isEmpty() && !offered.isEmpty()) {
            items.add(new SeparatorMenuItem());
        }
        items.addAll(offered);
        return items;
    }

    /** A cell that is one class dependency can be cut and restored, like its arrow. */
    private List<MenuItem> menuOf(DependencyMatrix.Cell content) {
        if (content == null || actions == null || content.allEdges().size() != 1) {
            return List.of();
        }
        DependencyEdge edge = content.allEdges().get(0);
        boolean cut = !content.cut().isEmpty();
        MenuItem item = new MenuItem(cut ? "Restore" : "Cut");
        item.setOnAction(event -> {
            if (cut) {
                actions.restore(edge.from(), edge.to());
            } else {
                actions.cut(edge.from(), edge.to());
            }
        });
        return List.of(item);
    }

    /* ----- Drawing ------------------------------------------------------------------ */

    private void paint() {
        if (painter == null) {
            canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            return;
        }
        painter.paint(canvas.getGraphicsContext2D(), canvas.getWidth(), canvas.getHeight(),
                horizontal.getValue(), vertical.getValue(), state());
    }

    private MatrixPainter.State state() {
        int first = selectedElement == null ? -1 : matrix.indexOf(selectedElement);
        int last = first;
        for (DependencyMatrix.Group group : matrix.groups()) {
            if (group.fqn().equals(selectedElement)) {
                first = group.first();
                last = group.last();
            }
        }
        int row = cellRow == null ? -1 : rowOf(cellRow);
        int column = cellColumn == null ? -1 : rowOf(cellColumn);
        return new MatrixPainter.State(hoverRow, hoverColumn, first, last, row, column, calls.isSelected());
    }

    /** The row with exactly this name: the cell of a package is not that of its closed container. */
    private int rowOf(String fqn) {
        int index = matrix.indexOf(fqn);
        return index >= 0 && matrix.items().get(index).fqn().equals(fqn) ? index : -1;
    }

    /** The whole matrix as a node of its own, for a picture. */
    public Node picture() {
        if (painter == null) {
            return canvas;
        }
        double size = cell();
        MatrixPainter whole = new MatrixPainter(matrix, size, isInterface);
        while (size > MatrixPainter.MIN_CELL && Math.max(whole.headerWidth(), whole.headerHeight())
                + whole.contentSize() > MAX_PICTURE) {
            size = Math.max(MatrixPainter.MIN_CELL, size * 0.8);
            whole = new MatrixPainter(matrix, size, isInterface);
        }
        Canvas picture = new Canvas(Math.min(MAX_PICTURE, whole.headerWidth() + whole.contentSize() + 1),
                Math.min(MAX_PICTURE, whole.headerHeight() + whole.contentSize() + 1));
        whole.paint(picture.getGraphicsContext2D(), picture.getWidth(), picture.getHeight(), 0, 0, state());
        return picture;
    }
}
