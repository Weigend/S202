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
package de.weigend.s202.ui.wfx;

import de.weigend.s202.analysis.invariants.LayoutInvariantChecker;
import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.LanguageAnalyzer;
import de.weigend.s202.ui.core.canvas.ArchitectureView;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.Dialogs;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.RefactoringPreviewState;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Die Analyse-Pipeline (raw analysis → levels → node tree → render): lädt
 * JARs oder Source-Roots auf einem Hintergrund-Thread, baut das Ergebnis
 * zusammen und wendet es auf einen frischen Architektur-Tab an. Aus
 * S202Module extrahiert.
 */
public final class AnalysisPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisPipeline.class);
    static final String JAVA_BYTECODE_ANALYZER = "Java bytecode";

    private final ArchitectureNodeBuilder architectureNodeBuilder = new ArchitectureNodeBuilder();
    private final LayoutInvariantChecker invariantChecker = new LayoutInvariantChecker();

    private final ArchitectureViewManager viewManager;
    private final RefactoringPreviewState previewState;
    private final ProgressPublisher progress;
    private final Supplier<Stage> dialogOwner;

    public AnalysisPipeline(ArchitectureViewManager viewManager,
                            RefactoringPreviewState previewState,
                            ProgressPublisher progress,
                            Supplier<Stage> dialogOwner) {
        this.viewManager = viewManager;
        this.previewState = previewState;
        this.progress = progress;
        this.dialogOwner = dialogOwner;
    }

    record AnalysisInputSummary(String label, int count) {}

    record AnalysisResult(DependencyModel rawModel, ArchitectureNode rootNode,
                          QualityMetrics metrics, DomainModel domainModel,
                          LayoutInvariantReport invariants,
                          Set<DependencyEdge> cycleBreakEdges) {}

    static S202Project.Source projectSource(String kind, File root, List<File> jars) {
        return new S202Project.Source(
                kind.toUpperCase(),
                jars.stream().map(File::getAbsolutePath).toList(),
                root == null ? null : root.getAbsolutePath());
    }

    static LanguageAnalyzer requireLanguageAnalyzer(String displayName) {
        return Lookup.lookupAll(LanguageAnalyzer.class).stream()
                .filter(analyzer -> displayName.equalsIgnoreCase(analyzer.displayName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No language analyzer registered for " + displayName));
    }

    private static DomainComputer requireDomainComputer() {
        DomainComputer computer = Lookup.lookup(DomainComputer.class);
        if (computer == null) {
            throw new IllegalStateException("No domain computer registered");
        }
        return computer;
    }

    /**
     * Run the analysis pipeline (raw analysis -> levels -> node tree -> render)
     * on a background thread; rendering happens in {@code onSucceeded} on the FX
     * thread. Each invocation opens a new architecture tab, which then becomes
     * the focused view.
     */
    public void loadJarFiles(List<File> jarFiles) {
        if (jarFiles == null || jarFiles.isEmpty()) {
            return;
        }
        loadJarFiles(jarFiles, projectSource("JAR", null, jarFiles));
    }

    public void loadJarFiles(List<File> jarFiles, S202Project.Source source) {
        if (jarFiles == null || jarFiles.isEmpty()) {
            return;
        }
        ArchitectureWfxView target = viewManager.createArchitectureView();
        viewManager.registerArchitectureView(target);
        final ArchitectureView view = target.getArchitectureView();
        final String fileNames = jarFiles.stream().map(File::getName).collect(Collectors.joining(", "));
        final List<String> jarPaths = jarFiles.stream().map(File::getAbsolutePath).toList();
        final List<Path> jarInputPaths = jarFiles.stream().map(File::toPath).toList();
        final LanguageAnalyzer languageAnalyzer = requireLanguageAnalyzer(JAVA_BYTECODE_ANALYZER);

        progress.progress("Analyzing: " + fileNames + " (this may take a moment)...", -1);

        Task<AnalysisResult> task = new Task<>() {
            @Override
            protected AnalysisResult call() throws Exception {
                progress.progress("Reading bytecode: " + fileNames, 0.20);
                DependencyModel rawModel = languageAnalyzer.analyze(jarInputPaths);
                return buildAnalysisResult(rawModel, jarPaths);
            }
        };

        task.setOnSucceeded(e -> {
            AnalysisResult result = task.getValue();
            if (result.rootNode() == null) {
                progress.progress("Error: No classes found in JAR file(s)", 1);
                Dialogs.showError("No Classes Found", "The JAR file(s) do not contain any .class files");
                return;
            }
            progress.progress("Building JavaFX architecture view...", 0.97);

            PauseTransition yieldToPulse = new PauseTransition(Duration.millis(50));
            AnalysisInputSummary summary = new AnalysisInputSummary("JAR(s)", jarFiles.size());
            yieldToPulse.setOnFinished(event -> applyAnalysisResult(summary, view, source, result));
            yieldToPulse.play();
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            LOGGER.error("Analysis failed", t);
            String msg = t != null ? t.getMessage() : "unknown error";
            progress.progress("Error: " + msg, 1);
            Dialogs.showError("Analysis Error", "Failed to analyze JAR file(s):\n" + msg);
        });

        Thread analyzer = new Thread(task, "s202-analyzer");
        analyzer.setDaemon(true);
        analyzer.start();
    }

    public void loadSourceRoot(LanguageAnalyzer analyzer, Path root, String sourceKind, String titlePrefix,
                               String readerProgress, String emptyTitle, String emptyMessage) {
        if (root == null || !root.toFile().isDirectory()) {
            return;
        }
        File rootFile = root.toFile();
        ArchitectureWfxView target = viewManager.createArchitectureView(titlePrefix + ": " + rootFile.getName());
        viewManager.registerArchitectureView(target);
        final ArchitectureView view = target.getArchitectureView();
        final String rootPath = rootFile.getAbsolutePath();
        final S202Project.Source source = new S202Project.Source(sourceKind, List.of(rootPath), rootPath);

        progress.progress("Analyzing " + analyzer.displayName() + " source: "
                + rootFile.getName() + " (this may take a moment)...", -1);

        Task<AnalysisResult> task = new Task<>() {
            @Override
            protected AnalysisResult call() throws Exception {
                progress.progress(readerProgress, 0.20);
                DependencyModel rawModel = analyzer.analyze(List.of(root));
                return buildAnalysisResult(rawModel, List.of(rootPath));
            }
        };

        task.setOnSucceeded(e -> {
            AnalysisResult result = task.getValue();
            if (result.rootNode() == null) {
                progress.progress("Error: " + emptyTitle, 1);
                Dialogs.showError(emptyTitle, emptyMessage);
                return;
            }
            progress.progress("Building JavaFX architecture view...", 0.97);

            PauseTransition yieldToPulse = new PauseTransition(Duration.millis(50));
            AnalysisInputSummary summary = new AnalysisInputSummary(analyzer.displayName() + " root(s)", 1);
            yieldToPulse.setOnFinished(event -> applyAnalysisResult(summary, view, source, result));
            yieldToPulse.play();
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            LOGGER.error("{} analysis failed", analyzer.displayName(), t);
            String msg = t != null ? t.getMessage() : "unknown error";
            progress.progress("Error: " + msg, 1);
            Dialogs.showError(titlePrefix + " Analysis Error",
                    "Failed to analyze " + analyzer.displayName() + " source root:\n" + msg);
        });

        Thread analyzerThread = new Thread(task, "s202-" + sourceKind.toLowerCase(Locale.ROOT) + "-analyzer");
        analyzerThread.setDaemon(true);
        analyzerThread.start();
    }

    private AnalysisResult buildAnalysisResult(DependencyModel rawModel, List<String> sourcePaths) {
        if (rawModel.getAllClasses().isEmpty()) {
            return new AnalysisResult(rawModel, null, null, null, null, Set.of());
        }

        progress.progress("Calculating architectural levels...", 0.75);
        DomainModel calculated = requireDomainComputer().compute(rawModel);
        Set<DependencyEdge> cycleBreakEdges = calculated.getClassBackEdges();

        progress.progress("Building architecture tree...", 0.85);
        ArchitectureNode root = architectureNodeBuilder.build(calculated);
        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
        de.weigend.s202.ui.core.consistency.ArchitectureConsistencyDevHook
                .runIfEnabled(calculated, root);

        progress.progress("Preparing quality metrics...", 0.90);
        QualityMetrics metrics = QualityMetrics.compute(calculated);

        // Layout invariant check runs on the same background thread so
        // findings reach the FX thread together with the rest of the result.
        progress.progress("Verifying layout invariants...", 0.95);
        LayoutInvariantReport invariants = invariantChecker.check(calculated, rawModel, sourcePaths);
        return new AnalysisResult(rawModel, root, metrics, calculated, invariants, cycleBreakEdges);
    }

    private void applyAnalysisResult(AnalysisInputSummary summary, ArchitectureView view,
                                     S202Project.Source source, AnalysisResult result) {
        previewState.clear();
        // Domain model first so listeners on architectureRoot/metrics can
        // already query scoped data (e.g. quality module on package select).
        view.setDomainModel(result.domainModel());
        view.setRawDependencyModel(result.rawModel());
        view.setCycleBreakEdges(result.cycleBreakEdges());
        view.setArchitectureRootAsync(
                result.rootNode(),
                buildProgress -> progress.javaFxBuildProgress("Building JavaFX architecture view", buildProgress),
                () -> finishAppliedAnalysisResult(summary, view, source, result));
    }

    private void finishAppliedAnalysisResult(AnalysisInputSummary summary, ArchitectureView view,
                                             S202Project.Source source, AnalysisResult result) {
        view.setQualityMetrics(result.metrics());
        viewManager.putSource(view, source);
        viewManager.putInvariants(view, result.invariants());

        LayoutInvariantReport invariants = result.invariants();
        String invariantSuffix = invariantSuffix(invariants);
        progress.progress(String.format(
                "Loaded %d %s | %d classes | %d levels | Max level %d%s",
                summary.count(),
                summary.label(),
                result.rawModel().getAllClasses().size(),
                result.rootNode().getLevelCount(),
                result.rootNode().getMaxLevel(),
                invariantSuffix), 1);

        showInvariantReportIfNeeded(invariants);
    }

    private String invariantSuffix(LayoutInvariantReport invariants) {
        if (invariants == null) {
            return "";
        }
        int n = invariants.findings().size();
        if (n == 0) {
            return " | invariants OK";
        }
        LOGGER.warn("Layout invariant report ({} finding(s)):\n{}",
                n, invariants.toReproducerText());
        return " | " + n + " invariant finding" + (n == 1 ? "" : "s");
    }

    private void showInvariantReportIfNeeded(LayoutInvariantReport invariants) {
        if (invariants == null || !invariants.hasFindings()) {
            return;
        }
        Platform.runLater(() ->
                InvariantReportDialog.show(dialogOwner.get(), invariants));
    }
}
