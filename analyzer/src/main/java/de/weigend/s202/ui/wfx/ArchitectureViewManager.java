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
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.ArchitectureViewStyle;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.wfx.events.NodeSelectionEvent;
import de.weigend.s202.ui.wfx.view.ArchitectureWfxView;
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
public final class ArchitectureViewManager {

    private final ProgressPublisher progress;
    private final RefactoringPreviewState previewCuts;

    private int viewCounter;
    private boolean propagatingArchitectureAnnotations;

    // Dedicated tangle tabs keyed by the tangle's member set. Each tangle row
    // gets one view instance and reopens/focuses that view on later requests.
    private final Map<String, ArchitectureWfxView> tangleViews = new HashMap<>();
    private final Map<ArchitectureView, S202Project.Source> viewSources = new HashMap<>();
    private final Map<ArchitectureView, LayoutInvariantReport> viewInvariantReports = new HashMap<>();

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
        ArchitectureView view = new ArchitectureView();
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

    public void applyStylesheet(ArchitectureView view) {
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
        ArchitectureView view = wrapper.getArchitectureView();
        view.architectureAnnotationsProperty().addListener((obs, oldValue, newValue) ->
                propagateArchitectureAnnotations(view, newValue));
        Lookup.lookup(de.weigend.s202.ui.wfx.whatif.WhatIfDependenciesModule.class)
                .dockUnder(wrapper);
    }

    private void propagateArchitectureAnnotations(ArchitectureView source,
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
                ArchitectureView target = wrapper.getArchitectureView();
                if (target == source || target.getDomainModel() != source.getDomainModel()) {
                    continue;
                }
                target.setArchitectureAnnotations(effective);
                if ((target.getViewStyle() == ArchitectureViewStyle.COMPONENT
                        || target.getViewStyle() == ArchitectureViewStyle.HEXAGONAL)
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

    public S202Project.Source sourceOf(ArchitectureView view, S202Project.Source fallback) {
        return viewSources.getOrDefault(view, fallback);
    }

    public void putSource(ArchitectureView view, S202Project.Source source) {
        viewSources.put(view, source);
    }

    public LayoutInvariantReport invariantsOf(ArchitectureView view) {
        return viewInvariantReports.get(view);
    }

    public void putInvariants(ArchitectureView view, LayoutInvariantReport report) {
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
        ArchitectureView view = wrapper.getArchitectureView();
        viewSources.remove(view);
        viewInvariantReports.remove(view);
        tangleViews.values().removeIf(wrapper::equals);
    }

    /* ----- Projektions-Views (Component / Hexagonal / Scope) ---------------- */

    public void newArchitectureWindow() {
        registerArchitectureView(createArchitectureView());
    }

    public void openComponentView() {
        openProjectionView("Component View", ArchitectureViewStyle.COMPONENT, "component");
    }

    public void openHexagonalView() {
        openProjectionView("Hexagonal View", ArchitectureViewStyle.HEXAGONAL, "hexagonal");
    }

    private void openProjectionView(String title, ArchitectureViewStyle style, String label) {
        ArchitectureWfxView sourceWrapper = focusedSourceArchitectureView();
        if (sourceWrapper == null) {
            progress.progress("No architecture view available for " + label + " projection", 1);
            return;
        }

        ArchitectureView sourceView = sourceWrapper.getArchitectureView();
        ArchitectureNode sourceRoot = sourceView.getArchitectureRoot();
        if (sourceRoot == null) {
            progress.progress("No architecture loaded for " + label + " projection", 1);
            return;
        }

        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView wrapper = createArchitectureView(title);
        ArchitectureView projectionView = wrapper.getArchitectureView();
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

    public void openScopeView(String scope, ArchitectureView requestedSourceView) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        ArchitectureView sourceView = requestedSourceView;
        if (sourceView == null) {
            ArchitectureWfxView source = focusedSourceArchitectureView();
            sourceView = source == null ? null : source.getArchitectureView();
        }
        if (sourceView == null) {
            return;
        }
        ArchitectureView finalSourceView = sourceView;
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
        ArchitectureView scopeView = wrapper.getArchitectureView();
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
