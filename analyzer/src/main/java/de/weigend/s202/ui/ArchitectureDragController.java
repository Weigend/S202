package de.weigend.s202.ui;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * Drag-and-drop controller for the architecture view. Phase 2 of the
 * What-If refactor (see ADR_PULSE_COALESCING_AND_DND §2.3): enables moving
 * class and package boxes between slots in any layout row, including
 * cross-parent moves. Reine UI — no model mutation yet; the dropped node is
 * simply removed from its old HBox and added at the target slot. Model
 * synchronisation comes in Phase 3.
 *
 * <p>Drop targets are slots between children in any HBox row tagged via
 * {@link #markAsRow(HBox)}. While the pointer hovers over a valid slot a
 * thin blue insert marker is inserted there to show the proposed position.
 *
 * <p>API is intentionally static: there is exactly one active drag at a
 * time, scene-wide, so a singleton matches the domain. The opacity ghost of
 * the dragged source stays in place at half opacity until drop or cancel.
 */
public final class ArchitectureDragController {

    private static final String ROW_TAG = "s202.dnd.row";
    private static final String DRAGGABLE_TAG = "s202.dnd.draggable";

    private static final double DRAG_THRESHOLD_PX = 4.0;
    private static final double INSERT_MARKER_WIDTH = 6.0;
    private static final String INSERT_MARKER_STYLE =
            "-fx-background-color: #3b82f6;"
                    + "-fx-background-radius: 2;"
                    + "-fx-opacity: 0.85;";

    private static Node dragSource;
    private static double dragStartScreenX;
    private static double dragStartScreenY;
    private static boolean dragActive;

    private static HBox currentDropRow;
    private static int currentDropSlot = -1;
    private static Region insertMarker;

    private ArchitectureDragController() {}

    /** Tag an HBox as a valid drop-target row. Idempotent. */
    public static void markAsRow(HBox row) {
        row.getProperties().put(ROW_TAG, Boolean.TRUE);
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
            // Mouse press without enough movement to trigger a drag — let the
            // click handlers fire normally. Just clear the press-tracking.
            reset();
            return;
        }
        if (currentDropRow != null && currentDropSlot >= 0
                && isValidDrop(currentDropRow, currentDropSlot)) {
            performDrop();
        }
        if (dragSource != null) {
            dragSource.setOpacity(1.0);
        }
        reset();
        e.consume();
    }

    private static void beginDrag() {
        dragActive = true;
        dragSource.setOpacity(0.5);
    }

    private static void updateDropTarget(MouseEvent e) {
        HBox row = findEnclosingRow(e);
        int slot = computeSlot(row, e.getSceneX());

        if (row != currentDropRow || slot != currentDropSlot) {
            removeInsertMarker();
            currentDropRow = row;
            currentDropSlot = slot;
            if (row != null && slot >= 0 && isValidDrop(row, slot)) {
                installInsertMarker(row, slot);
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

    private static int computeSlot(HBox row, double sceneX) {
        if (row == null) {
            return -1;
        }
        var children = row.getChildren();
        List<Double> midXs = new ArrayList<>(children.size());
        List<Integer> slotForChild = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            if (child == insertMarker) {
                continue;
            }
            Bounds b = child.localToScene(child.getBoundsInLocal());
            midXs.add((b.getMinX() + b.getMaxX()) / 2.0);
            slotForChild.add(i);
        }
        int idx = slotIndexForMidpoints(midXs, sceneX);
        return idx < slotForChild.size() ? slotForChild.get(idx) : children.size();
    }

    /**
     * Pure slot-picking arithmetic: given the centre-X of each non-marker
     * child, return the index of the slot whose insertion would land before
     * the first child whose centre lies to the right of {@code sceneX}. Slot
     * {@code midpoints.size()} means "after the last child".
     */
    static int slotIndexForMidpoints(List<Double> midpoints, double sceneX) {
        for (int i = 0; i < midpoints.size(); i++) {
            if (sceneX < midpoints.get(i)) {
                return i;
            }
        }
        return midpoints.size();
    }

    private static boolean isValidDrop(HBox row, int slot) {
        if (dragSource == null || row == null || slot < 0) {
            return false;
        }
        // No drop into own subtree (hierarchy move would be self-referential).
        Node n = row;
        while (n != null) {
            if (n == dragSource) {
                return false;
            }
            n = n.getParent();
        }
        // No-op drop (same row, same neighbourhood as current position).
        if (dragSource.getParent() == row) {
            int srcIdx = row.getChildren().indexOf(dragSource);
            if (slot == srcIdx || slot == srcIdx + 1) {
                return false;
            }
        }
        return true;
    }

    private static void installInsertMarker(HBox row, int slot) {
        if (insertMarker == null) {
            insertMarker = new Region();
            insertMarker.setPrefWidth(INSERT_MARKER_WIDTH);
            insertMarker.setMinWidth(INSERT_MARKER_WIDTH);
            insertMarker.setMaxWidth(INSERT_MARKER_WIDTH);
            insertMarker.setStyle(INSERT_MARKER_STYLE);
            insertMarker.setMouseTransparent(true);
        }
        int safeSlot = Math.min(slot, row.getChildren().size());
        row.getChildren().add(safeSlot, insertMarker);
    }

    private static void removeInsertMarker() {
        if (insertMarker != null && insertMarker.getParent() instanceof HBox h) {
            h.getChildren().remove(insertMarker);
        }
    }

    private static void performDrop() {
        if (!(dragSource.getParent() instanceof HBox srcRow)) {
            return;
        }
        int srcIdx = srcRow.getChildren().indexOf(dragSource);
        int targetIdx = currentDropSlot;
        srcRow.getChildren().remove(dragSource);
        // Removing source from same row shifts indices left for any slot
        // after source's old position. The insert marker has already been
        // removed in reset() — but reset runs after performDrop. Account for
        // the marker (still in the row at currentDropSlot) by recomputing.
        if (insertMarker != null && insertMarker.getParent() == currentDropRow) {
            targetIdx = currentDropRow.getChildren().indexOf(insertMarker);
            currentDropRow.getChildren().remove(insertMarker);
        } else if (srcRow == currentDropRow && srcIdx < targetIdx) {
            targetIdx--;
        }
        targetIdx = Math.min(targetIdx, currentDropRow.getChildren().size());
        currentDropRow.getChildren().add(targetIdx, dragSource);
    }

    private static void reset() {
        removeInsertMarker();
        dragSource = null;
        dragActive = false;
        currentDropRow = null;
        currentDropSlot = -1;
    }

}
