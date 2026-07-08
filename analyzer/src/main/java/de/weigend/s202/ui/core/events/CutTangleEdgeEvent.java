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

import java.util.EventObject;

/**
 * Published when the user applies a recommended tangle cut edge from the
 * dedicated tangle view. This is an interactive UI cut: the persisted analysis
 * model is not mutated.
 */
public class CutTangleEdgeEvent extends EventObject {

    private final String from;
    private final String to;

    public CutTangleEdgeEvent(String from, String to, Object source) {
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
