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
package de.weigend.s202.ui;

import de.weigend.s202.domain.architecture.WhatIfArchitecture;
import de.weigend.s202.ui.component.ComponentBox;
import de.weigend.s202.ui.core.graph.ArchitectureDragController;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.graph.PulseCoalescer;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Das What-If-Editieren einer Architektur-View: DnD-Drop-Verarbeitung
 * (inkl. Component-API-Zuordnung), Undo/Redo der Moves, das Nachziehen der
 * Szene und die orange „moved“-Dekoration. Aus ArchitectureView extrahiert;
 * alle Rückgriffe laufen über Callbacks — keine Abhängigkeit auf die View.
 */
final class WhatIfEditController {

    /** Szene-Wurzel dieser View — nur für den „gehört der Drop zu uns?“-Check. */
    private final Node viewRoot;
    private final Map<String, Node> elementRegistry;
    private final PulseCoalescer arrowsCoalescer;
    private final Consumer<String> status;
    private final Supplier<WhatIfArchitecture> whatIf;
    private final Supplier<ArchitectureViewStyle> viewStyle;
    private final Supplier<VBox> rootContainer;
    private final ComponentApiAnnotator apiAnnotator;
    /** Baut das Layout neu auf (Undo spielt danach die verbliebenen Moves ein). */
    private final Runnable resetVisualLayout;
    private final BooleanProperty showWhatIfViolations;

    /** Sinnvoll benannte Teilmenge der API-Drop-Callbacks in die View. */
    interface ComponentApiAnnotator {
        void addToApi(String fqn);

        void removeFromApi(String fqn);
    }

    // What-If layer: drives the orange "moved" glow on the affected boxes.
    // Cleared on every fresh analysis. The structural truth of where each box
    // currently sits lives in the WhatIfArchitecture; this set only tracks
    // "user touched it" for the cosmetic decoration.
    private final Set<String> movedFqns = new HashSet<>();
    private ArchitectureDragController.DropListener dropListener;

    private final WhatIfUndoManager undoManager = new WhatIfUndoManager();

    WhatIfEditController(Node viewRoot,
                         Map<String, Node> elementRegistry,
                         PulseCoalescer arrowsCoalescer,
                         Consumer<String> status,
                         Supplier<WhatIfArchitecture> whatIf,
                         Supplier<ArchitectureViewStyle> viewStyle,
                         Supplier<VBox> rootContainer,
                         ComponentApiAnnotator apiAnnotator,
                         Runnable resetVisualLayout,
                         BooleanProperty showWhatIfViolations) {
        this.viewRoot = viewRoot;
        this.elementRegistry = elementRegistry;
        this.arrowsCoalescer = arrowsCoalescer;
        this.status = status;
        this.whatIf = whatIf;
        this.viewStyle = viewStyle;
        this.rootContainer = rootContainer;
        this.apiAnnotator = apiAnnotator;
        this.resetVisualLayout = resetVisualLayout;
        this.showWhatIfViolations = showWhatIfViolations;
    }

    WhatIfUndoManager undoManager() {
        return undoManager;
    }

    void clearMovedFqns() {
        movedFqns.clear();
    }

    void ensureDropListenerRegistered() {
        if (dropListener != null) {
            return;
        }
        dropListener = this::handleWhatIfDrop;
        ArchitectureDragController.addDropListener(dropListener);
    }

    private void handleWhatIfDrop(Node movedSource, HBox destinationRow, boolean wasNewRow) {
        if (!isInsideThisView(movedSource)) {
            return;
        }
        if (!(movedSource instanceof GraphSelection.Selectable selectable)) {
            return;
        }
        String movedFqcn = selectable.getFullName();
        if (movedFqcn == null || movedFqcn.isEmpty()) {
            return;
        }
        if (viewStyle.get() == ArchitectureViewStyle.COMPONENT
                && handleComponentApiDrop(movedSource, movedFqcn, destinationRow)) {
            return;
        }
        String destinationContainerFqcn = resolveDestinationContainer(destinationRow);
        if (destinationContainerFqcn == null) {
            return;
        }
        WhatIfArchitecture wif = whatIf.get();
        if (wif == null) {
            return;
        }
        if (!(destinationRow.getParent() instanceof VBox stack)) {
            return;
        }
        int rowIndex = stack.getChildren().indexOf(destinationRow);
        if (rowIndex < 0) {
            return;
        }
        if (wasNewRow) {
            undoManager.record(new WhatIfUndoManager.Move.AsNewRow(movedFqcn, destinationContainerFqcn, rowIndex));
            wif.moveElementAsNewRow(movedFqcn, destinationContainerFqcn, rowIndex);
        } else {
            int colIndex = destinationRow.getChildren().indexOf(movedSource);
            if (colIndex < 0) {
                return;
            }
            undoManager.record(new WhatIfUndoManager.Move.InRow(movedFqcn, destinationContainerFqcn, rowIndex, colIndex));
            wif.moveElement(movedFqcn, destinationContainerFqcn, rowIndex, colIndex);
        }
        movedFqns.add(movedFqcn);
        arrowsCoalescer.markDirty();
        status.accept(buildWhatIfStatusMessage(movedFqcn, destinationContainerFqcn));
    }

    private boolean handleComponentApiDrop(Node movedSource, String movedFqcn, HBox destinationRow) {
        boolean apiDestination = ComponentBox.isApiDropTarget(destinationRow);
        boolean apiSource = ComponentBox.isApiElement(movedSource);
        if (!apiDestination && !apiSource) {
            return false;
        }
        if (apiDestination && apiSource) {
            arrowsCoalescer.markDirty();
            return true;
        }
        if (apiDestination) {
            apiAnnotator.addToApi(movedFqcn);
        } else {
            apiAnnotator.removeFromApi(movedFqcn);
        }
        return true;
    }

    private boolean isInsideThisView(Node node) {
        Node n = node;
        while (n != null) {
            if (n == viewRoot) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    /**
     * Resolve the static fqcn of the package container the drop landed in.
     * Walks up the scene graph from the destination row: the first enclosing
     * {@link LevelPackageBox} wins. If the drop lands at top level (no
     * enclosing package box), the row stack is tagged with the effective
     * root's fqcn by the tree builder, but the {@link WhatIfArchitecture}
     * uses {@code ""} for its root regardless of any transparent passthroughs
     * the builder skipped, so the empty string is what we return here.
     */
    private static String resolveDestinationContainer(Node row) {
        Node n = row == null ? null : row.getParent();
        while (n != null) {
            if (n instanceof LevelPackageBox lpb) {
                String fqcn = lpb.getFullName();
                return fqcn == null ? "" : fqcn;
            }
            if (n.getProperties().get("s202.whatif.rootFqcn") instanceof String) {
                return "";
            }
            n = n.getParent();
        }
        return null;
    }

    private String buildWhatIfStatusMessage(String movedFqcn, String destinationContainerFqcn) {
        String parentLabel = destinationContainerFqcn.isEmpty() ? "<root>" : destinationContainerFqcn;
        return String.format("What-If: %s → %s — marked as moved", simple(movedFqcn), parentLabel);
    }

    /* ----- Undo / Redo ------------------------------------------------------ */

    void undo() {
        if (whatIf.get() == null) return;
        List<WhatIfUndoManager.Move> remaining = undoManager.decrement();
        if (remaining == null) return;
        boolean violations = showWhatIfViolations.get();
        resetVisualLayout.run();
        remaining.forEach(this::applyMoveToScene);
        showWhatIfViolations.set(violations);
        arrowsCoalescer.markDirty();
    }

    void redo() {
        if (whatIf.get() == null) return;
        WhatIfUndoManager.Move m = undoManager.increment();
        if (m == null) return;
        applyMoveToScene(m);
        arrowsCoalescer.markDirty();
    }

    private VBox findRowStack(String containerFqn) {
        if (containerFqn == null || containerFqn.isEmpty()) {
            return rootContainer.get();
        }
        Node container = elementRegistry.get(containerFqn);
        return container instanceof LevelPackageBox lpb ? lpb.getContentContainer() : null;
    }

    private void applyMoveToScene(WhatIfUndoManager.Move move) {
        Node node = elementRegistry.get(move.fqn());
        if (node == null) return;
        VBox stack = findRowStack(move.containerFqn());
        if (stack == null) return;
        WhatIfArchitecture wif = whatIf.get();
        if (wif == null) return;

        if (node.getParent() instanceof HBox srcRow) {
            srcRow.getChildren().remove(node);
            if (srcRow.getChildren().isEmpty() && srcRow.getParent() instanceof VBox v) {
                v.getChildren().remove(srcRow);
            }
        }

        if (move instanceof WhatIfUndoManager.Move.AsNewRow asNewRow) {
            HBox newRow = new HBox(8);
            newRow.setMaxWidth(Double.MAX_VALUE);
            newRow.setMaxHeight(Double.MAX_VALUE);
            newRow.setAlignment(Pos.CENTER);
            newRow.setStyle("-fx-background-color: transparent;");
            VBox.setVgrow(newRow, Priority.ALWAYS);
            ArchitectureDragController.markAsRow(newRow);
            int idx = Math.max(0, Math.min(asNewRow.rowIndex(), stack.getChildren().size()));
            stack.getChildren().add(idx, newRow);
            newRow.getChildren().add(node);
            wif.moveElementAsNewRow(move.fqn(), move.containerFqn(), asNewRow.rowIndex());
        } else if (move instanceof WhatIfUndoManager.Move.InRow inRow) {
            while (stack.getChildren().size() <= inRow.rowIndex()) {
                HBox gap = new HBox(8);
                ArchitectureDragController.markAsRow(gap);
                stack.getChildren().add(gap);
            }
            HBox targetRow = (HBox) stack.getChildren().get(inRow.rowIndex());
            int col = Math.max(0, Math.min(inRow.colIndex(), targetRow.getChildren().size()));
            targetRow.getChildren().add(col, node);
            wif.moveElement(move.fqn(), move.containerFqn(), inRow.rowIndex(), inRow.colIndex());
        }

        movedFqns.add(move.fqn());
    }

    /** Orange „moved“-Dekoration mit dem aktuellen movedFqns-Stand abgleichen. */
    void applyVirtuallyMovedDecorations() {
        for (Map.Entry<String, Node> entry : elementRegistry.entrySet()) {
            String fqcn = entry.getKey();
            boolean moved = movedFqns.contains(fqcn);
            Node node = entry.getValue();
            if (node instanceof LevelClassBox cls) {
                cls.setVirtuallyMoved(moved);
            } else if (node instanceof LevelPackageBox pkg) {
                pkg.setVirtuallyMoved(moved);
            }
        }
    }

    private static String simple(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
