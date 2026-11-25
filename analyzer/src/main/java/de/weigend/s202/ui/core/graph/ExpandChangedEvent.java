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
package de.weigend.s202.ui.core.graph;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;

/**
 * A box was opened or closed. The event rises from the box through the scene
 * graph, so it reaches the view the box belongs to and no other.
 */
public final class ExpandChangedEvent extends Event {

    public static final EventType<ExpandChangedEvent> EXPAND_CHANGED =
            new EventType<>(Event.ANY, "S202_EXPAND_CHANGED");

    private ExpandChangedEvent() {
        super(EXPAND_CHANGED);
    }

    /**
     * Fires the event from {@code box} after the current pulse, so that
     * listeners observe stable bounds: running inside the pulse that just
     * queued the layout invalidation makes bounds-readers see stale values.
     */
    public static void fireFrom(Node box) {
        Platform.runLater(() -> box.fireEvent(new ExpandChangedEvent()));
    }
}
