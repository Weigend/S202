package de.weigend.s202.ui.rendering.circuit;

import de.weigend.s202.ui.LevelClassBox;
import de.weigend.s202.ui.LevelPackageBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link RoutingGrid} from the current visible architecture layout.
 * Only CLASS boxes are used as obstacles. Expanded packages are transparent —
 * routing may pass through their interior corridors. Collapsed packages block
 * their whole bounding box (since their children are not visible).
 *
 * <p>Each class box receives four ports (top, right, bottom, left), each
 * placed at the midpoint of its respective side. Ports are recorded per class
 * in a {@link Map} keyed by the box node so the router can pick a sensible
 * side when emitting edges.
 */
public final class GridBuilder {

    /** A single port anchor on one side of a class box. */
    public static final class Port {
        public enum Side { TOP, RIGHT, BOTTOM, LEFT }
        public final int col;
        public final int row;
        public final Side side;
        /** World coordinate of the actual box-edge point where the stub should attach. */
        public final double stubX;
        public final double stubY;

        public Port(int col, int row, Side side, double stubX, double stubY) {
            this.col = col;
            this.row = row;
            this.side = side;
            this.stubX = stubX;
            this.stubY = stubY;
        }
    }

    /** Four ports for a single class box, one per side. */
    public static final class BoxPorts {
        public final Port top;
        public final Port right;
        public final Port bottom;
        public final Port left;

        public BoxPorts(Port top, Port right, Port bottom, Port left) {
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.left = left;
        }
    }

    /** Result of a grid build: the grid plus per-class port info and package ancestry. */
    public static final class Result {
        public final RoutingGrid grid;
        public final Map<Node, BoxPorts> ports;
        /** For each class box node, the list of enclosing package ids from outermost to innermost. */
        public final Map<Node, int[]> classAncestors;

        public Result(RoutingGrid grid, Map<Node, BoxPorts> ports, Map<Node, int[]> classAncestors) {
            this.grid = grid;
            this.ports = ports;
            this.classAncestors = classAncestors;
        }
    }

    /** Padding (in grid cells) around each blocked box to keep lines off the border.
     *  0 → routes may hug the class edge, ports sit in the very next free cell. */
    private static final int BLOCK_PADDING = 0;

    public static Result build(Pane overlayPane, Node contentRoot) {
        List<Node> classBoxes = new ArrayList<>();
        List<Node> collapsedPackages = new ArrayList<>();
        List<LevelPackageBox> expandedPackages = new ArrayList<>();
        Map<Node, List<LevelPackageBox>> classChains = new IdentityHashMap<>();
        collect(contentRoot, classBoxes, collapsedPackages, expandedPackages, classChains, new ArrayDeque<>());

        // Compute overall extent in overlay coordinates
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Node n : classBoxes) {
            Bounds b = overlayLocalBounds(n, overlayPane);
            if (b == null) continue;
            if (b.getMinX() < minX) minX = b.getMinX();
            if (b.getMinY() < minY) minY = b.getMinY();
            if (b.getMaxX() > maxX) maxX = b.getMaxX();
            if (b.getMaxY() > maxY) maxY = b.getMaxY();
        }
        for (Node n : collapsedPackages) {
            Bounds b = overlayLocalBounds(n, overlayPane);
            if (b == null) continue;
            if (b.getMinX() < minX) minX = b.getMinX();
            if (b.getMinY() < minY) minY = b.getMinY();
            if (b.getMaxX() > maxX) maxX = b.getMaxX();
            if (b.getMaxY() > maxY) maxY = b.getMaxY();
        }

        if (!Double.isFinite(minX)) {
            // Nothing visible
            return new Result(new RoutingGrid(0, 0, 1, 1), new HashMap<>(), new IdentityHashMap<>());
        }

        // Margin so routes can leave the outermost boxes
        double margin = RoutingGrid.PITCH * 4;
        minX -= margin;
        minY -= margin;
        maxX += margin;
        maxY += margin;

        int cols = (int) Math.ceil((maxX - minX) / RoutingGrid.PITCH) + 1;
        int rows = (int) Math.ceil((maxY - minY) / RoutingGrid.PITCH) + 1;
        RoutingGrid grid = new RoutingGrid(minX, minY, cols, rows);

        // Assign integer ids to expanded packages, sorted by bounding-box area
        // descending so nested packages overwrite outer ones.
        Map<LevelPackageBox, Integer> pkgIds = new IdentityHashMap<>();
        Map<LevelPackageBox, Bounds> pkgBounds = new IdentityHashMap<>();
        for (LevelPackageBox pkg : expandedPackages) {
            Bounds b = overlayLocalBounds(pkg, overlayPane);
            if (b != null) pkgBounds.put(pkg, b);
        }
        List<LevelPackageBox> sortedPkgs = new ArrayList<>(pkgBounds.keySet());
        sortedPkgs.sort((a, b) -> {
            double aa = pkgBounds.get(a).getWidth() * pkgBounds.get(a).getHeight();
            double bb = pkgBounds.get(b).getWidth() * pkgBounds.get(b).getHeight();
            return Double.compare(bb, aa);
        });
        for (int i = 0; i < sortedPkgs.size(); i++) {
            LevelPackageBox pkg = sortedPkgs.get(i);
            pkgIds.put(pkg, i);
            Bounds b = pkgBounds.get(pkg);
            grid.setPackageRect(b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY(), i);
        }

        // Block collapsed packages and class boxes
        for (Node n : collapsedPackages) {
            Bounds b = overlayLocalBounds(n, overlayPane);
            if (b == null) continue;
            blockWithPadding(grid, b);
        }
        for (Node n : classBoxes) {
            Bounds b = overlayLocalBounds(n, overlayPane);
            if (b == null) continue;
            blockWithPadding(grid, b);
        }

        // Assign ports per class box (override BLOCKED at side midpoints)
        Map<Node, BoxPorts> ports = new HashMap<>();
        for (Node n : classBoxes) {
            Bounds b = overlayLocalBounds(n, overlayPane);
            if (b == null) continue;
            BoxPorts bp = assignPorts(grid, b);
            ports.put(n, bp);
        }

        // Build per-class ancestor id chain
        Map<Node, int[]> classAncestors = new IdentityHashMap<>();
        for (Node n : classBoxes) {
            List<LevelPackageBox> chain = classChains.get(n);
            if (chain == null) { classAncestors.put(n, new int[0]); continue; }
            int[] ids = new int[chain.size()];
            int w = 0;
            for (LevelPackageBox p : chain) {
                Integer id = pkgIds.get(p);
                if (id != null) ids[w++] = id;
            }
            if (w < ids.length) {
                int[] shrunk = new int[w];
                System.arraycopy(ids, 0, shrunk, 0, w);
                ids = shrunk;
            }
            classAncestors.put(n, ids);
        }

        return new Result(grid, ports, classAncestors);
    }

    private static void blockWithPadding(RoutingGrid grid, Bounds b) {
        double pad = BLOCK_PADDING * RoutingGrid.PITCH;
        grid.blockRect(b.getMinX() - pad, b.getMinY() - pad,
                       b.getMaxX() + pad, b.getMaxY() + pad);
    }

    private static BoxPorts assignPorts(RoutingGrid grid, Bounds b) {
        double cx = (b.getMinX() + b.getMaxX()) / 2.0;
        double cy = (b.getMinY() + b.getMaxY()) / 2.0;

        // First free cell outside the blocked box on each side
        int topCol = grid.toColClamped(cx);
        int topRow = Math.max(0, grid.toRowClamped(b.getMinY()) - 1);

        int bottomCol = grid.toColClamped(cx);
        int bottomRow = Math.min(grid.rows() - 1, grid.toRowClamped(b.getMaxY()) + 1);

        int leftCol = Math.max(0, grid.toColClamped(b.getMinX()) - 1);
        int leftRow = grid.toRowClamped(cy);

        int rightCol = Math.min(grid.cols() - 1, grid.toColClamped(b.getMaxX()) + 1);
        int rightRow = grid.toRowClamped(cy);

        grid.setPort(topCol, topRow);
        grid.setPort(bottomCol, bottomRow);
        grid.setPort(leftCol, leftRow);
        grid.setPort(rightCol, rightRow);

        // Stub attach points = actual box edge midpoints
        return new BoxPorts(
            new Port(topCol,    topRow,    Port.Side.TOP,    cx,            b.getMinY()),
            new Port(rightCol,  rightRow,  Port.Side.RIGHT,  b.getMaxX(),   cy),
            new Port(bottomCol, bottomRow, Port.Side.BOTTOM, cx,            b.getMaxY()),
            new Port(leftCol,   leftRow,   Port.Side.LEFT,   b.getMinX(),   cy)
        );
    }

    private static void collect(Node node,
                                 List<Node> classBoxes,
                                 List<Node> collapsedPackages,
                                 List<LevelPackageBox> expandedPackages,
                                 Map<Node, List<LevelPackageBox>> classChains,
                                 Deque<LevelPackageBox> stack) {
        if (node == null || !node.isVisible()) return;
        if (node instanceof LevelClassBox) {
            classBoxes.add(node);
            // Chain from outermost to innermost
            List<LevelPackageBox> chain = new ArrayList<>(stack);
            java.util.Collections.reverse(chain);
            classChains.put(node, chain);
            return;
        }
        if (node instanceof LevelPackageBox pkg) {
            int beforeClasses = classBoxes.size();
            stack.push(pkg);
            if (node instanceof Parent p) {
                for (Node child : p.getChildrenUnmodifiable()) {
                    collect(child, classBoxes, collapsedPackages, expandedPackages, classChains, stack);
                }
            }
            stack.pop();
            boolean hasVisibleClasses = classBoxes.size() > beforeClasses;
            if (hasVisibleClasses) {
                expandedPackages.add(pkg);
            } else {
                collapsedPackages.add(pkg);
            }
            return;
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, classBoxes, collapsedPackages, expandedPackages, classChains, stack);
            }
        }
    }

    /** Transforms a node's local bounds into the overlay pane's coordinate system. */
    public static Bounds overlayLocalBounds(Node node, Pane overlayPane) {
        try {
            Bounds sceneBounds = node.localToScene(node.getBoundsInLocal());
            return overlayPane.sceneToLocal(sceneBounds);
        } catch (Exception e) {
            return null;
        }
    }

    private GridBuilder() {}
}
