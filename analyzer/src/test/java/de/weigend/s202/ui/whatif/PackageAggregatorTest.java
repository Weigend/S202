package de.weigend.s202.ui.whatif;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageAggregatorTest {

    private static final ClassEdge AB_TO_C = new ClassEdge("a.b.X", "c.Y", 3);
    private static final ClassEdge AB_TO_C_OTHER = new ClassEdge("a.b.Z", "c.Y", 5);
    private static final ClassEdge INTRA_A = new ClassEdge("a.X", "a.Y", 2);
    private static final ClassEdge AB_TO_D = new ClassEdge("a.b.X", "d.W", 1);

    @Test
    void emptyInputProducesEmptyAggregates() {
        PackageAggregator agg = new PackageAggregator();
        agg.recompute(new VirtualIdentity(), List.of());

        assertTrue(agg.aggregates().isEmpty());
    }

    @Test
    void singleCrossPackageEdgeBecomesOneAggregate() {
        PackageAggregator agg = new PackageAggregator();
        agg.recompute(new VirtualIdentity(), List.of(AB_TO_C));

        PackageAggregate ab2c = agg.get("a.b", "c");
        assertNotNull(ab2c);
        assertEquals(1, ab2c.classEdgeCount());
        assertEquals(3, ab2c.totalWeight());
    }

    @Test
    void multipleEdgesBetweenSamePackagesGroupIntoOneAggregate() {
        PackageAggregator agg = new PackageAggregator();
        agg.recompute(new VirtualIdentity(), List.of(AB_TO_C, AB_TO_C_OTHER));

        PackageAggregate ab2c = agg.get("a.b", "c");
        assertNotNull(ab2c);
        assertEquals(2, ab2c.classEdgeCount());
        assertEquals(8, ab2c.totalWeight());
    }

    @Test
    void intraPackageEdgesAreFilteredOut() {
        PackageAggregator agg = new PackageAggregator();
        agg.recompute(new VirtualIdentity(), List.of(INTRA_A, AB_TO_C));

        assertEquals(1, agg.size());
        assertNull(agg.get("a", "a"));
        assertNotNull(agg.get("a.b", "c"));
    }

    @Test
    void overrideRegroupsClassIntoNewPackageBucket() {
        // Move class a.b.X to package q. Edge a.b.X → c.Y should now appear as q → c.
        VirtualIdentity vi = new VirtualIdentity();
        vi.setOverride("a.b.X", "q");

        PackageAggregator agg = new PackageAggregator();
        agg.recompute(vi, List.of(AB_TO_C, AB_TO_C_OTHER));

        // The two source edges had source a.b.X (moved to q) and a.b.Z (still a.b).
        assertEquals(2, agg.size());

        PackageAggregate ab2c = agg.get("a.b", "c");
        assertNotNull(ab2c);
        assertEquals(1, ab2c.classEdgeCount());
        assertEquals(5, ab2c.totalWeight());

        PackageAggregate q2c = agg.get("q", "c");
        assertNotNull(q2c);
        assertEquals(1, q2c.classEdgeCount());
        assertEquals(3, q2c.totalWeight());
    }

    @Test
    void overridePackageRelocatesAllItsClasses() {
        // Move package a.b → x. Both a.b.X and a.b.Z now sit in x.
        VirtualIdentity vi = new VirtualIdentity();
        vi.setOverride("a.b", "x");

        PackageAggregator agg = new PackageAggregator();
        agg.recompute(vi, List.of(AB_TO_C, AB_TO_C_OTHER, AB_TO_D));

        // a.b is virtually x.b, so containing package of a.b.X is x.b.
        PackageAggregate xb2c = agg.get("x.b", "c");
        assertNotNull(xb2c);
        assertEquals(2, xb2c.classEdgeCount());
        assertEquals(8, xb2c.totalWeight());

        PackageAggregate xb2d = agg.get("x.b", "d");
        assertNotNull(xb2d);
        assertEquals(1, xb2d.classEdgeCount());
        assertEquals(1, xb2d.totalWeight());

        assertNull(agg.get("a.b", "c"), "old key must be gone after package move");
    }

    @Test
    void overrideThatMakesEdgeIntraPackageDropsItFromAggregates() {
        // Move c.Y into a.b. Edge a.b.X → c.Y becomes intra a.b → a.b and drops out.
        VirtualIdentity vi = new VirtualIdentity();
        vi.setOverride("c.Y", "a.b");

        PackageAggregator agg = new PackageAggregator();
        agg.recompute(vi, List.of(AB_TO_C));

        assertTrue(agg.aggregates().isEmpty(),
                "moved-into-same-package edge should not appear as a cross-package aggregate");
    }

    @Test
    void recomputeIsIdempotentAndReplacesPriorState() {
        PackageAggregator agg = new PackageAggregator();
        agg.recompute(new VirtualIdentity(), List.of(AB_TO_C, AB_TO_D));
        assertEquals(2, agg.size());

        agg.recompute(new VirtualIdentity(), List.of(AB_TO_C));
        assertEquals(1, agg.size());
        assertNotNull(agg.get("a.b", "c"));
        assertNull(agg.get("a.b", "d"), "stale aggregate from previous recompute must be gone");
    }
}
