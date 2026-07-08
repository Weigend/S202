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
package de.weigend.s202.ui.views.hexagonal;

import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.HexagonalArchitecture;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.ui.core.canvas.ArchitectureTreeBuilder;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.spi.StyleView;
import de.weigend.s202.ui.core.spi.ViewServices;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Die Hexagonal-Ansicht als {@link StyleView}: radiale Ring-Projektion.
 * Hält den Expand-Zustand ihrer Paket-Overlays selbst (die Ansicht wird
 * bei jeder Annotations-Änderung vollständig neu gebaut) — vorher lag
 * diese Map als Hexagonal-Sonderwissen im Canvas.
 */
final class HexagonalStyleView implements StyleView {

    private static final Set<ViolationKind> VIOLATION_KINDS = Set.of(
            ViolationKind.HEXAGON_OUTWARD_DEPENDENCY,
            ViolationKind.HEXAGON_PORT_BYPASS);

    private final ViewServices services;

    /**
     * Expand/collapse state of the hexagonal package overlay, keyed by package
     * FQN. Held here (not in the UI nodes) because the hexagonal view is fully
     * rebuilt on every annotation change.
     */
    private final Map<String, Boolean> packageExpansionState = new HashMap<>();

    HexagonalStyleView(ViewServices services) {
        this.services = services;
    }

    @Override
    public ArchitectureKind kind() {
        return ArchitectureKind.HEXAGONAL;
    }

    @Override
    public VBox buildTree(ArchitectureNode root, int maxDepth) {
        return builder().buildTree(root, maxDepth);
    }

    @Override
    public void buildTreeAsync(ArchitectureNode root, int maxDepth,
                               ArchitectureTreeBuilder.ProgressSink progressSink,
                               Consumer<VBox> onComplete) {
        builder().buildTreeAsync(root, maxDepth, progressSink, onComplete);
    }

    private HexagonalArchitectureTreeBuilder builder() {
        return new HexagonalArchitectureTreeBuilder(
                services.elementRegistry(),
                services.selectionSink(),
                services.annotations().get(),
                services.architecture().get() instanceof HexagonalArchitecture hex ? hex : null,
                services.annotationsChanged(),
                services.markArrowsDirty(),
                packageExpansionState);
    }

    @Override
    public Set<ViolationKind> violationOverlayKinds() {
        return VIOLATION_KINDS;
    }
}
