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
package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.wfx.shell.ArchitectureViewManager;
import de.weigend.s202.ui.wfx.shell.Dialogs;
import de.weigend.s202.ui.wfx.shell.ProgressPublisher;
import de.weigend.s202.ui.wfx.shell.RefactoringPreviewState;
import de.weigend.s202.ui.wfx.events.CutTangleEdgeEvent;
import de.weigend.s202.ui.wfx.events.MethodSelectionEvent;
import de.weigend.s202.ui.wfx.events.NodeSelectionEvent;
import de.weigend.s202.ui.wfx.events.RestoreTangleEdgeEvent;
import de.weigend.s202.ui.wfx.view.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.EventObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Öffnet und pflegt die dedizierten Tangle-Tabs: pro Tangle ein wiederver-
 * wendeter Tab mit dem {@code TangleEdgeRenderer}-Overlay, plus die
 * Verteilung der Preview-Cuts (cut/restore) auf alle offenen Views.
 * Aus S202Module extrahiert; die EventBus-Subscriptions bleiben dort.
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
     * The tab uses the dedicated {@code TangleEdgeRenderer} for the cycle
     * visualisation — independent of the toolbar's Show Dependencies / Show
     * SCC checkboxes, with arrows that dock to the box perimeters and an
     * orthogonal intra-SCC edge visualisation.
     */
    public void openTangleView(Set<String> members, String tangleKey, String title) {
        if (members == null || members.isEmpty()) {
            return;
        }
        ArchitectureWfxView source = viewManager.focusedSourceArchitectureView();
        if (source == null) {
            return;
        }
        ArchitectureView sourceView = source.getArchitectureView();
        ArchitectureNode sourceRoot = sourceView.getArchitectureRoot();
        if (sourceRoot == null) {
            return;
        }
        ArchitectureNode filteredRoot = TangleFilter.filter(sourceRoot, members);
        if (filteredRoot == null) {
            Dialogs.showError("Open Tangle", "None of the tangle's classes were found in the focused architecture.");
            return;
        }

        List<DependencyEdge> edges =
                collectInternalEdges(filteredRoot, members, sourceView.getRawDependencyModel());

        WindowManager wm = Lookup.lookup(WindowManager.class);
        String key = tangleKey == null || tangleKey.isBlank()
                ? members.stream().sorted().collect(Collectors.joining("|"))
                : tangleKey;
        ArchitectureWfxView wrapper = reusableTangleWrapper(wm, key, title);
        ArchitectureView tangleView = wrapper.getArchitectureView();

        // Snapshot the current zoom before setArchitectureRoot wipes it via
        // resetZoom — the user typically tunes the zoom once for a tangle
        // and we don't want each subsequent open-from-tree to snap back to
        // 100%. -1 sentinel = no previous zoom (singleton just created).
        double previousZoom = -1;
        javafx.beans.property.ReadOnlyDoubleProperty zoomProp = tangleView.zoomFactorProperty();
        if (zoomProp != null) {
            previousZoom = zoomProp.get();
        }

        // Carry the focused view's models through so the side panels keep
        // working when this new tab gains focus.
        tangleView.setDomainModel(sourceView.getDomainModel());
        tangleView.setRawDependencyModel(sourceView.getRawDependencyModel());
        tangleView.setCycleBreakEdges(sourceView.getCycleBreakEdges());
        tangleView.setAppliedTangleCutEdges(previewState.cuts());
        tangleView.setArchitectureRoot(filteredRoot);
        tangleView.setQualityMetrics(sourceView.getQualityMetrics());

        // Install the dedicated tangle edge overlay. The renderer listens to
        // layoutBounds itself so the first paint lands once the box layout
        // settles — no Platform.runLater fight with the FX pulse.
        tangleView.setTangleVisualization(edges, null, null);

        // Restore the captured zoom (if any) — defer one pulse so the new
        // ZoomController has a laid-out content node to scale against.
        if (previousZoom > 0) {
            final double zoom = previousZoom;
            Platform.runLater(() -> tangleView.setZoom(zoom));
        }

        wm.showView(wrapper);
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
        ArchitectureView tangleView = new ArchitectureView();
        tangleView.setTopTanglesScopeOwner(false);
        tangleView.setStatusSink(progress::status);
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        tangleView.setOnNodeSelected(fqn -> bus.publish(new NodeSelectionEvent(fqn, tangleView)));
        tangleView.setOnTangleEdgeClicked((from, to) ->
                publishTangleEdgeSelection(bus, tangleView, from, to));
        tangleView.setOnTangleEdgeCut((from, to) ->
                bus.publish(new CutTangleEdgeEvent(from, to, tangleView)));
        tangleView.setOnTangleEdgeRestore((from, to) ->
                bus.publish(new RestoreTangleEdgeEvent(from, to, tangleView)));
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
            wrapper.getArchitectureView().applyTangleEdgeCut(from, to);
        }
    }

    public void applyPreviewCutsToViews(Set<DependencyEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        previewState.addAll(edges);
        for (ArchitectureWfxView wrapper : viewManager.registeredArchitectureViews()) {
            wrapper.getArchitectureView().applyTangleEdgeCuts(edges);
        }
    }

    public void restorePreviewCutInViews(String from, String to) {
        previewState.remove(new DependencyEdge(from, to));
        for (ArchitectureWfxView wrapper : viewManager.registeredArchitectureViews()) {
            wrapper.getArchitectureView().restoreTangleEdgeCut(from, to);
        }
    }

    /* ----- Kanten-Selektion & Kanten-Sammlung -------------------------------- */

    private void publishTangleEdgeSelection(EventBus<EventObject> bus,
                                            ArchitectureView tangleView,
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

    /**
     * Walk the (already filtered) tangle subtree and emit one edge per
     * intra-tangle dependency. Edges are deduplicated by (from, to) and
     * sorted alphabetically for stable rendering.
     */
    private static List<DependencyEdge> collectInternalEdges(ArchitectureNode root, Set<String> members,
                                                             DependencyModel rawModel) {
        Set<DependencyEdge> seen = new LinkedHashSet<>();
        collectInternalEdgesRec(root, members, rawModel, seen);
        List<DependencyEdge> sorted = new ArrayList<>(seen);
        sorted.sort((a, b) -> {
            int c = a.from().compareTo(b.from());
            return c != 0 ? c : a.to().compareTo(b.to());
        });
        return sorted;
    }

    private static void collectInternalEdgesRec(ArchitectureNode node,
                                                Set<String> members,
                                                DependencyModel rawModel,
                                                Set<DependencyEdge> out) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS && members.contains(node.getFullName())) {
            String fromClass = node.getFullName();
            for (String dep : node.getDependencies()) {
                if (!members.contains(dep) || dep.equals(fromClass)) continue;
                // Replace CALLS dependencies with method-level edges so individual
                // method cuts don't silently remove unrelated calls on the same pair.
                Set<DependencyEdge> methodEdges = collectMethodLevelEdges(rawModel, fromClass, dep);
                if (methodEdges.isEmpty()) {
                    out.add(new DependencyEdge(fromClass, dep));  // non-CALLS (EXTENDS etc.)
                } else {
                    out.addAll(methodEdges);
                }
            }
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectInternalEdgesRec(child, members, rawModel, out);
        }
    }

    private static Set<DependencyEdge> collectMethodLevelEdges(
            DependencyModel rawModel, String fromClass, String toClass) {
        if (rawModel == null) return Set.of();
        DependencyModel.ClassInfo info = rawModel.getClass(fromClass);
        if (info == null) return Set.of();
        Set<DependencyEdge> result = new LinkedHashSet<>();
        for (DependencyModel.MethodInfo m : info.methods.values()) {
            for (String call : m.methodCalls.keySet()) {
                int dot = call.lastIndexOf('.');
                if (dot <= 0) continue;
                String owner = call.substring(0, dot);
                String methodName = call.substring(dot + 1);
                if (!toClass.equals(owner)) continue;
                if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) continue;
                result.add(new DependencyEdge(fromClass, toClass + "|" + methodName));
            }
        }
        return result;
    }
}
