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

import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.layout.HBox;

/**
 * A drag finished with a visual drop. Fired by the dragged box from its new
 * place, the event rises to the view the box now sits in.
 */
public final class BoxDroppedEvent extends Event {

    public static final EventType<BoxDroppedEvent> BOX_DROPPED =
            new EventType<>(Event.ANY, "S202_BOX_DROPPED");

    private final transient Node box;
    private final transient HBox destinationRow;
    private final boolean newRow;

    public BoxDroppedEvent(Node box, HBox destinationRow, boolean newRow) {
        super(BOX_DROPPED);
        this.box = box;
        this.destinationRow = destinationRow;
        this.newRow = newRow;
    }

    /** The dragged box. */
    public Node box() {
        return box;
    }

    /** The row the box now sits in. */
    public HBox destinationRow() {
        return destinationRow;
    }

    /** Whether the row was created for the box, by a drop into the gap between two rows. */
    public boolean isNewRow() {
        return newRow;
    }
}
