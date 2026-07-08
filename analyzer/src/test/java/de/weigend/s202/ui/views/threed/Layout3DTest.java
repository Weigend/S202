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
package de.weigend.s202.ui.views.threed;

import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import de.weigend.s202.ui.views.threed.Layout3D.NodeLayout3D;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link Layout3D} reproduces the same structural ordering as the
 * 2D architecture view, but with the Y axis swapped to Z.
 *
 * <p><b>The core invariant:</b> when all nodes have identical fanin and fanout
 * (equal metric box sizes), the X positions in 3D must follow the same
 * {@code horizontalLayoutOrder} ordering as the 2D algorithm, and the Z
 * positions must reflect the {@code level} grouping the 2D algorithm uses
 * for its vertical rows.
 *
 * <p>No JavaFX toolkit is required – {@link Layout3D} is pure Java.
 */
class Layout3DTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Creates a PACKAGE node with architectureLevel set (so it is displayable). */
    private static ArchitectureNode pkg(String fqn, int architectureLevel) {
        ArchitectureNode n = new ArchitectureNode(fqn, fqn, NodeType.PACKAGE, false);
        n.setArchitectureLevel(architectureLevel);
        return n;
    }

    /** Creates a CLASS node. */
    private static ArchitectureNode cls(String fqn, int architectureLevel) {
        ArchitectureNode n = new ArchitectureNode(fqn, fqn, NodeType.CLASS, false);
        n.setArchitectureLevel(architectureLevel);
        return n;
    }

    private static NodeLayout3D find(List<NodeLayout3D> layouts, String fqn) {
        return layouts.stream()
                .filter(l -> l.fullName().equals(fqn))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No layout for: " + fqn));
    }

    /** Synthetic root (architectureLevel=-1, never rendered). */
    private static ArchitectureNode root() {
        return new ArchitectureNode("root", "root", NodeType.PACKAGE, false);
        // architectureLevel stays -1 (default)
    }

    // -----------------------------------------------------------------------
    // 1. Siblings ordered by horizontalLayoutOrder along X
    // -----------------------------------------------------------------------

    @Test
    void siblings_orderedByHorizontalLayoutOrder_alongX() {
        ArchitectureNode root = root();

        ArchitectureNode a = pkg("a", 1); a.setLevel(0); a.setHorizontalLayoutOrder(0);
        ArchitectureNode b = pkg("b", 1); b.setLevel(0); b.setHorizontalLayoutOrder(1);
        ArchitectureNode c = pkg("c", 1); c.setLevel(0); c.setHorizontalLayoutOrder(2);
        root.addChild(a); root.addChild(b); root.addChild(c);

        List<NodeLayout3D> layouts = new Layout3D().compute(root);

        NodeLayout3D la = find(layouts, "a");
        NodeLayout3D lb = find(layouts, "b");
        NodeLayout3D lc = find(layouts, "c");

        assertTrue(la.centerX() < lb.centerX(), "a (order=0) must be left of b (order=1)");
        assertTrue(lb.centerX() < lc.centerX(), "b (order=1) must be left of c (order=2)");
    }

    // -----------------------------------------------------------------------
    // 2. Same level → same Z (Y↔Z swap of the 2D row grouping)
    // -----------------------------------------------------------------------

    @Test
    void siblings_atSameLevel_shareZ() {
        ArchitectureNode root = root();

        ArchitectureNode a = pkg("a", 1); a.setLevel(0); a.setHorizontalLayoutOrder(0);
        ArchitectureNode b = pkg("b", 2); b.setLevel(0); b.setHorizontalLayoutOrder(1);
        root.addChild(a); root.addChild(b);

        List<NodeLayout3D> layouts = new Layout3D().compute(root);

        NodeLayout3D la = find(layouts, "a");
        NodeLayout3D lb = find(layouts, "b");

        assertEquals(la.centerZ(), lb.centerZ(), 0.01,
                "Nodes at the same level must share the same Z centre");
    }

    // -----------------------------------------------------------------------
    // 3. Different levels → separated along Z (mirrors 2D vertical separation)
    // -----------------------------------------------------------------------

    @Test
    void differentLevels_separatedAlongZ() {
        ArchitectureNode root = root();

        ArchitectureNode near = pkg("near", 1); near.setLevel(0); near.setHorizontalLayoutOrder(0);
        ArchitectureNode far  = pkg("far",  1); far.setLevel(1);  far.setHorizontalLayoutOrder(0);
        root.addChild(near); root.addChild(far);

        List<NodeLayout3D> layouts = new Layout3D().compute(root);

        NodeLayout3D lNear = find(layouts, "near");
        NodeLayout3D lFar  = find(layouts, "far");

        assertTrue(lNear.centerZ() < lFar.centerZ(),
                "level=0 node must be nearer (smaller Z) than level=1 node");
    }

    // -----------------------------------------------------------------------
    // 4. Children nested inside parent footprint
    // -----------------------------------------------------------------------

    @Test
    void children_nestedInsideParentFootprint() {
        ArchitectureNode root = root();

        ArchitectureNode parent = pkg("parent", 2);
        parent.setLevel(0); parent.setHorizontalLayoutOrder(0);

        ArchitectureNode child1 = pkg("parent.child1", 1);
        child1.setLevel(0); child1.setHorizontalLayoutOrder(0);

        ArchitectureNode child2 = pkg("parent.child2", 1);
        child2.setLevel(0); child2.setHorizontalLayoutOrder(1);

        parent.addChild(child1); parent.addChild(child2);
        root.addChild(parent);

        List<NodeLayout3D> layouts = new Layout3D().compute(root);

        NodeLayout3D lParent = find(layouts, "parent");
        NodeLayout3D lChild1 = find(layouts, "parent.child1");
        NodeLayout3D lChild2 = find(layouts, "parent.child2");

        assertContains(lParent, lChild1, "child1 must lie within parent footprint");
        assertContains(lParent, lChild2, "child2 must lie within parent footprint");

        // Child ordering within parent: child1 (order=0) left of child2 (order=1)
        assertTrue(lChild1.centerX() < lChild2.centerX(),
                "child1 (order=0) must be left of child2 (order=1) within parent");
    }

    // -----------------------------------------------------------------------
    // 5. Box height = architectureLevel × UNIT
    // -----------------------------------------------------------------------

    @Test
    void boxHeight_equalsArchitectureLevelTimesUnit() {
        ArchitectureNode root = root();

        ArchitectureNode pkg1 = pkg("p1", 1); pkg1.setLevel(0); pkg1.setHorizontalLayoutOrder(0);
        ArchitectureNode pkg3 = pkg("p3", 3); pkg3.setLevel(0); pkg3.setHorizontalLayoutOrder(1);
        root.addChild(pkg1); root.addChild(pkg3);

        List<NodeLayout3D> layouts = new Layout3D().compute(root);

        assertEquals(1 * Layout3D.UNIT, find(layouts, "p1").height(), 0.01, "height = 1 × UNIT");
        assertEquals(3 * Layout3D.UNIT, find(layouts, "p3").height(), 0.01, "height = 3 × UNIT");
    }

    // -----------------------------------------------------------------------
    // 6. Box dimensions match fanin / fanout when no children expand the footprint
    // -----------------------------------------------------------------------

    @Test
    void boxDimensions_matchFaninFanout_withoutChildren() {
        ArchitectureNode root = root();

        ArchitectureNode pkg = pkg("p", 2);
        pkg.setLevel(0); pkg.setHorizontalLayoutOrder(0);
        pkg.setDependents(Set.of("x", "y"));        // fanin = 2  → width = 2 × UNIT
        pkg.setDependencies(Set.of("z"));            // fanout = 1 → depth = 1 × UNIT
        root.addChild(pkg);

        NodeLayout3D l = find(new Layout3D().compute(root), "p");

        assertEquals(2 * Layout3D.UNIT, l.width(), 0.01, "width = fanin × UNIT");
        assertEquals(1 * Layout3D.UNIT, l.depth(), 0.01, "depth = fanout × UNIT");
    }

    // -----------------------------------------------------------------------
    // 7. Y↔Z swap invariant: equal-size boxes → 3D X/Z match 2D X/Y ordering
    //
    //    With fanin=fanout=1 (uniform box size = UNIT×UNIT):
    //      expected X of node at horizontalLayoutOrder k, same parent =
    //        k × (UNIT + NODE_GAP) + UNIT/2  [at top level, no parent padding]
    //      expected Z of node at level L, same parent =
    //        L × (UNIT + GROUP_GAP) + UNIT/2
    //
    //    This is the mathematical equivalent of the 2D layout with equal boxes.
    // -----------------------------------------------------------------------

    @Test
    void equalSizeBoxes_xyZPositionsMatch2DAlgorithm() {
        double U = Layout3D.UNIT;
        double G = Layout3D.NODE_GAP;
        double LG = Layout3D.GROUP_GAP;

        ArchitectureNode root = root();

        // Row Z=0 (level 0): two siblings side by side
        ArchitectureNode a = pkg("a", 1); a.setLevel(0); a.setHorizontalLayoutOrder(0);
        ArchitectureNode b = pkg("b", 1); b.setLevel(0); b.setHorizontalLayoutOrder(1);
        // Row Z=1 (level 1): one node
        ArchitectureNode c = pkg("c", 1); c.setLevel(1); c.setHorizontalLayoutOrder(0);

        root.addChild(a); root.addChild(b); root.addChild(c);

        List<NodeLayout3D> layouts = new Layout3D().compute(root);

        NodeLayout3D la = find(layouts, "a");
        NodeLayout3D lb = find(layouts, "b");
        NodeLayout3D lc = find(layouts, "c");

        double expectedXa = U / 2;                   // order 0, first box starts at X=0
        double expectedXb = U + G + U / 2;           // order 1, after first box + gap
        double expectedZlevel0 = U / 2;              // level 0: Z starts at 0
        double expectedZlevel1 = U + LG + U / 2;    // level 1: after level-0 row + GROUP_GAP

        assertEquals(expectedXa,      la.centerX(), 0.01, "a X");
        assertEquals(expectedXb,      lb.centerX(), 0.01, "b X");
        assertEquals(expectedZlevel0, la.centerZ(), 0.01, "a Z (level 0)");
        assertEquals(expectedZlevel0, lb.centerZ(), 0.01, "b Z (level 0)");
        assertEquals(expectedXa,      lc.centerX(), 0.01, "c X (only node in level 1 row)");
        assertEquals(expectedZlevel1, lc.centerZ(), 0.01, "c Z (level 1)");
    }

    // -----------------------------------------------------------------------
    // 8. Mixed nodes (packages and classes) in the same tree
    // -----------------------------------------------------------------------

    @Test
    void packageAndClassNodes_layoutedCorrectly() {
        ArchitectureNode root = root();

        ArchitectureNode pkg = pkg("com.example", 1);
        pkg.setLevel(0); pkg.setHorizontalLayoutOrder(0);

        ArchitectureNode clazz = cls("com.example.Foo", 1);
        clazz.setLevel(0); clazz.setHorizontalLayoutOrder(0);

        pkg.addChild(clazz);
        root.addChild(pkg);

        List<NodeLayout3D> layouts = new Layout3D().compute(root);
        assertEquals(2, layouts.size(), "one package + one class");

        NodeLayout3D lPkg   = find(layouts, "com.example");
        NodeLayout3D lClass = find(layouts, "com.example.Foo");

        assertContains(lPkg, lClass, "class must be inside package footprint");
    }

    // -----------------------------------------------------------------------
    // Helper assertions
    // -----------------------------------------------------------------------

    private static void assertContains(NodeLayout3D outer, NodeLayout3D inner, String msg) {
        assertTrue(outer.minX() <= inner.minX() + 0.01,
                msg + " [minX: outer=" + outer.minX() + " inner=" + inner.minX() + "]");
        assertTrue(outer.maxX() >= inner.maxX() - 0.01,
                msg + " [maxX: outer=" + outer.maxX() + " inner=" + inner.maxX() + "]");
        assertTrue(outer.minZ() <= inner.minZ() + 0.01,
                msg + " [minZ: outer=" + outer.minZ() + " inner=" + inner.minZ() + "]");
        assertTrue(outer.maxZ() >= inner.maxZ() - 0.01,
                msg + " [maxZ: outer=" + outer.maxZ() + " inner=" + inner.maxZ() + "]");
    }
}
