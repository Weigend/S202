package de.weigend.s202.ui.whatif;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualPackageGraphTest {

    private static PackageAggregate aggregate(String src, String tgt, int weight) {
        return new PackageAggregate(src, tgt, List.of(new ClassEdge(src + ".X", tgt + ".Y", weight)));
    }

    @Test
    void emptyAggregatesProduceEmptyGraph() {
        VirtualPackageGraph g = VirtualPackageGraph.compute(List.of());

        assertTrue(g.packages().isEmpty());
        assertTrue(g.sccs().isEmpty());
    }

    @Test
    void simpleDagAssignsLevelsByLongestPath() {
        // A → B → C, plus A → C. C is leaf, B sits above C, A sits above B.
        VirtualPackageGraph g = VirtualPackageGraph.compute(List.of(
                aggregate("a", "b", 1),
                aggregate("b", "c", 1),
                aggregate("a", "c", 1)));

        assertEquals(0, g.levelOf("c"));
        assertEquals(1, g.levelOf("b"));
        assertEquals(2, g.levelOf("a"));
    }

    @Test
    void siblingsAtSameLevelGetSameLevel() {
        // A → C and B → C, both A and B are direct ancestors of leaf C.
        VirtualPackageGraph g = VirtualPackageGraph.compute(List.of(
                aggregate("a", "c", 1),
                aggregate("b", "c", 1)));

        assertEquals(0, g.levelOf("c"));
        assertEquals(1, g.levelOf("a"));
        assertEquals(1, g.levelOf("b"));
    }

    @Test
    void twoCyclePackagesShareALevelAndAreTangled() {
        // A ↔ B forms a 2-cycle SCC, B → leaf C.
        VirtualPackageGraph g = VirtualPackageGraph.compute(List.of(
                aggregate("a", "b", 1),
                aggregate("b", "a", 1),
                aggregate("b", "c", 1)));

        assertEquals(0, g.levelOf("c"));
        assertEquals(g.levelOf("a"), g.levelOf("b"), "members of an SCC share a level");
        assertEquals(1, g.levelOf("a"));
        assertTrue(g.isInTangle("a"));
        assertTrue(g.isInTangle("b"));
        assertFalse(g.isInTangle("c"));
    }

    @Test
    void disconnectedGraphLevelsAreIndependent() {
        VirtualPackageGraph g = VirtualPackageGraph.compute(List.of(
                aggregate("a", "b", 1),
                aggregate("c", "d", 1)));

        assertEquals(0, g.levelOf("b"));
        assertEquals(1, g.levelOf("a"));
        assertEquals(0, g.levelOf("d"));
        assertEquals(1, g.levelOf("c"));
    }

    @Test
    void packageNotInAggregatesReturnsMinusOne() {
        VirtualPackageGraph g = VirtualPackageGraph.compute(List.of(aggregate("a", "b", 1)));

        assertEquals(-1, g.levelOf("z"));
        assertEquals(-1, g.sccIdOf("z"));
    }

    @Test
    void sccIdsAreStableWithinASingleComputeCall() {
        VirtualPackageGraph g = VirtualPackageGraph.compute(List.of(
                aggregate("a", "b", 1),
                aggregate("b", "a", 1),
                aggregate("c", "d", 1)));

        // a and b are in one SCC; c and d are in separate trivial SCCs.
        assertEquals(g.sccIdOf("a"), g.sccIdOf("b"));
        assertNotEquals(g.sccIdOf("a"), g.sccIdOf("c"));
        assertNotEquals(g.sccIdOf("c"), g.sccIdOf("d"));
    }

    @Test
    void recomputeAfterVirtualIdentityChangeProducesDifferentLevels() {
        // Start: classes in three packages A, B, C, with edges A.X→B.Y, B.Y→C.Z.
        // Levels should be C=0, B=1, A=2.
        VirtualIdentity vi = new VirtualIdentity();
        PackageAggregator agg = new PackageAggregator();
        List<ClassEdge> edges = List.of(
                new ClassEdge("a.X", "b.Y", 1),
                new ClassEdge("b.Y", "c.Z", 1));

        agg.recompute(vi, edges);
        VirtualPackageGraph g1 = VirtualPackageGraph.compute(agg.aggregates().values());
        assertEquals(0, g1.levelOf("c"));
        assertEquals(1, g1.levelOf("b"));
        assertEquals(2, g1.levelOf("a"));

        // Move class a.X virtually into c. Now A no longer has the outgoing
        // edge as "a.X → b.Y" — it becomes "c.X → b.Y". So new edges:
        // c.X (lives in c) → b.Y (lives in b)  and  b.Y → c.Z (lives in c).
        // That makes the package graph c → b → c — a 2-cycle.
        vi.setOverride("a.X", "c");
        agg.recompute(vi, edges);
        VirtualPackageGraph g2 = VirtualPackageGraph.compute(agg.aggregates().values());

        assertTrue(g2.isInTangle("b"), "b is now part of a cycle with c");
        assertTrue(g2.isInTangle("c"), "c is now part of a cycle with b");
        assertEquals(g2.levelOf("b"), g2.levelOf("c"), "SCC members share a level");
    }
}
