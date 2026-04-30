package de.weigend.s202.ui;

import java.util.function.Consumer;

/**
 * Shared single-selection state across {@link LevelClassBox} and
 * {@link LevelPackageBox}. At most one node — class OR package — is selected
 * at any time, so selecting a package automatically deselects the previously
 * selected class and vice versa.
 *
 * <p>Two static callback slots:
 * <ul>
 *   <li>{@link #setOnSelectionChange(Consumer)} — fires on every selection
 *       change with the new full name (null when cleared).</li>
 *   <li>{@link #setOnDoubleClick(Consumer)} — fires when a node is
 *       double-clicked with the full name.</li>
 * </ul>
 *
 * <p>Static state is consistent with the rest of this package; multi-tab
 * isolation is a known limitation of the existing design.
 */
public final class GraphSelection {

    /** A node — class or package — that participates in single-selection. */
    public interface Selectable {
        String getFullName();
        void applySelectedStyle();
        void applyUnselectedStyle();
    }

    private GraphSelection() {}

    private static Selectable current;
    private static String currentFullName;
    private static Consumer<String> onSelectionChange;
    private static Consumer<String> onDoubleClick;

    /**
     * Toggle/select {@code target}: clicking the already-selected target
     * deselects it; clicking anything else replaces the selection.
     */
    public static void select(Selectable target) {
        if (target == null) {
            clear();
            return;
        }
        if (current == target) {
            target.applyUnselectedStyle();
            current = null;
            currentFullName = null;
            notifyChanged();
            return;
        }
        if (current != null) {
            current.applyUnselectedStyle();
        }
        current = target;
        currentFullName = target.getFullName();
        target.applySelectedStyle();
        notifyChanged();
    }

    /** Force-select without toggling off; used by double-click to keep it selected. */
    public static void ensureSelected(Selectable target) {
        if (target == null || current == target) {
            return;
        }
        if (current != null) {
            current.applyUnselectedStyle();
        }
        current = target;
        currentFullName = target.getFullName();
        target.applySelectedStyle();
        notifyChanged();
    }

    public static void clear() {
        if (current == null) {
            return;
        }
        current.applyUnselectedStyle();
        current = null;
        currentFullName = null;
        notifyChanged();
    }

    public static String getCurrentFullName() {
        return currentFullName;
    }

    public static void setOnSelectionChange(Consumer<String> callback) {
        onSelectionChange = callback;
    }

    public static void setOnDoubleClick(Consumer<String> callback) {
        onDoubleClick = callback;
    }

    static void fireDoubleClick(String fullName) {
        if (onDoubleClick != null && fullName != null) {
            onDoubleClick.accept(fullName);
        }
    }

    private static void notifyChanged() {
        if (onSelectionChange != null) {
            onSelectionChange.accept(currentFullName);
        }
    }
}
