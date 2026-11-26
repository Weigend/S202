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
package de.weigend.s202.ui.features.hotspots;

import de.weigend.s202.analysis.quality.Hotspots.Hotspot;
import de.weigend.s202.domain.Origins.Origin;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewAnchor;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Where to look first in a review: the packages of the active tab ranked by
 * breakout, what they use outside the analysis, and the files the classes
 * were read from.
 */
public final class HotspotsView implements View {

    public static final String VIEW_ID = "s202-hotspots";

    /**
     * A package outside the analysis.
     *
     * @param pkg      its name
     * @param classes  its classes that are used
     * @param users    the analyzed classes that use them
     * @param packages the analyzed packages those are in
     */
    record Library(String pkg, int classes, int users, int packages) {
    }

    /** What a click or the menu on a package asks for. */
    interface PackageActions {
        void select(String pkg);

        void openScope(String pkg);

        void fromTo(String from, String to);
    }

    private final BorderPane root = new BorderPane();
    private final Label summary = new Label();
    private final TableView<Hotspot> packages = new TableView<>();
    private final TableView<Library> libraries = new TableView<>();
    private final TableView<Origin> origins = new TableView<>();
    private final ToggleGroup tables = new ToggleGroup();
    private final ToggleButton packagesButton = toggle("Packages",
            "The packages of the tab, measured as in the Quality panel; the largest breakout first");
    private final ToggleButton librariesButton = toggle("Libraries",
            "The packages used outside the analysis: the JDK, frameworks, other libraries");
    private final ToggleButton originsButton = toggle("JARs",
            "The files the classes were read from: which uses which, and the packages split across them");
    private PackageActions actions;
    private Function<String, String> nameOf = pkg -> pkg;

    public HotspotsView() {
        root.getStyleClass().add("hotspots-view");
        summary.getStyleClass().add("hotspots-summary");
        HBox buttons = new HBox(6, packagesButton, librariesButton, originsButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        FlowPane bar = new FlowPane(6, 3, buttons, summary);
        bar.setPadding(new Insets(3, 6, 3, 6));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("hotspots-bar");
        root.setTop(bar);

        packages.getColumns().setAll(List.of(
                text("Package", hotspot -> nameOf.apply(hotspot.pkg()), 260),
                number("Classes", Hotspot::classes, "The classes in the package, at any depth"),
                number("Breakout", Hotspot::breakout,
                        "Dependencies between the direct members of the package, each pair and direction once"),
                decimal("Avg deps", Hotspot::average,
                        "Average class dependencies per class, the number behind Fat"),
                percent("Tangled", Hotspot::tangled, "Share of the class dependencies that lie on a class cycle"),
                number("Against", Hotspot::against,
                        "Class dependencies inside the package that run against its layers"),
                number("External", Hotspot::externals, "Classes outside the analysis that the package uses")));
        packages.getSortOrder().setAll(List.of(packages.getColumns().get(2)));
        packages.getColumns().get(2).setSortType(TableColumn.SortType.DESCENDING);
        packages.setRowFactory(table -> packageRow());
        packages.setPlaceholder(new Label("No packages in this tab"));

        libraries.getColumns().setAll(List.of(
                text("Package", Library::pkg, 260),
                number("Classes", Library::classes, "Its classes that are used"),
                number("Used by", Library::users, "The analyzed classes that use them"),
                number("Packages", Library::packages, "The analyzed packages those classes are in")));
        libraries.getSortOrder().setAll(List.of(libraries.getColumns().get(2)));
        libraries.getColumns().get(2).setSortType(TableColumn.SortType.DESCENDING);
        libraries.setRowFactory(table -> libraryRow());
        libraries.setPlaceholder(new Label("Nothing is used outside the analysis"));

        origins.getColumns().setAll(List.of(
                text("JAR", Origin::origin, 220),
                number("Classes", Origin::classes, "The classes read from it"),
                number("Packages", Origin::packages, "The packages they are in"),
                text("Uses", origin -> String.join(", ", origin.uses()), 180),
                text("Used by", origin -> String.join(", ", origin.usedBy()), 180),
                text("Split packages", origin -> String.join(", ", origin.split()), 200)));
        origins.setPlaceholder(new Label("No classes"));

        for (TableView<?> table : List.of(packages, libraries, origins)) {
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        }
        packagesButton.setToggleGroup(tables);
        librariesButton.setToggleGroup(tables);
        originsButton.setToggleGroup(tables);
        tables.selectedToggleProperty().addListener((obs, was, isNow) -> {
            if (isNow == null) {
                was.setSelected(true);
            } else {
                showTable();
            }
        });
        packagesButton.setSelected(true);
    }

    private static ToggleButton toggle(String text, String tip) {
        ToggleButton button = new ToggleButton(text);
        button.getStyleClass().add("between-button");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setTooltip(new Tooltip(tip));
        return button;
    }

    private void showTable() {
        Table shown = Table.of(tables.getSelectedToggle() == librariesButton ? 1
                : tables.getSelectedToggle() == originsButton ? 2 : 0);
        root.setCenter(switch (shown) {
            case PACKAGES -> packages;
            case LIBRARIES -> libraries;
            case ORIGINS -> origins;
        });
        summary.setText(switch (shown) {
            case PACKAGES -> count(packages.getItems().size(), "package", "packages")
                    + " · click selects, the menu opens a scope or From / To";
            case LIBRARIES -> count(libraries.getItems().size(), "package", "packages")
                    + " outside the analysis · double-click shows who uses it in From / To";
            case ORIGINS -> count(origins.getItems().size(), "file", "files");
        });
    }

    private enum Table {
        PACKAGES, LIBRARIES, ORIGINS;

        static Table of(int index) {
            return values()[index];
        }
    }

    private static String count(int number, String one, String many) {
        return number + " " + (number == 1 ? one : many);
    }

    void setActions(PackageActions newActions) {
        actions = newActions;
    }

    /** What the rows are called; a planned package has an id, and a name only in the plan. */
    void setNames(Function<String, String> names) {
        nameOf = names;
    }

    void show(List<Hotspot> hotspots, List<Library> libs, List<Origin> files) {
        packages.getItems().setAll(hotspots);
        packages.sort();
        libraries.getItems().setAll(libs);
        libraries.sort();
        origins.getItems().setAll(files);
        showTable();
    }

    private TableRow<Hotspot> packageRow() {
        TableRow<Hotspot> row = new TableRow<>();
        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && !row.isEmpty() && actions != null) {
                actions.select(row.getItem().pkg());
            }
        });
        row.setOnContextMenuRequested(event -> {
            if (row.isEmpty() || actions == null) {
                return;
            }
            String pkg = row.getItem().pkg();
            ContextMenu menu = new ContextMenu(
                    item("Open Scope", () -> actions.openScope(pkg)),
                    item("Dependencies From Here", () -> actions.fromTo(pkg, null)),
                    item("Dependencies To Here", () -> actions.fromTo(null, pkg)));
            menu.show(row, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        return row;
    }

    private TableRow<Library> libraryRow() {
        TableRow<Library> row = new TableRow<>();
        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()
                    && actions != null) {
                actions.fromTo(null, row.getItem().pkg());
            }
        });
        return row;
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private static <T> TableColumn<T, String> text(String name, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(name);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(value.apply(data.getValue())));
        column.setPrefWidth(width);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
                setTooltip(empty || item == null || item.isEmpty() ? null : new Tooltip(item));
            }
        });
        return column;
    }

    private static <T> TableColumn<T, Integer> number(String name, Function<T, Integer> value, String tip) {
        return numeric(name, value, tip, number -> number < 0 ? "" : Integer.toString(number));
    }

    private static <T> TableColumn<T, Double> decimal(String name, Function<T, Double> value, String tip) {
        return numeric(name, value, tip, number -> String.format(Locale.ROOT, "%.1f", number));
    }

    private static <T> TableColumn<T, Double> percent(String name, Function<T, Double> value, String tip) {
        return numeric(name, value, tip, number -> String.format(Locale.ROOT, "%.0f %%", number * 100));
    }

    private static <T, N extends Number> TableColumn<T, N> numeric(String name, Function<T, N> value, String tip,
                                                                   Function<N, String> format) {
        TableColumn<T, N> column = new TableColumn<>();
        Label header = new Label(name);
        header.setTooltip(new Tooltip(tip));
        column.setGraphic(header);
        column.setPrefWidth(78);
        column.setMinWidth(60);
        column.setMaxWidth(100);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(value.apply(data.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(N item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.apply(item));
            }
        });
        column.getStyleClass().add("hotspots-number");
        return column;
    }

    // ===== View interface =====

    @Override
    public String getViewId() {
        return VIEW_ID;
    }

    @Override
    public String getTitle() {
        return "Hotspots";
    }

    @Override
    public String getToolTipInfo() {
        return "The packages ranked by breakout, the libraries used and the JARs read";
    }

    /** A tab beside the Dependencies panel, like From / To. */
    @Override
    public Position getDefaultPosition() {
        return Position.CENTER;
    }

    @Override
    public ViewAnchor getDefaultAnchor() {
        return ViewAnchor.EDITOR;
    }

    @Override
    public Parent getRootNode() {
        return root;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 0.50;
    }

    /** For tests: the rows of the package table, in the order shown. */
    List<Hotspot> shownPackages() {
        return List.copyOf(packages.getItems());
    }

    /** For tests. */
    List<Library> shownLibraries() {
        return List.copyOf(libraries.getItems());
    }
}
