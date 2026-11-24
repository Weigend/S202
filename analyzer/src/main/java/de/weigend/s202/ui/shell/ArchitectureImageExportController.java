/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.weigend.s202.ui.shell;

import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.platform.AnalysisDocument;
import de.weigend.s202.ui.core.platform.ArchitecturePngExporter;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import de.weigend.s202.ui.core.platform.Dialogs;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.RecentDirectories;
import de.weigend.s202.ui.core.platform.SnapshotView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/** Coordinates the file chooser and PNG export for the currently active graphic. */
final class ArchitectureImageExportController {

    private final ArchitectureViewManager viewManager;
    private final ArchitecturePngExporter exporter;
    private final RecentDirectories recentDirectories;
    private final ProgressPublisher progress;
    private final Supplier<Window> owner;

    ArchitectureImageExportController(ArchitectureViewManager viewManager,
                                      ArchitecturePngExporter exporter,
                                      RecentDirectories recentDirectories,
                                      ProgressPublisher progress,
                                      Supplier<Window> owner) {
        this.viewManager = viewManager;
        this.exporter = exporter;
        this.recentDirectories = recentDirectories;
        this.progress = progress;
        this.owner = owner;
    }

    /** Exports the focused 3D view, otherwise the architecture view the user works in. */
    void exportCurrentGraphic() {
        SnapshotView snapshotView =
                Lookup.lookup(WindowManager.class).getFocusedView() instanceof SnapshotView focused ? focused : null;
        ArchitectureCanvas canvas = viewManager.activeCanvas();
        if (snapshotView == null && canvas == null) {
            Dialogs.showError("Export Image", "No architecture view is active.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        chooser.setInitialFileName(fileName(titleOf(snapshotView, canvas), ".png"));
        File initialDirectory = recentDirectories.initialImageDirectory();
        if (initialDirectory != null) {
            chooser.setInitialDirectory(initialDirectory);
        }

        File selected = chooser.showSaveDialog(owner.get());
        if (selected == null) {
            return;
        }
        File target = withPngExtension(selected);
        recentDirectories.setLastImageDirectory(target.getParentFile());
        try {
            if (snapshotView != null) {
                exporter.write(snapshotView.snapshot(), target.toPath());
            } else {
                exporter.export(canvas, target.toPath());
            }
            progress.status("Image saved: " + target);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            Dialogs.showError("Export Image", "Could not export the image:\n" + message);
        }
    }

    /** The title of the tab, so the views of one analysis get different files. */
    private String titleOf(SnapshotView snapshotView, ArchitectureCanvas canvas) {
        AnalysisDocument document = viewManager.activeDocument();
        if (snapshotView != null) {
            return (document == null ? "" : document.name() + " ") + snapshotView.getTitle();
        }
        ArchitectureWfxView wrapper = viewManager.wrapperOf(canvas);
        if (wrapper != null) {
            return wrapper.getTitle();
        }
        return document == null ? null : document.name();
    }

    /** A title as a file name: other characters than letters, digits, dot and dash become one dash. */
    static String fileName(String title, String suffix) {
        String safe = title == null ? ""
                : title.replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return (safe.isBlank() ? "architecture" : safe) + suffix;
    }

    static File withPngExtension(File selected) {
        return selected.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                ? selected
                : new File(selected.getParentFile(), selected.getName() + ".png");
    }
}
