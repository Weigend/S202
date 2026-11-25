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
package de.weigend.s202.ui.shell;

import de.weigend.s202.analysis.invariants.LayoutInvariantChecker;
import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.consistency.ArchitectureConsistencyDevHook;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import io.softwareecg.wfx.lookup.api.Lookup;

import java.util.List;
import java.util.Set;

/**
 * Computes everything an analysis consists of — levels, cycles, the tree,
 * metrics, invariants — from the dependencies that were read. One way for code
 * that was just read and for a project file: what can be computed is not
 * stored, so both always show the same picture.
 */
final class AnalysisBuilder {

    private final ArchitectureNodeBuilder architectureNodeBuilder = new ArchitectureNodeBuilder();
    private final LayoutInvariantChecker invariantChecker = new LayoutInvariantChecker();
    private final ProgressPublisher progress;

    /** @param rootNode {@code null} when there is nothing to show, the other results likewise */
    record AnalysisResult(DependencyModel rawModel, ArchitectureNode rootNode,
                          QualityMetrics metrics, DomainModel domainModel,
                          LayoutInvariantReport invariants,
                          Set<DependencyEdge> cycleBreakEdges) {}

    AnalysisBuilder(ProgressPublisher progress) {
        this.progress = progress;
    }

    /** Slow; not for the JavaFX thread. */
    AnalysisResult build(DependencyModel rawModel, List<String> sourcePaths) {
        if (rawModel.getAllClasses().isEmpty()) {
            return new AnalysisResult(rawModel, null, null, null, null, Set.of());
        }

        progress.progress("Calculating architectural levels...", 0.75);
        DomainModel calculated = requireDomainComputer().compute(rawModel);
        Set<DependencyEdge> cycleBreakEdges = calculated.getClassBackEdges();

        progress.progress("Building architecture tree...", 0.85);
        ArchitectureNode root = architectureNodeBuilder.build(calculated);
        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
        ArchitectureConsistencyDevHook.runIfEnabled(calculated, root);

        progress.progress("Preparing quality metrics...", 0.90);
        QualityMetrics metrics = QualityMetrics.compute(calculated);

        // Layout invariant check runs on the same background thread so
        // findings reach the FX thread together with the rest of the result.
        progress.progress("Verifying layout invariants...", 0.95);
        LayoutInvariantReport invariants = invariantChecker.check(calculated, rawModel, sourcePaths);
        return new AnalysisResult(rawModel, root, metrics, calculated, invariants, cycleBreakEdges);
    }

    private static DomainComputer requireDomainComputer() {
        DomainComputer computer = Lookup.lookup(DomainComputer.class);
        if (computer == null) {
            throw new IllegalStateException("No domain computer registered");
        }
        return computer;
    }
}
