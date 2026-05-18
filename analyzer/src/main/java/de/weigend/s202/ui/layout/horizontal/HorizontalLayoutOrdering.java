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
package de.weigend.s202.ui.layout.horizontal;

import de.weigend.s202.ui.model.ArchitectureNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Provides sorted layout views over {@link ArchitectureNode} children.
 *
 * <p>This class deliberately returns a copy. The underlying child list keeps
 * the semantic tree traversal order; only rendering uses this row-aware view.
 */
public final class HorizontalLayoutOrdering {

    private static final Comparator<ArchitectureNode> LAYOUT_CHILD_ORDER = Comparator
            .comparingInt(ArchitectureNode::getLevel).reversed()
            .thenComparingInt(ArchitectureNode::getHorizontalLayoutOrder)
            .thenComparing(ArchitectureNode::getFullName);

    private HorizontalLayoutOrdering() {
    }

    public static List<ArchitectureNode> childrenInLayoutOrder(ArchitectureNode parent) {
        Objects.requireNonNull(parent, "parent cannot be null");
        List<ArchitectureNode> children = new ArrayList<>(parent.getChildren());
        children.sort(LAYOUT_CHILD_ORDER);
        return children;
    }
}
