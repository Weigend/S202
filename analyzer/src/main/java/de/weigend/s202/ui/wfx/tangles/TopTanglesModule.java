package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.wfx.ArchitectureWfxView;
import de.weigend.s202.ui.wfx.events.OpenTangleEvent;
import de.weigend.s202.ui.wfx.events.TangleEdgeSelectedEvent;
import de.weigend.s202.ui.wfx.outline.OutlineExplorerView;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmtg.api.View;
import io.softwareecg.wfx.windowmtg.api.WindowManager;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * WFX module for the {@link TopTanglesView}. Sits between the outline
 * explorer and the quality view in the LEFT dock.
 * <p>
 * Tracks {@link WindowManager#focusedViewProperty()}: when an
 * {@link ArchitectureWfxView} gains focus, the view recomputes the top
 * tangles from that view's domain model, scoped to the current selection
 * (class → enclosing package's subtree, package → its subtree, none → all
 * classes).
 */
// Priority 25 (after Quality at 20) so we register last and our
// register-with-outline-sibling slot lands directly under outline,
// pushing the already-registered Quality view further down.
@Singleton
@Priority(25)
public class TopTanglesModule implements Module {

    private static final int TOP_N = 5;

    private TopTanglesView tanglesView;

    private ArchitectureView boundView;
    private ChangeListener<ArchitectureNode> rootListener;
    private ChangeListener<String> selectionListener;

    // Last data we pushed into the view. Used to short-circuit repeated
    // applyCurrentScope calls (rebind + architectureRoot fire on
    // openTangleView) when the computed tangle list is identical — replacing
    // the TreeView root would otherwise discard the user's expansion state.
    private String lastScopeLabel;
    private List<TopTanglesView.Tangle> lastTangles;

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
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying Top Tangles preload", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        WindowManager wm = Lookup.lookup(WindowManager.class);

        // Stack into the LEFT split alongside the outline; OutlineExplorerModule
        // (priority 10) has already registered by the time we run.
        View outline = wm.findView(OutlineExplorerView.VIEW_ID);
        if (outline != null) {
            wm.register(tanglesView, outline);
        } else {
            wm.register(tanglesView);
        }

        // Double-click on a method/kind row → bus event; S202Module turns that
        // into a new architecture tab filtered to the tangle's classes and
        // pre-highlights the from→to SCC edge that backed the click.
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        tanglesView.setOnOpenTangle(req ->
                bus.publish(new OpenTangleEvent(
                        new HashSet<>(req.tangle().members()),
                        req.fromClass(), req.toClass(), tanglesView)));

        // Reverse direction: when the user picks an edge in the Tangle tab's
        // graph, mirror that selection in our tree so the textual list always
        // reflects what's highlighted in the chart.
        bus.subscribe(TangleEdgeSelectedEvent.class, ev -> {
            tanglesView.selectEdgeRow(ev.getFrom(), ev.getTo());
            return true;
        });

        wm.focusedViewProperty().addListener((obs, was, isNow) -> rebindToFocusedView());
        rebindToFocusedView();
    }

    @Override
    public void stop() {
        unbind();
    }

    private void rebindToFocusedView() {
        ArchitectureWfxView focused = focusedArchitectureView();
        ArchitectureView newBound = focused == null ? null : focused.getArchitectureView();

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

        unbind();

        if (newBound == null) {
            tanglesView.clear();
            return;
        }

        boundView = newBound;
        if (!sameDataset) {
            applyCurrentScope();
        }

        rootListener = (obs, was, isNow) -> applyCurrentScope();
        selectionListener = (obs, was, isNow) -> applyCurrentScope();
        newBound.architectureRootProperty().addListener(rootListener);
        newBound.selectedFullNameProperty().addListener(selectionListener);
    }

    private void unbind() {
        if (boundView != null) {
            if (rootListener != null) {
                boundView.architectureRootProperty().removeListener(rootListener);
            }
            if (selectionListener != null) {
                boundView.selectedFullNameProperty().removeListener(selectionListener);
            }
        }
        boundView = null;
        rootListener = null;
        selectionListener = null;
    }

    private void applyCurrentScope() {
        if (boundView == null) {
            tanglesView.clear();
            lastScopeLabel = null;
            lastTangles = null;
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

        String selected = boundView.getSelectedFullName();
        String scope = resolveScope(model, selected);
        String scopeLabel = scope == null ? "All classes" : scope;

        List<TopTanglesView.Tangle> tangles = computeTopTangles(
                model, boundView.getRawDependencyModel(), scope, TOP_N);

        // Skip the setData call when nothing actually changed — records'
        // generated equals walks the full edge / kind structure, so identical
        // tangle data short-circuits and the TreeView root stays in place.
        if (scopeLabel.equals(lastScopeLabel) && tangles.equals(lastTangles)) {
            return;
        }
        lastScopeLabel = scopeLabel;
        lastTangles = tangles;
        tanglesView.setData(scopeLabel, tangles);
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
        if (model.getClass(selected) != null) {
            int dot = selected.lastIndexOf('.');
            return dot < 0 ? null : selected.substring(0, dot);
        }
        return selected;
    }

    /**
     * Build the scoped class-dependency graph, run Tarjan, take the largest
     * {@code topN} tangles and enumerate their internal edges for display.
     * The {@code rawModel} (may be null) is used to look up edge kinds —
     * EXTENDS / IMPLEMENTS / CALLS / INSTANTIATES — for each from→to pair.
     */
    static List<TopTanglesView.Tangle> computeTopTangles(DomainModel model,
                                                        DependencyModel rawModel,
                                                        String scope, int topN) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (var entry : model.getAllClasses().entrySet()) {
            String fqn = entry.getKey();
            if (!inScope(fqn, scope)) {
                continue;
            }
            Set<String> deps = entry.getValue().dependencies.stream()
                    .filter(d -> inScope(d, scope))
                    .collect(Collectors.toCollection(HashSet::new));
            graph.put(fqn, deps);
        }

        List<StronglyConnectedComponent> sccs = new TarjanSCCFinder(graph).findSCCs();
        return sccs.stream()
                .filter(StronglyConnectedComponent::isTangle)
                .sorted(Comparator.comparingInt(StronglyConnectedComponent::getSize).reversed())
                .limit(topN)
                .map(scc -> toTangle(scc, graph, rawModel))
                .toList();
    }

    private static TopTanglesView.Tangle toTangle(StronglyConnectedComponent scc,
                                                  Map<String, Set<String>> graph,
                                                  DependencyModel rawModel) {
        Set<String> members = scc.getMembers();
        List<String> sortedMembers = members.stream().sorted().toList();
        List<TopTanglesView.TangleEdge> edges = new ArrayList<>();
        for (String from : sortedMembers) {
            for (String to : graph.getOrDefault(from, Set.of())) {
                if (members.contains(to)) {
                    edges.add(new TopTanglesView.TangleEdge(
                            from, to, buildKindEntries(rawModel, from, to)));
                }
            }
        }
        edges.sort(Comparator.comparing(TopTanglesView.TangleEdge::from)
                .thenComparing(TopTanglesView.TangleEdge::to));
        return new TopTanglesView.Tangle(members.size(), sortedMembers, edges);
    }

    /**
     * Decompose an edge into one entry per kind. {@link EdgeKind#CALLS}
     * expands further into one entry per method name (sourced from
     * {@link DependencyModel.MethodInfo#methodCalls}); falls back to a
     * single {@code calls} entry when no method name was captured. Order
     * follows {@link EdgeKind#values()} (extends, implements, instantiates,
     * calls), so structural relations sit above the call list.
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
                Set<String> names = lookupMethodNames(rawModel, from, to);
                if (names.isEmpty()) {
                    out.add(new TopTanglesView.KindEntry(EdgeKind.CALLS, null));
                } else {
                    for (String name : names) {
                        out.add(new TopTanglesView.KindEntry(EdgeKind.CALLS, name));
                    }
                }
            } else {
                out.add(new TopTanglesView.KindEntry(kind, null));
            }
        }
        return out;
    }

    private static Set<EdgeKind> lookupKinds(DependencyModel rawModel, String from, String to) {
        if (rawModel == null) {
            return Set.of();
        }
        DependencyModel.ClassInfo info = rawModel.getClass(from);
        return info == null ? Set.of() : info.getKinds(to);
    }

    /**
     * Names of methods of {@code to} that are invoked from any method of
     * {@code from}. Walks {@link DependencyModel.MethodInfo#methodCalls}
     * (keys are {@code "owner.methodName"}) and matches against {@code to}'s
     * outer class so inner-class call sites still attribute correctly.
     * {@code <init>} is filtered out — INSTANTIATES already conveys that.
     */
    private static Set<String> lookupMethodNames(DependencyModel rawModel, String from, String to) {
        if (rawModel == null) {
            return Set.of();
        }
        DependencyModel.ClassInfo info = rawModel.getClass(from);
        if (info == null) {
            return Set.of();
        }
        Set<String> names = new TreeSet<>();
        for (DependencyModel.MethodInfo m : info.methods.values()) {
            for (String key : m.methodCalls.keySet()) {
                int dot = key.lastIndexOf('.');
                if (dot < 0) {
                    continue;
                }
                String ownerClass = key.substring(0, dot);
                String methodName = key.substring(dot + 1);
                String outerOwner = ownerClass.contains("$")
                        ? ownerClass.substring(0, ownerClass.indexOf('$'))
                        : ownerClass;
                if (outerOwner.equals(to) && !"<init>".equals(methodName)) {
                    names.add(methodName);
                }
            }
        }
        return names;
    }

    private static boolean inScope(String fqn, String scope) {
        if (scope == null) {
            return true;
        }
        return fqn.equals(scope) || fqn.startsWith(scope + ".");
    }

    private ArchitectureWfxView focusedArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(v -> v == wm.getFocusedView())
                .findFirst()
                .orElseGet(() -> wm.getRegisteredViews().stream()
                        .filter(ArchitectureWfxView.class::isInstance)
                        .map(ArchitectureWfxView.class::cast)
                        .findFirst()
                        .orElse(null));
    }
}
