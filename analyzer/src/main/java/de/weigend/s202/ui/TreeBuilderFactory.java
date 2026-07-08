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

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.HexagonalArchitecture;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.canvas.ArchitectureTreeBuilder;
import de.weigend.s202.ui.views.component.ComponentArchitectureTreeBuilder;
import de.weigend.s202.ui.views.hexagonal.HexagonalArchitectureTreeBuilder;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Wählt und konfiguriert den zum View-Stil passenden Tree-Builder
 * (Layered / Component / Hexagonal), synchron und asynchron. Aus
 * ArchitectureView extrahiert.
 */
final class TreeBuilderFactory {

    private final Map<String, Node> elementRegistry;
    private final Consumer<String> graphSelectionSink;
    private final Supplier<ArchitectureViewStyle> viewStyle;
    private final Supplier<ArchitectureAnnotations> annotations;
    private final Supplier<DependencyModel> rawDependencyModel;
    private final Supplier<Architecture> architecture;
    /** Gemeinsamer Handler für Annotations-Änderungen aus Component-/Hexagonal-Builder. */
    private final BiConsumer<ArchitectureAnnotations, String> annotationsChanged;
    private final Runnable markArrowsDirty;
    private final Map<String, Boolean> hexagonalPackageExpansionState;
    private final Supplier<Boolean> skipTransparentTopLevelPackages;

    TreeBuilderFactory(Map<String, Node> elementRegistry,
                       Consumer<String> graphSelectionSink,
                       Supplier<ArchitectureViewStyle> viewStyle,
                       Supplier<ArchitectureAnnotations> annotations,
                       Supplier<DependencyModel> rawDependencyModel,
                       Supplier<Architecture> architecture,
                       BiConsumer<ArchitectureAnnotations, String> annotationsChanged,
                       Runnable markArrowsDirty,
                       Map<String, Boolean> hexagonalPackageExpansionState,
                       Supplier<Boolean> skipTransparentTopLevelPackages) {
        this.elementRegistry = elementRegistry;
        this.graphSelectionSink = graphSelectionSink;
        this.viewStyle = viewStyle;
        this.annotations = annotations;
        this.rawDependencyModel = rawDependencyModel;
        this.architecture = architecture;
        this.annotationsChanged = annotationsChanged;
        this.markArrowsDirty = markArrowsDirty;
        this.hexagonalPackageExpansionState = hexagonalPackageExpansionState;
        this.skipTransparentTopLevelPackages = skipTransparentTopLevelPackages;
    }

    VBox buildTree(ArchitectureNode rootNode, int maxDepth) {
        ArchitectureViewStyle style = viewStyle.get();
        if (style == ArchitectureViewStyle.COMPONENT) {
            return componentBuilder().buildTree(rootNode, maxDepth);
        }
        if (style == ArchitectureViewStyle.HEXAGONAL) {
            return hexagonalBuilder().buildTree(rootNode, maxDepth);
        }
        return layeredBuilder().buildTree(rootNode, maxDepth, skipTransparentTopLevelPackages.get());
    }

    void buildTreeAsync(ArchitectureNode rootNode,
                        int maxDepth,
                        ArchitectureTreeBuilder.ProgressSink progressSink,
                        Consumer<VBox> onComplete) {
        ArchitectureViewStyle style = viewStyle.get();
        if (style == ArchitectureViewStyle.COMPONENT) {
            componentBuilder().buildTreeAsync(rootNode, maxDepth, progressSink, onComplete);
            return;
        }
        if (style == ArchitectureViewStyle.HEXAGONAL) {
            hexagonalBuilder().buildTreeAsync(rootNode, maxDepth, progressSink, onComplete);
            return;
        }
        layeredBuilder().buildTreeAsync(rootNode, maxDepth,
                skipTransparentTopLevelPackages.get(),
                progressSink,
                onComplete);
    }

    private ArchitectureTreeBuilder layeredBuilder() {
        return new ArchitectureTreeBuilder(elementRegistry, graphSelectionSink);
    }

    private ComponentArchitectureTreeBuilder componentBuilder() {
        return new ComponentArchitectureTreeBuilder(
                elementRegistry,
                graphSelectionSink,
                annotations.get(),
                rawDependencyModel.get(),
                architecture.get() instanceof ComponentArchitecture component ? component : null,
                annotationsChanged);
    }

    private HexagonalArchitectureTreeBuilder hexagonalBuilder() {
        return new HexagonalArchitectureTreeBuilder(
                elementRegistry,
                graphSelectionSink,
                annotations.get(),
                architecture.get() instanceof HexagonalArchitecture hex ? hex : null,
                annotationsChanged,
                markArrowsDirty,
                hexagonalPackageExpansionState);
    }
}
