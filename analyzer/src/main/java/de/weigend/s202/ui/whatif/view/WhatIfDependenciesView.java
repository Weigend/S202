package de.weigend.s202.ui.whatif.view;

import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.whatif.ClassEdge;
import de.weigend.s202.ui.whatif.PackageAggregate;
import de.weigend.s202.ui.whatif.VirtualPackageGraph;
import de.weigend.s202.ui.whatif.WhatIfModel;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Side panel that lists the current What-If consequences. Phase 5 of the
 * What-If refactor (ADR §2.6).
 *
 * <p>Two sections:
 * <ul>
 *   <li><b>Upward Edges</b> — TreeView with three drill levels. Roots are
 *       aggregated package-to-package edges where source's virtual level is
 *       below target's. Children are the individual class-to-class edges in
 *       that aggregate. Grandchildren are the method calls behind each
 *       class edge (extracted lazily from {@link DependencyModel}).</li>
 *   <li><b>Package SCCs</b> — ListView showing the current virtual tangles
 *       annotated with a diff vs. the static baseline: tangles introduced
 *       by What-If moves are tagged {@code NEW}, tangles the moves
 *       dissolved are tagged {@code dissolved}.</li>
 * </ul>
 *
 * <p>The view subscribes to {@link WhatIfModel#addChangeListener} so it
 * refreshes automatically after every move.
 */
public final class WhatIfDependenciesView extends VBox {

    private final Label upwardHeader = new Label();
    private final TreeView<String> upwardTree = new TreeView<>();
    private final Label sccHeader = new Label();
    private final ListView<String> sccList = new ListView<>();

    private WhatIfModel model;
    private DependencyModel rawDepModel;
    private final Runnable changeListener = this::refresh;

    public WhatIfDependenciesView() {
        super(6);
        setPadding(new Insets(8));
        setMinWidth(280);
        setPrefWidth(320);
        setMaxWidth(380);
        setStyle("-fx-background-color: #fafafa; -fx-border-color: #d0d0d0; -fx-border-width: 0 1 0 0;");

        upwardHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");
        sccHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");

        upwardTree.setShowRoot(false);
        VBox.setVgrow(upwardTree, Priority.ALWAYS);

        sccList.setPrefHeight(160);

        getChildren().addAll(
                upwardHeader,
                upwardTree,
                new Separator(),
                sccHeader,
                sccList);

        refresh();
    }

    /**
     * Bind to a model. Pass {@code null} to detach the view (e.g. when no
     * analysis is loaded). The raw dependency model carries the per-method
     * call data used for the deepest drill level.
     */
    public void setModel(WhatIfModel newModel, DependencyModel rawDepModel) {
        if (this.model != null) {
            this.model.removeChangeListener(changeListener);
        }
        this.model = newModel;
        this.rawDepModel = rawDepModel;
        if (this.model != null) {
            this.model.addChangeListener(changeListener);
        }
        refresh();
    }

    public void refresh() {
        TreeItem<String> root = buildUpwardTree();
        upwardTree.setRoot(root);
        int upwardEdgeCount = countUpwardClassEdges();
        upwardHeader.setText("Upward edges — " + upwardEdgeCount + " class edge"
                + (upwardEdgeCount == 1 ? "" : "s"));

        ObservableList<String> sccItems = buildSccDiffList();
        sccList.setItems(sccItems);
        sccHeader.setText("Package tangles — " + sccItems.size());
    }

    private int countUpwardClassEdges() {
        if (model == null) {
            return 0;
        }
        VirtualPackageGraph graph = model.graph();
        int count = 0;
        for (PackageAggregate aggregate : model.aggregator().aggregates().values()) {
            int srcLevel = graph.levelOf(aggregate.source());
            int tgtLevel = graph.levelOf(aggregate.target());
            if (srcLevel >= 0 && tgtLevel >= 0 && srcLevel < tgtLevel) {
                count += aggregate.classEdgeCount();
            }
        }
        return count;
    }

    private TreeItem<String> buildUpwardTree() {
        TreeItem<String> root = new TreeItem<>("");
        if (model == null) {
            return root;
        }
        VirtualPackageGraph graph = model.graph();
        // Sort aggregates for stable display (source pkg asc, then target pkg asc).
        List<PackageAggregate> sorted = model.aggregator().aggregates().values().stream()
                .filter(a -> {
                    int sl = graph.levelOf(a.source());
                    int tl = graph.levelOf(a.target());
                    return sl >= 0 && tl >= 0 && sl < tl;
                })
                .sorted(Comparator.<PackageAggregate, String>comparing(PackageAggregate::source)
                        .thenComparing(PackageAggregate::target))
                .toList();

        for (PackageAggregate aggregate : sorted) {
            String label = aggregate.source() + " ↑ " + aggregate.target()
                    + "  (" + aggregate.classEdgeCount() + ")";
            TreeItem<String> pkgItem = new TreeItem<>(label);
            for (ClassEdge edge : sortClassEdges(aggregate.classEdges())) {
                TreeItem<String> classItem = new TreeItem<>(simple(edge.source()) + " → " + simple(edge.target()));
                attachMethodCallChildren(classItem, edge);
                pkgItem.getChildren().add(classItem);
            }
            root.getChildren().add(pkgItem);
        }
        return root;
    }

    private static List<ClassEdge> sortClassEdges(List<ClassEdge> edges) {
        return edges.stream()
                .sorted(Comparator.<ClassEdge, String>comparing(ClassEdge::source)
                        .thenComparing(ClassEdge::target))
                .toList();
    }

    private void attachMethodCallChildren(TreeItem<String> classItem, ClassEdge edge) {
        if (rawDepModel == null) {
            return;
        }
        DependencyModel.ClassInfo srcInfo = rawDepModel.getClass(edge.source());
        if (srcInfo == null) {
            return;
        }
        String prefix = edge.target() + ".";
        Map<String, Integer> totals = new TreeMap<>();
        for (DependencyModel.MethodInfo srcMethod : srcInfo.methods.values()) {
            for (Map.Entry<String, Integer> call : srcMethod.methodCalls.entrySet()) {
                String key = call.getKey();
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String targetMethodName = key.substring(prefix.length());
                String entryLabel = srcMethod.name + "() → " + targetMethodName + "()";
                totals.merge(entryLabel, call.getValue(), Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            classItem.getChildren().add(new TreeItem<>(entry.getKey() + "  ×" + entry.getValue()));
        }
    }

    private ObservableList<String> buildSccDiffList() {
        ObservableList<String> items = FXCollections.observableArrayList();
        if (model == null) {
            return items;
        }
        Set<Set<String>> current = tangleMemberSets(model.graph());
        Set<Set<String>> baseline = tangleMemberSets(model.staticGraph());

        // Display current tangles first (stable across moves), then dissolved
        // tangles separately.
        for (Set<String> tangle : current) {
            String suffix = baseline.contains(tangle) ? "" : "   [NEW]";
            items.add(formatTangle(tangle) + suffix);
        }
        for (Set<String> tangle : baseline) {
            if (!current.contains(tangle)) {
                items.add(formatTangle(tangle) + "   [dissolved]");
            }
        }
        return items;
    }

    private static Set<Set<String>> tangleMemberSets(VirtualPackageGraph graph) {
        Set<Set<String>> result = new HashSet<>();
        for (StronglyConnectedComponent scc : graph.sccs()) {
            if (scc.isTangle()) {
                result.add(new HashSet<>(scc.getMembers()));
            }
        }
        return result;
    }

    private static String formatTangle(Set<String> members) {
        List<String> sorted = members.stream().sorted().toList();
        return "{ " + String.join(", ", sorted) + " }";
    }

    private static String simple(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }
}
