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
package de.weigend.s202.ui.views.tangle;

import de.weigend.s202.ui.views.tangle.TangleEdgeMethodResolver;
import de.weigend.s202.ui.core.model.TangleFilter;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.arrows.TangleEdgeScope;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.Dialogs;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.RefactoringPreviewState;
import de.weigend.s202.ui.core.events.MethodSelectionEvent;
import de.weigend.s202.ui.core.events.NodeSelectionEvent;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.application.Platform;

import java.util.EventObject;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Öffnet und pflegt die Tangle-Tabs: pro Tangle ein wiederverwendeter Tab.
 * Ein Tangle-Tab ist die normale Architekturansicht, beschränkt auf das
 * Tangle und als Graph gezeichnet: die Mitglieder als Knoten (Symbol und
 * Name, ohne Box), Paket-Mitglieder zunächst zugeklappt und aufgeklappt nur
 * mit den beteiligten Klassen, und als Pfeile nur die Abhängigkeiten zwischen
 * zwei Mitgliedern — gezeichnet nach dem, was sie sind (Back-Edge,
 * Klassenzyklus, nur Paketzyklus), beschriftet mit ihrem Gewicht. Die
 * {@link TangleLegendBar} erklärt und filtert.
 * Dazu die Verteilung der Preview-Cuts (cut/restore) auf alle offenen Views.
 */
public final class TangleTabController {

    private final ArchitectureViewManager viewManager;
    private final RefactoringPreviewState previewState;
    private final ProgressPublisher progress;

    public TangleTabController(ArchitectureViewManager viewManager,
                               RefactoringPreviewState previewState,
                               ProgressPublisher progress) {
        this.viewManager = viewManager;
        this.previewState = previewState;
        this.progress = progress;
    }

    /**
     * Open the Tangle tab focused on a specific tangle. Each tangle entry gets
     * one tab, reused on later double-clicks of that same entry.
     * <p>
     * The tab is a regular architecture view cut down to the tangle (see
     * {@link TangleFilter#scope}) with the dependency arrows switched on;
     * collapsed packages roll their dependencies up into one counted arrow
     * as usual.
     */
    public void openTangleView(Set<String> members, String tangleKey, String title) {
        if (members == null || members.isEmpty()) {
            return;
        }
        ArchitectureWfxView source = viewManager.focusedSourceArchitectureView();
        if (source == null) {
            return;
        }
        ArchitectureCanvas sourceView = source.getArchitectureView();
        ArchitectureNode sourceRoot = sourceView.getArchitectureRoot();
        if (sourceRoot == null) {
            return;
        }
        ArchitectureNode filteredRoot = TangleFilter.scope(sourceRoot, members, false);
        if (filteredRoot == null) {
            Dialogs.showError("Open Tangle", "None of the tangle's classes were found in the focused architecture.");
            return;
        }

        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView wrapper = reusableTangleWrapper(wm, tangleKeyOf(members, tangleKey), title);
        ArchitectureCanvas tangleView = wrapper.getArchitectureView();

        // Carry the focused view's models through so the side panels keep
        // working when this new tab gains focus.
        tangleView.setDomainModel(sourceView.getDomainModel());
        tangleView.setRawDependencyModel(sourceView.getRawDependencyModel());
        tangleView.setCycleBreakEdges(sourceView.getCycleBreakEdges());
        tangleView.setAppliedCutEdges(previewState.cuts());
        // Classes only: the tree holds nothing but them and their package
        // chains, show all of it. Otherwise every member starts as one node,
        // so the first picture is the tangle itself: its members and the
        // weighted dependencies between them. Opening a package member
        // shows the classes behind its arrows.
        tangleView.setPackageDepth(isClassOnly(members, sourceView.getRawDependencyModel())
                ? CLASS_ONLY_DEPTH : MEMBERS_ONLY_DEPTH);

        // The scope tells the arrows which dependencies are the tangle; the
        // legend above the view explains and filters them.
        TangleEdgeScope scope = new TangleEdgeScope(members);
        TangleLegendBar legend = (TangleLegendBar) tangleView.getTop();
        legend.bind(scope, () -> {
            ArchitectureNode root = TangleFilter.scope(sourceRoot, members, scope.isShowContext());
            if (root != null) {
                showRoot(tangleView, root);
            }
        });
        tangleView.setTangleScope(scope);

        // The legend's context switch survives reopening a tab; a fresh tab
        // starts without context.
        ArchitectureNode context = scope.isShowContext() ? TangleFilter.scope(sourceRoot, members, true) : null;
        showRoot(tangleView, context != null ? context : filteredRoot);
        tangleView.setQualityMetrics(sourceView.getQualityMetrics());
        legend.refreshCounts();

        wm.showView(wrapper);
    }

    private static String tangleKeyOf(Set<String> members, String tangleKey) {
        return tangleKey == null || tangleKey.isBlank()
                ? members.stream().sorted().collect(Collectors.joining("|"))
                : tangleKey;
    }

    /** Shows {@code root} in the tangle view with the arrows on, keeping the user's zoom. */
    private static void showRoot(ArchitectureCanvas tangleView, ArchitectureNode root) {
        // Snapshot the current zoom before setArchitectureRoot wipes it via
        // resetZoom — the user typically tunes the zoom once for a tangle
        // and we don't want each subsequent open-from-tree to snap back to
        // 100%. -1 sentinel = no previous zoom (singleton just created).
        double previousZoom = -1;
        javafx.beans.property.ReadOnlyDoubleProperty zoomProp = tangleView.zoomFactorProperty();
        if (zoomProp != null) {
            previousZoom = zoomProp.get();
        }

        tangleView.setArchitectureRoot(root);

        // Every root build switches the arrows off; a tangle tab is about the
        // dependencies, so switch them back on.
        tangleView.setShowDependencies(true);

        // Restore the captured zoom (if any) — defer one pulse so the new
        // ZoomController has a laid-out content node to scale against.
        if (previousZoom > 0) {
            final double zoom = previousZoom;
            Platform.runLater(() -> tangleView.setZoom(zoom));
        }
    }

    /** Package depth for views of classes only — the maximum the toolbar's depth spinner offers. */
    private static final int CLASS_ONLY_DEPTH = 10;
    /** Package depth that leaves every package member collapsed. */
    private static final int MEMBERS_ONLY_DEPTH = 0;

    private static boolean isClassOnly(Set<String> members, DependencyModel rawModel) {
        if (rawModel == null) {
            return false;
        }
        for (String member : members) {
            if (rawModel.getClass(member) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the tangle wrapper for {@code key}, reused if still registered;
     *         otherwise a freshly created one.
     */
    @SuppressWarnings("unchecked")
    private ArchitectureWfxView reusableTangleWrapper(WindowManager wm, String key, String title) {
        ArchitectureWfxView existing = viewManager.tangleViewFor(key);
        if (existing != null && wm.hasRegisteredView(existing)) {
            return existing;
        }
        String viewId = viewManager.nextViewId();
        ArchitectureCanvas tangleView = new ArchitectureCanvas();
        tangleView.setScopeOwner(false);
        // The tree is rooted at the tangle's container; its direct content
        // are the members, none of which may be skipped as a namespace wrapper.
        tangleView.setSkipTransparentTopLevelPackages(false);
        // A tangle is a graph: nodes and weighted arrows, not nested boxes.
        tangleView.setPlainNodes(true);
        tangleView.setTop(new TangleLegendBar(tangleView));
        tangleView.setStatusSink(progress::status);
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        tangleView.setOnNodeSelected(fqn -> bus.publish(new NodeSelectionEvent(fqn, tangleView)));
        tangleView.setOnDependencyEdgeClicked((from, to) ->
                publishTangleEdgeSelection(bus, tangleView, from, to));
        viewManager.applyStylesheet(tangleView);
        String viewTitle = title == null || title.isBlank() ? "Tangle" : title;
        ArchitectureWfxView wrapper = new ArchitectureWfxView(viewId, viewTitle, tangleView);
        viewManager.registerArchitectureView(wrapper);
        viewManager.putTangleView(key, wrapper);
        return wrapper;
    }

    /* ----- Preview-Cuts auf alle offenen Views verteilen -------------------- */

    public void applyPreviewCutToViews(String from, String to) {
        previewState.add(new DependencyEdge(from, to));
        for (ArchitectureWfxView wrapper : viewManager.registeredArchitectureViews()) {
            wrapper.getArchitectureView().applyEdgeCut(from, to);
        }
    }

    public void applyPreviewCutsToViews(Set<DependencyEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        previewState.addAll(edges);
        for (ArchitectureWfxView wrapper : viewManager.registeredArchitectureViews()) {
            wrapper.getArchitectureView().applyEdgeCuts(edges);
        }
    }

    public void restorePreviewCutInViews(String from, String to) {
        previewState.remove(new DependencyEdge(from, to));
        for (ArchitectureWfxView wrapper : viewManager.registeredArchitectureViews()) {
            wrapper.getArchitectureView().restoreEdgeCut(from, to);
        }
    }

    /* ----- Kanten-Selektion & Kanten-Sammlung -------------------------------- */

    private void publishTangleEdgeSelection(EventBus<EventObject> bus,
                                            ArchitectureCanvas tangleView,
                                            String from,
                                            String to) {
        if (from == null) {
            bus.publish(new MethodSelectionEvent(null, null, null, tangleView));
            return;
        }
        TangleEdgeMethodResolver.TargetMethod targetMethod =
                TangleEdgeMethodResolver.firstTargetMethodCalledByDependency(
                        tangleView.getRawDependencyModel(), from, to);
        if (targetMethod != null) {
            bus.publish(new MethodSelectionEvent(
                    targetMethod.className(), targetMethod.methodName(), targetMethod.descriptor(), tangleView));
            return;
        }
        bus.publish(new NodeSelectionEvent(to != null ? to : from, tangleView));
    }
}
