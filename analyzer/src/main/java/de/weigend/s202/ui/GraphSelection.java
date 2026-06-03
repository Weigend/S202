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
package de.weigend.s202.ui;

import java.util.function.Consumer;

/**
 * Shared single-selection state across {@link LevelClassBox} and
 * {@link LevelPackageBox}. At most one node — class OR package — is selected
 * at any time, so selecting a package automatically deselects the previously
 * selected class and vice versa.
 *
 * <p>Selection changes are routed to the selected node's owner sink when
 * present. The static callback remains as a fallback for direct test/demo boxes
 * that are not built through an {@link ArchitectureView}.
 *
 * <p>Two callback slots:
 * <ul>
 *   <li>{@link #setOnSelectionChange(Consumer)} — fires on every selection
 *       change with the new full name (null when cleared) when the selectable
 *       does not provide an owner sink.</li>
 *   <li>{@link #setOnDoubleClick(Consumer)} — fires when a node is
 *       double-clicked with the full name.</li>
 * </ul>
 */
public final class GraphSelection {

    /** A node — class or package — that participates in single-selection. */
    public interface Selectable {
        String getFullName();
        void applySelectedStyle();
        void applyUnselectedStyle();

        default Consumer<String> selectionChangeSink() {
            return null;
        }
    }

    private GraphSelection() {}

    private static Selectable current;
    private static String currentFullName;
    private static Consumer<String> onSelectionChange;
    private static Consumer<String> onDoubleClick;

    /**
     * Toggle/select {@code target}: clicking the already-selected target
     * deselects it; clicking anything else replaces the selection.
     */
    public static void select(Selectable target) {
        if (target == null) {
            clear();
            return;
        }
        if (current == target) {
            Consumer<String> sink = sinkFor(current);
            target.applyUnselectedStyle();
            current = null;
            currentFullName = null;
            notifyChanged(sink, null);
            return;
        }
        Selectable previous = current;
        Consumer<String> previousSink = sinkFor(previous);
        if (current != null) {
            current.applyUnselectedStyle();
        }
        current = target;
        currentFullName = target.getFullName();
        target.applySelectedStyle();
        Consumer<String> targetSink = sinkFor(target);
        if (previous != null && previousSink != targetSink) {
            notifyChanged(previousSink, null);
        }
        notifyChanged(targetSink, currentFullName);
    }

    /** Force-select without toggling off; used by double-click to keep it selected. */
    public static void ensureSelected(Selectable target) {
        if (target == null || current == target) {
            return;
        }
        Selectable previous = current;
        Consumer<String> previousSink = sinkFor(previous);
        if (current != null) {
            current.applyUnselectedStyle();
        }
        current = target;
        currentFullName = target.getFullName();
        target.applySelectedStyle();
        Consumer<String> targetSink = sinkFor(target);
        if (previous != null && previousSink != targetSink) {
            notifyChanged(previousSink, null);
        }
        notifyChanged(targetSink, currentFullName);
    }

    public static void clear() {
        if (current == null) {
            return;
        }
        Consumer<String> sink = sinkFor(current);
        current.applyUnselectedStyle();
        current = null;
        currentFullName = null;
        notifyChanged(sink, null);
    }

    public static String getCurrentFullName() {
        return currentFullName;
    }

    public static void setOnSelectionChange(Consumer<String> callback) {
        onSelectionChange = callback;
    }

    public static void setOnDoubleClick(Consumer<String> callback) {
        onDoubleClick = callback;
    }

    public static void fireDoubleClick(String fullName) {
        if (onDoubleClick != null && fullName != null) {
            onDoubleClick.accept(fullName);
        }
    }

    private static Consumer<String> sinkFor(Selectable selectable) {
        if (selectable != null && selectable.selectionChangeSink() != null) {
            return selectable.selectionChangeSink();
        }
        return onSelectionChange;
    }

    private static void notifyChanged(Consumer<String> sink, String fullName) {
        if (sink != null) {
            sink.accept(fullName);
        }
    }
}
