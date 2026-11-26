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
package de.weigend.s202.ui.features.details;

import de.weigend.s202.domain.ShownNames;
import de.weigend.s202.ui.features.details.DetailContent.Place;
import de.weigend.s202.ui.features.details.DetailContent.Row;
import de.weigend.s202.ui.features.details.DetailContent.Style;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewAnchor;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;

import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * WFX side-panel view that explains the selected dependency arrow: one row
 * per call, with the calling method on the left and the called one on the
 * right. Clicking a method asks for it to be shown elsewhere, see
 * {@link #setOnReveal(Consumer)}.
 */
public final class DependencyDetailsView implements View {

    public static final String VIEW_ID = "s202-dependency-details";

    static final String HINT = "Select a dependency arrow to see what carries it";

    private final BorderPane root = new BorderPane();
    private final Label title = new Label();
    private final Label summary = new Label();
    private final TextField filter = new TextField();
    private final FlowPane header = new FlowPane(6, 3, filter, title, summary);
    private final TreeTableView<Row> table = new TreeTableView<>();

    private Consumer<Place> revealHandler = place -> { };
    private DetailContent shown;

    public DependencyDetailsView() {
        root.getStyleClass().add("dependency-details");
        title.getStyleClass().add("dependency-details-title");
        summary.getStyleClass().add("dependency-details-summary");
        header.getStyleClass().add("dependency-details-header");

        header.setPadding(new Insets(3, 6, 3, 6));
        header.setAlignment(Pos.CENTER_LEFT);
        filter.setPromptText("Filter");
        filter.setPrefColumnCount(14);
        filter.textProperty().addListener((obs, was, isNow) -> fill());

        table.setShowRoot(false);
        table.setPlaceholder(new Label(HINT));
        table.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TreeTableColumn<Row, Row> from = column("From", Row::from, Row::fromPlace);
        TreeTableColumn<Row, Row> to = column("To", Row::to, Row::toPlace);
        TreeTableColumn<Row, Row> calls = column("Calls", Row::calls, row -> null);
        calls.setPrefWidth(50);
        calls.setMinWidth(50);
        calls.setMaxWidth(70);
        table.getColumns().setAll(List.of(from, to, calls));

        root.setTop(header);
        root.setCenter(table);
        show(null);
    }

    /** Shows {@code content}; {@code null} shows the hint how to fill the panel. */
    void show(DetailContent content) {
        header.setVisible(content != null);
        header.setManaged(content != null);
        // In a Physical View the names carry their files; shown without them.
        title.setText(content == null ? "" : ShownNames.shown(content.title()));
        summary.setText(content == null ? "" : ShownNames.shown(content.summary()));
        shown = content;
        fill();
    }

    private void fill() {
        String wanted = filter.getText().trim().toLowerCase(Locale.ROOT);
        TreeItem<Row> rootItem = new TreeItem<>();
        if (shown != null) {
            for (Row row : shown.rows()) {
                TreeItem<Row> item = item(row, wanted);
                if (item != null) {
                    rootItem.getChildren().add(item);
                }
            }
        }
        table.setRoot(rootItem);
    }

    /** Called with the place behind a clicked cell. */
    void setOnReveal(Consumer<Place> handler) {
        this.revealHandler = handler == null ? place -> { } : handler;
    }

    /**
     * The row with what is in it, as far as it is wanted.
     *
     * @return {@code null} when neither the row nor anything in it is wanted
     */
    private static TreeItem<Row> item(Row row, String wanted) {
        boolean all = wanted.isEmpty() || contains(row.from(), wanted) || contains(row.to(), wanted)
                || contains(row.tooltip(), wanted) && row.style() != Style.REFERENCE && row.style() != Style.GROUP;
        TreeItem<Row> item = new TreeItem<>(row);
        boolean found = false;
        for (Row child : row.children()) {
            TreeItem<Row> childItem = item(child, all ? "" : wanted);
            if (childItem != null) {
                item.getChildren().add(childItem);
                found = true;
            }
        }
        if (!all && !found) {
            return null;
        }
        item.setExpanded(row.expanded() || !all);
        return item;
    }

    private static boolean contains(String text, String wanted) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(wanted);
    }

    private TreeTableColumn<Row, Row> column(String name, Function<Row, String> text, Function<Row, Place> place) {
        TreeTableColumn<Row, Row> column = new TreeTableColumn<>(name);
        column.setSortable(false);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().getValue()));
        column.setCellFactory(ignored -> new RowCell(text, place));
        return column;
    }

    private final class RowCell extends TreeTableCell<Row, Row> {

        private final Function<Row, String> text;

        RowCell(Function<Row, String> text, Function<Row, Place> place) {
            this.text = text;
            setOnMouseClicked(event -> {
                Row row = getItem();
                if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 1 || row == null) {
                    return;
                }
                Place target = place.apply(row);
                if (target != null) {
                    revealHandler.accept(target);
                }
            });
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll(STYLE_CLASSES);
            if (empty || row == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(ShownNames.shown(text.apply(row)));
            setTooltip(row.tooltip() == null ? null : new Tooltip(ShownNames.shown(row.tooltip())));
            getStyleClass().add(styleClass(row.style()));
        }
    }

    private static final List<String> STYLE_CLASSES =
            Arrays.stream(Style.values()).map(DependencyDetailsView::styleClass).toList();

    private static String styleClass(Style style) {
        return "dependency-details-" + style.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    // ===== View interface =====

    @Override
    public String getViewId() {
        return VIEW_ID;
    }

    @Override
    public String getTitle() {
        return "Dependency Details";
    }

    @Override
    public String getToolTipInfo() {
        return "The calls behind the selected dependency arrow and the cycle it is part of";
    }

    /** Beside the Dependencies panel it is registered next to, so both stay in sight. */
    @Override
    public Position getDefaultPosition() {
        return Position.RIGHT;
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
}
