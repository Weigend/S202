package de.weigend.s202.ui.layout.horizontal;

import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorizontalRowLayoutOptimizerTest {

    @Test
    void connectedClassesOnDifferentRowsReceiveAlignedHorizontalOrders() {
        ArchitectureNode root = pkg("root", "root", 0);
        root.addChild(cls("p.SourceLeft", "SourceLeft", 1, "p.ZTargetLeft"));
        root.addChild(cls("p.SourceRight", "SourceRight", 1, "p.ATargetRight"));
        root.addChild(cls("p.ATargetRight", "ATargetRight", 0));
        root.addChild(cls("p.ZTargetLeft", "ZTargetLeft", 0));

        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);

        assertTrue(order(root, "p.SourceLeft") < order(root, "p.SourceRight"));
        assertTrue(order(root, "p.ZTargetLeft") < order(root, "p.ATargetRight"),
                "Bottom row should follow the connected top-row sources, not alphabetical order");
    }

    @Test
    void packageRowElementsArePositionedByTheirSubtreeClasses() {
        ArchitectureNode root = pkg("root", "root", 0);
        root.addChild(cls("p.SourceLeft", "SourceLeft", 1, "p.ZTargetPackage.Target"));
        root.addChild(cls("p.SourceRight", "SourceRight", 1, "p.ATargetPackage.Target"));

        ArchitectureNode rightAlphabeticalPackage = pkg("p.ATargetPackage", "ATargetPackage", 0);
        rightAlphabeticalPackage.addChild(cls("p.ATargetPackage.Target", "Target", 0));
        root.addChild(rightAlphabeticalPackage);

        ArchitectureNode leftConnectedPackage = pkg("p.ZTargetPackage", "ZTargetPackage", 0);
        leftConnectedPackage.addChild(cls("p.ZTargetPackage.Target", "Target", 0));
        root.addChild(leftConnectedPackage);

        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);

        assertTrue(order(root, "p.ZTargetPackage") < order(root, "p.ATargetPackage"),
                "A package child should be ordered by the dependencies to classes in its subtree");
    }

    @Test
    void optimizerDoesNotMutateChildListOrder() {
        ArchitectureNode root = pkg("root", "root", 0);
        root.addChild(cls("p.SourceLeft", "SourceLeft", 1, "p.ZTargetLeft"));
        root.addChild(cls("p.SourceRight", "SourceRight", 1, "p.ATargetRight"));
        root.addChild(cls("p.ATargetRight", "ATargetRight", 0));
        root.addChild(cls("p.ZTargetLeft", "ZTargetLeft", 0));
        List<ArchitectureNode> originalChildren = List.copyOf(root.getChildren());

        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);

        assertEquals(originalChildren, root.getChildren(),
                "Horizontal order must be stored in the model field without sorting children");
    }

    @Test
    void layoutOrderingUsesHorizontalOrderWithinRowsWithoutMutatingChildren() {
        ArchitectureNode root = pkg("root", "root", 0);
        ArchitectureNode alphabeticalFirst = pkg("a", "a", 0);
        ArchitectureNode orderedFirst = pkg("z", "z", 0);
        alphabeticalFirst.setHorizontalLayoutOrder(1);
        orderedFirst.setHorizontalLayoutOrder(0);
        root.addChild(alphabeticalFirst);
        root.addChild(orderedFirst);

        List<ArchitectureNode> orderedView = HorizontalLayoutOrdering.childrenInLayoutOrder(root);

        assertEquals(List.of(orderedFirst, alphabeticalFirst), orderedView);
        assertEquals(List.of(alphabeticalFirst, orderedFirst), root.getChildren(),
                "Ordering helper must return a sorted view without sorting children");
    }

    private static ArchitectureNode pkg(String fullName, String simpleName, int level) {
        return new ArchitectureNode(fullName, simpleName, NodeType.PACKAGE, true, level);
    }

    private static ArchitectureNode cls(String fullName, String simpleName, int level, String... dependencies) {
        ArchitectureNode node = new ArchitectureNode(fullName, simpleName, NodeType.CLASS, false, level);
        node.setDependencies(new HashSet<>(Arrays.asList(dependencies)));
        return node;
    }

    private static int order(ArchitectureNode root, String fullName) {
        ArchitectureNode node = find(root, fullName);
        if (node == null) {
            throw new AssertionError("Node not found: " + fullName);
        }
        return node.getHorizontalLayoutOrder();
    }

    private static ArchitectureNode find(ArchitectureNode node, String fullName) {
        if (fullName.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = find(child, fullName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
