package de.weigend.s202.ui.whatif.view;

import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.GraphSelection;
import de.weigend.s202.ui.rendering.WhatIfUpwardEdgeRenderer;
import de.weigend.s202.ui.whatif.ClassEdge;
import de.weigend.s202.ui.whatif.VirtualPackageGraph;
import de.weigend.s202.ui.whatif.WhatIfModel;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Side panel listing the current What-If consequences (ADR §2.6). Driven
 * entirely by the live scene positions of the architecture boxes:
 *
 * <ul>
 *   <li><b>Wrong-direction edges</b> — same data the
 *       {@link WhatIfUpwardEdgeRenderer} draws on the canvas, grouped by
 *       (source box, target box) pair. Each top-level entry expands into
 *       the underlying class-to-class edges; each class edge expands into
 *       the method calls behind it (from the raw {@link DependencyModel}).</li>
 *   <li><b>Package tangles</b> — static package SCCs extracted from the
 *       analyzer. These reflect cycles in the code itself and are
 *       independent of any visual rearrangement.</li>
 * </ul>
 *
 * <p>The view subscribes to {@link WhatIfModel#addChangeListener} for
 * override-triggered refreshes; layout-triggered refreshes come through
 * the architecture view's pulse-coalescer calling {@link #refresh()}
 * after each redraw pass.
 */
public final class WhatIfDependenciesView extends VBox {

    private final Label upwardHeader = new Label();
    private final TreeView<String> upwardTree = new TreeView<>();
    private final Label sccHeader = new Label();
    private final ListView<String> sccList = new ListView<>();

    private WhatIfModel model;
    private DependencyModel rawDepModel;
    private WhatIfUpwardEdgeRenderer renderer;
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

    public void setModel(WhatIfModel newModel,
                         DependencyModel rawDepModel,
                         WhatIfUpwardEdgeRenderer renderer) {
        if (this.model != null) {
            this.model.removeChangeListener(changeListener);
        }
        this.model = newModel;
        this.rawDepModel = rawDepModel;
        this.renderer = renderer;
        if (this.model != null) {
            this.model.addChangeListener(changeListener);
        }
        refresh();
    }

    public void refresh() {
        List<WhatIfUpwardEdgeRenderer.Violation> violations = currentViolations();
        upwardTree.setRoot(buildUpwardTree(violations));
        int totalClassEdges = violations.stream().mapToInt(v -> v.classEdges().size()).sum();
        upwardHeader.setText("Wrong-direction edges — " + totalClassEdges + " class edge"
                + (totalClassEdges == 1 ? "" : "s"));

        ObservableList<String> sccItems = buildSccList();
        sccList.setItems(sccItems);
        sccHeader.setText("Package tangles (static) — " + sccItems.size());
    }

    private List<WhatIfUpwardEdgeRenderer.Violation> currentViolations() {
        if (renderer == null || model == null) {
            return List.of();
        }
        return renderer.findVisibleViolations(model.staticEdges());
    }

    private TreeItem<String> buildUpwardTree(List<WhatIfUpwardEdgeRenderer.Violation> violations) {
        TreeItem<String> root = new TreeItem<>("");
        if (violations.isEmpty()) {
            return root;
        }
        // Stable display order — by source label, then target label.
        violations = violations.stream()
                .sorted(Comparator.comparing((WhatIfUpwardEdgeRenderer.Violation v) -> endpointLabel(v.source()))
                        .thenComparing(v -> endpointLabel(v.target())))
                .toList();

        for (WhatIfUpwardEdgeRenderer.Violation v : violations) {
            String label = endpointLabel(v.source()) + " ↑ " + endpointLabel(v.target())
                    + "  (" + v.classEdges().size() + ")";
            TreeItem<String> top = new TreeItem<>(label);
            List<ClassEdge> sortedEdges = v.classEdges().stream()
                    .sorted(Comparator.comparing(ClassEdge::source).thenComparing(ClassEdge::target))
                    .toList();
            for (ClassEdge edge : sortedEdges) {
                TreeItem<String> classItem = new TreeItem<>(simple(edge.source()) + " → " + simple(edge.target()));
                attachMethodCallChildren(classItem, edge);
                top.getChildren().add(classItem);
            }
            root.getChildren().add(top);
        }
        return root;
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

    private ObservableList<String> buildSccList() {
        ObservableList<String> items = FXCollections.observableArrayList();
        if (model == null) {
            return items;
        }
        VirtualPackageGraph staticGraph = model.staticGraph();
        for (StronglyConnectedComponent scc : staticGraph.sccs()) {
            if (scc.isTangle()) {
                items.add(formatTangle(scc.getMembers().stream().sorted().toList()));
            }
        }
        return items;
    }

    private static String endpointLabel(Node node) {
        if (node instanceof GraphSelection.Selectable s && s.getFullName() != null) {
            return s.getFullName();
        }
        return node.getClass().getSimpleName();
    }

    private static String formatTangle(List<String> members) {
        return "{ " + String.join(", ", members) + " }";
    }

    private static String simple(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }
}
