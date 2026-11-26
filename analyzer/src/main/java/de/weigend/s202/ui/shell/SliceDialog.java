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
package de.weigend.s202.ui.shell;

import de.weigend.s202.domain.SliceHierarchy;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Asks for the depth of a horizontal slice and shows, for every depth, how
 * many packages lie side by side there and how many tangles they form. Depth 1
 * is what the layered view shows at the top; it is listed for comparison.
 */
final class SliceDialog {

    private SliceDialog() {
    }

    /** @return the depth chosen; empty when the user cancelled */
    static Optional<Integer> chooseDepth(Window owner, String analysis, List<SliceHierarchy.Level> levels) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Horizontal Slice");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("about-dialog", "slice-dialog");
        var css = SliceDialog.class.getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        pane.setHeader(null);
        pane.setHeaderText(null);

        FontIcon icon = new FontIcon(MaterialDesignL.LAYERS_TRIPLE_OUTLINE);
        icon.setIconSize(48);
        icon.setIconColor(Color.web("#ffd54f"));
        Label title = new Label("Slice " + analysis);
        title.getStyleClass().add("about-title");
        Label tagline = new Label("All packages of one depth side by side, whatever packages they lie in.");
        tagline.getStyleClass().add("about-tagline");
        HBox header = new HBox(18, icon, new VBox(2, title, tagline));
        header.setAlignment(Pos.CENTER_LEFT);

        TableView<SliceHierarchy.Level> table = new TableView<>();
        table.getColumns().add(column("Depth", level -> level.depth() == 1 ? "1 (layered)" : String.valueOf(level.depth()), false));
        table.getColumns().add(column("Packages", level -> String.valueOf(level.packages()), true));
        table.getColumns().add(column("Tangles", level -> String.valueOf(level.tangles()), true));
        table.getColumns().add(column("In tangles", level -> level.tangled() == 0 ? "" : String.valueOf(level.tangled()), true));
        TableColumn<SliceHierarchy.Level, String> which = column("Which", level -> String.join(";  ", level.names()), false);
        which.setPrefWidth(360);
        which.setMaxWidth(Double.MAX_VALUE);
        table.getColumns().add(which);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getItems().setAll(levels);
        table.setPrefHeight(Math.min(levels.size(), 10) * 26 + 34);
        table.setPrefWidth(760);
        table.getSelectionModel().select(levels.size() > 1 ? 1 : 0);
        VBox tableBox = new VBox(table);
        // The dark table of the Hotspots panel.
        tableBox.getStyleClass().add("hotspots-view");

        Label hint = new Label("Compare the depths: where the tangles disappear, the cycle lies in the grouping,\n"
                + "not in the code. Where they stay, only a change of the code resolves it.");
        hint.getStyleClass().add("about-tagline");

        VBox body = new VBox(14, header, tableBox, hint);
        pane.setContent(body);

        ButtonType open = new ButtonType("Open", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, open);
        Button openButton = (Button) pane.lookupButton(open);
        openButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull()
                .or(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> table.getSelectionModel().getSelectedItem() != null
                                && table.getSelectionModel().getSelectedItem().depth() < 2,
                        table.getSelectionModel().selectedItemProperty())));
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !openButton.isDisabled()) {
                openButton.fire();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == open && table.getSelectionModel().getSelectedItem() != null) {
            return Optional.of(table.getSelectionModel().getSelectedItem().depth());
        }
        return Optional.empty();
    }

    private static TableColumn<SliceHierarchy.Level, String> column(String name,
                                                                   Function<SliceHierarchy.Level, String> value,
                                                                   boolean number) {
        TableColumn<SliceHierarchy.Level, String> column = new TableColumn<>(name);
        column.setSortable(false);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(value.apply(data.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setTooltip(empty || item == null || item.isEmpty() ? null : new javafx.scene.control.Tooltip(item));
            }
        });
        column.setPrefWidth(number ? 76 : 120);
        column.setMaxWidth(number ? 90 : 130);
        if (number) {
            column.getStyleClass().add("hotspots-number");
        }
        return column;
    }
}
