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
import de.weigend.s202.domain.DependencyDetails;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.Placement;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.PlanState;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.ViewDescriptor;
import de.weigend.s202.ui.core.events.CutTangleEdgeEvent;
import de.weigend.s202.ui.core.events.CutTangleEdgesEvent;
import de.weigend.s202.ui.core.events.DependencySelectionEvent;
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
import java.util.stream.Stream;

/**
 * WFX module for the {@link TopTanglesView}. Sits between the outline
 * explorer and the quality view in the LEFT dock.
 * <p>
 * Follows {@link WindowManager#getActiveLookup()}: when another architecture
 * tab becomes the active one, the view recomputes the top tangles from that
 * tab's domain model, scoped to the current selection (class → enclosing
 * package's subtree, package → its subtree, none → all classes). Cuts and moves
 * count as the tab shows them, see {@link PlanState}.
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
    private ChangeListener<PlanState> planListener;

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
        tanglesView.setOnCutEdge((from, to) -> {
            showPlan();
            bus.publish(new CutTangleEdgeEvent(from, to, tanglesView));
        });
        tanglesView.setOnRestoreEdge((from, to) ->
                bus.publish(new RestoreTangleEdgeEvent(from, to, tanglesView)));
        tanglesView.setOnCutAll(edges -> {
            showPlan();
            bus.publish(new CutTangleEdgesEvent(edges, tanglesView));
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

        bus.subscribe(DependencySelectionEvent.class, ev -> {
            if (ev.getSource() instanceof ArchitectureCanvas canvas) {
                tangleTabs.revealInOutline(ev.getSelection(), canvas);
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

    /** A cut is to be seen where it is made. */
    private void showPlan() {
        if (boundView != null) {
            boundView.setShowPlan(true);
        }
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
        boolean samePlan = sameDataset && boundView.getPlan().equals(newBound.getPlan());
        boolean sameTangleScope = sameDataset
                && Objects.equals(activeScope, scopeFor(newBound, newBound.getDomainModel()));

        unbind();

        if (newBound == null) {
            clear();
            return;
        }

        boundView = newBound;
        if (!sameDataset || (!sameTangleScope && newBound.isScopeOwner())) {
            applyCurrentScope(true);
        } else if (!samePlan) {
            applyCurrentScope(false);
        }

        rootListener = (obs, was, isNow) -> applyCurrentScope(true);
        newBound.architectureRootProperty().addListener(rootListener);
        planListener = (obs, was, isNow) -> applyCurrentScope(false);
        newBound.planProperty().addListener(planListener);
    }

    private void unbind() {
        if (boundView != null) {
            if (rootListener != null) {
                boundView.architectureRootProperty().removeListener(rootListener);
            }
            if (planListener != null) {
                boundView.planProperty().removeListener(planListener);
            }
        }
        boundView = null;
        rootListener = null;
        planListener = null;
    }

    /** Empties the panel and forgets what it showed: the next data is shown even if it is the same. */
    private void clear() {
        tanglesView.clear();
        lastDataset = null;
        lastScopeLabel = null;
        lastTangles = null;
        lastPreviewEdges = null;
        currentScope = null;
        currentScopeInitialized = false;
    }

    private void requestScopeRefresh() {
        applyCurrentScope(false);
    }

    private void applyCurrentScope(boolean updateScopeFromSelection) {
        if (boundView == null) {
            clear();
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
        Set<DependencyEdge> appliedCutEdges = boundView.getPlan().cuts();
        Placement placement = boundView.getPlan().placement();

        // All of them: small cycles that are not listed still count for the hotspots.
        List<TopTanglesView.Tangle> allTangles = computeTopTangles(
                model, boundView.getRawDependencyModel(), boundView.getCycleBreakEdges(),
                appliedCutEdges, placement, scope, Integer.MAX_VALUE);
        List<TopTanglesView.Tangle> tangles = allTangles.subList(0, Math.min(TOP_N, allTangles.size()));
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
        return computeTopTangles(model, rawModel, cycleBreakEdges, Set.of(), Placement.OF_THE_CODE, scope, topN);
    }

    static List<TopTanglesView.Tangle> computeTopTangles(DomainModel model,
                                                        DependencyModel rawModel,
                                                        Set<DependencyEdge> cycleBreakEdges,
                                                        Set<DependencyEdge> appliedCutEdges,
                                                        Placement placement,
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
        List<de.weigend.s202.domain.architecture.Tangle> chosen = placement.tangles(graph, finder).stream()
                .map(de.weigend.s202.domain.architecture.Tangle::new)
                .filter(t -> t.inScope(scope, placement))
                // Package tangles (at least one package member) first — they are the
                // architectural findings; class-only tangles inside one package follow.
                .sorted(Comparator.comparing(
                                (de.weigend.s202.domain.architecture.Tangle t) ->
                                        t.members().stream().noneMatch(m -> model.getPackage(m) != null))
                        .thenComparing(Comparator.comparingInt(
                                (de.weigend.s202.domain.architecture.Tangle t) -> t.members().size()).reversed())
                        .thenComparing(t -> tangleKey(new ArrayList<>(t.members()))))
                .limit(topN)
                .toList();
        Map<String, List<DependencyEdge>> edges = edgesByTangle(chosen, graph, placement);
        return chosen.stream()
                .map(t -> toTangle(t, edges.getOrDefault(firstMember(t), List.of()),
                        model, rawModel, cycleBreakEdges, appliedCutEdges, placement))
                .toList();
    }

    /**
     * The class dependencies between two members of one tangle, by its first
     * member: the members are siblings, so each is in one tangle only.
     */
    private static Map<String, List<DependencyEdge>> edgesByTangle(
            List<de.weigend.s202.domain.architecture.Tangle> tangles, Map<String, Set<String>> graph,
            Placement placement) {
        Map<String, String> tangleOf = new HashMap<>();
        tangles.forEach(t -> t.members().forEach(member -> tangleOf.put(member, firstMember(t))));
        Map<String, List<DependencyEdge>> result = new HashMap<>();
        graph.forEach((from, targets) -> {
            for (String to : targets) {
                ContainerProjection.Projection p = graph.containsKey(to) ? placement.project(from, to) : null;
                String tangle = p == null ? null : tangleOf.get(p.fromSibling());
                if (tangle != null && tangle.equals(tangleOf.get(p.toSibling()))) {
                    result.computeIfAbsent(tangle, k -> new ArrayList<>()).add(new DependencyEdge(from, to));
                }
            }
        });
        return result;
    }

    private static String firstMember(de.weigend.s202.domain.architecture.Tangle tangle) {
        return tangle.members().iterator().next();
    }

    private static TopTanglesView.Tangle toTangle(de.weigend.s202.domain.architecture.Tangle tangle,
                                                  List<DependencyEdge> between,
                                                  DomainModel model,
                                                  DependencyModel rawModel,
                                                  Set<DependencyEdge> cycleBreakEdges,
                                                  Set<DependencyEdge> appliedCutEdges,
                                                  Placement placement) {
        List<String> sortedMembers = new ArrayList<>(tangle.members());
        List<TopTanglesView.TangleEdge> edges = new ArrayList<>();
        for (DependencyEdge edge : between) {
            edges.add(new TopTanglesView.TangleEdge(
                    edge.from(), edge.to(), buildKindEntries(rawModel, edge.from(), edge.to()),
                    isCycleBreakEdge(cycleBreakEdges, edge.from(), edge.to()),
                    isCycleBreakEdge(appliedCutEdges, edge.from(), edge.to())));
        }
        edges.sort(Comparator.comparing(TopTanglesView.TangleEdge::from)
                .thenComparing(TopTanglesView.TangleEdge::to));
        return new TopTanglesView.Tangle(
                sortedMembers.size(),
                tangleKey(sortedMembers),
                tangle.label(m -> model.getPackage(m) != null, placement),
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
        return DependencyDetails.of(rawModel, from, to).calls().stream()
                // Constructor calls are what the "instantiates" entry stands for.
                .filter(call -> !call.isConstructor())
                .flatMap(call -> call.descriptors().isEmpty()
                        ? Stream.of(call.methodName())
                        : call.descriptors().stream().map(descriptor -> call.methodName() + descriptor))
                .map(detail -> new TopTanglesView.KindEntry(EdgeKind.CALLS, detail))
                .distinct()
                .sorted(Comparator.comparing(TopTanglesView.KindEntry::detail))
                .toList();
    }

    static List<TopTanglesView.HotspotEntryRow> computeHotspots(List<TopTanglesView.Tangle> tangles, int topN) {
        // The distinct back-edges per called method across all tangles. They are
        // class dependencies like every other cut: what the preview removes is
        // the whole dependency, not the one call.
        Map<String, Set<DependencyEdge>> methodToEdges = new HashMap<>();
        for (TopTanglesView.Tangle tangle : tangles) {
            for (TopTanglesView.TangleEdge edge : tangle.edges()) {
                if (!edge.cycleBreakEdge()) continue;
                for (TopTanglesView.KindEntry entry : edge.entries()) {
                    if (entry.kind() == EdgeKind.CALLS && entry.detail() != null) {
                        String label = simple(edge.to()) + "." + methodLabel(entry.detail());
                        methodToEdges.computeIfAbsent(label, k -> new HashSet<>())
                                .add(new DependencyEdge(edge.from(), edge.to()));
                    }
                }
            }
        }
        return methodToEdges.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .sorted(Comparator.<Map.Entry<String, Set<DependencyEdge>>>comparingInt(e -> e.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(topN)
                .map(e -> new TopTanglesView.HotspotEntryRow(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream()
                                .sorted(Comparator.comparing((DependencyEdge edge) -> simple(edge.from()))
                                        .thenComparing(DependencyEdge::from)
                                        .thenComparing(DependencyEdge::to))
                                .map(edge -> new TopTanglesView.HotspotCallerRow(edge.from(), edge.to()))
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

    private static boolean inScope(String fqn, String scope) {
        if (scope == null) {
            return true;
        }
        return fqn.equals(scope) || fqn.startsWith(scope + ".");
    }
}
