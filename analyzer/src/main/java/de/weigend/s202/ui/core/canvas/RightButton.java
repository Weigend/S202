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

import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.function.BiConsumer;

/**
 * The right mouse button of a picture: dragged it moves the picture, clicked
 * it opens the context menu - in every view the same.
 *
 * <p>Linux asks for the context menu when the button goes down, Windows when
 * it comes up. Both would open the menu at the start or the end of a drag.
 * The request is therefore held back until the button comes up, and passed
 * on only if the mouse did not move in between.
 */
public final class RightButton {

    /** How far the mouse may move and still click, in pixels on the screen. */
    private static final double CLICK_SLOP = 4.0;

    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;
    private boolean down;
    private boolean dragged;
    private ContextMenuEvent held;
    private boolean replaying;

    private RightButton() {
    }

    /**
     * Installs the right button on {@code node}.
     *
     * @param pan told how far the picture moves, in pixels on the screen, while the right button is dragged
     */
    public static void install(Node node, BiConsumer<Double, Double> pan) {
        RightButton button = new RightButton();
        node.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                button.down = true;
                button.dragged = false;
                button.held = null;
                button.pressX = button.lastX = event.getScreenX();
                button.pressY = button.lastY = event.getScreenY();
            }
        });
        node.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!button.down || !event.isSecondaryButtonDown()) {
                return;
            }
            if (Math.hypot(event.getScreenX() - button.pressX, event.getScreenY() - button.pressY) > CLICK_SLOP) {
                button.dragged = true;
            }
            if (button.dragged) {
                pan.accept(event.getScreenX() - button.lastX, event.getScreenY() - button.lastY);
            }
            button.lastX = event.getScreenX();
            button.lastY = event.getScreenY();
            event.consume();
        });
        node.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() != MouseButton.SECONDARY || !button.down) {
                return;
            }
            button.down = false;
            ContextMenuEvent request = button.held;
            button.held = null;
            if (request != null && !button.dragged) {
                button.replay(request);
            }
        });
        node.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            if (button.replaying || event.isKeyboardTrigger()) {
                return;
            }
            if (button.down) {
                // Asked for while the button is down: wait whether it is a click.
                button.held = event;
                event.consume();
            } else if (button.dragged) {
                // Asked for at the end of a drag.
                event.consume();
            }
        });
    }

    private void replay(ContextMenuEvent request) {
        EventTarget target = request.getTarget();
        replaying = true;
        try {
            Event.fireEvent(target, request.copyFor(request.getSource(), target));
        } finally {
            replaying = false;
        }
    }
}
