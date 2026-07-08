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
package de.weigend.s202.ui.wfx.report;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.report.quality.QualityReportExporter;
import de.weigend.s202.report.quality.QualityReportInput;
import de.weigend.s202.report.quality.QualityReportModel;
import de.weigend.s202.report.quality.QualityReportOptions;
import de.weigend.s202.ui.core.canvas.ArchitectureView;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.wfx.report.impl.JavaFxQualityReportImageRenderer;
import de.weigend.s202.ui.wfx.shell.ArchitectureViewManager;
import de.weigend.s202.ui.wfx.shell.Dialogs;
import de.weigend.s202.ui.wfx.shell.ProgressPublisher;
import de.weigend.s202.ui.wfx.shell.RecentDirectories;
import de.weigend.s202.ui.wfx.shell.RefactoringPreviewState;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.concurrent.Task;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Erzeugt und exportiert den Quality-Report: Modell-Aufbau im Hintergrund,
 * JavaFX-Screenshots, HTML-Ausgabe, Report-Tab und Verzeichnis-Export.
 * Aus S202Module extrahiert — trägt die Abhängigkeit auf
 * {@link JavaFxQualityReportImageRenderer} ins Report-Paket.
 */
public final class QualityReportController {

    private static final Logger LOGGER = LoggerFactory.getLogger(QualityReportController.class);

    private static final double REPORT_PROGRESS_PREPARED = 0.05;
    private static final double REPORT_PROGRESS_MODEL = 0.20;
    private static final double REPORT_PROGRESS_IMAGES_START = 0.20;
    private static final double REPORT_PROGRESS_IMAGES_END = 0.86;
    private static final double REPORT_PROGRESS_HTML = 0.94;

    private final ArchitectureViewManager viewManager;
    private final RefactoringPreviewState previewState;
    private final ProgressPublisher progress;
    private final RecentDirectories recentDirs;
    private final Supplier<Window> dialogOwner;
    private final Supplier<String> appVersion;

    private int reportCounter;

    public QualityReportController(ArchitectureViewManager viewManager,
                                   RefactoringPreviewState previewState,
                                   ProgressPublisher progress,
                                   RecentDirectories recentDirs,
                                   Supplier<Window> dialogOwner,
                                   Supplier<String> appVersion) {
        this.viewManager = viewManager;
        this.previewState = previewState;
        this.progress = progress;
        this.recentDirs = recentDirs;
        this.dialogOwner = dialogOwner;
        this.appVersion = appVersion;
    }

    public void exportQualityReport() {
        ArchitectureWfxView focused = viewManager.focusedSourceArchitectureView();
        if (focused == null || focused.getArchitectureView().getDomainModel() == null
                || focused.getArchitectureView().getRawDependencyModel() == null
                || focused.getArchitectureView().getArchitectureRoot() == null) {
            Dialogs.showError("Quality Report", "There is no loaded analysis to report.");
            return;
        }

        Path outputDirectory;
        try {
            outputDirectory = Files.createTempDirectory("s202-quality-report-");
        } catch (IOException ex) {
            LOGGER.error("Could not create temporary quality report directory", ex);
            Dialogs.showError("Quality Report", "Could not create a temporary report directory:\n" + ex.getMessage());
            return;
        }

        ArchitectureView view = focused.getArchitectureView();
        S202Project.Source source = viewManager.sourceOf(view,
                new S202Project.Source("UNKNOWN", List.of(), null));
        QualityReportInput input = new QualityReportInput(
                "S202 Quality Report",
                appVersion.get(),
                source,
                view.getRawDependencyModel(),
                view.getDomainModel(),
                view.getArchitectureAnnotations(),
                view.getQualityMetrics(),
                viewManager.invariantsOf(view));
        ArchitectureNode reportRoot = ArchitectureNodeCloner.cloneTree(view.getArchitectureRoot());
        Set<DependencyEdge> reportCycleBreakEdges = Set.copyOf(view.getCycleBreakEdges());
        Set<DependencyEdge> reportPreviewCuts = Set.copyOf(previewState.cuts());
        int scopeImageLimit = qualityReportScopeImageLimit(input);

        progress.progress(scopeImageLimit < 5
                ? "Preparing quality report (large codebase: limiting JavaFX screenshots)..."
                : "Preparing quality report...", REPORT_PROGRESS_PREPARED);
        QualityReportExporter qualityReportExporter = Lookup.lookup(QualityReportExporter.class);
        if (qualityReportExporter == null) {
            Dialogs.showError("Quality Report", "No quality report exporter is registered.");
            return;
        }
        QualityReportOptions reportOptions = new QualityReportOptions("png", scopeImageLimit);

        Task<QualityReportModel> task = new Task<>() {
            @Override
            protected QualityReportModel call() {
                progress.progress("Building quality report model...", REPORT_PROGRESS_MODEL * 0.5);
                return qualityReportExporter.build(input, reportOptions);
            }
        };
        task.setOnSucceeded(e -> {
            progress.progress("Quality report model built", REPORT_PROGRESS_MODEL);
            QualityReportModel model = task.getValue();
            JavaFxQualityReportImageRenderer renderer = new JavaFxQualityReportImageRenderer(
                    reportRoot,
                    reportCycleBreakEdges,
                    reportPreviewCuts);
            renderer.renderImagesAsync(
                    model,
                    input,
                    outputDirectory,
                    (message, imageProgress) -> progress.progress(
                            message,
                            REPORT_PROGRESS_IMAGES_START
                                    + (REPORT_PROGRESS_IMAGES_END - REPORT_PROGRESS_IMAGES_START) * imageProgress),
                    () -> writeQualityReportHtml(qualityReportExporter, model, outputDirectory),
                    failure -> {
                        LOGGER.error("Could not render quality report screenshots to {}", outputDirectory, failure);
                        String msg = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                        progress.progress("Error generating quality report: " + msg, 1);
                        Dialogs.showError("Quality Report", "Could not generate quality report:\n" + msg);
                    });
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            LOGGER.error("Could not build quality report for {}", outputDirectory, t);
            String msg = t == null || t.getMessage() == null ? "unknown error" : t.getMessage();
            progress.progress("Error generating quality report: " + msg, 1);
            Dialogs.showError("Quality Report", "Could not generate quality report:\n" + msg);
        });

        Thread exporter = new Thread(task, "s202-quality-report-exporter");
        exporter.setDaemon(true);
        exporter.start();
    }

    private void writeQualityReportHtml(QualityReportExporter exporter,
                                        QualityReportModel model,
                                        Path outputDirectory) {
        Task<Path> writer = new Task<>() {
            @Override
            protected Path call() throws IOException {
                progress.progress("Writing quality report HTML...", REPORT_PROGRESS_HTML);
                return exporter.write(model, outputDirectory);
            }
        };
        writer.setOnSucceeded(e -> {
            Path html = writer.getValue();
            openQualityReportView(outputDirectory, html);
            progress.progress("Opened quality report: " + html.toAbsolutePath(), 1);
        });
        writer.setOnFailed(e -> {
            Throwable t = writer.getException();
            LOGGER.error("Could not write quality report to {}", outputDirectory, t);
            String msg = t == null || t.getMessage() == null ? "unknown error" : t.getMessage();
            progress.progress("Error generating quality report: " + msg, 1);
            Dialogs.showError("Quality Report", "Could not generate quality report:\n" + msg);
        });
        Thread htmlWriter = new Thread(writer, "s202-quality-report-writer");
        htmlWriter.setDaemon(true);
        htmlWriter.start();
    }

    private void openQualityReportView(Path reportDirectory, Path htmlPath) {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        reportCounter++;
        QualityReportView view = new QualityReportView(
                QualityReportView.VIEW_ID_PREFIX + reportCounter,
                "Quality Report " + reportCounter,
                reportDirectory,
                htmlPath,
                this::exportGeneratedQualityReport);
        wm.register(view);
        wm.showView(view);
    }

    private void exportGeneratedQualityReport(Path sourceDirectory) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Export Quality Report");
        File initial = recentDirs.initialReportDirectory();
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        File targetDirectory = chooser.showDialog(dialogOwner.get());
        if (targetDirectory == null) {
            return;
        }
        recentDirs.setLastReportDirectory(targetDirectory);

        Path target = targetDirectory.toPath();
        if (isInside(sourceDirectory, target)) {
            Dialogs.showError("Export Quality Report", "The target directory cannot be the temporary report directory.");
            return;
        }

        Task<Integer> copyTask = new Task<>() {
            @Override
            protected Integer call() throws IOException {
                return copyQualityReportDirectory(sourceDirectory, target);
            }
        };
        copyTask.setOnSucceeded(e -> {
            int files = copyTask.getValue();
            progress.progress("Exported quality report to " + target.toAbsolutePath() + " (" + files + " files)", 1);
        });
        copyTask.setOnFailed(e -> {
            Throwable t = copyTask.getException();
            LOGGER.error("Could not export quality report from {} to {}", sourceDirectory, target, t);
            String msg = t == null || t.getMessage() == null ? "unknown error" : t.getMessage();
            progress.progress("Error exporting quality report: " + msg, 1);
            Dialogs.showError("Export Quality Report", "Could not export quality report:\n" + msg);
        });

        progress.progress("Exporting quality report...", 0);
        Thread exporter = new Thread(copyTask, "s202-quality-report-copy");
        exporter.setDaemon(true);
        exporter.start();
    }

    private int copyQualityReportDirectory(Path sourceDirectory, Path targetDirectory) throws IOException {
        List<Path> entries;
        try (var stream = Files.walk(sourceDirectory)) {
            entries = stream
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .toList();
        }

        int fileCount = (int) entries.stream().filter(Files::isRegularFile).count();
        int copied = 0;
        for (Path source : entries) {
            Path relative = sourceDirectory.relativize(source);
            if (relative.toString().isEmpty()) {
                continue;
            }
            Path target = targetDirectory.resolve(relative);
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
                continue;
            }
            if (!Files.isRegularFile(source)) {
                continue;
            }

            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            copied++;
            double copyProgress = fileCount == 0 ? 1.0 : (double) copied / fileCount;
            progress.progress("Exporting quality report: " + relative, copyProgress);
        }
        return copied;
    }

    private boolean isInside(Path sourceDirectory, Path targetDirectory) {
        try {
            Path source = sourceDirectory.toRealPath();
            Path target = targetDirectory.toRealPath();
            return target.startsWith(source);
        } catch (IOException ex) {
            Path source = sourceDirectory.toAbsolutePath().normalize();
            Path target = targetDirectory.toAbsolutePath().normalize();
            return target.startsWith(source);
        }
    }

    private int qualityReportScopeImageLimit(QualityReportInput input) {
        int classes = input.rawModel().getAllClasses().size();
        if (classes >= 4_000) {
            return 1;
        }
        if (classes >= 1_500) {
            return 2;
        }
        return 5;
    }
}
