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

import de.weigend.s202.ui.core.model.TangleFilter;
import de.weigend.s202.domain.DependencyDetails;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.arrows.DependencySelection;
import de.weigend.s202.ui.core.arrows.TangleEdgeScope;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.platform.AnalysisDocument;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.Dialogs;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.ViewDescriptor;
import de.weigend.s202.ui.core.events.MethodSelectionEvent;
import de.weigend.s202.ui.core.events.NodeSelectionEvent;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.application.Platform;

import java.util.EventObject;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Öffnet und pflegt die Tangle-Tabs: pro Dokument und Tangle ein
 * wiederverwendeter Tab.
 * Ein Tangle-Tab ist die normale Architekturansicht, beschränkt auf das
 * Tangle und als Graph gezeichnet: die Mitglieder als Knoten (Symbol und
 * Name, ohne Box), Paket-Mitglieder zunächst zugeklappt und aufgeklappt nur
 * mit den beteiligten Klassen, und als Pfeile nur die Abhängigkeiten zwischen
 * zwei Mitgliedern — gezeichnet nach dem, was sie sind (Back-Edge,
 * Klassenzyklus, nur Paketzyklus), beschriftet mit ihrem Gewicht. Die
 * {@link TangleLegendBar} erklärt und filtert.
 * Dazu die Preview-Cuts (cut/restore): Sie gehören dem Dokument und werden auf
 * dessen Tabs verteilt — die Tabs eines anderen Dokuments bleiben unberührt.
 */
public final class TangleTabController {

    private final ArchitectureViewManager viewManager;
    private final ProgressPublisher progress;

    public TangleTabController(ArchitectureViewManager viewManager,
                               ProgressPublisher progress) {
        this.viewManager = viewManager;
        this.progress = progress;
    }

    /**
     * Open the Tangle tab of the active document for a specific tangle. Each
     * tangle entry gets one tab per document, reused on later double-clicks
     * of that same entry. The tangle is looked for in what the active tab
     * shows.
     */
    public void openTangleView(Set<String> members, String tangleKey, String title) {
        if (members == null || members.isEmpty()) {
            return;
        }
        AnalysisDocument document = viewManager.activeDocument();
        if (document == null) {
            return;
        }
        ViewDescriptor descriptor = ViewDescriptor.tangle(
                title == null || title.isBlank() ? "Tangle" : title,
                members, tangleKeyOf(members, tangleKey), viewManager.activeDescriptor());
        if (open(document, descriptor, canvas -> { }, wrapper -> { }) == null) {
            Dialogs.showError("Open Tangle", "None of the tangle's classes were found in the focused architecture.");
        }
    }

    /**
     * Opens the tangle tab that {@code descriptor} describes — the
     * {@link ArchitectureViewManager.ViewOpener} for tangle tabs, used by the
     * double-click on a tangle entry and by a loaded project alike.
     * <p>
     * The tab is a regular architecture view cut down to the tangle (see
     * {@link TangleFilter#scope}) with the dependency arrows switched on;
     * collapsed packages roll their dependencies up into one counted arrow
     * as usual.
     *
     * @return the tab; {@code null} if the document has none of the tangle's members
     */
    public ArchitectureWfxView open(AnalysisDocument document, ViewDescriptor descriptor,
                                    Consumer<ArchitectureCanvas> prepare,
                                    Consumer<ArchitectureWfxView> whenBuilt) {
        Set<String> members = descriptor.tangleMembers();
        ArchitectureNode sourceRoot = viewManager.rootFor(document, descriptor);
        ArchitectureNode filteredRoot = sourceRoot == null ? null : TangleFilter.scope(sourceRoot, members, false);
        if (filteredRoot == null) {
            return null;
        }

        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView existing = openTangleTab(document, descriptor.tangleKey());
        ArchitectureWfxView wrapper = existing != null ? existing : newTangleTab(document, descriptor);
        if (existing != null) {
            // Reopened: back to the document's models and current cuts.
            viewManager.attach(wrapper, document);
        }
        ArchitectureCanvas tangleView = wrapper.getArchitectureView();
        // Classes only: the tree holds nothing but them and their package
        // chains, show all of it. Otherwise every member starts as one node,
        // so the first picture is the tangle itself: its members and the
        // weighted dependencies between them. Opening a package member
        // shows the classes behind its arrows.
        tangleView.setPackageDepth(isClassOnly(members, document.rawModel())
                ? CLASS_ONLY_DEPTH : MEMBERS_ONLY_DEPTH);
        prepare.accept(tangleView);

        // The scope tells the arrows which dependencies are the tangle; the
        // legend above the view explains and filters them.
        TangleEdgeScope scope = new TangleEdgeScope(members);
        TangleLegendBar legend = (TangleLegendBar) tangleView.getTop();
        if (descriptor.tangleContext()) {
            legend.preselectContext();
        }
        legend.bind(scope, () -> {
            ArchitectureNode root = TangleFilter.scope(sourceRoot, members, scope.isShowContext());
            if (root != null) {
                showRoot(tangleView, root);
            }
        });
        tangleView.setTangleScope(scope);

        // The legend's context switch survives reopening a tab; a fresh tab
        // starts without context unless its description asks for it.
        ArchitectureNode context = scope.isShowContext() ? TangleFilter.scope(sourceRoot, members, true) : null;
        showRoot(tangleView, context != null ? context : filteredRoot);
        legend.refreshCounts();

        wm.showView(wrapper);
        whenBuilt.accept(wrapper);
        return wrapper;
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

    /** @return the open tab of {@code document} that shows the tangle {@code key}, or {@code null}. */
    private ArchitectureWfxView openTangleTab(AnalysisDocument document, String key) {
        for (ArchitectureWfxView wrapper : viewManager.viewsOf(document)) {
            if (key.equals(wrapper.getTangleKey())) {
                return wrapper;
            }
        }
        return null;
    }

    /** Creates and registers the tab of {@code document} that {@code descriptor} describes. */
    @SuppressWarnings("unchecked")
    private ArchitectureWfxView newTangleTab(AnalysisDocument document, ViewDescriptor descriptor) {
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
        viewManager.applyStylesheet(tangleView);
        String viewTitle = viewManager.titleOf(document, descriptor.label());
        ArchitectureWfxView wrapper = new ArchitectureWfxView(viewId, viewTitle, tangleView, descriptor);
        // The models before the tab appears: the panels that follow the new
        // tab find the same document there, not an empty view.
        viewManager.attach(wrapper, document);
        viewManager.registerArchitectureView(wrapper);
        return wrapper;
    }

    /* ----- Preview-Cuts: im Dokument merken, auf seine Tabs verteilen -------- */

    /** @return {@code false}, wenn sich an den Cuts des Dokuments nichts geändert hat */
    public boolean applyPreviewCut(AnalysisDocument document, String from, String to) {
        if (document == null || !document.addPreviewCut(new DependencyEdge(from, to))) {
            return false;
        }
        for (ArchitectureWfxView wrapper : viewManager.viewsOf(document)) {
            wrapper.getArchitectureView().applyEdgeCut(from, to);
        }
        return true;
    }

    /** @return {@code false}, wenn sich an den Cuts des Dokuments nichts geändert hat */
    public boolean applyPreviewCuts(AnalysisDocument document, Set<DependencyEdge> edges) {
        if (document == null || edges == null || edges.isEmpty() || !document.addPreviewCuts(edges)) {
            return false;
        }
        for (ArchitectureWfxView wrapper : viewManager.viewsOf(document)) {
            wrapper.getArchitectureView().applyEdgeCuts(edges);
        }
        return true;
    }

    /** @return {@code false}, wenn sich an den Cuts des Dokuments nichts geändert hat */
    public boolean restorePreviewCut(AnalysisDocument document, String from, String to) {
        if (document == null || !document.removePreviewCut(new DependencyEdge(from, to))) {
            return false;
        }
        for (ArchitectureWfxView wrapper : viewManager.viewsOf(document)) {
            wrapper.getArchitectureView().restoreEdgeCut(from, to);
        }
        return true;
    }

    /* ----- Kanten-Selektion -------------------------------------------------- */

    /**
     * Shows in the outline what a class arrow selected in a tangle tab calls.
     * An arrow between packages stands for many classes; the outline has no
     * single place for it.
     */
    @SuppressWarnings("unchecked")
    public void revealInOutline(DependencySelection selection, ArchitectureCanvas tangleView) {
        ArchitectureWfxView wrapper = viewManager.wrapperOf(tangleView);
        if (wrapper == null || wrapper.getTangleKey() == null) {
            return;
        }
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        if (selection.isEmpty()) {
            bus.publish(new MethodSelectionEvent(null, null, null, tangleView));
            return;
        }
        if (!selection.isClassDependency()) {
            return;
        }
        String to = selection.to();
        DependencyDetails.Call call = DependencyDetails.of(
                tangleView.getRawDependencyModel(), selection.from(), to).mainCall().orElse(null);
        if (call == null) {
            bus.publish(new NodeSelectionEvent(to, tangleView));
            return;
        }
        // A descriptor the model does not know would find nothing; the name may.
        String descriptor = call.declaringClass() == null ? null : call.descriptor();
        bus.publish(new MethodSelectionEvent(to, call.member(), descriptor, tangleView));
    }
}
