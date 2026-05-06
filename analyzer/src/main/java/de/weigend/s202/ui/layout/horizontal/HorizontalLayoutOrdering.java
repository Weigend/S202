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
