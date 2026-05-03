package de.weigend.s202.ui.tree;

import de.weigend.s202.ui.LevelPackageBox;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ArchitectureTreeBuilderTest {

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (Exception e) {
            // JavaFX already started
        }
    }

    /**
     * Top-level packages at the same level must be placed side-by-side (same HBox),
     * not stacked vertically (separate rows).
     */
    @Test
    public void testSameLevelTopPackagesAreSideBySide() {
        // Build tree: root -> analysis(L:0), reader(L:0), domain(L:1), ui(L:2)
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        root.addChild(new ArchitectureNode("analysis", "analysis", NodeType.PACKAGE, true, 0));
        root.addChild(new ArchitectureNode("reader", "reader", NodeType.PACKAGE, true, 0));
        root.addChild(new ArchitectureNode("domain", "domain", NodeType.PACKAGE, true, 1));
        root.addChild(new ArchitectureNode("ui", "ui", NodeType.PACKAGE, true, 2));

        Map<String, Node> registry = new HashMap<>();
        ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
        VBox topLevel = builder.buildTree(root);

        // topLevel should have 3 HBox rows: L2, L1, L0
        List<HBox> rows = new ArrayList<>();
        for (Node child : topLevel.getChildren()) {
            if (child instanceof HBox) {
                rows.add((HBox) child);
            }
        }
        assertEquals(3, rows.size(), "Should have 3 level rows (L2, L1, L0)");

        // Row 0 (L:2): ui alone
        assertEquals(1, rows.get(0).getChildren().size(), "L2 row should have 1 package (ui)");

        // Row 1 (L:1): domain alone
        assertEquals(1, rows.get(1).getChildren().size(), "L1 row should have 1 package (domain)");

        // Row 2 (L:0): analysis and reader side-by-side
        assertEquals(2, rows.get(2).getChildren().size(),
                "L0 row should have 2 packages (analysis and reader) side-by-side");
    }

    /**
     * When all top-level packages have the same level, they must all be in one HBox.
     */
    @Test
    public void testAllSameLevelInOneRow() {
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        root.addChild(new ArchitectureNode("a", "a", NodeType.PACKAGE, true, 0));
        root.addChild(new ArchitectureNode("b", "b", NodeType.PACKAGE, true, 0));
        root.addChild(new ArchitectureNode("c", "c", NodeType.PACKAGE, true, 0));

        Map<String, Node> registry = new HashMap<>();
        ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
        VBox topLevel = builder.buildTree(root);

        List<HBox> rows = new ArrayList<>();
        for (Node child : topLevel.getChildren()) {
            if (child instanceof HBox) {
                rows.add((HBox) child);
            }
        }
        assertEquals(1, rows.size(), "All same-level packages should be in 1 row");
        assertEquals(3, rows.get(0).getChildren().size(), "The single row should contain all 3 packages");
    }

    /**
     * Each distinct level gets its own row, ordered descending (highest level at top).
     */
    @Test
    public void testDistinctLevelsGetSeparateRows() {
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        root.addChild(new ArchitectureNode("low", "low", NodeType.PACKAGE, true, 0));
        root.addChild(new ArchitectureNode("mid", "mid", NodeType.PACKAGE, true, 1));
        root.addChild(new ArchitectureNode("high", "high", NodeType.PACKAGE, true, 2));

        Map<String, Node> registry = new HashMap<>();
        ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
        VBox topLevel = builder.buildTree(root);

        List<HBox> rows = new ArrayList<>();
        for (Node child : topLevel.getChildren()) {
            if (child instanceof HBox) {
                rows.add((HBox) child);
            }
        }
        assertEquals(3, rows.size(), "3 distinct levels should produce 3 rows");

        // Each row should have exactly 1 package
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(1, rows.get(i).getChildren().size(),
                    "Row " + i + " should have exactly 1 package");
        }
    }

    @Test
    public void testTopLevelPaddingReservesOuterTangleLanes() {
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        root.addChild(new ArchitectureNode("ui", "ui", NodeType.PACKAGE, true, 2));

        Map<String, Node> registry = new HashMap<>();
        ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
        VBox topLevel = builder.buildTree(root);

        assertEquals(52.0, topLevel.getPadding().getTop(), 0.0001,
                "Top gap should fit seven tangle lanes");
        assertEquals(52.0, topLevel.getPadding().getBottom(), 0.0001,
                "Bottom gap should fit seven tangle lanes");
        assertEquals(10.0, topLevel.getPadding().getLeft(), 0.0001);
        assertEquals(10.0, topLevel.getPadding().getRight(), 0.0001);
    }
}
