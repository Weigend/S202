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
package de.weigend.s202.ui.features.whatif;

import de.weigend.s202.domain.DependencyDetails;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.EndpointPair;
import de.weigend.s202.domain.architecture.HexagonalArchitecture;
import de.weigend.s202.domain.architecture.Tangle;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.reader.DependencyModel;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewAnchor;
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
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * WFX side-panel view that lists the current What-If consequences for the
 * focused {@link de.weigend.s202.ui.core.canvas.ArchitectureCanvas ArchitectureCanvas}.
 * Decoupled from the chart — wired in by {@link WhatIfDependenciesModule}
 * and fed from the architecture model directly.
 *
 * <ul>
 *   <li><b>Wrong-direction edges</b> — every UPWARD class edge the model
 *       reports, grouped by the source-class's and target-class's parent
 *       package. Independent of the chart's package-depth setting so the
 *       count is stable across expand/collapse operations.
 *       Drilldown: aggregate → class-to-class edges → method calls.</li>
 *   <li><b>Component/Hexagonal violations</b> — shown only for the active
 *       style projection and sourced from its style-specific
 *       {@link Architecture#violations()}.</li>
 *   <li><b>Package tangles</b> — package SCCs for the displayed architecture,
 *       including What-If class moves.</li>
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
    private String selectedFullName;
    /**
     * Classes the bound view displays, or {@code null} when it shows the whole
     * architecture. A tangle tab shows a handful of classes of a much larger
     * model; the panel then lists only what lies between those classes.
     */
    private Set<String> visibleClasses;
    /** Tangles behind the rows of {@link #sccList}, same order. */
    private List<Tangle> sccTangles = List.of();
    /** What a double-click on a row of {@link #upwardTree} opens. */
    private Map<TreeItem<String>, OpenTangleRequest> upwardRequests = Map.of();
    private Consumer<OpenTangleRequest> onOpenTangle;

    /**
     * A double-click on a row: open a tangle view for it. For a package-tangle
     * row {@code tangle} is that tangle; for a wrong-direction row it holds the
     * classes of the clicked edge or edge group, so the view shows exactly
     * those classes and every dependency between them.
     */
    public record OpenTangleRequest(Tangle tangle, String title) {}

    private static final String OPEN_HINT = "Double-click an entry to open it in a tangle view";
    private static final Set<ViolationKind> COMPONENT_VIOLATION_KINDS = Set.of(
            ViolationKind.COMPONENT_API_BYPASS,
            ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION,
            ViolationKind.COMPONENT_INTERNAL_LAYER_BREAK);
    private static final Set<ViolationKind> HEXAGONAL_VIOLATION_KINDS = Set.of(
            ViolationKind.HEXAGON_OUTWARD_DEPENDENCY,
            ViolationKind.HEXAGON_PORT_BYPASS);

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
        sccList.setOnMouseClicked(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY || ev.getClickCount() != 2 || onOpenTangle == null) {
                return;
            }
            int index = sccList.getSelectionModel().getSelectedIndex();
            if (index < 0 || index >= sccTangles.size()) {
                return;
            }
            onOpenTangle.accept(new OpenTangleRequest(sccTangles.get(index), sccList.getItems().get(index)));
            ev.consume();
        });
        sccList.setTooltip(new Tooltip(OPEN_HINT));

        // Filter on PRESSED, like the Top Tangles tree: the second press of a
        // double-click must open the view before the default handler toggles
        // the row's expansion.
        upwardTree.addEventFilter(MouseEvent.MOUSE_PRESSED, ev -> {
            if (ev.getButton() != MouseButton.PRIMARY || ev.getClickCount() != 2 || onOpenTangle == null) {
                return;
            }
            OpenTangleRequest request = upwardRequests.get(upwardTree.getSelectionModel().getSelectedItem());
            if (request != null) {
                onOpenTangle.accept(request);
                ev.consume();
            }
        });
        upwardTree.setTooltip(new Tooltip(OPEN_HINT));

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
        bind(architecture, rawDepModel, null);
    }

    /** Re-bind the view to a different architecture context and optional class/package scope. */
    public void bind(Architecture architecture, DependencyModel rawDepModel, String selectedFullName) {
        bind(architecture, rawDepModel, selectedFullName, null);
    }

    /**
     * Re-bind the view to an architecture context of which the bound view
     * displays only {@code visibleClasses}; {@code null} means all of it.
     */
    public void bind(Architecture architecture, DependencyModel rawDepModel, String selectedFullName,
                     Set<String> visibleClasses) {
        this.architecture = architecture;
        this.rawDepModel = rawDepModel;
        this.selectedFullName = selectedFullName;
        this.visibleClasses = visibleClasses;
        refresh();
    }

    public void refresh() {
        Map<EndpointPair, List<Violation>> grouped = aggregatedUpwardViolations();
        upwardTree.setRoot(buildUpwardTree(grouped));
        int totalClassEdges = grouped.values().stream().mapToInt(List::size).sum();
        upwardHeader.setText("Wrong-direction edges" + scopeSuffix() + " — " + totalClassEdges + " class edge"
                + (totalClassEdges == 1 ? "" : "s"));

        boolean styleView = architecture instanceof ComponentArchitecture
                || architecture instanceof HexagonalArchitecture;
        updateComponentSection(styleView);
        Map<EndpointPair, List<Violation>> componentGrouped = aggregatedStyleViolations();
        componentTree.setRoot(buildComponentViolationTree(componentGrouped));
        int totalComponentEdges = componentGrouped.values().stream().mapToInt(List::size).sum();
        componentHeader.setText(styleViolationHeader() + scopeSuffix() + " — " + totalComponentEdges + " class edge"
                + (totalComponentEdges == 1 ? "" : "s"));

        ObservableList<String> sccItems = buildSccList();
        sccList.setItems(sccItems);
        sccHeader.setText("Package tangles" + scopeSuffix() + " — " + sccItems.size());
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
        if (hasSelection() || visibleClasses != null) {
            return groupScopedViolations(architecture, rawDepModel, selectedFullName,
                    Set.of(ViolationKind.UPWARD), visibleClasses);
        }
        return architecture.groupUpwardViolations(WhatIfDependenciesView::parentOf);
    }

    private Map<EndpointPair, List<Violation>> aggregatedStyleViolations() {
        if (architecture instanceof ComponentArchitecture) {
            if (hasSelection() || visibleClasses != null) {
                return groupScopedViolations(architecture, rawDepModel, selectedFullName,
                        COMPONENT_VIOLATION_KINDS, visibleClasses);
            }
            return architecture.groupViolations(WhatIfDependenciesView::parentOf, COMPONENT_VIOLATION_KINDS);
        }
        if (architecture instanceof HexagonalArchitecture) {
            if (hasSelection() || visibleClasses != null) {
                return groupScopedViolations(architecture, rawDepModel, selectedFullName,
                        HEXAGONAL_VIOLATION_KINDS, visibleClasses);
            }
            return architecture.groupViolations(WhatIfDependenciesView::parentOf, HEXAGONAL_VIOLATION_KINDS);
        }
        return Map.of();
    }

    static Map<EndpointPair, List<Violation>> groupScopedViolations(Architecture architecture,
                                                                    DependencyModel rawDepModel,
                                                                    String selectedFullName,
                                                                    Set<ViolationKind> kinds) {
        return groupScopedViolations(architecture, rawDepModel, selectedFullName, kinds, null);
    }

    /**
     * @param visibleClasses when non-null, only violations between two of these
     *                       classes count — the bound view shows nothing else
     */
    static Map<EndpointPair, List<Violation>> groupScopedViolations(Architecture architecture,
                                                                    DependencyModel rawDepModel,
                                                                    String selectedFullName,
                                                                    Set<ViolationKind> kinds,
                                                                    Set<String> visibleClasses) {
        if (architecture == null) {
            return Map.of();
        }
        if (!hasSelection(selectedFullName) && visibleClasses == null) {
            return architecture.groupViolations(WhatIfDependenciesView::parentOf, kinds);
        }
        Map<EndpointPair, List<Violation>> grouped = new LinkedHashMap<>();
        for (Violation v : architecture.violations()) {
            if (kinds != null && !kinds.isEmpty() && !kinds.contains(v.kind())) {
                continue;
            }
            if (visibleClasses != null
                    && !(visibleClasses.contains(v.sourceFqn()) && visibleClasses.contains(v.targetFqn()))) {
                continue;
            }
            if (!violationTouchesSelection(v, rawDepModel, selectedFullName)) {
                continue;
            }
            String src = parentOf(v.sourceFqn());
            String tgt = parentOf(v.targetFqn());
            if (src == null || tgt == null) {
                continue;
            }
            grouped.computeIfAbsent(new EndpointPair(src, tgt), k -> new ArrayList<>()).add(v);
        }
        return grouped;
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
        Map<TreeItem<String>, OpenTangleRequest> requests = new IdentityHashMap<>();
        upwardRequests = requests;
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
            requests.put(top, requestForGroup(pair, edges));
            List<Violation> sortedEdges = edges.stream()
                    .sorted(Comparator.comparing(Violation::sourceFqn).thenComparing(Violation::targetFqn))
                    .toList();
            for (Violation v : sortedEdges) {
                TreeItem<String> classItem = new TreeItem<>(simple(v.sourceFqn()) + " → " + simple(v.targetFqn()));
                attachMethodCallChildren(classItem, v.sourceFqn(), v.targetFqn());
                OpenTangleRequest edgeRequest = requestForEdge(v);
                requests.put(classItem, edgeRequest);
                // A method-call row opens the class edge it belongs to.
                classItem.getChildren().forEach(methodItem -> requests.put(methodItem, edgeRequest));
                top.getChildren().add(classItem);
            }
            rootItem.getChildren().add(top);
        }
        return rootItem;
    }

    /**
     * Tangle view for one wrong-direction class edge: its two classes. The view
     * then shows this edge together with whatever runs back between them.
     * {@code null} for a self-dependency, which has nothing to show.
     */
    static OpenTangleRequest requestForEdge(Violation v) {
        if (v.sourceFqn().equals(v.targetFqn())) {
            return null;
        }
        return new OpenTangleRequest(
                new Tangle(Set.of(v.sourceFqn(), v.targetFqn())),
                simple(v.sourceFqn()) + " → " + simple(v.targetFqn()));
    }

    /**
     * Tangle view for a group of wrong-direction edges between two packages:
     * every class that is an endpoint of one of them. Classes rather than the
     * two packages, so the view stays focused on the finding and also works
     * when both ends lie in the same package or one package contains the other.
     */
    static OpenTangleRequest requestForGroup(EndpointPair pair, List<Violation> edges) {
        Set<String> classes = new TreeSet<>();
        for (Violation v : edges) {
            classes.add(v.sourceFqn());
            classes.add(v.targetFqn());
        }
        if (classes.size() < 2) {
            return null;
        }
        return new OpenTangleRequest(new Tangle(classes),
                packageLabel(pair.source()) + " ↑ " + packageLabel(pair.target()));
    }

    private static String packageLabel(String packageFqn) {
        return packageFqn == null || packageFqn.isEmpty() ? "<root>" : simple(packageFqn);
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
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (DependencyDetails.Call call : DependencyDetails.of(rawDepModel, sourceFqn, targetFqn).calls()) {
            totals.merge(call.callerName() + "() → " + call.member() + "()", call.count(), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            classItem.getChildren().add(new TreeItem<>(entry.getKey() + "  ×" + entry.getValue()));
        }
    }

    /** Handler for a double-click on a wrong-direction row or a package-tangle row. */
    public void setOnOpenTangle(Consumer<OpenTangleRequest> handler) {
        this.onOpenTangle = handler;
    }

    private ObservableList<String> buildSccList() {
        ObservableList<String> items = FXCollections.observableArrayList();
        if (architecture == null) {
            sccTangles = List.of();
            return items;
        }
        List<Tangle> tangles = scopedTangles(architecture, rawDepModel, selectedFullName, visibleClasses);
        for (Tangle t : tangles) {
            items.add(t.label(m -> rawDepModel == null || rawDepModel.getClass(m) == null));
        }
        sccTangles = tangles;
        return items;
    }

    static List<Tangle> scopedTangles(Architecture architecture,
                                      DependencyModel rawDepModel,
                                      String selectedFullName) {
        return scopedTangles(architecture, rawDepModel, selectedFullName, null);
    }

    /**
     * @param visibleClasses when non-null, only tangles the bound view shows
     *                       completely count: every member is one of these
     *                       classes or a package holding at least one of them
     */
    static List<Tangle> scopedTangles(Architecture architecture,
                                      DependencyModel rawDepModel,
                                      String selectedFullName,
                                      Set<String> visibleClasses) {
        if (architecture == null) {
            return List.of();
        }
        String packageScope = hasSelection(selectedFullName)
                ? selectedPackageScope(rawDepModel, selectedFullName)
                : null;
        if (packageScope == null && visibleClasses == null) {
            return architecture.tangles();
        }
        return architecture.tangles().stream()
                .filter(t -> t.inScope(packageScope))
                .filter(t -> visibleClasses == null || allMembersVisible(t, visibleClasses))
                .toList();
    }

    private static boolean allMembersVisible(Tangle tangle, Set<String> visibleClasses) {
        for (String member : tangle.members()) {
            String prefix = member + ".";
            boolean visible = visibleClasses.contains(member);
            if (!visible) {
                for (String cls : visibleClasses) {
                    if (cls.startsWith(prefix)) {
                        visible = true;
                        break;
                    }
                }
            }
            if (!visible) {
                return false;
            }
        }
        return true;
    }

    private static boolean violationTouchesSelection(Violation violation,
                                                     DependencyModel rawDepModel,
                                                     String selectedFullName) {
        return fqnInSelectedScope(violation.sourceFqn(), rawDepModel, selectedFullName)
                || fqnInSelectedScope(violation.targetFqn(), rawDepModel, selectedFullName);
    }

    private static boolean fqnInSelectedScope(String fqn, DependencyModel rawDepModel, String selectedFullName) {
        if (!hasSelection(selectedFullName)) {
            return true;
        }
        if (isClassSelection(rawDepModel, selectedFullName)) {
            return selectedFullName.equals(fqn);
        }
        return packageInScope(fqn, selectedFullName);
    }

    private static boolean isClassSelection(DependencyModel rawDepModel, String selectedFullName) {
        return rawDepModel != null
                && selectedFullName != null
                && rawDepModel.getClass(selectedFullName) != null;
    }

    private static String selectedPackageScope(DependencyModel rawDepModel, String selectedFullName) {
        if (!hasSelection(selectedFullName)) {
            return null;
        }
        if (rawDepModel != null) {
            DependencyModel.ClassInfo selectedClass = rawDepModel.getClass(selectedFullName);
            if (selectedClass != null) {
                return selectedClass.packageName;
            }
        }
        return selectedFullName;
    }

    private boolean hasSelection() {
        return hasSelection(selectedFullName);
    }

    private static boolean hasSelection(String selectedFullName) {
        return selectedFullName != null && !selectedFullName.isBlank();
    }

    private String scopeSuffix() {
        if (hasSelection()) {
            return " for " + selectedFullName;
        }
        return visibleClasses != null ? " in this view" : "";
    }

    private static boolean packageInScope(String fqn, String packageScope) {
        if (fqn == null || packageScope == null) {
            return false;
        }
        return fqn.equals(packageScope) || (!packageScope.isEmpty() && fqn.startsWith(packageScope + "."));
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
            case HEXAGON_OUTWARD_DEPENDENCY -> "Outward dependency";
            case HEXAGON_PORT_BYPASS -> "Port bypass";
            default -> kind.name();
        };
    }

    private String styleViolationHeader() {
        if (architecture instanceof HexagonalArchitecture) {
            return "Hexagonal violations";
        }
        return "Component violations";
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
        return "Wrong-direction class edges, style violations, and package tangles for the focused architecture";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.BOTTOM;
    }

    /**
     * Under the architecture tabs only, not under the panels beside them.
     * The editor area is there before any analysis is open, so the panel is
     * registered at startup and the layout of the last session can place it.
     */
    @Override
    public ViewAnchor getDefaultAnchor() {
        return ViewAnchor.EDITOR;
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
