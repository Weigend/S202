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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.Externals;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.DependencyMatrix;
import de.weigend.s202.domain.architecture.LayeredArchitecture;
import de.weigend.s202.ui.core.arrows.TangleEdgeScope;
import de.weigend.s202.ui.core.matrix.MatrixView;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.EdgeKindGroup;
import de.weigend.s202.ui.core.model.PlanState;
import de.weigend.s202.ui.core.model.WhatIfMove;
import io.softwareecg.wfx.lookup.api.Lookup;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Keeps the dependency matrix of a canvas at what its picture shows: the same
 * classes in the order of the same layers, with the same filters and the same
 * plan.
 */
final class MatrixController {

    /** Everything the matrix depends on; it is made anew when one of these changes. */
    private record Made(DomainModel model, ArchitectureNode root, PlanState plan, List<WhatIfMove> moves,
                        Set<EdgeKindGroup> hiddenKinds, TangleEdgeScope tangle, Set<String> open,
                        boolean externals) {
    }

    private final MatrixView view;
    private final ArchitectureProjectionModel projection;
    private final Supplier<ArchitectureNode> root;
    private final Supplier<PlanState> plan;
    private final Supplier<List<WhatIfMove>> moves;
    private final Supplier<Set<EdgeKindGroup>> hiddenKinds;
    private final Supplier<TangleEdgeScope> tangle;
    private final Supplier<EdgeCycleClassifier> cycles;
    private Made made;

    MatrixController(MatrixView view,
                     ArchitectureProjectionModel projection,
                     Supplier<ArchitectureNode> root,
                     Supplier<PlanState> plan,
                     Supplier<List<WhatIfMove>> moves,
                     Supplier<Set<EdgeKindGroup>> hiddenKinds,
                     Supplier<TangleEdgeScope> tangle,
                     Supplier<EdgeCycleClassifier> cycles) {
        this.view = view;
        this.projection = projection;
        this.root = root;
        this.plan = plan;
        this.moves = moves;
        this.hiddenKinds = hiddenKinds;
        this.tangle = tangle;
        this.cycles = cycles;
        view.setOnOpenChanged(this::refreshIfShown);
        view.sceneProperty().addListener((obs, was, isNow) -> refreshIfShown());
    }

    /** {@link #refresh()}, unless nobody looks at the matrix. */
    void refreshIfShown() {
        if (view.getScene() != null) {
            refresh();
        }
    }

    /** Makes the matrix if what it depends on has changed. */
    private void refresh() {
        DomainModel model = projection.getDomainModel();
        Architecture shown = projection.packageCycleArchitecture();
        if (model == null || root.get() == null || !(shown instanceof LayeredArchitecture layers)) {
            made = null;
            view.show(null, fqn -> false);
            return;
        }
        Made next = new Made(model, root.get(), plan.get(), plan.get().shown() ? moves.get() : List.of(),
                hiddenKinds.get(), tangle.get(), view.getOpen(), view.isShowingExternals());
        if (next.equals(made)) {
            return;
        }
        made = next;
        Set<String> classes = new HashSet<>();
        collectClasses(root.get(), classes);
        view.show(DependencyMatrix.of(new DependencyMatrix.Input(layers.rows(), classes, view.getOpen(), model,
                        cycles.get(), filter(next), next.plan().cuts(),
                        next.externals() ? Externals.of(projection.getRawDependencyModel()) : Externals.NONE),
                        Lookup.lookup(SCCFinder.class)),
                fqn -> model.getClass(fqn) != null && model.getClass(fqn).interfaceType);
    }

    private BiPredicate<String, String> filter(Made of) {
        return (from, to) -> (of.tangle() == null || of.tangle().draws(from, to))
                && EdgeKindGroup.shows(of.hiddenKinds(), projection.getRawDependencyModel(), from, to);
    }

    private static void collectClasses(ArchitectureNode node, Set<String> classes) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            classes.add(node.getFullName());
        }
        node.getChildren().forEach(child -> collectClasses(child, classes));
    }
}
