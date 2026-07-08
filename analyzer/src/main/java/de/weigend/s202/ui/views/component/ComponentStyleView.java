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

import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.ui.core.canvas.ArchitectureTreeBuilder;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.spi.StyleView;
import de.weigend.s202.ui.core.spi.ViewServices;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Die Component-Ansicht als {@link StyleView}: API-/Implementierungs-Boxen
 * aus der Komponenten-Projektion, komponentenspezifische Verstoß-Arten und
 * der Erhalt der API-/Implementierungs-Expansion über Rebuilds.
 */
final class ComponentStyleView implements StyleView {

    private static final Set<ViolationKind> VIOLATION_KINDS = Set.of(
            ViolationKind.COMPONENT_API_BYPASS,
            ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION,
            ViolationKind.COMPONENT_INTERNAL_LAYER_BREAK);

    /** Expand-Zustand einer Komponenten-Box (Implementierung + API-Bereich). */
    private record ComponentExpansionState(boolean expanded, boolean apiExpanded) {}

    private final ViewServices services;
    private final ComponentRefactoringPlanner planner;

    ComponentStyleView(ViewServices services) {
        this.services = services;
        this.planner = new ComponentRefactoringPlanner(services);
    }

    @Override
    public ArchitectureKind kind() {
        return ArchitectureKind.COMPONENT;
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

    private ComponentArchitectureTreeBuilder builder() {
        // Expand/Collapse der Komponenten-Boxen stößt das Pfeil-Overlay an —
        // vorher setzte der Canvas diesen (statischen) Callback für alle Stile.
        ComponentBox.setOnExpandChangeCallback(services.markArrowsDirty());
        return new ComponentArchitectureTreeBuilder(
                services.elementRegistry(),
                services.selectionSink(),
                services.annotations().get(),
                services.rawDependencyModel().get(),
                services.architecture().get() instanceof ComponentArchitecture component ? component : null,
                services.annotationsChanged());
    }

    @Override
    public Set<ViolationKind> violationOverlayKinds() {
        return VIOLATION_KINDS;
    }

    @Override
    public void afterContentBuilt(VBox topLevelContainer) {
        if (planner.hasPlannedPackages()) {
            planner.injectPlannedPackagesIntoScene(topLevelContainer);
        }
    }

    @Override
    public List<MenuItem> contextMenuItems() {
        return planner.contextMenuItems();
    }

    @Override
    public void collectStyleExpansion(Node node,
                                      Map<String, Boolean> byFqn,
                                      Map<String, Object> styleState) {
        if (node instanceof ComponentBox component) {
            styleState.put(
                    component.getFullName(),
                    new ComponentExpansionState(component.isExpanded(), component.isApiExpanded()));
            byFqn.putIfAbsent(component.getFullName(), component.isExpanded());
        }
    }

    @Override
    public void restoreStyleExpansion(Node node,
                                      Map<String, Boolean> byFqn,
                                      Map<String, Object> styleState) {
        if (node instanceof ComponentBox component) {
            Object state = styleState.get(component.getFullName());
            if (state instanceof ComponentExpansionState expansion) {
                component.setExpanded(expansion.expanded());
                component.setApiExpanded(expansion.apiExpanded());
            } else {
                Boolean expanded = byFqn.get(component.getFullName());
                if (expanded != null) {
                    component.setExpanded(expanded);
                }
            }
        }
    }
}
