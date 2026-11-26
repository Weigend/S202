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
 * Whether a box is part of the picture or hidden in a collapsed package, and
 * which box stands for an element that is hidden.
 */
public final class BoxVisibility {

    private BoxVisibility() {
    }

    /**
     * Whether {@code box} and everything around it up to {@code content} is
     * visible. The question ends at the content of the view: a tab that is not
     * in front is invisible as a whole, but its picture stays the same and is
     * drawn for the moment it comes to the front.
     *
     * @param content the node that holds the boxes of the view; {@code null}
     *                asks up to the root of the scene
     */
    public static boolean isShown(Node box, Node content) {
        if (box == null) {
            return false;
        }
        for (Node node = box; node != null && node != content; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The box an arrow from or to the element ends at:
     * <ul>
     *   <li>the element's own box, if it is {@link #isShown shown};</li>
     *   <li>otherwise, inside a collapsed package or component, the nearest
     *       {@link ContainerBox} around it that is shown.</li>
     * </ul>
     *
     * @return {@code null} when nothing in the view stands for the element
     */
    public static Node visibleEndpoint(Map<String, Node> elementRegistry, String fqn, Node content) {
        Node element = elementRegistry.get(fqn);
        if (element == null) {
            // No box yet: the element lies in a collapsed package that builds
            // its content lazily. That package stands in for it.
            element = ElementLookup.pendingContainer(elementRegistry, fqn);
            if (element == null) {
                return null;
            }
        }
        if (isShown(element, content)) {
            return element;
        }
        for (Node parent = element.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getProperties().get("s202.rollupEndpointFqn") instanceof String endpointFqn) {
                Node endpoint = elementRegistry.get(endpointFqn);
                if (endpoint != null && isShown(endpoint, content)) {
                    return endpoint;
                }
            }
            if (parent instanceof ContainerBox && isShown(parent, content)) {
                return parent;
            }
        }
        return null;
    }
}
