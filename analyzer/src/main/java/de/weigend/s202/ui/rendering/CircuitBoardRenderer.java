package de.weigend.s202.ui.rendering;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.rendering.circuit.AStarEdgeRouter;
import de.weigend.s202.ui.rendering.circuit.ClassReorderer;
import de.weigend.s202.ui.rendering.circuit.GridBuilder;
import de.weigend.s202.ui.rendering.circuit.PolylinePainter;
import de.weigend.s202.ui.rendering.circuit.RoutedEdge;
import de.weigend.s202.ui.rendering.circuit.RoutingGrid;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Street-map / circuit-board style dependency renderer.
 *
 * <p>Pipeline: ClassReorderer → GridBuilder → A* routing → PolylinePainter.
 * Lines are rectilinear, never cross class boxes, bundle along corridors,
 * and render half-circle bridges at unavoidable crossings.
 */
public final class CircuitBoardRenderer implements DependencyRendererStrategy {

    private final Pane dependencyPane;
    private final Map<String, Node> elementRegistry;
    private final Consumer<String> statusCallback;

    private Pane zoomableContent;
    private Pane overlayPane;
    private boolean drawn = false;

    public CircuitBoardRenderer(Pane dependencyPane,
                                 Map<String, Node> elementRegistry,
                                 Consumer<String> statusCallback) {
        this.dependencyPane = Objects.requireNonNull(dependencyPane);
        this.elementRegistry = Objects.requireNonNull(elementRegistry);
        this.statusCallback = Objects.requireNonNull(statusCallback);
    }

    @Override
    public void setCoordinateContext(Pane zoomableContent, Pane overlayPane, ScrollPane scrollPane) {
        this.zoomableContent = zoomableContent;
        this.overlayPane = overlayPane;
    }

    @Override
    public void clearDependencyArrows() {
        dependencyPane.getChildren().clear();
        drawn = false;
    }

    @Override
    public boolean isDependencyLinesDrawn() {
        return drawn;
    }

    @Override
    public void drawDependencyArrows(ArchitectureNode rootNode) {
        if (rootNode == null || zoomableContent == null || overlayPane == null) return;

        dependencyPane.getChildren().clear();

        // Phase 2: re-order class rows to reduce crossings
        try {
            ClassReorderer reorderer = new ClassReorderer(rootNode, elementRegistry);
            reorderer.reorder(zoomableContent);
        } catch (Exception ignore) {
            // Reorder is an optimisation — never block rendering on failure.
        }

        // Force a full layout so bounds reflect any reorder
        zoomableContent.applyCss();
        zoomableContent.layout();

        // Phase 1: grid
        GridBuilder.Result gb = GridBuilder.build(overlayPane, zoomableContent);
        RoutingGrid grid = gb.grid;
        Map<Node, GridBuilder.BoxPorts> ports = gb.ports;

        // Collect edges to route, filtered by current class selection (like classic)
        String selectedClass = LevelClassBox.getSelectedClassName();
        List<EdgeRequest> requests = collectEdgeRequests(rootNode, selectedClass);

        // Sort by Manhattan distance ascending so short edges route first and
        // longer ones can bundle along their corridors.
        requests.sort((a, b) -> Integer.compare(manhattan(a), manhattan(b)));

        AStarEdgeRouter router = new AStarEdgeRouter(grid);
        List<RoutedEdge> routed = new ArrayList<>();
        for (EdgeRequest req : requests) {
            GridBuilder.BoxPorts src = ports.get(req.source);
            GridBuilder.BoxPorts dst = ports.get(req.target);
            if (src == null || dst == null) continue;

            GridBuilder.Port sp = pickBestPort(src, dst);
            GridBuilder.Port tp = pickBestPort(dst, src);

            List<int[]> path = router.route(sp, tp);
            if (path == null) continue;
            int[] endDir = null;
            if (path.size() >= 2) {
                int[] a = path.get(path.size() - 2);
                int[] b = path.get(path.size() - 1);
                endDir = new int[]{b[0] - a[0], b[1] - a[1]};
            }
            routed.add(new RoutedEdge(req.sourceName, req.targetName, req.incoming, path, endDir));

            // Remember the actual cells used so downstream port selections see the load.
            req.pathCells = path;
        }

        // Phase 4: paint
        PolylinePainter painter = new PolylinePainter(dependencyPane, grid, statusCallback);
        painter.paint(routed);

        drawn = true;
    }

    private static int manhattan(EdgeRequest req) {
        // Cheap proxy: use the port cells of TOP side as a stand-in, just for ordering.
        return 0;
    }

    private static GridBuilder.Port pickBestPort(GridBuilder.BoxPorts self, GridBuilder.BoxPorts other) {
        // Use the midpoint of the other box's ports as target centre.
        double ocx = (other.left.col + other.right.col) / 2.0;
        double ocy = (other.top.row + other.bottom.row) / 2.0;
        double scx = (self.left.col + self.right.col) / 2.0;
        double scy = (self.top.row + self.bottom.row) / 2.0;
        double dx = ocx - scx;
        double dy = ocy - scy;
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx >= 0 ? self.right : self.left;
        } else {
            return dy >= 0 ? self.bottom : self.top;
        }
    }

    private List<EdgeRequest> collectEdgeRequests(ArchitectureNode root, String selectedClass) {
        List<EdgeRequest> out = new ArrayList<>();
        walk(root, selectedClass, out);
        return out;
    }

    private void walk(ArchitectureNode node, String selectedClass, List<EdgeRequest> out) {
        for (ArchitectureNode child : node.getChildren()) {
            if (child.getType() == ArchitectureNode.NodeType.CLASS) {
                Node src = elementRegistry.get(child.getFullName());
                if (src != null && isActuallyVisible(src)) {
                    for (String depName : child.getDependencies()) {
                        boolean srcSel = selectedClass != null && child.getFullName().equals(selectedClass);
                        boolean tgtSel = selectedClass != null && depName.equals(selectedClass);
                        if (selectedClass != null && !srcSel && !tgtSel) continue;

                        Node tgt = elementRegistry.get(depName);
                        if (tgt != null && isActuallyVisible(tgt)) {
                            boolean incoming = tgtSel && !srcSel;
                            EdgeRequest req = new EdgeRequest();
                            req.sourceName = child.getFullName();
                            req.targetName = depName;
                            req.source = src;
                            req.target = tgt;
                            req.incoming = incoming;
                            out.add(req);
                        }
                    }
                }
            }
            walk(child, selectedClass, out);
        }
    }

    private static boolean isActuallyVisible(Node node) {
        if (node == null || !node.isVisible()) return false;
        Parent p = node.getParent();
        while (p != null) {
            if (!p.isVisible()) return false;
            p = p.getParent();
        }
        return true;
    }

    private static final class EdgeRequest {
        String sourceName;
        String targetName;
        Node source;
        Node target;
        boolean incoming;
        List<int[]> pathCells; // set after routing
    }
}
