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
package de.weigend.s202.ui.core.platform;

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.report.text.RefactoringReportInput;
import de.weigend.s202.report.text.RefactoringReportInput.ElementMove;
import de.weigend.s202.report.text.RefactoringReportInput.ViewChanges;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Was der Benutzer an einem Dokument geplant hat, über alle seine Tabs hinweg:
 * Verschiebungen und geplante Pakete leben in den Tabs, Komponenten-Zuordnung
 * und Preview-Cuts im Dokument. Hier kommt beides zusammen — für den Report
 * und um es zu verwerfen.
 */
@Singleton
public final class Refactorings {

    /** Wie viel geplant ist, je Art. */
    public record Counts(int moves, int plannedPackages, int mappingChanges, int cuts) {
        public boolean isEmpty() {
            return moves + plannedPackages + mappingChanges + cuts == 0;
        }
    }

    private final ArchitectureViewManager viewManager;

    @Inject
    public Refactorings(ArchitectureViewManager viewManager) {
        this.viewManager = viewManager;
    }

    public Counts count(AnalysisDocument document) {
        int moves = 0;
        int plannedPackages = 0;
        for (ArchitectureWfxView view : viewManager.viewsOf(document)) {
            ArchitectureCanvas canvas = view.getArchitectureView();
            moves += canvas.getWhatIfMoves().size();
            plannedPackages += canvas.getPlannedPackages().size();
        }
        ArchitectureAnnotations annotations = document.annotations();
        int mapping = annotations.components().size()
                + annotations.componentApiIncludes().size()
                + annotations.componentApiExcludes().size()
                + annotations.roles().size()
                + annotations.ports().size();
        return new Counts(moves, plannedPackages, mapping, document.previewCuts().size());
    }

    public RefactoringReportInput reportInput(AnalysisDocument document) {
        List<ViewChanges> views = viewManager.viewsOf(document).stream()
                .map(view -> new ViewChanges(
                        view.getTitle(),
                        view.getArchitectureView().getViewStyle() == ArchitectureKind.COMPONENT,
                        view.getArchitectureView().getPlannedPackages(),
                        view.getArchitectureView().getWhatIfMoves().stream()
                                .map(move -> new ElementMove(
                                        move.fqn(), move.containerFqn(), move.rowIndex(), move.api()))
                                .toList()))
                .toList();
        return new RefactoringReportInput(
                document.name(),
                document.source().paths(),
                document.rawModel(),
                document.domainModel(),
                document.annotations(),
                document.previewCuts(),
                views);
    }

    /** Stellt alle Tabs des Dokuments auf den Stand des analysierten Codes zurück. */
    public void reset(AnalysisDocument document) {
        document.clearPreviewCuts();
        document.setAnnotations(ArchitectureAnnotations.empty());
        for (ArchitectureWfxView view : viewManager.viewsOf(document)) {
            view.getArchitectureView().resetRefactorings(document.annotations(), document.previewCuts());
        }
    }
}
