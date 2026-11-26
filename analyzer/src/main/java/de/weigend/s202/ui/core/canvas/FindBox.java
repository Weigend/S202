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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.domain.ShownNames;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ElementSearch;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The search over a picture (Ctrl+F): a field at the top of the view and,
 * while the user types, the classes and packages of the view whose name fits.
 * Enter or a click takes the hit selected, Escape or a click elsewhere puts
 * the box away.
 */
final class FindBox extends VBox {

    private static final int MAX_HITS = 50;
    private static final double ROW_HEIGHT = 24;
    // The colours of the From / To trees, which are dark as well.
    private static final Color PACKAGE = Color.web("#e6c46a");
    private static final Color CLASS = Color.web("#7fb3ff");
    private static final Color INTERFACE = Color.web("#4caf50");

    private final Supplier<ArchitectureNode> root;
    private final Consumer<String> onFound;
    private final Runnable onClosed;
    private final TextField field = new TextField();
    private final ListView<ElementSearch.Hit> hits = new ListView<>();
    private final Label nothing = new Label();

    private ArchitectureNode searchedRoot;
    private ElementSearch search;

    /**
     * @param root    what the view shows
     * @param onFound gets the full name of the element the user chose
     * @param onClosed runs when the box is put away
     */
    FindBox(Supplier<ArchitectureNode> root, Consumer<String> onFound, Runnable onClosed) {
        this.root = root;
        this.onFound = onFound;
        this.onClosed = onClosed;
        getStyleClass().add("find-box");
        setSpacing(4);
        setPadding(new Insets(6));
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setStyle("-fx-background-color: #3c3f41; -fx-background-radius: 0 0 4 4;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 8, 0, 0, 2);");

        FontIcon magnifier = new FontIcon(MaterialDesignM.MAGNIFY);
        magnifier.setIconColor(Color.web("#e0e0e0"));
        magnifier.setIconSize(16);
        field.setPromptText("Find class or package - CamelCase initials work too");
        field.setPrefColumnCount(30);
        HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        HBox top = new HBox(6, magnifier, field);
        top.setAlignment(Pos.CENTER_LEFT);

        hits.setCellFactory(list -> new HitCell());
        hits.setPrefWidth(460);
        hits.setFocusTraversable(false);
        hits.managedProperty().bind(hits.visibleProperty());
        hits.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                take(hits.getSelectionModel().getSelectedItem());
            }
        });
        nothing.setStyle("-fx-font-size: 11px; -fx-text-fill: #e0e0e0;");
        nothing.managedProperty().bind(nothing.visibleProperty());

        getChildren().addAll(top, hits, nothing);
        managedProperty().bind(visibleProperty());
        setVisible(false);

        field.textProperty().addListener((obs, was, isNow) -> showHits());
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        field.setOnAction(event -> take(hits.getSelectionModel().getSelectedItem()));
        field.focusedProperty().addListener((obs, was, isNow) -> {
            if (!isNow && !hits.isFocused()) {
                close();
            }
        });
    }

    /** Shows the box with what was typed last ready to be replaced. */
    void open() {
        setVisible(true);
        field.requestFocus();
        field.selectAll();
        showHits();
    }

    boolean isOpen() {
        return isVisible();
    }

    /** Puts the box away; the text stays for the next time. */
    void close() {
        if (isVisible()) {
            setVisible(false);
            onClosed.run();
        }
    }

    /** Types into the field, as the user would. */
    void type(String text) {
        field.setText(text);
    }

    /** The hits shown now, best first. */
    List<ElementSearch.Hit> shownHits() {
        return List.copyOf(hits.getItems());
    }

    /** Takes the hit selected, as Enter does. */
    void takeSelected() {
        take(hits.getSelectionModel().getSelectedItem());
    }

    private void keyPressed(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.DOWN || code == KeyCode.UP) {
            int size = hits.getItems().size();
            if (size > 0) {
                int index = hits.getSelectionModel().getSelectedIndex() + (code == KeyCode.DOWN ? 1 : -1);
                index = Math.max(0, Math.min(size - 1, index));
                hits.getSelectionModel().select(index);
                hits.scrollTo(Math.max(0, index - 4));
            }
            event.consume();
        } else if (code == KeyCode.ESCAPE) {
            close();
            event.consume();
        }
    }

    private void showHits() {
        ArchitectureNode rootNode = root.get();
        if (rootNode == null) {
            hits.getItems().clear();
        } else {
            if (rootNode != searchedRoot) {
                searchedRoot = rootNode;
                search = ElementSearch.of(rootNode);
            }
            hits.getItems().setAll(search.find(field.getText(), MAX_HITS));
        }
        boolean found = !hits.getItems().isEmpty();
        hits.setVisible(found);
        nothing.setVisible(!found && !field.getText().isBlank());
        nothing.setText("Nothing in this view is called \"" + field.getText().strip() + "\"");
        if (found) {
            hits.getSelectionModel().select(0);
            hits.scrollTo(0);
            hits.setPrefHeight(Math.min(hits.getItems().size(), 12) * ROW_HEIGHT + 4);
        }
    }

    private void take(ElementSearch.Hit hit) {
        if (hit == null) {
            return;
        }
        close();
        onFound.accept(hit.fullName());
    }

    /** Icon, name and the package the element lies in. */
    private static final class HitCell extends ListCell<ElementSearch.Hit> {
        @Override
        protected void updateItem(ElementSearch.Hit hit, boolean empty) {
            super.updateItem(hit, empty);
            setText(null);
            if (empty || hit == null) {
                setGraphic(null);
                return;
            }
            ArchitectureNode node = hit.node();
            boolean isClass = node.getType() == ArchitectureNode.NodeType.CLASS;
            FontIcon icon = new FontIcon(!isClass ? MaterialDesignP.PACKAGE_VARIANT_CLOSED
                    : node.isInterfaceType() ? MaterialDesignA.ALPHA_I_CIRCLE : MaterialDesignA.ALPHA_C_CIRCLE);
            icon.setIconColor(!isClass ? PACKAGE : node.isInterfaceType() ? INTERFACE : CLASS);
            icon.setIconSize(14);
            Label name = new Label(node.getSimpleName());
            name.getStyleClass().add("find-hit-name");
            Label location = new Label(ShownNames.shown(hit.location()));
            location.getStyleClass().add("find-hit-location");
            location.setMinWidth(0);
            HBox row = new HBox(6, icon, name, location);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
