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

import de.weigend.s202.ui.core.canvas.WhatIfUndoManager;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.ui.views.component.ComponentBox;
import de.weigend.s202.ui.core.graph.ArchitectureDragController;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.core.model.ScopeExtensionModel;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Scope-Erweiterung (Kontextmenü + Dialog), geplante Pakete der
 * Component-View und der Refactoring-Report (JSON aller Session-Änderungen).
 * Aus ArchitectureView extrahiert; Rückgriffe laufen über Callbacks.
 */
final class ScopeExtensionController {

    private final Consumer<String> status;
    private final Supplier<ArchitectureNode> currentRoot;
    private final Supplier<ArchitectureNode> scopeSource;
    private final Supplier<Window> dialogOwner;
    private final Consumer<ArchitectureNode> setArchitectureRoot;
    private final Consumer<String> selectByFullName;

    ScopeExtensionController(Consumer<String> status,
                             Supplier<ArchitectureNode> currentRoot,
                             Supplier<ArchitectureNode> scopeSource,
                             Supplier<Window> dialogOwner,
                             Consumer<ArchitectureNode> setArchitectureRoot,
                             Consumer<String> selectByFullName) {
        this.status = status;
        this.currentRoot = currentRoot;
        this.scopeSource = scopeSource;
        this.dialogOwner = dialogOwner;
        this.setArchitectureRoot = setArchitectureRoot;
        this.selectByFullName = selectByFullName;
    }

    /** Menü-Eintrag „Add to Scope…“ — der Canvas hängt ihn in sein Kontextmenü. */
    MenuItem addToScopeMenuItem() {
        MenuItem addToScope = new MenuItem("Add to Scope...");
        addToScope.setOnAction(action -> showScopeExtensionDialog());
        return addToScope;
    }

    /* ----- Kontextmenü ------------------------------------------------------- */

    /* ----- Scope-Erweiterung -------------------------------------------------- */

    private void showScopeExtensionDialog() {
        if (scopeSource.get() == null || currentRoot.get() == null) {
            return;
        }

        List<ScopeExtensionModel.Candidate> candidates =
                ScopeExtensionModel.candidates(scopeSource.get(), currentRoot.get());
        if (candidates.isEmpty()) {
            status.accept("Scope already contains all packages and classes");
            return;
        }

        Dialog<ScopeExtensionModel.Candidate> dialog = new Dialog<>();
        dialog.setTitle("Add to Scope");
        dialog.setHeaderText("Select a package or class");
        if (dialogOwner.get() != null) {
            dialog.initOwner(dialogOwner.get());
        }

        ButtonType addButtonType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        TextField filterField = new TextField();
        filterField.setPromptText("Filter packages/classes");

        ObservableList<ScopeExtensionModel.Candidate> items = FXCollections.observableArrayList(candidates);
        FilteredList<ScopeExtensionModel.Candidate> filteredItems = new FilteredList<>(items, item -> true);
        filterField.textProperty().addListener((obs, oldValue, newValue) -> {
            String query = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            filteredItems.setPredicate(item -> query.isEmpty()
                    || item.fullName().toLowerCase(Locale.ROOT).contains(query)
                    || item.kind().toLowerCase(Locale.ROOT).contains(query));
        });

        ListView<ScopeExtensionModel.Candidate> candidateList = new ListView<>(filteredItems);
        candidateList.setPrefWidth(620);
        candidateList.setPrefHeight(420);
        candidateList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ScopeExtensionModel.Candidate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        candidateList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY
                    && event.getClickCount() == 2
                    && candidateList.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(candidateList.getSelectionModel().getSelectedItem());
                dialog.close();
            }
        });

        javafx.scene.Node addButton = dialogPane.lookupButton(addButtonType);
        addButton.disableProperty().bind(candidateList.getSelectionModel().selectedItemProperty().isNull());

        VBox content = new VBox(8, filterField, candidateList);
        dialogPane.setContent(content);
        dialog.setResultConverter(button -> button == addButtonType
                ? candidateList.getSelectionModel().getSelectedItem()
                : null);

        Optional<ScopeExtensionModel.Candidate> selected = dialog.showAndWait();
        selected.ifPresent(this::addScopeCandidate);
    }

    private void addScopeCandidate(ScopeExtensionModel.Candidate candidate) {
        if (candidate == null || currentRoot.get() == null || scopeSource.get() == null) {
            return;
        }

        ArchitectureNode extendedRoot = ArchitectureNodeCloner.cloneTree(currentRoot.get());
        boolean added = ScopeExtensionModel.addToScope(
                extendedRoot,
                scopeSource.get(),
                candidate.fullName());
        if (!added) {
            status.accept("Scope already contains " + candidate.fullName());
            return;
        }

        setArchitectureRoot.accept(extendedRoot);
        selectByFullName.accept(candidate.fullName());
        status.accept("Scope extended: " + candidate.fullName());
    }

    /* ----- Geplante Pakete ----------------------------------------------------- */

    /* ----- Refactoring-Report ---------------------------------------------------- */
}
