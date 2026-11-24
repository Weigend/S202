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

import de.weigend.s202.domain.ContainerProjection;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.platform.AnalysisDocument;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.ViewDescriptor;
import de.weigend.s202.ui.core.events.CutTangleEdgeEvent;
import de.weigend.s202.ui.core.events.CutTangleEdgesEvent;
import de.weigend.s202.ui.core.events.OpenTangleEvent;
import de.weigend.s202.ui.core.events.RefactoringsResetEvent;
import de.weigend.s202.ui.core.events.RestoreTangleEdgeEvent;
import de.weigend.s202.ui.features.outline.OutlineExplorerView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WFX module for the {@link TopTanglesView}. Sits between the outline
 * explorer and the quality view in the LEFT dock.
 * <p>
 * Follows {@link WindowManager#getActiveLookup()}: when another architecture
 * tab becomes the active one, the view recomputes the top tangles from that
 * tab's domain model, scoped to the current selection (class → enclosing
 * package's subtree, package → its subtree, none → all classes). The preview
 * cuts it takes into account are those of the tab's {@link AnalysisDocument}.
 */
// Priority 25 (after Quality at 20) so we register last and our
// register-with-outline-sibling slot lands directly under outline,
// pushing the already-registered Quality view further down.
@Singleton
@Priority(25)
public class TopTanglesModule implements Module {

    private static final int TOP_N = 5;

    private TopTanglesView tanglesView;

    private ArchitectureViewManager viewManager;
    private TangleTabController tangleTabs;
    private LookupContext.Subscription activeViewSubscription;

    private ArchitectureCanvas boundView;
    private ChangeListener<ArchitectureNode> rootListener;

    // Last data we pushed into the view. Used to short-circuit repeated
    // applyCurrentScope calls (rebind + architectureRoot fire on
    // openTangleView) when the computed tangle list is identical — replacing
    // the TreeView root would otherwise discard the user's expansion state.
    private String lastScopeLabel;
    private String currentScope;
    private boolean currentScopeInitialized;
    private List<TopTanglesView.Tangle> lastTangles;
    private List<TopTanglesView.RefactoringPreviewEdge> lastPreviewEdges;
    private DomainModel lastDataset;

    @Override
    public String getName() {
        return "Top Tangles";
    }

    @Override
    public void preload() throws PlatformException {
        waitForDemoPreloader();
        tanglesView = new TopTanglesView();
    }

    private void waitForDemoPreloader() throws PlatformException {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying Top Tangles preload", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        viewManager = Lookup.lookup(ArchitectureViewManager.class);
        tangleTabs = new TangleTabController(viewManager, Lookup.lookup(ProgressPublisher.class));
        viewManager.registerOpener(ViewDescriptor.Kind.TANGLE, tangleTabs::open);
        WindowManager wm = Lookup.lookup(WindowManager.class);

        // Stack into the LEFT split alongside the outline; OutlineExplorerModule
        // (priority 10) has already registered by the time we run.
        View outline = wm.findView(OutlineExplorerView.VIEW_ID);
        if (outline != null) {
            wm.register(tanglesView, outline);
        } else {
            wm.register(tanglesView);
        }

        // Double-click on a tangle row -> bus event; S202Module opens one
        // dedicated architecture tab per tangle entry.
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        tanglesView.setOnOpenTangle(req ->
                bus.publish(new OpenTangleEvent(
                        new HashSet<>(req.tangle().members()),
                        req.tangle().key(), req.tangle().title(), tanglesView)));
        tanglesView.setOnCutEdge((from, to) ->
                bus.publish(new CutTangleEdgeEvent(from, to, tanglesView)));
        tanglesView.setOnRestoreEdge((from, to) ->
                bus.publish(new RestoreTangleEdgeEvent(from, to, tanglesView)));
        tanglesView.setOnCutAll(tangle -> {
            Set<DependencyEdge> candidates = tangle.edges().stream()
                    .filter(edge -> edge.cycleBreakEdge() && !edge.cutApplied())
                    .map(edge -> new DependencyEdge(edge.from(), edge.to()))
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (candidates.isEmpty()) {
                return;
            }
            bus.publish(new CutTangleEdgesEvent(candidates, tanglesView));
        });
        // Tangle tabs and preview cuts. A cut belongs to the document of the
        // view it was made in (or, made in this panel, to the active one): it
        // is recorded there and shown in that document's tabs only.
        bus.subscribe(OpenTangleEvent.class, ev -> {
            tangleTabs.openTangleView(ev.getMembers(), ev.getTangleKey(), ev.getTitle());
            return true;
        });
        bus.subscribe(CutTangleEdgeEvent.class, ev -> {
            if (tangleTabs.applyPreviewCut(viewManager.documentFor(ev.getSource()), ev.getFrom(), ev.getTo())) {
                requestScopeRefresh();
            }
            return true;
        });
        bus.subscribe(CutTangleEdgesEvent.class, ev -> {
            if (tangleTabs.applyPreviewCuts(viewManager.documentFor(ev.getSource()), ev.getEdges())) {
                requestScopeRefresh();
            }
            return true;
        });
        bus.subscribe(RestoreTangleEdgeEvent.class, ev -> {
            if (tangleTabs.restorePreviewCut(viewManager.documentFor(ev.getSource()), ev.getFrom(), ev.getTo())) {
                requestScopeRefresh();
            }
            return true;
        });

        bus.subscribe(RefactoringsResetEvent.class, ev -> {
            requestScopeRefresh();
            return true;
        });

        LookupContext active = wm.getActiveLookup();
        activeViewSubscription = active.observe(ArchitectureCanvas.class,
                canvases -> rebindTo(canvases.isEmpty() ? null : canvases.get(0)));
        rebindTo(active.lookup(ArchitectureCanvas.class));
    }

    @Override
    public void stop() {
        if (activeViewSubscription != null) {
            activeViewSubscription.cancel();
            activeViewSubscription = null;
        }
        unbind();
    }

    /** The preview cuts of the document the bound view shows. */
    private Set<DependencyEdge> boundCuts() {
        AnalysisDocument document = viewManager.documentOf(boundView);
        return document == null ? Set.of() : document.previewCuts();
    }

    private void rebindTo(ArchitectureCanvas newBound) {
        // Focus moving onto a side panel should not retrigger work for the
        // same chart — the tangle list and selection are still current.
        if (newBound == boundView) {
            return;
        }

        // The Tangle satellite tab shares the source view's domain & raw
        // models. A focus hop between source and tangle therefore yields
        // identical tangle data — but rebuilding the tree from setData()
        // would still discard expansion / selection state, which is exactly
        // what the user just acted on (the dbl-click that opened the tab).
        // Skip the recompute in that case; just re-attach listeners.
        boolean sameDataset = boundView != null && newBound != null
                && boundView.getDomainModel() != null
                && boundView.getDomainModel() == newBound.getDomainModel();
        String activeScope = currentScopeInitialized
                ? currentScope
                : scopeFor(boundView, boundView == null ? null : boundView.getDomainModel());
        boolean sameTangleScope = sameDataset
                && Objects.equals(activeScope, scopeFor(newBound, newBound.getDomainModel()));

        unbind();

        if (newBound == null) {
            tanglesView.clear();
            lastDataset = null;
            currentScope = null;
            currentScopeInitialized = false;
            return;
        }

        boundView = newBound;
        if (!sameDataset || (!sameTangleScope && newBound.isScopeOwner())) {
            applyCurrentScope(true);
        }

        rootListener = (obs, was, isNow) -> applyCurrentScope(true);
        newBound.architectureRootProperty().addListener(rootListener);
    }

    private void unbind() {
        if (boundView != null) {
            if (rootListener != null) {
                boundView.architectureRootProperty().removeListener(rootListener);
            }
        }
        boundView = null;
        rootListener = null;
    }

    private void requestScopeRefresh() {
        applyCurrentScope(false);
    }

    private void applyCurrentScope(boolean updateScopeFromSelection) {
        if (boundView == null) {
            tanglesView.clear();
            lastScopeLabel = null;
            currentScope = null;
            currentScopeInitialized = false;
            lastTangles = null;
            lastPreviewEdges = null;
            return;
        }
        DomainModel model = boundView.getDomainModel();
        if (model == null) {
            // Transient state — the view was just registered and openTangleView
            // hasn't pushed its models in yet. Leaving the tree alone preserves
            // the user's expansion / selection until the real data arrives via
            // the architectureRoot listener.
            return;
        }
        if (model != lastDataset) {
            lastDataset = model;
            currentScope = null;
            currentScopeInitialized = false;
        }

        if (updateScopeFromSelection || !currentScopeInitialized) {
            currentScope = scopeFor(boundView, model);
            currentScopeInitialized = true;
        }

        String scope = currentScope;
        String scopeLabel = scope == null ? "All classes" : scope;
        Set<DependencyEdge> appliedCutEdges = boundCuts();

        List<TopTanglesView.Tangle> tangles = computeTopTangles(
                model, boundView.getRawDependencyModel(), boundView.getCycleBreakEdges(), appliedCutEdges, scope, TOP_N);
        List<TopTanglesView.RefactoringPreviewEdge> previewEdges = previewEdgesForScope(appliedCutEdges, scope);

        // Skip the setData call when nothing actually changed — records'
        // generated equals walks the full edge / kind structure, so identical
        // tangle data short-circuits and the TreeView root stays in place.
        if (scopeLabel.equals(lastScopeLabel) && tangles.equals(lastTangles)
                && previewEdges.equals(lastPreviewEdges)) {
            return;
        }
        lastScopeLabel = scopeLabel;
        lastTangles = tangles;
        lastPreviewEdges = previewEdges;

        // Hotspots are computed from ALL tangles so small cycles outside the
        // top-N display list still contribute to the method edge count.
        List<TopTanglesView.Tangle> allTangles = computeTopTangles(
                model, boundView.getRawDependencyModel(), boundView.getCycleBreakEdges(),
                appliedCutEdges, scope, Integer.MAX_VALUE);
        tanglesView.setData(scopeLabel, tangles, previewEdges, computeHotspots(allTangles, TOP_N));
    }

    /**
     * Pick the package scope to compute tangles in. Class selection scopes to
     * the class's enclosing package so we still show a useful list of
     * neighbouring cycles instead of a single-row table. Package selection
     * scopes to itself. Null selection means global.
     */
    private static String resolveScope(DomainModel model, String selected) {
        if (selected == null || selected.isEmpty()) {
            return null;
        }
        if (model == null) {
            return selected;
        }
        if (model.getClass(selected) != null) {
            int dot = selected.lastIndexOf('.');
            return dot < 0 ? null : selected.substring(0, dot);
        }
        return selected;
    }

    private static String scopeFor(ArchitectureCanvas view, DomainModel model) {
        if (view == null) {
            return null;
        }
        String preferred = view.getPreferredScope();
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return resolveScope(model, view.getSelectedFullName());
    }

    /**
     * Compute the container tangles of the current model (applied preview
     * cuts removed from the class graph), keep those in {@code scope}, take
     * the largest {@code topN} and enumerate the class edges between their
     * members. Members are siblings of one container — packages covering
     * their subtree or classes. The {@code rawModel} (may be null) is used to
     * look up edge kinds for each from→to pair.
     */
    static List<TopTanglesView.Tangle> computeTopTangles(DomainModel model,
                                                        DependencyModel rawModel,
                                                        Set<DependencyEdge> cycleBreakEdges,
                                                        String scope, int topN) {
        return computeTopTangles(model, rawModel, cycleBreakEdges, Set.of(), scope, topN);
    }

    static List<TopTanglesView.Tangle> computeTopTangles(DomainModel model,
                                                        DependencyModel rawModel,
                                                        Set<DependencyEdge> cycleBreakEdges,
                                                        Set<DependencyEdge> appliedCutEdges,
                                                        String scope, int topN) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (var entry : model.getAllClasses().entrySet()) {
            String fqn = entry.getKey();
            Set<String> deps = entry.getValue().dependencies.stream()
                    .filter(d -> !isCycleBreakEdge(appliedCutEdges, fqn, d))
                    .collect(Collectors.toCollection(HashSet::new));
            graph.put(fqn, deps);
        }

        SCCFinder finder = Lookup.lookup(SCCFinder.class);
        return ContainerProjection.tangles(graph, finder).stream()
                .map(de.weigend.s202.domain.architecture.Tangle::new)
                .filter(t -> t.inScope(scope))
                // Package tangles (at least one package member) first — they are the
                // architectural findings; class-only tangles inside one package follow.
                .sorted(Comparator.comparing(
                                (de.weigend.s202.domain.architecture.Tangle t) ->
                                        t.members().stream().noneMatch(m -> model.getPackage(m) != null))
                        .thenComparing(Comparator.comparingInt(
                                (de.weigend.s202.domain.architecture.Tangle t) -> t.members().size()).reversed())
                        .thenComparing(t -> tangleKey(new ArrayList<>(t.members()))))
                .limit(topN)
                .map(t -> toTangle(t, model, graph, rawModel, cycleBreakEdges, appliedCutEdges))
                .toList();
    }

    private static TopTanglesView.Tangle toTangle(de.weigend.s202.domain.architecture.Tangle tangle,
                                                  DomainModel model,
                                                  Map<String, Set<String>> graph,
                                                  DependencyModel rawModel,
                                                  Set<DependencyEdge> cycleBreakEdges,
                                                  Set<DependencyEdge> appliedCutEdges) {
        List<String> sortedMembers = new ArrayList<>(tangle.members());
        List<TopTanglesView.TangleEdge> edges = new ArrayList<>();
        for (String from : graph.keySet()) {
            String fromMember = tangle.memberContaining(from);
            if (fromMember == null) continue;
            for (String to : graph.getOrDefault(from, Set.of())) {
                String toMember = tangle.memberContaining(to);
                if (toMember == null || toMember.equals(fromMember)) continue;
                boolean cycleBreakEdge = isCycleBreakEdge(cycleBreakEdges, from, to);
                boolean appliedCutEdge = isCycleBreakEdge(appliedCutEdges, from, to);
                edges.add(new TopTanglesView.TangleEdge(
                        from, to, buildKindEntries(rawModel, from, to), cycleBreakEdge, appliedCutEdge));
            }
        }
        edges.sort(Comparator.comparing(TopTanglesView.TangleEdge::from)
                .thenComparing(TopTanglesView.TangleEdge::to));
        return new TopTanglesView.Tangle(
                sortedMembers.size(),
                tangleKey(sortedMembers),
                tangle.label(m -> model.getPackage(m) != null),
                sortedMembers,
                edges);
    }

    private static boolean isCycleBreakEdge(Set<DependencyEdge> cycleBreakEdges,
                                            String from,
                                            String to) {
        return cycleBreakEdges != null && cycleBreakEdges.contains(new DependencyEdge(from, to));
    }

    private static List<TopTanglesView.RefactoringPreviewEdge> previewEdgesForScope(
            Set<DependencyEdge> appliedCutEdges, String scope) {
        if (appliedCutEdges == null || appliedCutEdges.isEmpty()) {
            return List.of();
        }
        return appliedCutEdges.stream()
                .filter(edge -> inScope(edge.from(), scope) && inScope(edge.to(), scope))
                .sorted(Comparator.comparing(DependencyEdge::from)
                        .thenComparing(DependencyEdge::to))
                .map(edge -> new TopTanglesView.RefactoringPreviewEdge(edge.from(), edge.to()))
                .toList();
    }

    /**
     * Decompose an edge into one entry per kind. {@link EdgeKind#CALLS}
     * follows {@link EdgeKind#values()} (extends, implements, imports, instantiates,
     * calls), so structural relations sit above call relations.
     */
    private static List<TopTanglesView.KindEntry> buildKindEntries(DependencyModel rawModel,
                                                                   String from, String to) {
        Set<EdgeKind> kinds = lookupKinds(rawModel, from, to);
        if (kinds.isEmpty()) {
            return List.of();
        }
        List<TopTanglesView.KindEntry> out = new ArrayList<>();
        for (EdgeKind kind : EdgeKind.values()) {
            if (!kinds.contains(kind)) {
                continue;
            }
            if (kind == EdgeKind.CALLS) {
                List<TopTanglesView.KindEntry> callEntries = buildCallKindEntries(rawModel, from, to);
                if (callEntries.isEmpty()) {
                    out.add(new TopTanglesView.KindEntry(kind, null));
                } else {
                    out.addAll(callEntries);
                }
            } else {
                out.add(new TopTanglesView.KindEntry(kind, null));
            }
        }
        return out;
    }

    private static List<TopTanglesView.KindEntry> buildCallKindEntries(DependencyModel rawModel,
                                                                       String from,
                                                                       String to) {
        if (rawModel == null) {
            return List.of();
        }
        DependencyModel.ClassInfo sourceClass = rawModel.getClass(from);
        if (sourceClass == null) {
            return List.of();
        }
        return sourceClass.methods.values().stream()
                .flatMap(method -> method.methodCalls.keySet().stream()
                        .filter(call -> callOwnerMatchesTarget(call, to))
                        .flatMap(call -> callDetails(method, call).stream()
                                .map(detail -> new TopTanglesView.KindEntry(EdgeKind.CALLS, detail))))
                .distinct()
                .sorted(Comparator.comparing(TopTanglesView.KindEntry::detail))
                .toList();
    }

    private static List<String> callDetails(DependencyModel.MethodInfo sourceMethod, String methodCall) {
        String methodName = methodCallName(methodCall);
        if (methodName == null || "<init>".equals(methodName)) {
            return List.of();
        }
        Set<String> descriptors = sourceMethod.methodCallDescriptors.get(methodCall);
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of(methodName);
        }
        return descriptors.stream()
                .sorted()
                .map(descriptor -> methodName + descriptor)
                .toList();
    }

    static List<TopTanglesView.HotspotEntryRow> computeHotspots(List<TopTanglesView.Tangle> tangles, int topN) {
        // Collect distinct (from→to) edge pairs per called method across all tangles.
        // Two callers of the same method in the same tangle each count as a separate edge.
        // The map value encodes method-level to: "targetClass|methodName" so that a
        // Cut All for this hotspot only removes that specific method's contribution to
        // the dependency, leaving other method calls on the same class pair intact.
        Map<String, Set<Map.Entry<String, String>>> methodToEdges = new HashMap<>();
        for (TopTanglesView.Tangle tangle : tangles) {
            for (TopTanglesView.TangleEdge edge : tangle.edges()) {
                if (!edge.cycleBreakEdge()) continue;
                for (TopTanglesView.KindEntry entry : edge.entries()) {
                    if (entry.kind() == EdgeKind.CALLS && entry.detail() != null) {
                        String label = simple(edge.to()) + "." + methodLabel(entry.detail());
                        // Extract simple method name (strip descriptor) for the cut key.
                        String detail = entry.detail();
                        int paren = detail.indexOf('(');
                        String methodName = paren > 0 ? detail.substring(0, paren) : detail;
                        String methodLevelTo = edge.to() + "|" + methodName;
                        methodToEdges.computeIfAbsent(label, k -> new HashSet<>())
                                .add(Map.entry(edge.from(), methodLevelTo));
                    }
                }
            }
        }
        return methodToEdges.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .sorted(Comparator.<Map.Entry<String, Set<Map.Entry<String, String>>>>comparingInt(e -> e.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(topN)
                .map(e -> new TopTanglesView.HotspotEntryRow(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream()
                                .sorted(Comparator.comparing(p -> simple(p.getKey())))
                                .map(p -> new TopTanglesView.HotspotCallerRow(p.getKey(), p.getValue()))
                                .toList()))
                .toList();
    }

    private static String methodLabel(String detail) {
        int paren = detail.indexOf('(');
        return paren < 0 ? detail + "()" : detail.substring(0, paren) + "()";
    }

    private static String tangleKey(List<String> sortedMembers) {
        return String.join("|", sortedMembers);
    }

    private static String simple(String fqn) {
        if (fqn == null) {
            return "";
        }
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    private static Set<EdgeKind> lookupKinds(DependencyModel rawModel, String from, String to) {
        if (rawModel == null) {
            return Set.of();
        }
        DependencyModel.ClassInfo info = rawModel.getClass(from);
        return info == null ? Set.of() : info.getKinds(to);
    }

    private static boolean callOwnerMatchesTarget(String methodCall, String targetClass) {
        String owner = methodCallOwner(methodCall);
        return owner != null
                && (targetClass.equals(owner) || targetClass.equals(outerClassName(owner)));
    }

    private static String methodCallOwner(String methodCall) {
        if (methodCall == null) {
            return null;
        }
        int dot = methodCall.lastIndexOf('.');
        return dot <= 0 ? null : methodCall.substring(0, dot);
    }

    private static String methodCallName(String methodCall) {
        if (methodCall == null) {
            return null;
        }
        int dot = methodCall.lastIndexOf('.');
        return dot < 0 || dot == methodCall.length() - 1 ? null : methodCall.substring(dot + 1);
    }

    private static String outerClassName(String className) {
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }

    private static boolean inScope(String fqn, String scope) {
        if (scope == null) {
            return true;
        }
        return fqn.equals(scope) || fqn.startsWith(scope + ".");
    }
}
