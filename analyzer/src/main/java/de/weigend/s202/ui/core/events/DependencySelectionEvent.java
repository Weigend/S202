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

import de.weigend.s202.ui.core.arrows.DependencySelection;

import java.util.EventObject;

/**
 * Bus event published when the user selects or deselects a dependency arrow.
 * The source is the {@code ArchitectureCanvas} that shows the arrow.
 */
public class DependencySelectionEvent extends EventObject {

    private final DependencySelection selection;

    public DependencySelectionEvent(DependencySelection selection, Object source) {
        super(source);
        this.selection = selection;
    }

    /** The selected arrow; {@link DependencySelection#NONE} after a deselection. */
    public DependencySelection getSelection() {
        return selection;
    }
}
