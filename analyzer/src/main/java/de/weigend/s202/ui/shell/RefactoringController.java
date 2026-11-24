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

import de.weigend.s202.report.text.TextReportWriter;
import de.weigend.s202.report.text.ViolationReportInput;
import de.weigend.s202.ui.core.events.RefactoringsResetEvent;
import de.weigend.s202.ui.core.platform.AnalysisDocument;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.Dialogs;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.RecentDirectories;
import de.weigend.s202.ui.core.platform.Refactorings;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.function.Supplier;

/** The text reports of the active analysis, and the way back to the analyzed code. */
final class RefactoringController {

    private final ArchitectureViewManager viewManager;
    private final Refactorings refactorings;
    private final RecentDirectories recentDirectories;
    private final ProgressPublisher progress;
    private final Supplier<Window> owner;

    RefactoringController(ArchitectureViewManager viewManager,
                          Refactorings refactorings,
                          RecentDirectories recentDirectories,
                          ProgressPublisher progress,
                          Supplier<Window> owner) {
        this.viewManager = viewManager;
        this.refactorings = refactorings;
        this.recentDirectories = recentDirectories;
        this.progress = progress;
        this.owner = owner;
    }

    void exportRefactoringReport() {
        String title = "Export Refactoring Report";
        AnalysisDocument document = activeDocument(title);
        if (document == null) {
            return;
        }
        if (refactorings.count(document).isEmpty()) {
            Dialogs.showInfo(title, "Nothing was moved, mapped or cut in " + document.name() + " yet.");
            return;
        }
        save(title, document, "refactoring", () ->
                Lookup.lookup(TextReportWriter.class).refactoringReport(refactorings.reportInput(document)));
    }

    void exportViolations() {
        String title = "Export Violations";
        AnalysisDocument document = activeDocument(title);
        if (document == null) {
            return;
        }
        save(title, document, "violations", () ->
                Lookup.lookup(TextReportWriter.class).violationReport(new ViolationReportInput(
                        document.name(),
                        document.source().paths(),
                        document.rawModel(),
                        document.domainModel(),
                        document.annotations(),
                        document.previewCuts())));
    }

    @SuppressWarnings("unchecked")
    void resetRefactorings() {
        String title = "Reset Refactorings";
        AnalysisDocument document = activeDocument(title);
        if (document == null) {
            return;
        }
        Refactorings.Counts counts = refactorings.count(document);
        if (counts.isEmpty()) {
            progress.status("Nothing to reset in " + document.name());
            return;
        }
        if (!Dialogs.confirm(title,
                "Go back to the analyzed code of " + document.name() + "?",
                summary(counts) + " will be discarded. This cannot be undone.",
                "Reset")) {
            return;
        }
        refactorings.reset(document);
        ((EventBus<EventObject>) Lookup.lookup(EventBus.class)).publish(new RefactoringsResetEvent(document));
        progress.status("Refactorings reset: " + document.name());
    }

    static String summary(Refactorings.Counts counts) {
        List<String> parts = new ArrayList<>();
        add(parts, counts.moves(), "move", "moves");
        add(parts, counts.plannedPackages(), "planned package", "planned packages");
        add(parts, counts.mappingChanges(), "mapping change (API, role, port)", "mapping changes (API, roles, ports)");
        add(parts, counts.cuts(), "cut dependency", "cut dependencies");
        return String.join(", ", parts);
    }

    private static void add(List<String> parts, int n, String singular, String plural) {
        if (n > 0) {
            parts.add(n + " " + (n == 1 ? singular : plural));
        }
    }

    private AnalysisDocument activeDocument(String title) {
        AnalysisDocument document = viewManager.activeDocument();
        if (document == null) {
            Dialogs.showError(title, "There is no loaded analysis.");
        }
        return document;
    }

    private void save(String title, AnalysisDocument document, String kind, Supplier<String> text) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text file", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        chooser.setInitialFileName(
                ArchitectureImageExportController.fileName(document.name() + "-" + kind, ".txt"));
        File initialDirectory = recentDirectories.initialReportDirectory();
        if (initialDirectory != null) {
            chooser.setInitialDirectory(initialDirectory);
        }
        File target = chooser.showSaveDialog(owner.get());
        if (target == null) {
            return;
        }
        recentDirectories.setLastReportDirectory(target.getParentFile());
        try {
            Files.writeString(target.toPath(), text.get());
            progress.status("Saved: " + target);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            Dialogs.showError(title, "Could not write the file:\n" + message);
        }
    }
}
