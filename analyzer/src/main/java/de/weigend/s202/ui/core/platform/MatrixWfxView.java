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

import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.matrix.MatrixView;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.lookup.api.MutableLookupContext;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.ViewKind;
import javafx.scene.Parent;
import javafx.scene.image.WritableImage;

/**
 * Der Tab mit der Dependency-Matrix eines Architektur-Tabs. Er ist eine zweite
 * Ansicht desselben Canvas: In seinem Kontext meldet er dessen Canvas und
 * Dokument, sodass Panels und Toolbar beim Wechsel zwischen Bild und Matrix
 * auf derselben Sache bleiben - mit denselben Moves, Filtern und derselben
 * Selektion.
 */
public final class MatrixWfxView implements SnapshotView {

    private final String viewId;
    private final String title;
    private final ArchitectureCanvas source;
    private final MutableLookupContext context;

    MatrixWfxView(String viewId, String title, ArchitectureCanvas source, AnalysisDocument document) {
        this.viewId = viewId;
        this.title = title;
        this.source = source;
        this.context = new MutableLookupContext(source, document, this);
    }

    /** Der Canvas, dessen Abhängigkeiten die Matrix zeigt. */
    public ArchitectureCanvas getSource() {
        return source;
    }

    public MatrixView getMatrix() {
        return source.getMatrix();
    }

    @Override
    public WritableImage snapshot() {
        return getMatrix().picture().snapshot(null, null);
    }

    @Override
    public LookupContext getLookup() {
        return context;
    }

    @Override
    public String getViewId() {
        return viewId;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getToolTipInfo() {
        return "Dependency matrix";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.CENTER;
    }

    /** Lebt wie ein Architektur-Tab: Schließen heißt weg, nicht versteckt. */
    @Override
    public ViewKind getKind() {
        return ViewKind.DOCUMENT;
    }

    @Override
    public Parent getRootNode() {
        return getMatrix();
    }

    @Override
    public double getViewAreaSize() {
        return 1.0;
    }
}
