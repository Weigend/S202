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
package de.weigend.s202.ui.wfx.whatif;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.EndpointPair;
import de.weigend.s202.domain.architecture.Tangle;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.reader.DependencyModel;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WFX side-panel view that lists the current What-If consequences for the
 * focused {@link de.weigend.s202.ui.ArchitectureView ArchitectureView}.
 * Decoupled from the chart — wired in by {@link WhatIfDependenciesModule}
 * and fed from the architecture model directly.
 *
 * <ul>
 *   <li><b>Wrong-direction edges</b> — every UPWARD class edge the model
 *       reports, grouped by the source-class's and target-class's parent
 *       package. Independent of the chart's package-depth setting so the
 *       count is stable across expand/collapse operations.
 *       Drilldown: aggregate → class-to-class edges → method calls.</li>
 *   <li><b>Component violations</b> — shown only for the component
 *       architecture projection and sourced from its style-specific
 *       {@link Architecture#violations()}.</li>
 *   <li><b>Package tangles</b> — static package SCCs from the analyzer.
 *       Independent of any visual rearrangement.</li>
 * </ul>
 */
public final class WhatIfDependenciesView implements View {

    public static final String VIEW_ID = "s202-whatif-dependencies";

    private final BorderPane root = new BorderPane();
    private final Label upwardHeader = new Label();
    private final TreeView<String> upwardTree = new TreeView<>();
    private final Label componentHeader = new Label();
    private final TreeView<String> componentTree = new TreeView<>();
    private final Label sccHeader = new Label();
    private final ListView<String> sccList = new ListView<>();
    private final VBox upwardSection;
    private final VBox componentSection;
    private final VBox sccSection;
    private final SplitPane split;

    private Architecture architecture;
    private DependencyModel rawDepModel;
    private static final Set<ViolationKind> COMPONENT_VIOLATION_KINDS = Set.of(
            ViolationKind.COMPONENT_API_BYPASS,
            ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION,
            ViolationKind.COMPONENT_INTERNAL_LAYER_BREAK);

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
        componentHeader.setStyle(HEADER_STYLE);
        sccHeader.setStyle(HEADER_STYLE);

        upwardTree.setShowRoot(false);
        componentTree.setShowRoot(false);
        upwardTree.setStyle(PANEL_STYLE);
        componentTree.setStyle(PANEL_STYLE);
        sccList.setStyle(PANEL_STYLE);

        upwardSection = new VBox(upwardHeader, upwardTree);
        upwardSection.setStyle(PANEL_STYLE);
        VBox.setVgrow(upwardTree, Priority.ALWAYS);

        componentSection = new VBox(componentHeader, componentTree);
        componentSection.setStyle(PANEL_STYLE);
        VBox.setVgrow(componentTree, Priority.ALWAYS);

        sccSection = new VBox(sccHeader, sccList);
        sccSection.setStyle(PANEL_STYLE);
        VBox.setVgrow(sccList, Priority.ALWAYS);

        split = new SplitPane(upwardSection, sccSection);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.65);
        SplitPane.setResizableWithParent(sccSection, true);

        root.setCenter(split);
        root.setStyle(PANEL_STYLE);
        root.setPadding(new Insets(0));
        refresh();
    }

    /** Re-bind the view to a different architecture context. Pass nulls to clear. */
    public void bind(Architecture architecture, DependencyModel rawDepModel) {
        this.architecture = architecture;
        this.rawDepModel = rawDepModel;
        refresh();
    }

    public void refresh() {
        Map<EndpointPair, List<Violation>> grouped = aggregatedUpwardViolations();
        upwardTree.setRoot(buildUpwardTree(grouped));
        int totalClassEdges = grouped.values().stream().mapToInt(List::size).sum();
        upwardHeader.setText("Wrong-direction edges — " + totalClassEdges + " class edge"
                + (totalClassEdges == 1 ? "" : "s"));

        boolean componentView = architecture instanceof ComponentArchitecture;
        updateComponentSection(componentView);
        Map<EndpointPair, List<Violation>> componentGrouped = aggregatedComponentViolations();
        componentTree.setRoot(buildComponentViolationTree(componentGrouped));
        int totalComponentEdges = componentGrouped.values().stream().mapToInt(List::size).sum();
        componentHeader.setText("Component violations — " + totalComponentEdges + " class edge"
                + (totalComponentEdges == 1 ? "" : "s"));

        ObservableList<String> sccItems = buildSccList();
        sccList.setItems(sccItems);
        sccHeader.setText("Package tangles (static) — " + sccItems.size());
    }

    /**
     * Architecture aggregates the UPWARD violations using the side panel's
     * UI context — which is "always roll up class-FQN to its parent package
     * FQN" so the count and grouping are stable across chart depth changes.
     */
    private Map<EndpointPair, List<Violation>> aggregatedUpwardViolations() {
        if (architecture == null) {
            return Map.of();
        }
        return architecture.groupUpwardViolations(WhatIfDependenciesView::parentOf);
    }

    private Map<EndpointPair, List<Violation>> aggregatedComponentViolations() {
        if (!(architecture instanceof ComponentArchitecture)) {
            return Map.of();
        }
        return architecture.groupViolations(WhatIfDependenciesView::parentOf, COMPONENT_VIOLATION_KINDS);
    }

    private void updateComponentSection(boolean visible) {
        boolean currentlyVisible = split.getItems().contains(componentSection);
        if (visible == currentlyVisible) {
            return;
        }
        if (visible) {
            split.getItems().setAll(componentSection, upwardSection, sccSection);
            split.setDividerPositions(0.45, 0.75);
        } else {
            split.getItems().setAll(upwardSection, sccSection);
            split.setDividerPositions(0.65);
        }
    }

    private TreeItem<String> buildUpwardTree(Map<EndpointPair, List<Violation>> grouped) {
        TreeItem<String> rootItem = new TreeItem<>("");
        if (grouped.isEmpty()) {
            return rootItem;
        }
        List<Map.Entry<EndpointPair, List<Violation>>> sortedGroups = grouped.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<EndpointPair, List<Violation>>, String>comparing(e -> e.getKey().source())
                        .thenComparing(e -> e.getKey().target()))
                .toList();
        for (Map.Entry<EndpointPair, List<Violation>> entry : sortedGroups) {
            EndpointPair pair = entry.getKey();
            List<Violation> edges = entry.getValue();
            String label = pair.source() + " ↑ " + pair.target() + "  (" + edges.size() + ")";
            TreeItem<String> top = new TreeItem<>(label);
            List<Violation> sortedEdges = edges.stream()
                    .sorted(Comparator.comparing(Violation::sourceFqn).thenComparing(Violation::targetFqn))
                    .toList();
            for (Violation v : sortedEdges) {
                TreeItem<String> classItem = new TreeItem<>(simple(v.sourceFqn()) + " → " + simple(v.targetFqn()));
                attachMethodCallChildren(classItem, v.sourceFqn(), v.targetFqn());
                top.getChildren().add(classItem);
            }
            rootItem.getChildren().add(top);
        }
        return rootItem;
    }

    private TreeItem<String> buildComponentViolationTree(Map<EndpointPair, List<Violation>> grouped) {
        TreeItem<String> rootItem = new TreeItem<>("");
        if (grouped.isEmpty()) {
            return rootItem;
        }
        List<Map.Entry<EndpointPair, List<Violation>>> sortedGroups = grouped.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<EndpointPair, List<Violation>>, String>comparing(e -> e.getKey().source())
                        .thenComparing(e -> e.getKey().target()))
                .toList();
        for (Map.Entry<EndpointPair, List<Violation>> entry : sortedGroups) {
            EndpointPair pair = entry.getKey();
            List<Violation> edges = entry.getValue();
            String label = pair.source() + " → " + pair.target() + "  (" + edges.size() + ")";
            TreeItem<String> top = new TreeItem<>(label);
            List<Violation> sortedEdges = edges.stream()
                    .sorted(Comparator
                            .comparing((Violation v) -> componentKindLabel(v.kind()))
                            .thenComparing(Violation::sourceFqn)
                            .thenComparing(Violation::targetFqn))
                    .toList();
            for (Violation v : sortedEdges) {
                TreeItem<String> classItem = new TreeItem<>(
                        componentKindLabel(v.kind()) + ": "
                                + simple(v.sourceFqn()) + " → " + simple(v.targetFqn()));
                attachMethodCallChildren(classItem, v.sourceFqn(), v.targetFqn());
                top.getChildren().add(classItem);
            }
            rootItem.getChildren().add(top);
        }
        return rootItem;
    }

    private void attachMethodCallChildren(TreeItem<String> classItem, String sourceFqn, String targetFqn) {
        if (rawDepModel == null) {
            return;
        }
        DependencyModel.ClassInfo srcInfo = rawDepModel.getClass(sourceFqn);
        if (srcInfo == null) {
            return;
        }
        String prefix = targetFqn + ".";
        Map<String, Integer> totals = new LinkedHashMap<>();
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
        if (architecture == null) {
            return items;
        }
        for (Tangle t : architecture.tangles()) {
            items.add(formatTangle(t.members().stream().sorted().toList()));
        }
        return items;
    }

    private static String formatTangle(List<String> members) {
        return "{ " + String.join(", ", members) + " }";
    }

    private static String simple(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    private static String componentKindLabel(ViolationKind kind) {
        return switch (kind) {
            case COMPONENT_API_BYPASS -> "API bypass";
            case COMPONENT_API_LEAKS_IMPLEMENTATION -> "API leaks implementation";
            case COMPONENT_INTERNAL_LAYER_BREAK -> "Internal layer break";
            default -> kind.name();
        };
    }

    private static String parentOf(String fqcn) {
        if (fqcn == null) {
            return "";
        }
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? "" : fqcn.substring(0, dot);
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
        return "Wrong-direction class edges, component violations, and package tangles for the focused architecture";
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
