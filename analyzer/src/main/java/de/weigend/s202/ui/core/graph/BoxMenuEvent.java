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
import javafx.scene.control.MenuItem;

import java.util.ArrayList;
import java.util.List;

/**
 * A box is about to show its menu and asks whoever is around it for more
 * entries: what can be done with any box of any view.
 */
public final class BoxMenuEvent extends Event {

    public static final EventType<BoxMenuEvent> BOX_MENU = new EventType<>(Event.ANY, "S202_BOX_MENU");

    private final String fullName;
    private final List<MenuItem> items = new ArrayList<>();

    private BoxMenuEvent(String fullName) {
        super(BOX_MENU);
        this.fullName = fullName;
    }

    /** The entries the view around {@code box} offers for the element {@code fullName}. */
    public static List<MenuItem> itemsFor(Node box, String fullName) {
        BoxMenuEvent event = new BoxMenuEvent(fullName);
        // The copies made on the way up share the list.
        Event.fireEvent(box, event);
        return event.items;
    }

    public String fullName() {
        return fullName;
    }

    public void offer(List<MenuItem> offered) {
        items.addAll(offered);
    }
}
