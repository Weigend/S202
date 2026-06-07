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
package de.weigend.s202.ui.wfx.view3d;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.EndpointPair;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.impl.TarjanSCCFinder;
import de.weigend.s202.ui.model.ArchitectureNode;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewKind;
import javafx.animation.AnimationTimer;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * WFX {@link View} that renders the architecture as an interactive 3D landscape.
 *
 * <p>The 3D layout is built directly from the 2D element bounds read out of
 * {@link de.weigend.s202.ui.ArchitectureView#getElementFootprintBoundsInLayout()}
 * after each layout pulse:
 * <pre>
 *   2D layout X →  3D world X
 *   2D layout Y →  3D world -Z  ("tip horizontal")
 *   new 3D Y    →  thin stack offset by package nesting depth
 * </pre>
 *
 * <p>The 3D view deliberately uses the 2D bounds as the source of truth:
 * packages become thin stacked slabs, classes become thin rectangles on their
 * package slab. It must not recompute package or class ordering.
 *
 * <p>Navigation: click to focus/capture the 3D view, WASD to fly, CTRL+mouse
 * to look around, CTRL+scroll to zoom, ESC to release. Without CTRL the pointer
 * selects classes and packages.
 */
public class ArchitectureView3D implements View {

    public static final String VIEW_ID = "s202-3d-view";

    private final StackPane root     = new StackPane();
    private final Group     scene3D  = new Group();
    private final Group     edgeLayer = new Group();
    private final SubScene  subScene;
    private final Label     selectionOverlay = new Label();
    private final FlyCamera flyCamera;
    private Map<String, SceneBuilder3D.HoverTarget> hoverTargets = Map.of();
    private Map<String, SceneBuilder3D.EdgeTarget> edgeTargets = Map.of();
    private Map<String, ArchitectureNode> nodeByFqn = Map.of();
    private Map<String, String> parentByFqn = Map.of();
    private SceneBuilder3D.CameraHint cameraHint;
    private SceneBuilder3D.HoverTarget hoveredTarget;
    private SceneBuilder3D.HoverTarget selectedTarget;
    private String selectedFqn;
    private ArchitectureNode currentRoot;
    private Architecture currentArchitecture;
    private boolean showDependencies;
    private boolean showScc;
    private boolean showViolations;
    private Consumer<String> elementSelectionSink = fqn -> {};

    public ArchitectureView3D() {
        edgeLayer.setMouseTransparent(true);
        subScene = new SubScene(scene3D, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.rgb(20, 20, 30));
        subScene.widthProperty().bind(root.widthProperty());
        subScene.heightProperty().bind(root.heightProperty());

        flyCamera = new FlyCamera();
        subScene.setCamera(flyCamera.getCamera());
        configureSelectionOverlay();
        installSelectionHandlers();
        root.getChildren().addAll(subScene, selectionOverlay);

        AnimationTimer timer = new AnimationTimer() {
            @Override public void handle(long now) { flyCamera.tick(); }
        };
        timer.start();
    }

    public void setOnElementSelected(Consumer<String> selectionSink) {
        elementSelectionSink = selectionSink == null ? fqn -> {} : selectionSink;
    }

    /** Selects the element with the given FQN in the 3D scene without firing the selection sink. */
    public void selectByFullName(String fqn) {
        applySelection(fqn);
        ensureSelectedTargetVisible(fqn);
        rebuildEdgeOverlays();
    }

    private void applySelection(String fqn) {
        SceneBuilder3D.HoverTarget target = fqn == null ? null : hoverTargets.get(fqn);
        if (target == null) {
            clearSelection();
            hideSelectionOverlay();
            return;
        }
        if (selectedTarget == target) return;
        clearSelection();
        selectedTarget = target;
        selectedFqn = fqn;
        selectedTarget.setSelected(true);
        ArchitectureNode node = nodeByFqn.get(fqn);
        String kind = (node != null && node.getType() == ArchitectureNode.NodeType.CLASS) ? "Class" : "Package";
        selectionOverlay.setText(kind + ": " + fqn);
        selectionOverlay.setVisible(true);
    }

    public void setOverlayVisibility(boolean showDependencies, boolean showScc, boolean showViolations) {
        this.showDependencies = showDependencies;
        this.showScc = showScc;
        this.showViolations = showViolations;
        rebuildEdgeOverlays();
    }

    public void setShowDependencies(boolean showDependencies) {
        this.showDependencies = showDependencies;
        rebuildEdgeOverlays();
    }

    public void setShowScc(boolean showScc) {
        this.showScc = showScc;
        rebuildEdgeOverlays();
    }

    public void setShowViolations(boolean showViolations) {
        this.showViolations = showViolations;
        rebuildEdgeOverlays();
    }

    /**
     * Rebuilds the 3D scene from pre-read 2D layout bounds.
     * Pass {@code null} maps to clear the view.
     *
     * @param elementBounds      bounds per FQN read from the 2D ArchitectureView
     * @param root               root of the ArchitectureNode tree
     * @param architecture       domain model (for tangle/SCC detection)
     * @param visibleParentByFqn closest visible 2D package parent per FQN
     * @param stage              owning stage (needed for mouse-grab centering)
     * @param resetCamera        true → reposition camera to computed hint; false → keep current camera pose
     */
    public void setData(Map<String, Bounds> elementBounds,
                        ArchitectureNode root,
                        Architecture architecture,
                        Map<String, String> visibleParentByFqn,
                        Stage stage,
                        boolean resetCamera) {
        String prevSelectedFqn = selectedFqn; // save before clearSelection() zeroes it
        flyCamera.detach();
        scene3D.getChildren().clear();
        edgeLayer.getChildren().clear();
        clearHover();
        clearSelection();
        hoverTargets = Map.of();
        edgeTargets = Map.of();
        nodeByFqn = Map.of();
        parentByFqn = Map.of();
        cameraHint = null;
        currentRoot = root;
        currentArchitecture = architecture;
        hideSelectionOverlay();

        if (elementBounds == null || elementBounds.isEmpty()) return;

        SceneBuilder3D.SceneResult result = new SceneBuilder3D().build(elementBounds, root, architecture);
        hoverTargets = new HashMap<>(result.hoverTargets());
        edgeTargets = new HashMap<>(result.edgeTargets());
        cameraHint = result.cameraHint();
        nodeByFqn = buildNodeMap(root);
        parentByFqn = buildParentMap(root);
        if (visibleParentByFqn != null && !visibleParentByFqn.isEmpty()) {
            parentByFqn = new HashMap<>(parentByFqn);
            parentByFqn.putAll(visibleParentByFqn);
        }
        scene3D.getChildren().add(result.group());
        scene3D.getChildren().add(edgeLayer);

        flyCamera.attach(subScene, stage);
        if (resetCamera) {
            flyCamera.resetToLookAt(cameraHint.x(), cameraHint.y(), cameraHint.z(),
                    cameraHint.targetX(), cameraHint.targetY(), cameraHint.targetZ());
        } else if (prevSelectedFqn != null) {
            applySelection(prevSelectedFqn); // restore visual state without redundant edge rebuild
        }
        rebuildEdgeOverlays(); // single rebuild at the end with correct selectedFqn
    }

    private void ensureSelectedTargetVisible(String fqn) {
        SceneBuilder3D.EdgeTarget target = fqn == null ? null : edgeTargets.get(fqn);
        if (target == null || cameraHint == null) {
            return;
        }
        if (flyCamera.isWorldPointVisible(
                target.centerX(), target.topY(), target.centerZ(),
                subScene.getWidth(), subScene.getHeight())) {
            return;
        }

        double offsetX = cameraHint.x() - cameraHint.targetX();
        double offsetY = cameraHint.y() - cameraHint.targetY();
        double offsetZ = cameraHint.z() - cameraHint.targetZ();
        flyCamera.resetToLookAt(
                target.centerX() + offsetX,
                target.topY() + offsetY,
                target.centerZ() + offsetZ,
                target.centerX(),
                target.topY(),
                target.centerZ());
    }

    /**
     * Rebuilds the 3D scene and repositions the camera to the computed default view.
     * Convenience overload for callers that always want a camera reset (e.g. initial load,
     * root change, or view binding change).
     */
    public void setData(Map<String, Bounds> elementBounds,
                        ArchitectureNode root,
                        Architecture architecture,
                        Map<String, String> visibleParentByFqn,
                        Stage stage) {
        setData(elementBounds, root, architecture, visibleParentByFqn, stage, true);
    }

    /**
     * Rebuilds the 3D scene from pre-read 2D layout bounds.
     * Pass {@code null} maps to clear the view.
     *
     * @param elementBounds bounds per FQN read from the 2D ArchitectureView
     * @param root          root of the ArchitectureNode tree
     * @param architecture  domain model (for tangle/SCC detection)
     * @param stage         owning stage (needed for mouse-grab centering)
     */
    public void setData(Map<String, Bounds> elementBounds,
                        ArchitectureNode root,
                        Architecture architecture,
                        Stage stage) {
        setData(elementBounds, root, architecture, null, stage, true);
    }

    // -----------------------------------------------------------------------
    // View interface
    // -----------------------------------------------------------------------

    @Override public String getViewId()            { return VIEW_ID; }
    @Override public String getTitle()             { return "3D Architecture"; }
    @Override public String getToolTipInfo()       {
        return "3D view — click to focus, hover/click to select, CTRL+mouse to look, CTRL+scroll to zoom, ESC to release";
    }
    @Override public Position getDefaultPosition() { return Position.CENTER; }
    @Override public ViewKind getKind()            { return ViewKind.TOOL; }
    @Override public double getViewAreaSize()      { return 0.5; }
    @Override public javafx.scene.Parent getRootNode() { return root; }
    @Override public URL getViewImagePath()        { return null; }

    private void configureSelectionOverlay() {
        selectionOverlay.setMouseTransparent(true);
        selectionOverlay.setVisible(false);
        selectionOverlay.setStyle("""
                -fx-background-color: rgba(0, 0, 0, 0.52);
                -fx-background-radius: 4;
                -fx-text-fill: white;
                -fx-padding: 7 9 7 9;
                -fx-font-size: 12px;
                """);
        StackPane.setAlignment(selectionOverlay, Pos.TOP_LEFT);
        StackPane.setMargin(selectionOverlay, new Insets(12));
    }

    private void installSelectionHandlers() {
        subScene.addEventHandler(MouseEvent.MOUSE_MOVED, this::handleHover);
        subScene.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::handleHover);
        subScene.addEventHandler(MouseEvent.MOUSE_EXITED, event -> clearHover());
        subScene.addEventHandler(MouseEvent.MOUSE_CLICKED, this::handleSelectionClick);
    }

    private void handleHover(MouseEvent event) {
        if (event.isControlDown()) {
            clearHover();
            return;
        }
        setHovered(findPickable(event));
    }

    private void handleSelectionClick(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || event.isControlDown()) {
            return;
        }
        SceneBuilder3D.PickableElement pickable = findPickable(event);
        if (pickable == null) {
            return;
        }
        setHovered(pickable);
        toggleSelection(pickable);
        event.consume();
    }

    private SceneBuilder3D.PickableElement findPickable(MouseEvent event) {
        Node node = event.getPickResult() == null ? null : event.getPickResult().getIntersectedNode();
        while (node != null) {
            Object value = node.getProperties().get(SceneBuilder3D.PICKABLE_PROPERTY);
            if (value instanceof SceneBuilder3D.PickableElement pickable) {
                return pickable;
            }
            node = node.getParent();
        }
        return null;
    }

    private void setHovered(SceneBuilder3D.PickableElement pickable) {
        SceneBuilder3D.HoverTarget target = pickable == null ? null : hoverTargets.get(pickable.fullName());
        if (target == hoveredTarget) {
            return;
        }
        clearHover();
        hoveredTarget = target;
        if (hoveredTarget != null) {
            hoveredTarget.setHovered(true);
        }
    }

    private void clearHover() {
        if (hoveredTarget != null) {
            hoveredTarget.setHovered(false);
            hoveredTarget = null;
        }
    }

    private void toggleSelection(SceneBuilder3D.PickableElement pickable) {
        SceneBuilder3D.HoverTarget target = hoverTargets.get(pickable.fullName());
        if (target == null) {
            return;
        }
        if (selectedTarget == target) {
            clearSelection();
            hideSelectionOverlay();
            rebuildEdgeOverlays();
            return;
        }
        clearSelection();
        selectedTarget = target;
        selectedFqn = pickable.fullName();
        selectedTarget.setSelected(true);
        showSelectionOverlay(pickable);
        elementSelectionSink.accept(pickable.fullName());
        rebuildEdgeOverlays();
    }

    private void clearSelection() {
        if (selectedTarget != null) {
            selectedTarget.setSelected(false);
            selectedTarget = null;
        }
        selectedFqn = null;
    }

    private void showSelectionOverlay(SceneBuilder3D.PickableElement pickable) {
        String kind = pickable.type() == ArchitectureNode.NodeType.CLASS ? "Class" : "Package";
        selectionOverlay.setText(kind + ": " + pickable.fullName());
        selectionOverlay.setVisible(true);
    }

    private void hideSelectionOverlay() {
        selectionOverlay.setVisible(false);
        selectionOverlay.setText("");
    }

    private void rebuildEdgeOverlays() {
        edgeLayer.getChildren().clear();
        if (edgeTargets.isEmpty() || currentRoot == null) {
            return;
        }
        double safeCeilingY = safeCeilingY();
        int lane = 0;
        if (showDependencies && selectedFqn != null) {
            for (Edge edge : outgoingDependencyEdges(selectedFqn)) {
                lane = addArrow(edge.source(), edge.target(), Color.rgb(70, 130, 220), 1.4, safeCeilingY, lane);
            }
            for (Edge edge : incomingDependencyEdges(selectedFqn)) {
                lane = addArrow(edge.source(), edge.target(), Color.rgb(80, 200, 100), 1.4, safeCeilingY, lane);
            }
        }
        if (showScc) {
            for (Edge edge : sccEdges()) {
                lane = addArrow(edge.source(), edge.target(), Color.RED, 1.8, safeCeilingY, lane);
            }
        }
        if (showViolations && currentArchitecture != null) {
            for (Edge edge : violationEdges()) {
                lane = addArrow(edge.source(), edge.target(), Color.web("#ff9800"), 2.0, safeCeilingY, lane);
            }
        }
    }

    private int addArrow(String sourceFqn, String targetFqn, Color color,
                         double radius, double safeCeilingY, int lane) {
        SceneBuilder3D.EdgeTarget source = edgeTargets.get(sourceFqn);
        SceneBuilder3D.EdgeTarget target = edgeTargets.get(targetFqn);
        if (source == null || target == null) {
            return lane;
        }
        edgeLayer.getChildren().add(CurvedArrow3D.build(source, target, safeCeilingY, lane, radius, color));
        return lane + 1;
    }

    private List<Edge> outgoingDependencyEdges(String fqn) {
        ArchitectureNode node = nodeByFqn.get(fqn);
        if (node == null) return List.of();
        Set<String> sources = visibleClassFqns(node);
        if (sources.isEmpty()) return List.of();
        List<Edge> edges = new ArrayList<>();
        for (String sourceFqn : sources) {
            ArchitectureNode sourceNode = nodeByFqn.get(sourceFqn);
            if (sourceNode == null) continue;
            for (String dep : sourceNode.getDependencies()) {
                if (edgeTargets.containsKey(dep) && !dep.equals(sourceFqn) && !sources.contains(dep)) {
                    edges.add(new Edge(sourceFqn, dep));
                }
            }
        }
        return sorted(edges);
    }

    private List<Edge> incomingDependencyEdges(String fqn) {
        ArchitectureNode node = nodeByFqn.get(fqn);
        if (node == null) return List.of();
        Set<String> targets = visibleClassFqns(node);
        if (targets.isEmpty()) return List.of();
        List<Edge> edges = new ArrayList<>();
        for (ArchitectureNode caller : nodeByFqn.values()) {
            if (caller.getType() != ArchitectureNode.NodeType.CLASS) continue;
            if (!edgeTargets.containsKey(caller.getFullName())) continue;
            if (targets.contains(caller.getFullName())) continue; // skip internal
            for (String dep : caller.getDependencies()) {
                if (targets.contains(dep) && edgeTargets.containsKey(dep)) {
                    edges.add(new Edge(caller.getFullName(), dep));
                }
            }
        }
        return sorted(edges);
    }

    /** Returns all visible class FQNs reachable from node (the node itself if CLASS, all descendant classes if PACKAGE). */
    private Set<String> visibleClassFqns(ArchitectureNode node) {
        Set<String> result = new HashSet<>();
        collectVisibleClassFqns(node, result);
        return result;
    }

    private void collectVisibleClassFqns(ArchitectureNode node, Set<String> result) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            if (edgeTargets.containsKey(node.getFullName())) {
                result.add(node.getFullName());
            }
        } else {
            for (ArchitectureNode child : node.getChildren()) {
                collectVisibleClassFqns(child, result);
            }
        }
    }

    private List<Edge> sccEdges() {
        Map<String, Set<String>> graph = visibleClassGraph();
        if (graph.isEmpty()) {
            return List.of();
        }
        List<Edge> edges = new ArrayList<>();
        for (StronglyConnectedComponent scc : TarjanSCCFinder.create().findSCCs(graph)) {
            if (!scc.isTangle()) {
                continue;
            }
            Set<String> members = scc.getMembers();
            for (String member : members) {
                for (String dep : graph.getOrDefault(member, Set.of())) {
                    if (members.contains(dep)) {
                        edges.add(new Edge(member, dep));
                    }
                }
            }
        }
        return sorted(edges);
    }

    private Map<String, Set<String>> visibleClassGraph() {
        Map<String, Set<String>> graph = new HashMap<>();
        for (ArchitectureNode node : nodeByFqn.values()) {
            if (node.getType() != ArchitectureNode.NodeType.CLASS || !edgeTargets.containsKey(node.getFullName())) {
                continue;
            }
            Set<String> deps = new HashSet<>();
            for (String dep : node.getDependencies()) {
                ArchitectureNode depNode = nodeByFqn.get(dep);
                if (depNode != null
                        && depNode.getType() == ArchitectureNode.NodeType.CLASS
                        && edgeTargets.containsKey(dep)) {
                    deps.add(dep);
                }
            }
            graph.put(node.getFullName(), deps);
        }
        return graph;
    }

    private List<Edge> violationEdges() {
        Map<EndpointPair, List<Violation>> grouped =
                currentArchitecture.groupUpwardViolations(this::visibleEndpointFqn);
        List<Edge> edges = new ArrayList<>(grouped.size());
        for (EndpointPair pair : grouped.keySet()) {
            if (edgeTargets.containsKey(pair.source()) && edgeTargets.containsKey(pair.target())) {
                edges.add(new Edge(pair.source(), pair.target()));
            }
        }
        return sorted(edges);
    }

    private String visibleEndpointFqn(String fqn) {
        if (edgeTargets.containsKey(fqn)) {
            return fqn;
        }
        String parent = parentByFqn.get(fqn);
        while (parent != null) {
            if (edgeTargets.containsKey(parent)) {
                return parent;
            }
            parent = parentByFqn.get(parent);
        }
        int dot = fqn.lastIndexOf('.');
        while (dot > 0) {
            String candidate = fqn.substring(0, dot);
            if (edgeTargets.containsKey(candidate)) {
                return candidate;
            }
            dot = candidate.lastIndexOf('.');
        }
        return null;
    }

    private double safeCeilingY() {
        double top = edgeTargets.values().stream()
                .filter(t -> t.type() == ArchitectureNode.NodeType.CLASS)
                .mapToDouble(SceneBuilder3D.EdgeTarget::topY)
                .min()
                .orElseGet(() -> edgeTargets.values().stream()
                        .mapToDouble(SceneBuilder3D.EdgeTarget::topY)
                        .min()
                        .orElse(-100.0));
        return top - 45.0;
    }

    private static Map<String, ArchitectureNode> buildNodeMap(ArchitectureNode root) {
        Map<String, ArchitectureNode> map = new HashMap<>();
        if (root != null) {
            collectNodes(root, map);
        }
        return map;
    }

    private static void collectNodes(ArchitectureNode node, Map<String, ArchitectureNode> map) {
        map.put(node.getFullName(), node);
        for (ArchitectureNode child : node.getChildren()) {
            collectNodes(child, map);
        }
    }

    private static Map<String, String> buildParentMap(ArchitectureNode root) {
        Map<String, String> map = new HashMap<>();
        if (root != null) {
            for (ArchitectureNode child : root.getChildren()) {
                collectParents(child, null, map);
            }
        }
        return map;
    }

    private static void collectParents(ArchitectureNode node, String parentFqn, Map<String, String> map) {
        if (parentFqn != null) {
            map.put(node.getFullName(), parentFqn);
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectParents(child, node.getFullName(), map);
        }
    }

    private static List<Edge> sorted(List<Edge> edges) {
        edges.sort(Comparator.comparing(Edge::source).thenComparing(Edge::target));
        return edges;
    }

    private record Edge(String source, String target) {}
}
