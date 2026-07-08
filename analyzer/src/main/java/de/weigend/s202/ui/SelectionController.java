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

import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.PulseCoalescer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Selektion einer Architektur-View: das selectedFullName-Property, die
 * programmatische Auswahl (inkl. Scroll-into-View) und die Weitergabe von
 * Graph-Selektionen an die Host-Shell. Aus ArchitectureView extrahiert.
 */
final class SelectionController {

    private final Map<String, Node> elementRegistry;
    private final ScrollPane scrollPane;
    private final PulseCoalescer arrowsCoalescer;
    /** Markiert die Pfeile als veraltet (Neuzeichnen beim nächsten Flush). */
    private final Runnable invalidateLines;

    private final ReadOnlyStringWrapper selectedFullName = new ReadOnlyStringWrapper(null);
    private Consumer<String> nodeSelectionSink = fqn -> { /* no-op default */ };
    private boolean suppressSelectionSink = false;

    SelectionController(Map<String, Node> elementRegistry,
                        ScrollPane scrollPane,
                        PulseCoalescer arrowsCoalescer,
                        Runnable invalidateLines) {
        this.elementRegistry = elementRegistry;
        this.scrollPane = scrollPane;
        this.arrowsCoalescer = arrowsCoalescer;
        this.invalidateLines = invalidateLines;
    }

    ReadOnlyStringProperty selectedFullNameProperty() {
        return selectedFullName.getReadOnlyProperty();
    }

    String getSelectedFullName() {
        return selectedFullName.get();
    }

    void setOnNodeSelected(Consumer<String> sink) {
        this.nodeSelectionSink = sink != null ? sink : (fqn -> {});
    }

    /** Sink für Klick-Selektionen aus dem Graphen (LevelClassBox & Co.). */
    void handleGraphSelectionChanged(String fqn) {
        selectedFullName.set(fqn);
        invalidateLines.run();
        // Defer arrow redraw via the coalescer so the CSS layout pass
        // (border-width change on the selected box) finishes first —
        // direct drawDependencyArrows() here uses stale bounds.
        arrowsCoalescer.markDirty();
        if (!suppressSelectionSink && fqn != null) {
            nodeSelectionSink.accept(fqn);
        }
    }

    /**
     * Select the node (class or package) with the given full name (if present)
     * and scroll it into view. No-op if the name is unknown or the
     * architecture is not yet loaded. Idempotent — re-selecting the current
     * node does not toggle it off.
     */
    void selectByFullName(String fullName) {
        if (fullName == null) {
            return;
        }
        Node node = elementRegistry.get(fullName);
        if (node instanceof GraphSelection.Selectable target) {
            runWithoutSelectionSink(() -> GraphSelection.ensureSelected(target));
            // Defer scrolling until layout has settled; the box may have just
            // been created during a refresh and have unresolved bounds.
            Platform.runLater(() -> scrollToNodeIfNeeded(node));
            return;
        }
        // Tree-only node (e.g. a package skipped as a transparent passthrough
        // — "com", "de", … — that has no visible box in the chart). No visual
        // highlight to apply, but external observers (quality view, etc.) still
        // need the announcement. Clear any visible chart selection and update
        // the property directly.
        runWithoutSelectionSink(GraphSelection::clear);
        selectedFullName.set(fullName);
    }

    /** Auswahl nach einem Rebuild wiederherstellen, ohne den Viewport zu bewegen. */
    void restoreSelectionWithoutScrolling(String fullName) {
        Node node = elementRegistry.get(fullName);
        if (node instanceof GraphSelection.Selectable target) {
            runWithoutSelectionSink(() -> GraphSelection.ensureSelected(target));
            return;
        }
        if (node != null) {
            runWithoutSelectionSink(GraphSelection::clear);
            selectedFullName.set(fullName);
            invalidateLines.run();
            arrowsCoalescer.markDirty();
        }
    }

    private void runWithoutSelectionSink(Runnable action) {
        boolean wasSuppressing = suppressSelectionSink;
        suppressSelectionSink = true;
        try {
            action.run();
        } finally {
            suppressSelectionSink = wasSuppressing;
        }
    }

    private void scrollToNodeIfNeeded(Node target) {
        if (scrollPane == null || scrollPane.getContent() == null) {
            return;
        }
        Node content = scrollPane.getContent();
        Bounds contentBounds = content.getBoundsInLocal();
        Bounds targetInContent = content.sceneToLocal(target.localToScene(target.getBoundsInLocal()));

        double contentWidth = contentBounds.getWidth();
        double contentHeight = contentBounds.getHeight();
        Bounds viewport = scrollPane.getViewportBounds();
        if (viewport == null || contentWidth <= 0 || contentHeight <= 0) {
            return;
        }

        double overflowX = Math.max(0, contentWidth - viewport.getWidth());
        double overflowY = Math.max(0, contentHeight - viewport.getHeight());
        double viewMinX = contentBounds.getMinX() + scrollPane.getHvalue() * overflowX;
        double viewMinY = contentBounds.getMinY() + scrollPane.getVvalue() * overflowY;
        double viewMaxX = viewMinX + viewport.getWidth();
        double viewMaxY = viewMinY + viewport.getHeight();
        double margin = 24.0;

        if (overflowX > 0) {
            double targetMinX = targetInContent.getMinX();
            double targetMaxX = targetInContent.getMaxX();
            if (targetMinX < viewMinX + margin || targetMaxX > viewMaxX - margin) {
                double newViewMinX = nextVisibleStart(
                        targetMinX, targetMaxX, viewport.getWidth(), margin, viewMinX);
                scrollPane.setHvalue(clamp01((newViewMinX - contentBounds.getMinX()) / overflowX));
            }
        }
        if (overflowY > 0) {
            double targetMinY = targetInContent.getMinY();
            double targetMaxY = targetInContent.getMaxY();
            if (targetMinY < viewMinY + margin || targetMaxY > viewMaxY - margin) {
                double newViewMinY = nextVisibleStart(
                        targetMinY, targetMaxY, viewport.getHeight(), margin, viewMinY);
                scrollPane.setVvalue(clamp01((newViewMinY - contentBounds.getMinY()) / overflowY));
            }
        }
    }

    private static double nextVisibleStart(double targetMin,
                                           double targetMax,
                                           double viewportSize,
                                           double margin,
                                           double currentStart) {
        double targetSize = targetMax - targetMin;
        if (targetSize + margin * 2.0 >= viewportSize) {
            return targetMin - margin;
        }
        if (targetMin < currentStart + margin) {
            return targetMin - margin;
        }
        return targetMax - viewportSize + margin;
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
