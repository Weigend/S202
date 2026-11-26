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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.ui.core.graph.ContainerBox;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;

import java.util.function.Consumer;

/**
 * Follows the mouse over a picture and tells which box it is on: a class, or a
 * closed package. An open package is the ground its classes stand on.
 */
final class HoverController {

    private final Consumer<Node> onHover;
    private Node hovered;

    HoverController(Consumer<Node> onHover) {
        this.onHover = onHover;
    }

    /** Follows the mouse over {@code content} from now on. */
    void attach(Node content) {
        hovered = null;
        content.addEventFilter(MouseEvent.MOUSE_MOVED,
                event -> hover(event.getTarget() instanceof Node node ? boxAt(node) : null));
        content.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, event -> {
            if (event.getTarget() == content) {
                hover(null);
            }
        });
        // A box that is dragged away takes its arrows along afterwards.
        content.addEventFilter(MouseEvent.DRAG_DETECTED, event -> hover(null));
    }

    private void hover(Node box) {
        if (box != hovered) {
            hovered = box;
            onHover.accept(box);
        }
    }

    private static Node boxAt(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof LevelClassBox) {
                return n;
            }
            if (n instanceof ContainerBox box) {
                return box.isExpanded() ? null : n;
            }
        }
        return null;
    }
}
