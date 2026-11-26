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
package de.weigend.s202.ui.core.arrows;

import de.weigend.s202.domain.DependencyEdge;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The one selected line of a view, whichever overlay it belongs to: arrow,
 * violation or cycle. It outlives a redraw - a line that stands for the same
 * thing is selected again.
 */
public final class LineSelection {

    /** How a drawn line shows that it is selected, or under the mouse. */
    public interface Look {
        void selected(boolean selected);

        default void hovered(boolean hovered) {
        }
    }

    /**
     * What a line stands for.
     *
     * @param overlay tells lines of different overlays between the same boxes apart
     * @param edges   the class dependencies it stands for
     */
    public record Key(String overlay, String from, String to, List<DependencyEdge> edges) {

        public Key {
            edges = List.copyOf(edges);
        }

        private boolean isSameLine(Key other) {
            return other != null && overlay.equals(other.overlay)
                    && Objects.equals(from, other.from) && Objects.equals(to, other.to);
        }
    }

    private final Consumer<String> status;
    private DependencyEdgeActions actions;
    private Key key;
    private Look look;

    public LineSelection(Consumer<String> status) {
        this.status = Objects.requireNonNull(status, "status cannot be null");
    }

    /** Is told what is selected, and cuts and restores; {@code null} for none. */
    public void setActions(DependencyEdgeActions actions) {
        this.actions = actions;
    }

    /** A line was drawn; it is selected if it stands for what was selected before. */
    void drawn(Key line, Look lineLook) {
        if (line.isSameLine(key)) {
            key = line;
            look = lineLook;
            lineLook.selected(true);
        }
    }

    boolean isSelected(Look lineLook) {
        return look == lineLook;
    }

    /** The lines of an overlay are gone; what was selected is still meant. */
    void cleared(String overlay) {
        if (key != null && key.overlay().equals(overlay)) {
            look = null;
        }
    }

    /** Selects the line, or lifts the selection if it is the selected one. */
    void toggle(Key line, Look lineLook) {
        boolean lift = look == lineLook;
        if (look != null) {
            look.selected(false);
        }
        key = lift ? null : line;
        look = lift ? null : lineLook;
        if (!lift) {
            lineLook.selected(true);
        }
        status.accept(lift ? "Ready" : "Dependency: " + simple(line.from()) + " → " + simple(line.to()));
        if (actions != null) {
            actions.selected(lift ? DependencySelection.NONE
                    : new DependencySelection(line.from(), line.to(), line.edges()));
        }
    }

    /** Lets {@code target} - the line itself or what stands for it - react to the mouse for the line. */
    void install(Node target, Key line, Look lineLook) {
        target.setOnMouseEntered(e -> {
            if (!isSelected(lineLook)) {
                lineLook.hovered(true);
            }
            target.setCursor(Cursor.HAND);
        });
        target.setOnMouseExited(e -> {
            if (!isSelected(lineLook)) {
                lineLook.hovered(false);
            }
            target.setCursor(Cursor.DEFAULT);
        });
        // The secondary button opens the menu.
        target.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                toggle(line, lineLook);
                e.consume();
            }
        });
    }

    /** "Cut" on a line that is one class dependency, or "Restore" if it is cut. */
    void installCutMenu(Node target, String from, String to) {
        if (actions == null) {
            return;
        }
        target.setOnContextMenuRequested(e -> {
            boolean cut = actions.isCut(from, to);
            MenuItem item = new MenuItem(cut ? "Restore" : "Cut");
            item.setOnAction(action -> {
                if (cut) {
                    actions.restore(from, to);
                } else {
                    actions.cut(from, to);
                }
            });
            new ContextMenu(item).show(target, e.getScreenX(), e.getScreenY());
            // Keep the canvas-wide context menu from opening as well.
            e.consume();
        });
    }

    boolean isCut(String from, String to) {
        return actions != null && actions.isCut(from, to);
    }

    /** Makes {@code area}, a copy of a line, an invisible area around it that catches the mouse. */
    static <T extends Shape> T hitArea(T area, double width) {
        area.setFill(null);
        area.setStroke(javafx.scene.paint.Color.TRANSPARENT);
        area.setStrokeWidth(width);
        // The area ends with the line; it must not reach over the boxes.
        area.setStrokeLineCap(StrokeLineCap.BUTT);
        return area;
    }

    private static String simple(String fqn) {
        return fqn == null ? "" : fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
