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

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.project.ProjectMapper;
import de.weigend.s202.project.ProjectStore;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.model.EdgeKindGroup;
import de.weigend.s202.ui.core.model.WhatIfMove;
import de.weigend.s202.ui.core.platform.AnalysisDocument;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.Dialogs;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.RecentDirectories;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import de.weigend.s202.ui.core.platform.ViewDescriptor;
import de.weigend.s202.ui.shell.AnalysisBuilder.AnalysisResult;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/**
 * Projekt speichern / laden / schließen (.s202.json), Persistenz-IO auf
 * Hintergrund-Threads. Eine Projektdatei ist die gespeicherte Form genau
 * eines {@link AnalysisDocument}: Speichern schreibt das aktive Dokument,
 * Laden öffnet ein weiteres neben den schon offenen — wie Open JAR —, und
 * Schließen schließt das aktive mit allen seinen Tabs. Aus S202Module
 * extrahiert.
 * <p>
 * Zum Dokument gehören seine Tabs: Gespeichert wird zu jedem, was er zeigt
 * (seine {@link ViewDescriptor Beschreibung}) und wie der Benutzer ihn
 * verlassen hat (Pakettiefe, Schalter, Zoom, aufgeklappte Pakete, geplante
 * Moves und Pakete), dazu die Selektion des aktiven Tabs.
 * Beim Laden entsteht jeder Tab auf demselben Weg wie durch die Menü-Aktion,
 * einer nach dem anderen. Wo die Tabs im Fenster lagen, steht nicht in der
 * Datei — die Anordnung gehört dem Fenster, nicht einem Dokument.
 */
public final class ProjectPersistenceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectPersistenceController.class);

    private final ArchitectureViewManager viewManager;
    private final ProgressPublisher progress;
    private final RecentDirectories recentDirs;
    private final Supplier<Window> dialogOwner;
    private final Supplier<String> appVersion;
    private final AnalysisBuilder analysisBuilder;

    public ProjectPersistenceController(ArchitectureViewManager viewManager,
                                        ProgressPublisher progress,
                                        RecentDirectories recentDirs,
                                        Supplier<Window> dialogOwner,
                                        Supplier<String> appVersion) {
        this.viewManager = viewManager;
        this.progress = progress;
        this.recentDirs = recentDirs;
        this.dialogOwner = dialogOwner;
        this.appVersion = appVersion;
        this.analysisBuilder = new AnalysisBuilder(progress);
    }

    private record LoadedProject(S202Project project, AnalysisResult analysis,
                                 ArchitectureAnnotations annotations,
                                 Set<DependencyEdge> previewCuts) {}

    /** Speichert das aktive Dokument — das, an dem der Benutzer gerade arbeitet. */
    public void saveProject() {
        AnalysisDocument document = viewManager.activeDocument();
        if (document == null) {
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
        saveProject(document, target.toPath());
    }

    /** Schreibt {@code document} mit seinen Tabs als Projektdatei nach {@code target}. */
    public void saveProject(AnalysisDocument document, Path target) {
        ArchitectureCanvas activeCanvas = viewManager.activeCanvas();
        List<S202Project.ViewDto> views = viewManager.viewsOf(document).stream()
                .map(wrapper -> toViewDto(wrapper, wrapper.getArchitectureView() == activeCanvas,
                        viewManager.matrixOf(wrapper.getArchitectureView()) != null))
                .toList();
        S202Project project = projectMapper().toProject(
                appVersion.get(),
                document.source(),
                document.rawModel(),
                document.annotations(),
                document.previewCuts(),
                views);

        try {
            projectStore().save(target, project);
            progress.progress("Saved project: " + target.getFileName(), 1);
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
        loadProject(file.toPath());
    }

    /** Lädt die Projektdatei als weiteres Dokument; die offenen bleiben. */
    public void loadProject(Path file) {
        progress.progress("Loading project: " + file.getFileName() + "...", -1);

        Task<LoadedProject> task = new Task<>() {
            @Override
            protected LoadedProject call() throws Exception {
                ProjectMapper projectMapper = projectMapper();
                S202Project project = projectStore().load(file);
                DependencyModel rawModel = projectMapper.toDependencyModel(project.dependencyModel());
                AnalysisResult analysis = analysisBuilder.build(rawModel,
                        project.source() == null || project.source().paths() == null
                                ? List.of() : project.source().paths());
                if (analysis.rootNode() == null) {
                    throw new IOException("The project contains no classes");
                }
                return new LoadedProject(project, analysis,
                        projectMapper.toArchitectureAnnotations(project.architectureAnnotations()),
                        projectMapper.toPreviewCuts(project.previewCuts()));
            }
        };
        task.setOnSucceeded(e -> applyLoadedProject(file, task.getValue()));
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
        AnalysisDocument document = new AnalysisDocument(
                viewManager.uniqueDocumentName(documentName(path)),
                loaded.project().source(), loaded.analysis().rawModel(), loaded.analysis().domainModel(),
                loaded.analysis().rootNode(), loaded.analysis().metrics(), loaded.analysis().invariants(),
                loaded.analysis().cycleBreakEdges());
        document.setAnnotations(loaded.annotations());
        document.addPreviewCuts(loaded.previewCuts());

        List<S202Project.ViewDto> views = loaded.project().views();
        if (views == null || views.isEmpty()) {
            // A file that names no tabs: the analysis opens the way a freshly analysed one does.
            views = List.of(layeredViewOfTheWholeAnalysis());
        }
        int expected = views.size();
        String selected = views.stream().filter(S202Project.ViewDto::active)
                .findFirst().map(S202Project.ViewDto::selected).orElse(null);
        ArchitectureWfxView[] active = new ArchitectureWfxView[1];
        int[] opened = new int[1];
        openViews(document, views.iterator(), active, opened, () -> {
            WindowManager wm = Lookup.lookup(WindowManager.class);
            if (active[0] != null && wm.hasRegisteredView(active[0])) {
                wm.showView(active[0]);
                // There is one selection for all tabs, and every tab that is
                // built takes it. It goes back to where the user left it once
                // all tabs are there.
                if (selected != null && !selected.isBlank()) {
                    active[0].getArchitectureView().selectByFullName(selected);
                }
            }
            progress.progress(String.format(
                    "Loaded project %s | %d classes | %d levels | Max level %d | %d of %d tab%s",
                    path.getFileName(),
                    loaded.analysis().rawModel().getAllClasses().size(),
                    loaded.analysis().rootNode().getLevelCount(),
                    loaded.analysis().rootNode().getMaxLevel(),
                    opened[0], expected, expected == 1 ? "" : "s"), 1);
        });
    }

    /**
     * Öffnet die Tabs eines geladenen Dokuments nacheinander: Der nächste
     * beginnt, wenn der vorige seinen Inhalt zeigt. So entsteht jeder unter
     * denselben Bedingungen wie durch die Menü-Aktion, und die Reihenfolge
     * der Tabs ist die gespeicherte.
     */
    private void openViews(AnalysisDocument document, Iterator<S202Project.ViewDto> remaining,
                           ArchitectureWfxView[] active, int[] opened, Runnable done) {
        if (!remaining.hasNext()) {
            done.run();
            return;
        }
        S202Project.ViewDto dto = remaining.next();
        ArchitectureWfxView wrapper = null;
        try {
            wrapper = viewManager.open(document, toDescriptor(dto),
                    canvas -> canvas.setPackageDepth(dto.packageDepth()),
                    built -> {
                        restoreState(built.getArchitectureView(), dto);
                        if (dto.matrix()) {
                            viewManager.openMatrix(built.getArchitectureView());
                        }
                        opened[0]++;
                        if (dto.active()) {
                            active[0] = built;
                        }
                        openViews(document, remaining, active, opened, done);
                    });
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not reopen the tab '{}' ({}) of {}", dto.label(), dto.kind(), document.name(), ex);
        }
        if (wrapper == null) {
            // What the tab showed is not in the analysis (any more), or nobody
            // builds tabs of its kind: go on with the others.
            LOGGER.warn("Tab '{}' ({}) of {} was not reopened", dto.label(), dto.kind(), document.name());
            openViews(document, remaining, active, opened, done);
        }
    }

    private static S202Project.ViewDto toViewDto(ArchitectureWfxView wrapper, boolean active, boolean matrixOpen) {
        ViewDescriptor descriptor = wrapper.getDescriptor();
        ArchitectureCanvas canvas = wrapper.getArchitectureView();
        ArchitectureCanvas.ViewSnapshot snapshot = canvas.captureViewSnapshot();
        return new S202Project.ViewDto(
                descriptor.kind().name(),
                descriptor.label(),
                descriptor.scope(),
                descriptor.scopeExtensions(),
                descriptor.tangleMembers().stream().sorted().toList(),
                descriptor.tangleKey(),
                descriptor.tangleContext(),
                canvas.getPackageDepth(),
                canvas.isShowDependencies(),
                canvas.isShowScc(),
                canvas.isShowPackageScc(),
                canvas.isShowWhatIfViolations(),
                canvas.isLimitBoxWidth(),
                canvas.getHiddenEdgeKinds().stream().map(EdgeKindGroup::name).sorted().toList(),
                snapshot == null ? 1.0 : snapshot.zoom(),
                snapshot == null ? 0.0 : snapshot.horizontalScroll(),
                snapshot == null ? 0.0 : snapshot.verticalScroll(),
                snapshot == null ? Map.of() : new TreeMap<>(snapshot.packageExpansion()),
                canvas.getSelectedFullName(),
                active,
                canvas.getWhatIfMoves().stream().map(ProjectPersistenceController::toMoveDto).toList(),
                canvas.getPlannedPackages().entrySet().stream()
                        .map(planned -> new S202Project.PlannedPackageDto(planned.getKey(), planned.getValue()))
                        .toList(),
                !canvas.isShowPlan(),
                matrixOpen,
                canvas.getMatrix().getOpen().stream().sorted().toList(),
                canvas.getMatrix().isShowingExternals());
    }

    private static S202Project.MoveDto toMoveDto(WhatIfMove move) {
        return switch (move) {
            case WhatIfMove.InRow m -> new S202Project.MoveDto(
                    m.fqn(), m.containerFqn(), m.rowIndex(), m.colIndex(), false, m.api());
            case WhatIfMove.AsNewRow m -> new S202Project.MoveDto(
                    m.fqn(), m.containerFqn(), m.rowIndex(), 0, true, m.api());
        };
    }

    private static WhatIfMove toMove(S202Project.MoveDto dto) {
        return dto.newRow()
                ? new WhatIfMove.AsNewRow(dto.element(), dto.container(), dto.row(), dto.api())
                : new WhatIfMove.InRow(dto.element(), dto.container(), dto.row(), dto.column(), dto.api());
    }

    private static ViewDescriptor toDescriptor(S202Project.ViewDto dto) {
        return new ViewDescriptor(
                ViewDescriptor.Kind.valueOf(dto.kind()),
                dto.label(),
                dto.scope(),
                dto.scopeExtensions(),
                dto.tangleMembers() == null ? Set.of() : new LinkedHashSet<>(dto.tangleMembers()),
                dto.tangleKey(),
                dto.tangleContext());
    }

    /** Bringt einen frisch gebauten Tab in den Zustand, in dem er gespeichert wurde. */
    private static void restoreState(ArchitectureCanvas canvas, S202Project.ViewDto dto) {
        // The moves first: they put boxes where the snapshot expects them.
        Map<String, String> plannedPackages = new LinkedHashMap<>();
        if (dto.plannedPackages() != null) {
            dto.plannedPackages().forEach(planned -> plannedPackages.put(planned.id(), planned.name()));
        }
        canvas.restorePlan(
                dto.moves() == null ? List.of()
                        : dto.moves().stream().map(ProjectPersistenceController::toMove).toList(),
                plannedPackages, !dto.hidePlan());
        canvas.setShowDependencies(dto.showDependencies());
        canvas.setShowScc(dto.showClassCycles());
        canvas.setShowPackageScc(dto.showPackageCycles());
        canvas.setShowWhatIfViolations(dto.showViolations());
        canvas.setLimitBoxWidth(dto.limitBoxWidth());
        canvas.setHiddenEdgeKinds(dto.hiddenEdgeKinds() == null ? Set.of()
                : dto.hiddenEdgeKinds().stream().map(EdgeKindGroup::valueOf).collect(Collectors.toSet()));
        canvas.restoreViewSnapshot(new ArchitectureCanvas.ViewSnapshot(
                dto.zoom(), dto.horizontalScroll(), dto.verticalScroll(), dto.expandedPackages()));
        canvas.getMatrix().setOpen(dto.matrixOpen() == null ? Set.of() : Set.copyOf(dto.matrixOpen()));
        canvas.getMatrix().setShowingExternals(dto.matrixExternals());
    }

    private static S202Project.ViewDto layeredViewOfTheWholeAnalysis() {
        return new S202Project.ViewDto(
                ViewDescriptor.Kind.LAYERED.name(), null, null, List.of(), List.of(), null, false,
                ArchitectureCanvas.DEFAULT_PACKAGE_DEPTH, false, false, false, false, false, List.of(),
                1.0, 0.0, 0.0, Map.of(), null, true, List.of(), List.of(), false, false, List.of(), false);
    }

    /** Schließt das aktive Dokument mit allen seinen Tabs; die anderen bleiben offen. */
    public void closeProject() {
        AnalysisDocument document = viewManager.activeDocument();
        if (document == null) {
            return;
        }
        viewManager.closeDocument(document);
        if (viewManager.activeDocument() == null) {
            progress.progress("Ready to analyze code. Open JARs, Python source roots, or C source roots to begin.", 0);
        } else {
            progress.progress("Closed " + document.name(), 1);
        }
    }

    // Looked up when needed, not in a field initializer: this controller is
    // created inside S202Module's constructor, while the bean scope is still
    // being built and Lookup answers null.
    private static ProjectStore projectStore() {
        return Lookup.require(ProjectStore.class);
    }

    private static ProjectMapper projectMapper() {
        return Lookup.require(ProjectMapper.class);
    }

    /** Der Dateiname ohne die Projekt-Endung. */
    private static String documentName(Path projectFile) {
        String name = projectFile.getFileName().toString();
        for (String suffix : List.of(".s202.json", ".json")) {
            if (name.toLowerCase().endsWith(suffix) && name.length() > suffix.length()) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
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
