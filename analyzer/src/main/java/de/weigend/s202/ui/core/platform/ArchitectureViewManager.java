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

import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.core.events.NodeSelectionEvent;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewKind;
import io.softwareecg.wfx.windowmanager.api.WindowManager;

import java.util.ArrayList;
import java.util.EventObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Besitzt und verwaltet alle Architektur-Tabs: Erzeugung/Registrierung der
 * {@link ArchitectureWfxView}-Wrapper, Fokus-Auflösung, die View-Kontexte
 * (Quelle, Invarianten-Report), die Tangle-Tab-Registry sowie die
 * Projektions-Views (Component/Hexagonal/Scope). Aus S202Module extrahiert;
 * S202Module bleibt Composition-Root und verdrahtet nur noch.
 */
@jakarta.inject.Singleton
public final class ArchitectureViewManager {

    private final ProgressPublisher progress;
    private final RefactoringPreviewState previewCuts;

    private int viewCounter;
    private boolean propagatingArchitectureAnnotations;

    // Dedicated tangle tabs keyed by the tangle's member set. Each tangle row
    // gets one view instance and reopens/focuses that view on later requests.
    private final Map<String, ArchitectureWfxView> tangleViews = new HashMap<>();
    private final Map<ArchitectureCanvas, S202Project.Source> viewSources = new HashMap<>();
    private final Map<ArchitectureCanvas, LayoutInvariantReport> viewInvariantReports = new HashMap<>();

    @jakarta.inject.Inject
    public ArchitectureViewManager(ProgressPublisher progress, RefactoringPreviewState previewCuts) {
        this.progress = progress;
        this.previewCuts = previewCuts;
    }

    /* ----- Erzeugung & Registrierung --------------------------------------- */

    public ArchitectureWfxView createArchitectureView() {
        return createArchitectureView(null);
    }

    @SuppressWarnings("unchecked")
    public ArchitectureWfxView createArchitectureView(String title) {
        viewCounter++;
        ArchitectureCanvas view = new ArchitectureCanvas();
        view.setStatusSink(progress::status);

        // Bridge graph node selections (class or package) onto the bus so
        // the outline (and any future listener) can react without a direct
        // dependency.
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        view.setOnNodeSelected(fqn -> bus.publish(new NodeSelectionEvent(fqn, view)));

        applyStylesheet(view);

        return new ArchitectureWfxView(
                ArchitectureWfxView.VIEW_ID_PREFIX + viewCounter,
                title == null || title.isBlank() ? "Architecture " + viewCounter : title,
                view);
    }

    /** Nächste View-Id für Sonder-Wrapper (Tangle-Tabs), gleiche Zählung wie hier. */
    public String nextViewId() {
        viewCounter++;
        return ArchitectureWfxView.VIEW_ID_PREFIX + viewCounter;
    }

    public void applyStylesheet(ArchitectureCanvas view) {
        var css = getClass().getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            view.getStylesheets().add(css.toExternalForm());
        }
    }

    /**
     * Register an architecture wrapper with the {@link WindowManager} and,
     * on first call, dock the What-If Dependencies panel under it. Every
     * code path that creates an architecture view (Open JAR, New Window,
     * Open Scope, Open Tangle, Load Project) routes through here so the
     * Dependencies panel reliably attaches to the first chart that
     * appears.
     */
    public void registerArchitectureView(ArchitectureWfxView wrapper) {
        Lookup.lookup(WindowManager.class).register(wrapper);
        ArchitectureCanvas view = wrapper.getArchitectureView();
        view.architectureAnnotationsProperty().addListener((obs, oldValue, newValue) ->
                propagateArchitectureAnnotations(view, newValue));
        // Interessierte Module (z. B. What-If-Dependencies-Panel) docken sich
        // selbst an — der Manager kennt keine Komponenten.
        Lookup.lookup(io.softwareecg.wfx.platform.api.EventBus.class)
                .publish(new de.weigend.s202.ui.core.events.ArchitectureViewRegisteredEvent(wrapper, this));
    }

    private void propagateArchitectureAnnotations(ArchitectureCanvas source,
                                                  ArchitectureAnnotations annotations) {
        if (propagatingArchitectureAnnotations || source == null || source.getDomainModel() == null) {
            return;
        }
        propagatingArchitectureAnnotations = true;
        try {
            ArchitectureAnnotations effective =
                    annotations == null ? ArchitectureAnnotations.empty() : annotations;
            WindowManager wm = Lookup.lookup(WindowManager.class);
            for (View registered : wm.getRegisteredViews()) {
                if (!(registered instanceof ArchitectureWfxView wrapper)) {
                    continue;
                }
                ArchitectureCanvas target = wrapper.getArchitectureView();
                if (target == source || target.getDomainModel() != source.getDomainModel()) {
                    continue;
                }
                target.setArchitectureAnnotations(effective);
                if ((target.getViewStyle() == ArchitectureKind.COMPONENT
                        || target.getViewStyle() == ArchitectureKind.HEXAGONAL)
                        && target.hasRoot()) {
                    target.refreshStyleProjection();
                }
            }
        } finally {
            propagatingArchitectureAnnotations = false;
        }
    }

    /* ----- Fokus-Auflösung -------------------------------------------------- */

    public ArchitectureWfxView focusedArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View focused = wm.getFocusedView();
        if (focused instanceof ArchitectureWfxView a) {
            return a;
        }
        // When a tool panel (Outline, Quality, …) is focused, fall back to the
        // last architecture view that held focus rather than blindly picking the
        // first registered one.  This keeps toolbar actions and node-selection
        // events targeting the view the user was working in before switching to
        // the side panel.
        View last = wm.getLastFocusedView();
        if (last instanceof ArchitectureWfxView a) {
            return a;
        }
        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .findFirst()
                .orElse(null);
    }

    /** Pick a non-tangle architecture tab to use as the source for tangle filtering. */
    public ArchitectureWfxView focusedSourceArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View focused = wm.getFocusedView();
        if (focused instanceof ArchitectureWfxView arch && !tangleViews.containsValue(arch)) {
            return arch;
        }
        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(v -> !tangleViews.containsValue(v))
                .findFirst()
                .orElse(null);
    }

    public List<ArchitectureWfxView> registeredArchitectureViews() {
        return Lookup.lookup(WindowManager.class).getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .toList();
    }

    /* ----- View-Kontext (Quelle, Invarianten) ------------------------------- */

    public S202Project.Source sourceOf(ArchitectureCanvas view, S202Project.Source fallback) {
        return viewSources.getOrDefault(view, fallback);
    }

    public void putSource(ArchitectureCanvas view, S202Project.Source source) {
        viewSources.put(view, source);
    }

    public LayoutInvariantReport invariantsOf(ArchitectureCanvas view) {
        return viewInvariantReports.get(view);
    }

    public void putInvariants(ArchitectureCanvas view, LayoutInvariantReport report) {
        viewInvariantReports.put(view, report);
    }

    /* ----- Tangle-Tab-Registry ---------------------------------------------- */

    public ArchitectureWfxView tangleViewFor(String key) {
        return tangleViews.get(key);
    }

    public void putTangleView(String key, ArchitectureWfxView wrapper) {
        tangleViews.put(key, wrapper);
    }

    /* ----- Schließen & Zurücksetzen ------------------------------------------ */

    public void closeFocusedView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View focused = wm.getFocusedView();
        if (focused != null) {
            if (focused instanceof ArchitectureWfxView a) {
                forgetView(a);
            }
            // closeView is kind-aware: TOOL is hidden (still in View menu);
            // DOCUMENT is fully unregistered. Old code called unregister
            // unconditionally, which forcibly removed Outline/Quality from
            // the registry when the user happened to focus them.
            wm.closeView(focused);
        }
    }

    public void closeAllViews() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        // "Close All" means "close all open documents" — the user's analyses,
        // not the side panels. Filter to DOCUMENTs so TOOLs (Outline, Quality)
        // stay alive.
        for (View v : new ArrayList<>(wm.getVisibleViews())) {
            if (v.getKind() == ViewKind.DOCUMENT) {
                if (v instanceof ArchitectureWfxView a) {
                    forgetView(a);
                }
                wm.closeView(v);
            }
        }
    }

    /** Schließt alle Dokumente (auch unsichtbare) und leert alle Registries. */
    public void resetDocuments() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        for (View v : new ArrayList<>(wm.getRegisteredViews())) {
            if (v.getKind() == ViewKind.DOCUMENT) {
                if (v instanceof ArchitectureWfxView a) {
                    forgetView(a);
                }
                wm.closeView(v);
            }
        }
        tangleViews.clear();
        viewSources.clear();
        viewInvariantReports.clear();
    }

    private void forgetView(ArchitectureWfxView wrapper) {
        if (wrapper == null) {
            return;
        }
        ArchitectureCanvas view = wrapper.getArchitectureView();
        viewSources.remove(view);
        viewInvariantReports.remove(view);
        tangleViews.values().removeIf(wrapper::equals);
    }

    /* ----- Projektions-Views (Component / Hexagonal / Scope) ---------------- */

    public void newArchitectureWindow() {
        registerArchitectureView(createArchitectureView());
    }

    /**
     * Öffnet eine Stil-Projektion der fokussierten Analyse. Der Manager
     * kennt keine konkreten Stile mehr — der Stil ist reiner Parameter,
     * die Ansicht kommt über das StyleView-SPI des Canvas.
     */
    public void openStyleView(ArchitectureKind style) {
        String label = style.name().toLowerCase(java.util.Locale.ROOT);
        String title = Character.toUpperCase(label.charAt(0)) + label.substring(1) + " View";
        openProjectionView(title, style, label);
    }

    private void openProjectionView(String title, ArchitectureKind style, String label) {
        ArchitectureWfxView sourceWrapper = focusedSourceArchitectureView();
        if (sourceWrapper == null) {
            progress.progress("No architecture view available for " + label + " projection", 1);
            return;
        }

        ArchitectureCanvas sourceView = sourceWrapper.getArchitectureView();
        ArchitectureNode sourceRoot = sourceView.getArchitectureRoot();
        if (sourceRoot == null) {
            progress.progress("No architecture loaded for " + label + " projection", 1);
            return;
        }

        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView wrapper = createArchitectureView(title);
        ArchitectureCanvas projectionView = wrapper.getArchitectureView();
        projectionView.setViewStyle(style);
        projectionView.setArchitectureAnnotations(sourceView.getArchitectureAnnotations());
        projectionView.setRawDependencyModel(sourceView.getRawDependencyModel());
        projectionView.setDomainModel(sourceView.getDomainModel());
        projectionView.setCycleBreakEdges(sourceView.getCycleBreakEdges());
        projectionView.setAppliedTangleCutEdges(previewCuts.cuts());
        viewSources.put(projectionView, viewSources.get(sourceView));
        viewInvariantReports.put(projectionView, viewInvariantReports.get(sourceView));

        registerArchitectureView(wrapper);
        wm.showView(wrapper);

        projectionView.setArchitectureRootAsync(
                sourceRoot,
                buildProgress -> progress.javaFxBuildProgress("Building JavaFX " + label + " view", buildProgress),
                () -> {
                    projectionView.setQualityMetrics(sourceView.getQualityMetrics());
                    progress.progress("Opened " + label + " view", 1);
                });
    }

    public void openScopeView(String scope, ArchitectureCanvas requestedSourceView) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        ArchitectureCanvas sourceView = requestedSourceView;
        if (sourceView == null) {
            ArchitectureWfxView source = focusedSourceArchitectureView();
            sourceView = source == null ? null : source.getArchitectureView();
        }
        if (sourceView == null) {
            return;
        }
        ArchitectureCanvas finalSourceView = sourceView;
        ArchitectureNode sourceRoot = sourceView.getScopeExtensionSourceRoot();
        if (sourceRoot == null) {
            sourceRoot = sourceView.getArchitectureRoot();
        }
        if (sourceRoot == null) {
            return;
        }
        ArchitectureNode scopedRoot = filterPackageScope(sourceRoot, scope);
        if (scopedRoot == null) {
            Dialogs.showError("Open Scope", "Package scope was not found in the focused architecture: " + scope);
            return;
        }

        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView wrapper = createArchitectureView("Scope " + simple(scope));
        ArchitectureCanvas scopeView = wrapper.getArchitectureView();
        scopeView.setPreferredTopTanglesScope(scope);
        scopeView.enableScopeExtensionFrom(sourceRoot);
        scopeView.setArchitectureAnnotations(sourceView.getArchitectureAnnotations());
        scopeView.setDomainModel(sourceView.getDomainModel());
        scopeView.setRawDependencyModel(sourceView.getRawDependencyModel());
        scopeView.setCycleBreakEdges(sourceView.getCycleBreakEdges());
        scopeView.setAppliedTangleCutEdges(previewCuts.cuts());
        viewSources.put(scopeView, viewSources.get(sourceView));
        viewInvariantReports.put(scopeView, viewInvariantReports.get(sourceView));

        registerArchitectureView(wrapper);
        wm.showView(wrapper);

        scopeView.setArchitectureRootAsync(
                scopedRoot,
                buildProgress -> progress.javaFxBuildProgress("Building JavaFX scope view", buildProgress),
                () -> {
                    scopeView.setQualityMetrics(finalSourceView.getQualityMetrics());
                    scopeView.selectByFullName(scope);
                    progress.progress("Opened scope " + scope, 1);
                });
    }

    private static ArchitectureNode filterPackageScope(ArchitectureNode sourceRoot, String scope) {
        ArchitectureNode scopeNode = findPackageNode(sourceRoot, scope);
        if (scopeNode == null) {
            return null;
        }
        ArchitectureNode root = ArchitectureNodeCloner.cloneShallow(sourceRoot);
        root.addChild(ArchitectureNodeCloner.cloneTree(scopeNode));
        return root;
    }

    private static ArchitectureNode findPackageNode(ArchitectureNode node, String scope) {
        if (node.getType() == ArchitectureNode.NodeType.PACKAGE
                && scope.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findPackageNode(child, scope);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String simple(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
