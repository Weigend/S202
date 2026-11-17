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

import javafx.scene.Node;

import java.util.Map;

/**
 * Element lookup that knows about packages whose child boxes are created
 * lazily (see {@link LevelPackageBox#setLazyContent}). An element inside such a
 * package has no box and no registry entry until the package is expanded.
 * Callers pick what they need:
 * <ul>
 *   <li>renderers ask for the {@link #pendingContainer collapsed package}
 *       standing in for the element — nothing gets built;</li>
 *   <li>actions that need the element's own box (selection, what-if moves)
 *       {@link #materialize build it} on demand.</li>
 * </ul>
 */
public final class ElementLookup {

    private ElementLookup() {
    }

    /**
     * The package box whose not-yet-built content contains {@code fqn}, or
     * {@code null} when {@code fqn} is registered itself or is unknown to the
     * view.
     */
    public static LevelPackageBox pendingContainer(Map<String, Node> registry, String fqn) {
        if (fqn == null || registry.containsKey(fqn)) {
            return null;
        }
        String ancestor = parentOf(fqn);
        while (!ancestor.isEmpty()) {
            Node node = registry.get(ancestor);
            if (node != null) {
                // The innermost existing box decides: either it still has to
                // build the element, or the element is not part of this view.
                return node instanceof LevelPackageBox box && box.hasPendingElement(fqn) ? box : null;
            }
            ancestor = parentOf(ancestor);
        }
        return null;
    }

    /**
     * The box registered for {@code fqn}, creating it first when it lies in
     * packages that have not built their content yet. Nothing is expanded: the
     * new boxes stay hidden inside their collapsed packages. Returns
     * {@code null} when {@code fqn} is not part of the view.
     */
    public static Node materialize(Map<String, Node> registry, String fqn) {
        if (fqn == null) {
            return null;
        }
        Node node = registry.get(fqn);
        while (node == null) {
            LevelPackageBox container = pendingContainer(registry, fqn);
            if (container == null) {
                return null;
            }
            // Builds one level; a deeper element moves into the pending
            // content of one of the freshly created sub-packages.
            container.materialize();
            node = registry.get(fqn);
        }
        return node;
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }
}
