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

import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.project.ProjectMapper;
import de.weigend.s202.project.ProjectStore;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.wfx.shell.ArchitectureViewManager;
import de.weigend.s202.ui.wfx.shell.Dialogs;
import de.weigend.s202.ui.wfx.shell.ProgressPublisher;
import de.weigend.s202.ui.wfx.shell.RecentDirectories;
import de.weigend.s202.ui.wfx.shell.RefactoringPreviewState;
import de.weigend.s202.ui.wfx.view.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.concurrent.Task;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Projekt speichern / laden / schließen (.s202.json): Persistenz-IO auf
 * Hintergrund-Threads plus das Zurücksetzen der Dokument-Tabs. Aus
 * S202Module extrahiert.
 */
public final class ProjectPersistenceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectPersistenceController.class);

    private final ProjectStore projectStore = Lookup.lookup(ProjectStore.class);
    private final ProjectMapper projectMapper = Lookup.lookup(ProjectMapper.class);
    private final ArchitectureNodeBuilder architectureNodeBuilder = new ArchitectureNodeBuilder();

    private final ArchitectureViewManager viewManager;
    private final RefactoringPreviewState previewState;
    private final ProgressPublisher progress;
    private final RecentDirectories recentDirs;
    private final Supplier<Window> dialogOwner;
    private final Supplier<String> appVersion;
    /** Setzt UI-Zustand außerhalb dieses Controllers zurück (Toolbar-Bindung). */
    private final Runnable afterReset;

    public ProjectPersistenceController(ArchitectureViewManager viewManager,
                                        RefactoringPreviewState previewState,
                                        ProgressPublisher progress,
                                        RecentDirectories recentDirs,
                                        Supplier<Window> dialogOwner,
                                        Supplier<String> appVersion,
                                        Runnable afterReset) {
        this.viewManager = viewManager;
        this.previewState = previewState;
        this.progress = progress;
        this.recentDirs = recentDirs;
        this.dialogOwner = dialogOwner;
        this.appVersion = appVersion;
        this.afterReset = afterReset;
    }

    private record LoadedProject(S202Project project, DependencyModel rawModel,
                                 DomainModel domainModel, ArchitectureAnnotations annotations,
                                 ArchitectureNode rootNode,
                                 QualityMetrics metrics, LayoutInvariantReport invariants,
                                 Set<DependencyEdge> cycleBreakEdges) {}

    public void saveProject() {
        ArchitectureWfxView focused = viewManager.focusedSourceArchitectureView();
        if (focused == null || focused.getArchitectureView().getDomainModel() == null
                || focused.getArchitectureView().getRawDependencyModel() == null) {
            Dialogs.showError("Save Project", "There is no loaded analysis to save.");
            return;
        }

        FileChooser chooser = projectFileChooser("Save Structure202 Project");
        File target = chooser.showSaveDialog(dialogOwner.get());
        if (target == null) {
            return;
        }
        target = withDefaultProjectExtension(target);
        recentDirs.setLastProjectFileDirectory(target.getParentFile());

        ArchitectureView view = focused.getArchitectureView();
        S202Project.Source source = viewManager.sourceOf(view,
                new S202Project.Source("UNKNOWN", List.of(), null));
        S202Project project = projectMapper.toProject(
                appVersion.get(),
                source,
                view.getRawDependencyModel(),
                view.getDomainModel(),
                view.getArchitectureAnnotations(),
                viewManager.invariantsOf(view),
                view.getCycleBreakEdges());

        try {
            projectStore.save(target.toPath(), project);
            progress.progress("Saved project: " + target.getName(), 1);
        } catch (IOException ex) {
            LOGGER.error("Could not save project {}", target, ex);
            Dialogs.showError("Save Project", "Could not save project:\n" + ex.getMessage());
        }
    }

    public void loadProject() {
        FileChooser chooser = projectFileChooser("Load Structure202 Project");
        File file = chooser.showOpenDialog(dialogOwner.get());
        if (file == null) {
            return;
        }
        recentDirs.setLastProjectFileDirectory(file.getParentFile());
        progress.progress("Loading project: " + file.getName() + "...", -1);

        Task<LoadedProject> task = new Task<>() {
            @Override
            protected LoadedProject call() throws Exception {
                S202Project project = projectStore.load(file.toPath());
                DependencyModel rawModel = projectMapper.toDependencyModel(project.dependencyModel());
                DomainModel domainModel = projectMapper.toDomainModel(project.domainModel());
                ArchitectureAnnotations annotations =
                        projectMapper.toArchitectureAnnotations(project.architectureAnnotations());
                ArchitectureNode root = architectureNodeBuilder.build(domainModel);
                new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
                de.weigend.s202.ui.consistency.ArchitectureConsistencyDevHook
                        .runIfEnabled(domainModel, root);
                QualityMetrics metrics = QualityMetrics.compute(domainModel);
                LayoutInvariantReport invariants = projectMapper.toLayoutInvariantReport(project.layoutInvariantReport());
                Set<DependencyEdge> cycleBreakEdges =
                        projectMapper.toCycleBreakEdges(project.cycleBreakEdges());
                return new LoadedProject(project, rawModel, domainModel, annotations, root, metrics, invariants, cycleBreakEdges);
            }
        };
        task.setOnSucceeded(e -> applyLoadedProject(file.toPath(), task.getValue()));
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            LOGGER.error("Could not load project {}", file, t);
            String msg = t != null ? t.getMessage() : "unknown error";
            progress.progress("Error loading project: " + msg, 1);
            Dialogs.showError("Load Project", "Could not load project:\n" + msg);
        });

        Thread loader = new Thread(task, "s202-project-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void applyLoadedProject(Path path, LoadedProject loaded) {
        resetProjectUi();

        ArchitectureWfxView target = viewManager.createArchitectureView();
        viewManager.registerArchitectureView(target);
        ArchitectureView view = target.getArchitectureView();
        view.setArchitectureAnnotations(loaded.annotations());
        view.setDomainModel(loaded.domainModel());
        view.setRawDependencyModel(loaded.rawModel());
        view.setCycleBreakEdges(loaded.cycleBreakEdges());
        view.setArchitectureRootAsync(
                loaded.rootNode(),
                buildProgress -> progress.javaFxBuildProgress("Building JavaFX project view", buildProgress),
                () -> {
                    view.setQualityMetrics(loaded.metrics());
                    viewManager.putSource(view, loaded.project().source());
                    viewManager.putInvariants(view, loaded.invariants());

                    progress.progress(String.format(
                            "Loaded project %s | %d classes | %d levels | Max level %d",
                            path.getFileName(),
                            loaded.rawModel().getAllClasses().size(),
                            loaded.rootNode().getLevelCount(),
                            loaded.rootNode().getMaxLevel()), 1);
                });
    }

    public void closeProject() {
        resetProjectUi();
        progress.progress("Ready to analyze code. Open JARs, Python source roots, or C source roots to begin.", 0);
    }

    private void resetProjectUi() {
        viewManager.resetDocuments();
        previewState.clear();
        Lookup.lookup(WindowManager.class).restoreDefaultLayout();
        afterReset.run();
    }

    private FileChooser projectFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Structure202 Project", "*.s202.json"),
                new FileChooser.ExtensionFilter("JSON Files", "*.json"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File initial = recentDirs.initialProjectFileDirectory();
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        return chooser;
    }

    private static File withDefaultProjectExtension(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".s202.json") || name.endsWith(".json")) {
            return file;
        }
        File parent = file.getParentFile();
        return parent == null
                ? new File(file.getName() + ".s202.json")
                : new File(parent, file.getName() + ".s202.json");
    }
}
