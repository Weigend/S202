package de.weigend.s202.ui.whatif;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VirtualIdentityTest {

    @Test
    void unmodifiedFqcnsResolveToThemselves() {
        VirtualIdentity vi = new VirtualIdentity();

        assertEquals("a.b.C", vi.virtualFullName("a.b.C"));
        assertEquals("a.b", vi.virtualParent("a.b.C"));
    }

    @Test
    void overriddenClassMovesToNewPackage() {
        VirtualIdentity vi = new VirtualIdentity();
        vi.setOverride("a.b.C", "x.y");

        assertEquals("x.y.C", vi.virtualFullName("a.b.C"));
        assertEquals("x.y", vi.virtualParent("a.b.C"));

        // Sibling that wasn't moved remains untouched.
        assertEquals("a.b.D", vi.virtualFullName("a.b.D"));
    }

    @Test
    void overriddenPackageRelocatesDescendantsByPrefix() {
        VirtualIdentity vi = new VirtualIdentity();
        // Move package a.b to live under x: a.b's virtual fullName becomes x.b.
        vi.setOverride("a.b", "x");

        assertEquals("x.b", vi.virtualFullName("a.b"));
        assertEquals("x.b.C", vi.virtualFullName("a.b.C"));
        assertEquals("x.b.c.X", vi.virtualFullName("a.b.c.X"));
        assertEquals("x.b.c", vi.virtualParent("a.b.c.X"));
    }

    @Test
    void closestAncestorOverrideWinsOverDeeperAncestor() {
        VirtualIdentity vi = new VirtualIdentity();
        // Both a.b and a are overridden — the closest (a.b) wins for nodes
        // below it. Override of a only kicks in for nodes whose closest
        // ancestor in the override map is a (e.g. a.other).
        vi.setOverride("a", "q");
        vi.setOverride("a.b", "p");

        assertEquals("p.b.c.X", vi.virtualFullName("a.b.c.X"));
        assertEquals("q.a.other", vi.virtualFullName("a.other"));
    }

    @Test
    void directOverrideTrumpsAncestorOverride() {
        VirtualIdentity vi = new VirtualIdentity();
        vi.setOverride("a.b", "p");
        vi.setOverride("a.b.C", "z");

        assertEquals("z.C", vi.virtualFullName("a.b.C"));
        // Sibling D follows the ancestor override (a.b → p) since it has no
        // direct override of its own.
        assertEquals("p.b.D", vi.virtualFullName("a.b.D"));
    }

    @Test
    void overrideTargetsAreNotRecursivelyResolved() {
        VirtualIdentity vi = new VirtualIdentity();
        // p itself is overridden, but using it as a drop-target anchor for
        // a.b means "land literally at p", not at p's virtual location.
        vi.setOverride("p", "z");
        vi.setOverride("a.b", "p");

        assertEquals("p.b", vi.virtualFullName("a.b"));
    }

    @Test
    void clearRevertsToStaticIdentity() {
        VirtualIdentity vi = new VirtualIdentity();
        vi.setOverride("a.b.C", "x");
        vi.clear();

        assertEquals("a.b.C", vi.virtualFullName("a.b.C"));
        assertTrue(vi.isEmpty());
    }

    @Test
    void nullOverrideRemovesEntry() {
        VirtualIdentity vi = new VirtualIdentity();
        vi.setOverride("a.b.C", "x");
        vi.setOverride("a.b.C", null);

        assertEquals("a.b.C", vi.virtualFullName("a.b.C"));
        assertEquals(0, vi.size());
    }

    @Test
    void mutatingAnOverrideInvalidatesCachedResolution() {
        VirtualIdentity vi = new VirtualIdentity();
        vi.setOverride("a.b", "p");
        assertEquals("p.b.C", vi.virtualFullName("a.b.C"));

        vi.setOverride("a.b", "q");
        assertEquals("q.b.C", vi.virtualFullName("a.b.C"));
    }

    @Test
    void emptyFqcnRejected() {
        VirtualIdentity vi = new VirtualIdentity();

        assertThrows(IllegalArgumentException.class, () -> vi.setOverride("", "x"));
        assertThrows(IllegalArgumentException.class, () -> vi.setOverride(null, "x"));
    }

    @Test
    void simpleNameFqcnWithoutDotsHandled() {
        VirtualIdentity vi = new VirtualIdentity();

        assertEquals("X", vi.virtualFullName("X"));
        assertEquals("", vi.virtualParent("X"));
    }

    @Test
    void topLevelPackageMovedToAnotherTopLevel() {
        VirtualIdentity vi = new VirtualIdentity();
        // Top-level package a moved under top-level package x.
        vi.setOverride("a", "x");

        assertEquals("x.a", vi.virtualFullName("a"));
        assertEquals("x.a.b.C", vi.virtualFullName("a.b.C"));
    }
}
