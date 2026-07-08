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

import de.weigend.s202.project.S202Project;
import de.weigend.s202.reader.ProjectScanner;
import de.weigend.s202.ui.core.platform.Dialogs;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.wfx.shell.RecentDirectories;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.concurrent.Task;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Alle „Open …“-Einstiege: JAR-/Source-Root-/Projekt-Chooser samt
 * Maven-/Gradle-Scan, die dann in die {@link AnalysisPipeline} münden.
 * Aus S202Module extrahiert.
 */
public final class SourceOpenController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceOpenController.class);

    private static final String PYTHON_ANALYZER = "Python";
    private static final String C_ANALYZER = "C";
    private static final String GO_ANALYZER = "Go";
    private static final String MAVEN_SCANNER = "Maven";
    private static final String GRADLE_SCANNER = "Gradle";

    private final AnalysisPipeline pipeline;
    private final ProgressPublisher progress;
    private final RecentDirectories recentDirs;
    private final Supplier<Stage> dialogOwner;

    public SourceOpenController(AnalysisPipeline pipeline,
                                ProgressPublisher progress,
                                RecentDirectories recentDirs,
                                Supplier<Stage> dialogOwner) {
        this.pipeline = pipeline;
        this.progress = progress;
        this.recentDirs = recentDirs;
        this.dialogOwner = dialogOwner;
    }

    /* ----- Menü-Einstiege ----------------------------------------------------- */

    public void openJarChooser() {
        List<Path> inputs = new JarFileLoader().chooseInput(dialogOwner.get());
        if (!inputs.isEmpty()) {
            pipeline.loadJarFiles(toFiles(inputs));
        }
    }

    public void openPythonSourceRoot() {
        List<Path> inputs = new GenericDirectoryLoader("Open Python Source", "Select Python source root")
                .chooseInput(dialogOwner.get());
        if (!inputs.isEmpty()) {
            pipeline.loadSourceRoot(AnalysisPipeline.requireLanguageAnalyzer(PYTHON_ANALYZER),
                    inputs.getFirst(), "PYTHON", "Python",
                    "Reading Python ASTs...", "No Python Modules Found",
                    "The selected source root does not contain analyzable .py files.");
        }
    }

    public void openCSourceRoot() {
        List<Path> inputs = new GenericDirectoryLoader("Open C Source", "Select C source root")
                .chooseInput(dialogOwner.get());
        if (!inputs.isEmpty()) {
            pipeline.loadSourceRoot(AnalysisPipeline.requireLanguageAnalyzer(C_ANALYZER),
                    inputs.getFirst(), "C", "C",
                    "Scanning C translation units...", "No C Sources Found",
                    "The selected source root does not contain analyzable .c files.");
        }
    }

    public void openGoModuleRoot() {
        List<Path> inputs = new GenericDirectoryLoader("Open Go Module", "Select Go module root (directory with go.mod)")
                .chooseInput(dialogOwner.get());
        if (!inputs.isEmpty()) {
            pipeline.loadSourceRoot(AnalysisPipeline.requireLanguageAnalyzer(GO_ANALYZER),
                    inputs.getFirst(), "GO", "Go",
                    "Scanning Go packages...", "No Go Sources Found",
                    "The selected directory does not contain a go.mod file or analyzable .go files.");
        }
    }

    public void openMavenProject() {
        List<Path> inputs = new MavenProjectLoader().chooseInput(dialogOwner.get());
        if (!inputs.isEmpty()) {
            scanMavenProjectAt(inputs.getFirst().getParent().toFile());
        }
    }

    public void openGradleProject() {
        List<Path> inputs = new GradleProjectLoader().chooseInput(dialogOwner.get());
        if (!inputs.isEmpty()) {
            scanGradleProjectAt(inputs.getFirst().getParent().toFile());
        }
    }

    /**
     * Unified toolbar entry point. Accepts JARs, a Maven {@code pom.xml}, or
     * a Gradle settings/build script in a single FileChooser and dispatches
     * to the right loader by filename.
     */
    public void openAnyChooser() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open JAR(s), Maven pom.xml, or Gradle build script");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All supported",
                        "*.jar", "pom.xml",
                        "settings.gradle", "settings.gradle.kts",
                        "build.gradle", "build.gradle.kts"),
                new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
                new FileChooser.ExtensionFilter("Maven POM", "pom.xml"),
                new FileChooser.ExtensionFilter("Gradle settings/build script",
                        "settings.gradle", "settings.gradle.kts",
                        "build.gradle", "build.gradle.kts"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File initial = recentDirs.initialAnyDirectory();
        if (initial != null) {
            fileChooser.setInitialDirectory(initial);
        }
        List<File> picked = fileChooser.showOpenMultipleDialog(dialogOwner.get());
        if (picked == null || picked.isEmpty()) {
            return;
        }

        // Single-file picks may be a project descriptor; check the filename first.
        if (picked.size() == 1) {
            File f = picked.get(0);
            String name = f.getName();
            if (name.equalsIgnoreCase("pom.xml")) {
                scanMavenProjectAt(f.getParentFile());
                return;
            }
            if (name.equalsIgnoreCase("settings.gradle") || name.equalsIgnoreCase("settings.gradle.kts")
                    || name.equalsIgnoreCase("build.gradle") || name.equalsIgnoreCase("build.gradle.kts")) {
                scanGradleProjectAt(f.getParentFile());
                return;
            }
        }

        // Otherwise treat as JAR selection — but reject mixed picks, since
        // mixing a pom.xml with JARs has no defined semantics.
        for (File f : picked) {
            if (!f.getName().toLowerCase().endsWith(".jar")) {
                Dialogs.showError("Mixed selection",
                        "Pick either ONE pom.xml / settings.gradle / build.gradle for a project import,\n"
                                + "or one or more *.jar files for direct analysis. Mixed selections aren't supported.");
                return;
            }
        }
        loadJarSelection(picked);
    }

    /** Routes a list of JAR files through the staging dialog when there's
     *  more than one, and into the analysis pipeline otherwise. Shared by the
     *  JAR-only menu entry and the unified toolbar chooser. */
    private void loadJarSelection(List<File> picked) {
        if (picked == null || picked.isEmpty()) {
            return;
        }
        recentDirs.setLastDirectory(picked.get(0).getParentFile());

        if (picked.size() == 1) {
            pipeline.loadJarFiles(picked);
            return;
        }
        SourceSetDialog.chooseSourceSet(dialogOwner.get(), recentDirs.lastDirectory(), picked)
                .ifPresent(selected -> {
                    if (!selected.isEmpty()) {
                        recentDirs.setLastDirectory(selected.get(0).getParentFile());
                        pipeline.loadJarFiles(selected);
                    }
                });
    }

    /* ----- Maven-/Gradle-Scan -------------------------------------------------- */

    private void scanMavenProjectAt(File root) {
        recentDirs.setLastProjectDirectory(root);
        ProjectScanner scanner = requireProjectScanner(MAVEN_SCANNER);
        runProjectScan(scanner.displayName(), root,
                () -> toScanResult(scanner.scan(root.toPath())),
                scanner.buildHint());
    }

    private void scanGradleProjectAt(File root) {
        recentDirs.setLastProjectDirectory(root);
        ProjectScanner scanner = requireProjectScanner(GRADLE_SCANNER);
        runProjectScan(scanner.displayName(), root,
                () -> toScanResult(scanner.scan(root.toPath())),
                scanner.buildHint());
    }

    /**
     * Adapter result so the Maven and Gradle scanner result records can flow
     * through a shared post-scan handler without bleeding their concrete
     * record types into the controller.
     */
    private record ScanResult(List<File> jars, List<String> missingArtifactModules, int scannedModuleCount) {}

    @FunctionalInterface
    private interface ScanCallable {
        ScanResult call() throws Exception;
    }

    private static ScanResult toScanResult(ProjectScanner.ProjectScanResult result) {
        return new ScanResult(
                toFiles(result.jars()),
                result.missingArtifactModules(),
                result.scannedModuleCount());
    }

    private void runProjectScan(String kind, File root, ScanCallable scanCallable, String buildHint) {
        progress.progress("Scanning " + kind + " project " + root.getName() + "…", -1);

        Task<ScanResult> task = new Task<>() {
            @Override
            protected ScanResult call() throws Exception {
                return scanCallable.call();
            }
        };
        task.setOnSucceeded(e -> handleScanResult(kind, root, task.getValue(), buildHint));
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            String msg = t != null ? t.getMessage() : "unknown error";
            LOGGER.error("{} project scan failed", kind, t);
            progress.progress("Error scanning " + kind + " project: " + msg, 1);
            Dialogs.showError(kind + " scan failed", "Could not scan " + kind + " project:\n" + msg);
        });
        Thread th = new Thread(task, "s202-" + kind.toLowerCase() + "-scan");
        th.setDaemon(true);
        th.start();
    }

    private void handleScanResult(String kind, File root, ScanResult result, String buildHint) {
        List<File> jars = result.jars();
        List<String> missing = result.missingArtifactModules();
        int total = result.scannedModuleCount();

        if (jars.isEmpty()) {
            progress.progress("No built JARs found in " + kind + " project " + root.getName(), 0);
            Dialogs.showError("No JARs found",
                    "Found no analyzable JARs under\n" + root.getAbsolutePath()
                            + "\n\nTry running \"" + buildHint + "\" first to produce module artifacts.");
            return;
        }

        int built = total - missing.size();
        String summary = jars.size() + " JAR(s) from " + built + "/" + total
                + " " + kind + " module(s)"
                + (missing.isEmpty() ? "" : " (" + missing.size() + " not built)");
        progress.progress(summary, 0);
        if (!missing.isEmpty()) {
            LOGGER.warn("{} project '{}': {} module(s) without artifact: {}",
                    kind, root.getName(), missing.size(), missing);
        }

        File initialDir = jars.get(0).getParentFile();
        SourceSetDialog.chooseSourceSet(dialogOwner.get(), initialDir, jars)
                .ifPresent(selected -> {
                    if (!selected.isEmpty()) {
                        recentDirs.setLastDirectory(selected.get(0).getParentFile());
                        pipeline.loadJarFiles(selected, AnalysisPipeline.projectSource(kind, root, selected));
                    }
                });
    }

    private static ProjectScanner requireProjectScanner(String displayName) {
        return Lookup.lookupAll(ProjectScanner.class).stream()
                .filter(scanner -> displayName.equalsIgnoreCase(scanner.displayName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No project scanner registered for " + displayName));
    }

    private static List<File> toFiles(List<Path> paths) {
        return paths.stream().map(Path::toFile).toList();
    }

    /* ----- Datei-Auswahl-Dialoge ------------------------------------------------ */

    private interface FileLoader {
        List<Path> chooseInput(Window owner);
    }

    private final class JarFileLoader implements FileLoader {
        @Override
        public List<Path> chooseInput(Window owner) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select JAR file(s) to analyze");
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            if (recentDirs.initialJarDirectory() != null) {
                fileChooser.setInitialDirectory(recentDirs.initialJarDirectory());
            }
            List<File> picked = fileChooser.showOpenMultipleDialog(owner);
            if (picked == null || picked.isEmpty()) {
                return List.of();
            }
            recentDirs.setLastDirectory(picked.get(0).getParentFile());
            if (picked.size() == 1) {
                return picked.stream().map(File::toPath).toList();
            }
            return SourceSetDialog.chooseSourceSet(dialogOwner.get(), recentDirs.lastDirectory(), picked)
                    .map(selected -> {
                        if (selected.isEmpty()) {
                            return List.<Path>of();
                        }
                        recentDirs.setLastDirectory(selected.get(0).getParentFile());
                        return selected.stream().map(File::toPath).toList();
                    })
                    .orElseGet(List::of);
        }
    }

    private final class MavenProjectLoader implements FileLoader {
        @Override
        public List<Path> chooseInput(Window owner) {
            File pom = chooseProjectFile("Select Maven project root pom.xml",
                    new FileChooser.ExtensionFilter("Maven POM", "pom.xml"));
            return pom == null ? List.of() : List.of(pom.toPath());
        }
    }

    private final class GradleProjectLoader implements FileLoader {
        @Override
        public List<Path> chooseInput(Window owner) {
            File buildScript = chooseProjectFile("Select Gradle root settings.gradle or build.gradle",
                    new FileChooser.ExtensionFilter("Gradle settings/build script",
                            "settings.gradle", "settings.gradle.kts",
                            "build.gradle", "build.gradle.kts"));
            return buildScript == null ? List.of() : List.of(buildScript.toPath());
        }
    }

    private final class GenericDirectoryLoader implements FileLoader {
        private final String menuLabel;
        private final String title;

        private GenericDirectoryLoader(String menuLabel, String title) {
            this.menuLabel = menuLabel;
            this.title = title;
        }

        @Override
        public List<Path> chooseInput(Window owner) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(title);
            File initial = recentDirs.initialSourceDirectory();
            if (initial != null) {
                chooser.setInitialDirectory(initial);
            }
            File root = chooser.showDialog(owner);
            if (root == null) {
                return List.of();
            }
            recentDirs.setLastProjectDirectory(root);
            return List.of(root.toPath());
        }
    }

    private File chooseProjectFile(String title, FileChooser.ExtensionFilter filter) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(filter,
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File initial = recentDirs.initialSourceDirectory();
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        return chooser.showOpenDialog(dialogOwner.get());
    }
}
