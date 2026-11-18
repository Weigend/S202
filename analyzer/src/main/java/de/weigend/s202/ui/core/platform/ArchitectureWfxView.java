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
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.lookup.api.MutableLookupContext;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewKind;
import javafx.scene.Parent;

import java.net.URL;
import java.util.List;

/**
 * WFX {@link View} wrapper around the programmatic {@link ArchitectureCanvas}.
 * Lets WFX dock the existing UI without forcing it into an FXML round-trip.
 * <p>
 * Über seinen {@linkplain #getLookup() Kontext} sagt der Tab, was er zeigt:
 * immer seinen {@link ArchitectureCanvas}, und sobald die Analyse da ist, ihr
 * {@link AnalysisDocument}. Panels, Toolbar und Menü-Aktionen fragen den
 * aktiven Kontext des WindowManagers nach diesen beiden Typen und müssen
 * keinen Tab kennen.
 */
public class ArchitectureWfxView implements View {

    public static final String VIEW_ID_PREFIX = "s202-architecture-";

    private final String viewId;
    private final String title;
    private final ArchitectureCanvas architectureView;
    private final ViewDescriptor descriptor;
    private final MutableLookupContext context;
    private AnalysisDocument document;

    /** Ein Tab für die Schichten-Sicht eines ganzen Dokuments. */
    public ArchitectureWfxView(String viewId, String title, ArchitectureCanvas architectureView) {
        this(viewId, title, architectureView, ViewDescriptor.layered());
    }

    /**
     * @param descriptor was dieser Tab zeigt — daraus lässt er sich nach dem
     *                   Laden eines Projekts wieder aufbauen
     */
    public ArchitectureWfxView(String viewId, String title, ArchitectureCanvas architectureView,
                               ViewDescriptor descriptor) {
        this.viewId = viewId;
        this.title = title;
        this.architectureView = architectureView;
        this.descriptor = descriptor;
        this.context = new MutableLookupContext(architectureView);
    }

    public ArchitectureCanvas getArchitectureView() {
        return architectureView;
    }

    /** Das Dokument, das dieser Tab zeigt; {@code null}, solange noch keines geladen ist. */
    public AnalysisDocument getDocument() {
        return document;
    }

    /**
     * Macht {@code document} zum Dokument dieses Tabs und meldet es im
     * Kontext. Die Daten des Dokuments in den Canvas zu bringen ist Sache von
     * {@link ArchitectureViewManager#attach}.
     */
    void setDocument(AnalysisDocument document) {
        this.document = document;
        context.set(document == null ? List.of(architectureView) : List.of(architectureView, document));
    }

    /**
     * Was dieser Tab zeigt, mit dem Stand von jetzt: Was seit dem Öffnen per
     * „Add to Scope“ hinzukam und ob ein Tangle-Tab gerade seinen Kontext
     * zeigt, steht im Canvas.
     */
    public ViewDescriptor getDescriptor() {
        boolean tangleContext = architectureView.getTangleScope() != null
                ? architectureView.getTangleScope().isShowContext()
                : descriptor.tangleContext();
        return descriptor.withState(
                descriptor.kind() == ViewDescriptor.Kind.SCOPE
                        ? architectureView.getScopeExtensions()
                        : descriptor.scopeExtensions(),
                tangleContext);
    }

    /** Schlüssel des Tangles, den dieser Tab zeigt; {@code null}, wenn er kein Tangle-Tab ist. */
    public String getTangleKey() {
        return descriptor.tangleKey();
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
        return "Java bytecode architecture view";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.CENTER;
    }

    /**
     * Architecture tabs are content-bound: a fresh instance is created per
     * Open JAR / Windows → New, multiple may coexist, and closing one means
     * "I'm done with this analysis" — not "I'd like to find this view in
     * the View menu later". Marking it {@link ViewKind#DOCUMENT} makes wfx
     * fully unregister on tab-X (no leak), exclude it from the auto-built
     * View menu, and close it on Restore Default Layout.
     */
    @Override
    public ViewKind getKind() {
        return ViewKind.DOCUMENT;
    }

    @Override
    public Parent getRootNode() {
        return architectureView;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 1.0;
    }
}
