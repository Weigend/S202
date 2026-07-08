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

/**
 * Property-Tags, mit denen Fachkomponenten Szene-Knoten markieren, damit
 * Kern-Mechanik (Drag&Drop-Rollup, Expansion-Erhalt) sie erkennen kann,
 * ohne die konkreten Box-Typen zu kennen. Die Component-Ansicht setzt die
 * API-Tags; der Kern liest sie nur.
 */
public final class BoxTags {

    /** Markiert Elemente, die im API-Bereich einer Komponente liegen. */
    public static final String API_ELEMENT_TAG = "s202.component.api.element";
    /** Markiert Container, deren Zeilen als API-Drop-Ziel gelten. */
    public static final String API_DROP_TARGET_TAG = "s202.component.api.dropTarget";

    private BoxTags() {
    }

    public static boolean isApiElement(Node node) {
        return node != null && Boolean.TRUE.equals(node.getProperties().get(API_ELEMENT_TAG));
    }

    public static boolean isApiDropTarget(Node node) {
        Node current = node;
        while (current != null) {
            if (Boolean.TRUE.equals(current.getProperties().get(API_DROP_TARGET_TAG))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
