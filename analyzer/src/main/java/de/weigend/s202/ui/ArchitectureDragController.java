package de.weigend.s202.ui;

import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Drag-and-drop controller for the architecture view. Phase 2 of the
 * What-If refactor (ADR §2.3) with row-stack support added later for
 * dropping a node into a new row between existing rows.
 *
 * <p>Drop targets come in two flavours:
 * <ul>
 *   <li><b>Row slot</b> — a horizontal gap between siblings inside an HBox
 *       row tagged via {@link #markAsRow(HBox)}. A vertical blue marker is
 *       inserted at the proposed slot.</li>
 *   <li><b>Stack gap</b> — a vertical gap between rows inside a VBox row
 *       stack tagged via {@link #markAsRowStack(VBox)}. A horizontal blue
 *       marker spans the stack's width to signal "a new row would land
 *       here". On drop, a fresh HBox row is created at the gap and the
 *       dragged node becomes its sole child.</li>
 * </ul>
 *
 * <p>API is intentionally static: there is exactly one active drag at a
 * time, scene-wide.
 */
public final class ArchitectureDragController {

    private static final String ROW_TAG = "s202.dnd.row";
    private static final String STACK_TAG = "s202.dnd.stack";
    private static final String DRAGGABLE_TAG = "s202.dnd.draggable";

    private static final double DRAG_THRESHOLD_PX = 4.0;
    private static final double INSERT_MARKER_THICKNESS = 6.0;
    private static final String INSERT_MARKER_STYLE =
            "-fx-background-color: #3b82f6;"
                    + "-fx-background-radius: 2;"
                    + "-fx-opacity: 0.85;";

    private enum DropMode { ROW, STACK }

    private static Node dragSource;
    private static double dragStartScreenX;
    private static double dragStartScreenY;
    private static boolean dragActive;

    private static Pane currentDropContainer;
    private static DropMode currentDropMode;
    private static int currentDropIndex = -1;
    private static Region insertMarker;

    private static final List<DropListener> dropListeners = new CopyOnWriteArrayList<>();

    /**
     * Callback invoked when a drag finishes with a successful visual drop.
     * Receives the original drag source plus the HBox row it now sits in —
     * for row-slot drops that's the existing target row, for stack-gap
     * drops it's the freshly created row.
     */
    @FunctionalInterface
    public interface DropListener {
        void onDrop(Node movedSource, HBox destinationRow);
    }

    public static void addDropListener(DropListener listener) {
        dropListeners.add(listener);
    }

    public static void removeDropListener(DropListener listener) {
        dropListeners.remove(listener);
    }

    private ArchitectureDragController() {}

    /** Tag an HBox as a valid drop-target row (horizontal slots between children). */
    public static void markAsRow(HBox row) {
        row.getProperties().put(ROW_TAG, Boolean.TRUE);
    }

    /**
     * Tag a VBox as a row stack — gaps between its rows become drop targets.
     * Picking is forced onto the stack's full bounds so the empty space
     * between row HBoxes still hits the stack (a Pane without a background
     * doesn't pick its gaps by default, which used to fall through to the
     * enclosing top-level row and misroute the drop).
     */
    public static void markAsRowStack(VBox stack) {
        stack.getProperties().put(STACK_TAG, Boolean.TRUE);
        stack.setPickOnBounds(true);
    }

    /**
     * Register a node as draggable. Idempotent — registering the same node
     * twice does not double-attach the filters.
     */
    public static void makeDraggable(Node node) {
        if (Boolean.TRUE.equals(node.getProperties().get(DRAGGABLE_TAG))) {
            return;
        }
        node.getProperties().put(DRAGGABLE_TAG, Boolean.TRUE);
        node.addEventFilter(MouseEvent.MOUSE_PRESSED, ArchitectureDragController::onPress);
        node.addEventFilter(MouseEvent.MOUSE_DRAGGED, ArchitectureDragController::onDrag);
        node.addEventFilter(MouseEvent.MOUSE_RELEASED, ArchitectureDragController::onRelease);
    }

    private static void onPress(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragSource = (Node) e.getSource();
        dragStartScreenX = e.getScreenX();
        dragStartScreenY = e.getScreenY();
        dragActive = false;
    }

    private static void onDrag(MouseEvent e) {
        if (dragSource == null) {
            return;
        }
        if (!dragActive) {
            double dx = e.getScreenX() - dragStartScreenX;
            double dy = e.getScreenY() - dragStartScreenY;
            if (Math.hypot(dx, dy) < DRAG_THRESHOLD_PX) {
                return;
            }
            beginDrag();
        }
        updateDropTarget(e);
        e.consume();
    }

    private static void onRelease(MouseEvent e) {
        if (!dragActive) {
            reset();
            return;
        }
        Node movedSource = dragSource;
        HBox droppedInto = null;
        if (currentDropContainer != null && currentDropIndex >= 0
                && isValidDrop(currentDropContainer, currentDropIndex)) {
            droppedInto = performDrop();
        }
        if (dragSource != null) {
            dragSource.setOpacity(1.0);
        }
        reset();
        e.consume();
        if (droppedInto != null) {
            fireDrop(movedSource, droppedInto);
        }
    }

    private static void fireDrop(Node movedSource, HBox destinationRow) {
        for (DropListener listener : dropListeners) {
            listener.onDrop(movedSource, destinationRow);
        }
    }

    private static void beginDrag() {
        dragActive = true;
        dragSource.setOpacity(0.5);
    }

    private static void updateDropTarget(MouseEvent e) {
        Pane container = null;
        DropMode mode = null;
        int index = -1;

        HBox row = findEnclosingRow(e);
        if (row != null) {
            container = row;
            mode = DropMode.ROW;
            index = computeIndexInRow(row, e.getSceneX());
        } else {
            VBox stack = findEnclosingStack(e);
            if (stack != null) {
                container = stack;
                mode = DropMode.STACK;
                index = computeIndexInStack(stack, e.getSceneY());
            }
        }

        if (container != currentDropContainer || mode != currentDropMode || index != currentDropIndex) {
            removeInsertMarker();
            currentDropContainer = container;
            currentDropMode = mode;
            currentDropIndex = index;
            if (container != null && index >= 0 && isValidDrop(container, index)) {
                installInsertMarker();
            }
        }
    }

    private static HBox findEnclosingRow(MouseEvent e) {
        PickResult pick = e.getPickResult();
        if (pick == null) {
            return null;
        }
        Node n = pick.getIntersectedNode();
        while (n != null) {
            if (n instanceof HBox h && Boolean.TRUE.equals(h.getProperties().get(ROW_TAG))) {
                return h;
            }
            n = n.getParent();
        }
        return null;
    }

    private static VBox findEnclosingStack(MouseEvent e) {
        PickResult pick = e.getPickResult();
        if (pick == null) {
            return null;
        }
        Node n = pick.getIntersectedNode();
        while (n != null) {
            if (n instanceof VBox v && Boolean.TRUE.equals(v.getProperties().get(STACK_TAG))) {
                return v;
            }
            n = n.getParent();
        }
        return null;
    }

    private static int computeIndexInRow(HBox row, double sceneX) {
        return computeIndex(row, sceneX, true);
    }

    private static int computeIndexInStack(VBox stack, double sceneY) {
        return computeIndex(stack, sceneY, false);
    }

    private static int computeIndex(Pane container, double scenePos, boolean horizontal) {
        var children = container.getChildren();
        List<Double> midpoints = new ArrayList<>(children.size());
        List<Integer> slotForChild = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            if (child == insertMarker) {
                continue;
            }
            Bounds b = child.localToScene(child.getBoundsInLocal());
            double mid = horizontal
                    ? (b.getMinX() + b.getMaxX()) / 2.0
                    : (b.getMinY() + b.getMaxY()) / 2.0;
            midpoints.add(mid);
            slotForChild.add(i);
        }
        int idx = slotIndexForMidpoints(midpoints, scenePos);
        return idx < slotForChild.size() ? slotForChild.get(idx) : children.size();
    }

    /**
     * Pure midpoint-picking arithmetic. Given the centre coordinate of each
     * non-marker child along the relevant axis, return the index of the slot
     * that lies before the first child whose centre is past {@code scenePos}.
     */
    static int slotIndexForMidpoints(List<Double> midpoints, double scenePos) {
        for (int i = 0; i < midpoints.size(); i++) {
            if (scenePos < midpoints.get(i)) {
                return i;
            }
        }
        return midpoints.size();
    }

    private static boolean isValidDrop(Pane container, int index) {
        if (dragSource == null || container == null || index < 0) {
            return false;
        }
        // No drop into own subtree (hierarchy move would be self-referential).
        Node n = container;
        while (n != null) {
            if (n == dragSource) {
                return false;
            }
            n = n.getParent();
        }
        // Row mode: no-op if dropping back at source's current adjacent slot.
        if (currentDropMode == DropMode.ROW && dragSource.getParent() == container) {
            int srcIdx = container.getChildren().indexOf(dragSource);
            if (index == srcIdx || index == srcIdx + 1) {
                return false;
            }
        }
        return true;
    }

    private static void installInsertMarker() {
        if (insertMarker == null) {
            insertMarker = new Region();
            insertMarker.setStyle(INSERT_MARKER_STYLE);
            insertMarker.setMouseTransparent(true);
        }
        if (currentDropMode == DropMode.ROW) {
            insertMarker.setPrefWidth(INSERT_MARKER_THICKNESS);
            insertMarker.setMinWidth(INSERT_MARKER_THICKNESS);
            insertMarker.setMaxWidth(INSERT_MARKER_THICKNESS);
            insertMarker.setPrefHeight(Region.USE_COMPUTED_SIZE);
            insertMarker.setMinHeight(Region.USE_COMPUTED_SIZE);
            insertMarker.setMaxHeight(Double.MAX_VALUE);
        } else {
            insertMarker.setPrefHeight(INSERT_MARKER_THICKNESS);
            insertMarker.setMinHeight(INSERT_MARKER_THICKNESS);
            insertMarker.setMaxHeight(INSERT_MARKER_THICKNESS);
            insertMarker.setPrefWidth(Region.USE_COMPUTED_SIZE);
            insertMarker.setMinWidth(Region.USE_COMPUTED_SIZE);
            insertMarker.setMaxWidth(Double.MAX_VALUE);
        }
        int safeIndex = Math.min(currentDropIndex, currentDropContainer.getChildren().size());
        currentDropContainer.getChildren().add(safeIndex, insertMarker);
    }

    private static void removeInsertMarker() {
        if (insertMarker != null && insertMarker.getParent() instanceof Pane p) {
            p.getChildren().remove(insertMarker);
        }
    }

    /**
     * Execute the visual drop. Returns the HBox the dragged source now sits
     * in (existing target row for ROW mode, freshly created row for STACK
     * mode), or {@code null} if the drop couldn't proceed.
     */
    private static HBox performDrop() {
        if (currentDropMode == DropMode.ROW) {
            return performRowDrop();
        }
        return performStackDrop();
    }

    private static HBox performRowDrop() {
        HBox dropRow = (HBox) currentDropContainer;
        if (!(dragSource.getParent() instanceof HBox srcRow)) {
            return null;
        }
        int srcIdx = srcRow.getChildren().indexOf(dragSource);
        int targetIdx = currentDropIndex;
        srcRow.getChildren().remove(dragSource);
        if (insertMarker != null && insertMarker.getParent() == dropRow) {
            targetIdx = dropRow.getChildren().indexOf(insertMarker);
            dropRow.getChildren().remove(insertMarker);
        } else if (srcRow == dropRow && srcIdx < targetIdx) {
            targetIdx--;
        }
        targetIdx = Math.min(targetIdx, dropRow.getChildren().size());
        dropRow.getChildren().add(targetIdx, dragSource);
        cleanupEmptySourceRow(srcRow);
        return dropRow;
    }

    private static HBox performStackDrop() {
        VBox stack = (VBox) currentDropContainer;
        HBox srcRow = dragSource.getParent() instanceof HBox h ? h : null;
        int targetIdx = currentDropIndex;
        if (insertMarker != null && insertMarker.getParent() == stack) {
            targetIdx = stack.getChildren().indexOf(insertMarker);
            stack.getChildren().remove(insertMarker);
        }
        if (srcRow != null) {
            srcRow.getChildren().remove(dragSource);
        }
        HBox newRow = createDropRow();
        targetIdx = Math.min(targetIdx, stack.getChildren().size());
        stack.getChildren().add(targetIdx, newRow);
        newRow.getChildren().add(dragSource);
        cleanupEmptySourceRow(srcRow);
        return newRow;
    }

    /** Build an HBox styled like the existing layout rows and tag it as a drop row. */
    private static HBox createDropRow() {
        HBox row = new HBox(8);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setMaxHeight(Double.MAX_VALUE);
        row.setAlignment(Pos.CENTER);
        row.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(row, Priority.ALWAYS);
        markAsRow(row);
        return row;
    }

    /**
     * Remove a row from its stack if it ended up empty after the source was
     * extracted. Only removes rows that we tagged as drop rows and that sit
     * in a tagged stack, so it cannot strip layout scaffolding by accident.
     */
    private static void cleanupEmptySourceRow(HBox srcRow) {
        if (srcRow == null || srcRow == currentDropContainer) {
            return;
        }
        if (!srcRow.getChildren().isEmpty()) {
            return;
        }
        if (!Boolean.TRUE.equals(srcRow.getProperties().get(ROW_TAG))) {
            return;
        }
        if (srcRow.getParent() instanceof VBox parentStack
                && Boolean.TRUE.equals(parentStack.getProperties().get(STACK_TAG))) {
            parentStack.getChildren().remove(srcRow);
        }
    }

    private static void reset() {
        removeInsertMarker();
        dragSource = null;
        dragActive = false;
        currentDropContainer = null;
        currentDropMode = null;
        currentDropIndex = -1;
    }
}
