package de.weigend.s202.ui.wfx.whatif;

import de.weigend.s202.graph.StronglyConnectedComponent;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.GraphSelection;
import de.weigend.s202.ui.rendering.WhatIfUpwardEdgeRenderer;
import de.weigend.s202.ui.whatif.ClassEdge;
import de.weigend.s202.ui.whatif.VirtualPackageGraph;
import de.weigend.s202.ui.whatif.WhatIfModel;
import io.softwareecg.wfx.windowmtg.api.Position;
import io.softwareecg.wfx.windowmtg.api.View;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * WFX side-panel view that lists the current What-If consequences for the
 * focused {@link de.weigend.s202.ui.ArchitectureView ArchitectureView}.
 * Decoupled from the chart — wired in by {@link WhatIfDependenciesModule},
 * fed by a renderer + model triple.
 *
 * <ul>
 *   <li><b>Wrong-direction edges</b> — same data the
 *       {@link WhatIfUpwardEdgeRenderer} paints on the canvas, rolled up to
 *       the currently-visible source/target box and grouped per pair.
 *       Drilldown: aggregate → class-to-class edges → method calls.</li>
 *   <li><b>Package tangles</b> — static package SCCs from the analyzer.
 *       Independent of any visual rearrangement.</li>
 * </ul>
 */
public final class WhatIfDependenciesView implements View {

    private static final Logger LOGGER = LoggerFactory.getLogger(WhatIfDependenciesView.class);

    public static final String VIEW_ID = "s202-whatif-dependencies";

    private final BorderPane root = new BorderPane();
    private final Label upwardHeader = new Label();
    private final TreeView<String> upwardTree = new TreeView<>();
    private final Label sccHeader = new Label();
    private final ListView<String> sccList = new ListView<>();

    private WhatIfModel model;
    private DependencyModel rawDepModel;
    private WhatIfUpwardEdgeRenderer renderer;
    /** Last logged signature of the violations list so we only dump on change. */
    private String lastLoggedSignature = "";

    private static final String PANEL_BG = "#f5f5f0";
    private static final String PANEL_STYLE =
            "-fx-background-color: " + PANEL_BG + ";"
                    + "-fx-control-inner-background: " + PANEL_BG + ";"
                    + "-fx-background: " + PANEL_BG + ";";
    private static final String HEADER_STYLE =
            "-fx-font-weight: bold;"
                    + "-fx-font-size: 12;"
                    + "-fx-padding: 4 8 4 8;"
                    + "-fx-background-color: " + PANEL_BG + ";";

    public WhatIfDependenciesView() {
        upwardHeader.setStyle(HEADER_STYLE);
        sccHeader.setStyle(HEADER_STYLE);

        upwardTree.setShowRoot(false);
        upwardTree.setStyle(PANEL_STYLE);
        sccList.setStyle(PANEL_STYLE);

        VBox upwardSection = new VBox(upwardHeader, upwardTree);
        upwardSection.setStyle(PANEL_STYLE);
        VBox.setVgrow(upwardTree, Priority.ALWAYS);

        VBox sccSection = new VBox(sccHeader, sccList);
        sccSection.setStyle(PANEL_STYLE);
        VBox.setVgrow(sccList, Priority.ALWAYS);

        SplitPane split = new SplitPane(upwardSection, sccSection);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.65);
        SplitPane.setResizableWithParent(sccSection, true);

        root.setCenter(split);
        root.setStyle(PANEL_STYLE);
        root.setPadding(new Insets(0));
        refresh();
    }

    /** Re-bind the view to a different architecture context. Pass nulls to clear. */
    public void bind(WhatIfModel model,
                     DependencyModel rawDepModel,
                     WhatIfUpwardEdgeRenderer renderer) {
        this.model = model;
        this.rawDepModel = rawDepModel;
        this.renderer = renderer;
        refresh();
    }

    public void refresh() {
        List<WhatIfUpwardEdgeRenderer.Violation> violations = currentViolations();
        upwardTree.setRoot(buildUpwardTree(violations));
        int totalClassEdges = violations.stream().mapToInt(v -> v.classEdges().size()).sum();
        upwardHeader.setText("Wrong-direction edges — " + totalClassEdges + " class edge"
                + (totalClassEdges == 1 ? "" : "s"));

        dumpViolationsOnChange(violations, totalClassEdges);

        ObservableList<String> sccItems = buildSccList();
        sccList.setItems(sccItems);
        sccHeader.setText("Package tangles (static) — " + sccItems.size());
    }

    /**
     * Temporary diagnostic — logs the class-level edges the UI's Y-based
     * detector currently flags. Used to compare against the model's
     * Violation list so the two definitions can be reconciled. Removed
     * once the UI consumes {@code Architecture.violations()} directly.
     */
    private void dumpViolationsOnChange(List<WhatIfUpwardEdgeRenderer.Violation> violations,
                                        int totalClassEdges) {
        StringBuilder sig = new StringBuilder();
        for (WhatIfUpwardEdgeRenderer.Violation v : violations) {
            for (ClassEdge edge : v.classEdges()) {
                sig.append(edge.source()).append(" -> ").append(edge.target()).append('|');
            }
        }
        String signature = sig.toString();
        if (signature.equals(lastLoggedSignature)) {
            return;
        }
        lastLoggedSignature = signature;

        StringBuilder report = new StringBuilder(
                "[DEBUG] UI Wrong-direction class edges — " + totalClassEdges + ":");
        for (WhatIfUpwardEdgeRenderer.Violation v : violations) {
            for (ClassEdge edge : v.classEdges()) {
                report.append("\n  ").append(edge.source()).append(" -> ").append(edge.target());
            }
        }
        LOGGER.info(report.toString());
    }

    private List<WhatIfUpwardEdgeRenderer.Violation> currentViolations() {
        if (renderer == null || model == null) {
            return List.of();
        }
        return renderer.findVisibleViolations(model.staticEdges());
    }

    private TreeItem<String> buildUpwardTree(List<WhatIfUpwardEdgeRenderer.Violation> violations) {
        TreeItem<String> rootItem = new TreeItem<>("");
        if (violations.isEmpty()) {
            return rootItem;
        }
        List<WhatIfUpwardEdgeRenderer.Violation> sorted = violations.stream()
                .sorted(Comparator
                        .comparing((WhatIfUpwardEdgeRenderer.Violation v) -> endpointLabel(v.source()))
                        .thenComparing(v -> endpointLabel(v.target())))
                .toList();

        for (WhatIfUpwardEdgeRenderer.Violation v : sorted) {
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
            rootItem.getChildren().add(top);
        }
        return rootItem;
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

    // ===== View interface =====

    @Override
    public String getViewId() {
        return VIEW_ID;
    }

    @Override
    public String getTitle() {
        return "Dependencies";
    }

    @Override
    public String getToolTipInfo() {
        return "Wrong-direction class edges and package tangles for the focused architecture";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.BOTTOM;
    }

    @Override
    public Parent getRootNode() {
        return root;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 0.30;
    }
}
