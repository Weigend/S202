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
 * Whether a box is part of the picture or hidden in a collapsed package.
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
}
