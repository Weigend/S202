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
package de.weigend.s202.ui.core.events;

import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;

import java.util.EventObject;

/**
 * Published when the user asks in a view what is between two elements. The
 * source is the {@link ArchitectureCanvas} it was asked in.
 */
public class FromToEvent extends EventObject {

    private final String from;
    private final String to;

    /** @param from the using side, {@code null} to leave it as it is; {@code to} likewise the used side */
    public FromToEvent(String from, String to, ArchitectureCanvas source) {
        super(source);
        this.from = from;
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
