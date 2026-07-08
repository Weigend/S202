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
 * Carries a selection request between UI components without coupling them
 * directly. The full name may identify either a class or a package — the
 * receiver decides what to do based on its data (e.g. the outline tree
 * navigates to whichever node has that full name).
 *
 * <p>Publishers set themselves as the {@code source} so subscribers can skip
 * echoes of their own publishes.
 */
public class NodeSelectionEvent extends EventObject {

    private final String fullName;

    public NodeSelectionEvent(String fullName, Object source) {
        super(source);
        this.fullName = fullName;
    }

    public String getFullName() {
        return fullName;
    }
}
