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

/**
 * What the user can do with a class-to-class dependency arrow, and whether it
 * is currently cut. A cut is a refactoring preview: the dependency is treated
 * as if it were gone. The arrow stays visible, drawn as cut, so it can be
 * restored.
 */
public interface DependencyEdgeActions {

    /** True when the dependency {@code from → to} is cut in the refactoring preview. */
    boolean isCut(String from, String to);

    /** The user chose "Cut" on the arrow {@code from → to}. */
    void cut(String from, String to);

    /** The user chose "Restore" on the cut arrow {@code from → to}. */
    void restore(String from, String to);

    /** The user selected an arrow; {@link DependencySelection#NONE} when it was deselected. */
    void selected(DependencySelection selection);
}
