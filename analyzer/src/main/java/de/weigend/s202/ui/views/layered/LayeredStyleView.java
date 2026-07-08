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
package de.weigend.s202.ui.views.layered;

import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.ui.core.canvas.ArchitectureTreeBuilder;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.spi.StyleView;
import de.weigend.s202.ui.core.spi.ViewServices;
import javafx.scene.layout.VBox;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Die Schichten-Ansicht als {@link StyleView}: das klassische Level-Row-
 * Layout direkt aus der Row-Engine, Verstöße = Aufwärtskanten, Verstoß-
 * Quelle ist die What-If-Architektur (die Ansicht ist editierbar).
 */
final class LayeredStyleView implements StyleView {

    private static final Set<ViolationKind> VIOLATION_KINDS = Set.of(ViolationKind.UPWARD);

    private final ViewServices services;

    LayeredStyleView(ViewServices services) {
        this.services = services;
    }

    @Override
    public ArchitectureKind kind() {
        return ArchitectureKind.LAYERED;
    }

    @Override
    public VBox buildTree(ArchitectureNode root, int maxDepth) {
        return builder().buildTree(root, maxDepth, services.skipTransparentTopLevelPackages().get());
    }

    @Override
    public void buildTreeAsync(ArchitectureNode root, int maxDepth,
                               ArchitectureTreeBuilder.ProgressSink progressSink,
                               Consumer<VBox> onComplete) {
        builder().buildTreeAsync(root, maxDepth,
                services.skipTransparentTopLevelPackages().get(), progressSink, onComplete);
    }

    private ArchitectureTreeBuilder builder() {
        return new ArchitectureTreeBuilder(services.elementRegistry(), services.selectionSink());
    }

    @Override
    public Set<ViolationKind> violationOverlayKinds() {
        return VIOLATION_KINDS;
    }

    @Override
    public boolean usesWhatIfViolationSource() {
        return true;
    }
}
