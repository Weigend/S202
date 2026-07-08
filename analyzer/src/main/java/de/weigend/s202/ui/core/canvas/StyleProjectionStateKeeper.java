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

import de.weigend.s202.ui.core.spi.StyleView;
import de.weigend.s202.ui.core.canvas.ZoomController;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.graph.PulseCoalescer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Erhält Viewport (Zoom + Scroll) und Expand-Zustände über einen
 * Stil-Projektion-Rebuild hinweg: Zustand vor dem Rebuild einfangen, nach
 * dem Rebuild (über mehrere FX-Pulse verteilt) wiederherstellen. Aus
 * ArchitectureCanvas extrahiert.
 */
final class StyleProjectionStateKeeper {

    record ViewState(
            double zoomFactor,
            double horizontalScroll,
            double verticalScroll,
            Map<String, Boolean> packageExpansion,
            Map<String, Boolean> packageExpansionByFqn,
            Map<String, Object> styleState) {

        ViewState {
            packageExpansion = Map.copyOf(packageExpansion);
            packageExpansionByFqn = Map.copyOf(packageExpansionByFqn);
            styleState = Map.copyOf(styleState);
        }
    }

    private final ScrollPane scrollPane;
    /** ZoomController wird pro Root-Build ersetzt — daher Supplier. */
    private final Supplier<ZoomController> zoomController;
    private final Supplier<VBox> rootContainer;
    private final Supplier<Double> zoomFactor;
    private final PulseCoalescer arrowsCoalescer;
    private final Supplier<StyleView> styleView;

    StyleProjectionStateKeeper(ScrollPane scrollPane,
                               Supplier<ZoomController> zoomController,
                               Supplier<VBox> rootContainer,
                               Supplier<Double> zoomFactor,
                               PulseCoalescer arrowsCoalescer,
                               Supplier<StyleView> styleView) {
        this.scrollPane = scrollPane;
        this.zoomController = zoomController;
        this.rootContainer = rootContainer;
        this.zoomFactor = zoomFactor;
        this.arrowsCoalescer = arrowsCoalescer;
        this.styleView = styleView;
    }

    ViewState capture() {
        Map<String, Boolean> packageExpansion = new HashMap<>();
        Map<String, Boolean> packageExpansionByFqn = new HashMap<>();
        Map<String, Object> styleState = new HashMap<>();
        collectExpansionState(
                rootContainer.get(),
                packageExpansion,
                packageExpansionByFqn,
                styleState);
        return new ViewState(
                zoomFactor.get(),
                scrollPane == null ? 0.0 : scrollPane.getHvalue(),
                scrollPane == null ? 0.0 : scrollPane.getVvalue(),
                packageExpansion,
                packageExpansionByFqn,
                styleState);
    }

    private void collectExpansionState(Node node,
                                       Map<String, Boolean> packageExpansion,
                                       Map<String, Boolean> packageExpansionByFqn,
                                       Map<String, Object> styleState) {
        if (node == null) {
            return;
        }
        styleView.get().collectStyleExpansion(node, packageExpansionByFqn, styleState);
        if (node instanceof LevelPackageBox pkg && pkg.getFullName() != null) {
            packageExpansion.put(packageExpansionKey(pkg), pkg.isExpanded());
            packageExpansionByFqn.putIfAbsent(pkg.getFullName(), pkg.isExpanded());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectExpansionState(child, packageExpansion, packageExpansionByFqn, styleState);
            }
        }
    }

    void restoreExpansionState(Node node, ViewState state) {
        if (node == null) {
            return;
        }
        styleView.get().restoreStyleExpansion(
                node, state.packageExpansionByFqn(), state.styleState());
        if (node instanceof LevelPackageBox pkg && pkg.getFullName() != null) {
            Boolean expanded = state.packageExpansion().get(packageExpansionKey(pkg));
            if (expanded == null) {
                // A package moved between implementation and API during this
                // refresh. Its FQN fallback keeps the state across that role change.
                expanded = state.packageExpansionByFqn().get(pkg.getFullName());
            }
            if (expanded != null) {
                pkg.setExpanded(expanded);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                restoreExpansionState(child, state);
            }
        }
    }

    void restoreViewportState(ViewState state) {
        ZoomController zc = zoomController.get();
        if (zc != null) {
            zc.setZoom(state.zoomFactor());
        }
        if (scrollPane != null) {
            scrollPane.setHvalue(state.horizontalScroll());
            scrollPane.setVvalue(state.verticalScroll());
        }
    }

    void scheduleDeferredViewportRestore(ViewState state) {
        ZoomController restoredZoomController = zoomController.get();
        Node restoredScrollContent = scrollPane == null ? null : scrollPane.getContent();
        scheduleDeferredViewportRestore(state, restoredZoomController, restoredScrollContent, 2);
    }

    private void scheduleDeferredViewportRestore(ViewState state,
                                                 ZoomController restoredZoomController,
                                                 Node restoredScrollContent,
                                                 int remainingPulses) {
        Platform.runLater(() -> {
            if (zoomController.get() != restoredZoomController
                    || scrollPane == null
                    || scrollPane.getContent() != restoredScrollContent) {
                return;
            }
            restoreViewportState(state);
            if (remainingPulses > 1) {
                scheduleDeferredViewportRestore(
                        state,
                        restoredZoomController,
                        restoredScrollContent,
                        remainingPulses - 1);
                return;
            }
            arrowsCoalescer.markDirty();
        });
    }

    private static String packageExpansionKey(LevelPackageBox pkg) {
        String role = de.weigend.s202.ui.core.graph.BoxTags.isApiElement(pkg) ? "api:" : "regular:";
        return role + pkg.getFullName();
    }
}
