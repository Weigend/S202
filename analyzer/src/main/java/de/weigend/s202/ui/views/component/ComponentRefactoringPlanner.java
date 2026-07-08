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
package de.weigend.s202.ui.views.component;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.ui.core.canvas.WhatIfUndoManager;
import de.weigend.s202.ui.core.graph.ArchitectureDragController;
import de.weigend.s202.ui.core.spi.ViewServices;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Die Refactoring-Planung der Component-Ansicht: geplante (virtuelle)
 * Pakete samt Dialogen und Szene-Injektion sowie der Refactoring-Report
 * (JSON aller Session-Änderungen). Vorher als Teil des Canvas-Controllers
 * ScopeAndReport — jetzt vollständig Component-fachlich.
 */
final class ComponentRefactoringPlanner {

    private final ViewServices services;
    private final LinkedHashMap<String, String> plannedPackageNames = new LinkedHashMap<>();

    ComponentRefactoringPlanner(ViewServices services) {
        this.services = services;
    }

    boolean hasPlannedPackages() {
        return !plannedPackageNames.isEmpty();
    }

    List<MenuItem> contextMenuItems() {
        MenuItem addPkg = new MenuItem("Add Planned Package...");
        addPkg.setOnAction(action -> showAddPlannedPackageDialog());
        MenuItem reportItem = new MenuItem("Refactoring Report...");
        reportItem.setOnAction(action -> showRefactoringReport());
        return List.of(addPkg, reportItem);
    }

    /* ----- Geplante Pakete ----------------------------------------------------- */

    private void showAddPlannedPackageDialog() {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("Add Planned Package");
        dialog.setHeaderText("Enter a name for the new planned package");
        dialog.setContentText("Package name:");
        if (services.dialogOwner().get() != null) {
            dialog.initOwner(services.dialogOwner().get());
        }
        dialog.showAndWait()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .ifPresent(name -> {
                    String fqn = "virtual-pkg-" + java.util.UUID.randomUUID().toString().replace("-", "");
                    plannedPackageNames.put(fqn, name);
                    services.refreshView().accept("Added planned package: " + name);
                });
    }

    private void showRenamePlannedPackageDialog(String fqn) {
        String currentName = plannedPackageNames.getOrDefault(fqn, "");
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(currentName);
        dialog.setTitle("Rename Package");
        dialog.setHeaderText("Enter a new name for the planned package");
        dialog.setContentText("Package name:");
        if (services.dialogOwner().get() != null) {
            dialog.initOwner(services.dialogOwner().get());
        }
        dialog.showAndWait()
                .map(String::trim)
                .filter(name -> !name.isEmpty() && !name.equals(currentName))
                .ifPresent(name -> {
                    plannedPackageNames.put(fqn, name);
                    services.refreshView().accept("Renamed planned package to: " + name);
                });
    }

    private void deletePlannedPackage(String fqn) {
        String name = plannedPackageNames.remove(fqn);
        if (name != null) {
            services.refreshView().accept("Deleted planned package: " + name);
        }
    }

    void injectPlannedPackagesIntoScene(VBox topLevelContainer) {
        HBox plannedRow = new HBox(10);
        plannedRow.setMaxWidth(Double.MAX_VALUE);
        plannedRow.setAlignment(javafx.geometry.Pos.CENTER);
        VBox.setVgrow(plannedRow, Priority.ALWAYS);
        ArchitectureDragController.markAsRow(plannedRow);

        for (Map.Entry<String, String> entry : plannedPackageNames.entrySet()) {
            String fqn = entry.getKey();
            String displayName = entry.getValue();
            ComponentBox box = new ComponentBox(displayName, fqn, 0);
            box.setSelectionChangeSink(services.selectionSink());
            box.setCustomStyles(
                    "-fx-background-color: #f0f7e8; -fx-border-color: #4a8a3a; -fx-border-width: 2;"
                  + " -fx-border-style: dashed; -fx-background-radius: 7; -fx-border-radius: 7;",
                    "-fx-background-color: #f0f7e8; -fx-border-color: #ff8a00; -fx-border-width: 3;"
                  + " -fx-border-style: dashed; -fx-background-radius: 7; -fx-border-radius: 7;");
            attachPlannedPackageContextMenu(box, fqn);
            services.elementRegistry().put(fqn, box);
            HBox.setHgrow(box, Priority.ALWAYS);
            plannedRow.getChildren().add(box);
        }

        topLevelContainer.getChildren().add(plannedRow);
    }

    private void attachPlannedPackageContextMenu(ComponentBox box, String fqn) {
        box.setOnContextMenuRequested(event -> {
            MenuItem rename = new MenuItem("Rename Package...");
            rename.setOnAction(a -> showRenamePlannedPackageDialog(fqn));
            MenuItem delete = new MenuItem("Delete Package");
            delete.setOnAction(a -> deletePlannedPackage(fqn));
            new ContextMenu(rename, delete).show(box, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    /* ----- Refactoring-Report ---------------------------------------------------- */

    private void showRefactoringReport() {
        String json = buildRefactoringReportJson();

        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(json);
        area.setEditable(false);
        area.setWrapText(false);
        area.setFont(javafx.scene.text.Font.font("Monospaced", 12));
        area.setPrefRowCount(28);
        area.setPrefColumnCount(80);

        javafx.scene.control.ButtonType copyButton = new javafx.scene.control.ButtonType(
                "Copy to Clipboard", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        javafx.scene.control.ButtonType closeButton = javafx.scene.control.ButtonType.CLOSE;

        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Refactoring Report");
        dialog.setHeaderText("All architecture changes made in this session — paste into an AI prompt.");
        if (services.dialogOwner().get() != null) {
            dialog.initOwner(services.dialogOwner().get());
        }
        dialog.getDialogPane().getButtonTypes().addAll(copyButton, closeButton);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().setPrefSize(820, 560);

        javafx.scene.Node copyNode = dialog.getDialogPane().lookupButton(copyButton);
        copyNode.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(json);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            e.consume();
        });

        dialog.showAndWait();
    }

    private String buildRefactoringReportJson() {
        // Deduplicate moves: keep only the last move per FQN (latest destination wins).
        LinkedHashMap<String, WhatIfUndoManager.Move> lastMove = new LinkedHashMap<>();
        for (WhatIfUndoManager.Move m : services.whatIfMoves().get()) {
            lastMove.put(m.fqn(), m);
        }

        ArchitectureAnnotations ann = services.annotations().get();
        List<String> apiAdded = new java.util.ArrayList<>(ann.componentApiIncludes());
        List<String> apiRemoved = new java.util.ArrayList<>(ann.componentApiExcludes());
        Set<DependencyEdge> cuts = services.appliedCutEdges().get();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"tool\": \"Structure202\",\n");
        sb.append("  \"view\": \"Component View\",\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"plannedPackages\": ").append(plannedPackageNames.size()).append(",\n");
        sb.append("    \"movedElements\": ").append(lastMove.size()).append(",\n");
        sb.append("    \"cutDependencies\": ").append(cuts.size()).append(",\n");
        sb.append("    \"apiAdded\": ").append(apiAdded.size()).append(",\n");
        sb.append("    \"apiRemoved\": ").append(apiRemoved.size()).append("\n");
        sb.append("  },\n");
        sb.append("  \"changes\": [\n");

        List<String> entries = new java.util.ArrayList<>();

        for (String displayName : plannedPackageNames.values()) {
            entries.add("    {\n"
                    + "      \"action\": \"CREATE_PACKAGE\",\n"
                    + "      \"name\": " + jsonStr(displayName) + ",\n"
                    + "      \"note\": \"Planned package for cycle-free refactoring\"\n"
                    + "    }");
        }

        for (WhatIfUndoManager.Move m : lastMove.values()) {
            String dest = m.containerFqn() == null || m.containerFqn().isEmpty()
                    ? "(top-level)" : m.containerFqn();
            entries.add("    {\n"
                    + "      \"action\": \"MOVE\",\n"
                    + "      \"element\": " + jsonStr(m.fqn()) + ",\n"
                    + "      \"toPackage\": " + jsonStr(dest) + ",\n"
                    + "      \"toRow\": " + m.rowIndex() + ",\n"
                    + "      \"note\": \"Move class/package to new position to eliminate layer violation\"\n"
                    + "    }");
        }

        for (DependencyEdge e : cuts) {
            entries.add("    {\n"
                    + "      \"action\": \"CUT_DEPENDENCY\",\n"
                    + "      \"from\": " + jsonStr(e.from()) + ",\n"
                    + "      \"to\": " + jsonStr(e.to()) + ",\n"
                    + "      \"note\": \"Remove this direct dependency to break the tangle\"\n"
                    + "    }");
        }

        for (String fqn : apiAdded) {
            entries.add("    {\n"
                    + "      \"action\": \"ADD_TO_API\",\n"
                    + "      \"element\": " + jsonStr(fqn) + ",\n"
                    + "      \"note\": \"Expose this element as part of the component's public API\"\n"
                    + "    }");
        }

        for (String fqn : apiRemoved) {
            entries.add("    {\n"
                    + "      \"action\": \"REMOVE_FROM_API\",\n"
                    + "      \"element\": " + jsonStr(fqn) + ",\n"
                    + "      \"note\": \"Remove this element from the public API surface\"\n"
                    + "    }");
        }

        sb.append(String.join(",\n", entries));
        if (!entries.isEmpty()) {
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
