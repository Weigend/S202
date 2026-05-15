package de.weigend.s202.domain.architecture;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the {@link HierarchicalLayeredArchitectureBuilder}.
 * Each test wires a small synthetic {@link DomainModel} by hand so the
 * Rows-of-Cols expectation is obvious.
 */
class HierarchicalLayeredArchitectureBuilderTest {

    @Test
    void emptyDomainModelProducesEmptyArchitecture() {
        DomainModel domain = new DomainModel();
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        Architecture arch = new HierarchicalLayeredArchitectureBuilder().build(domain);

        assertInstanceOf(HierarchicalLayeredArchitecture.class, arch);
        HierarchicalLayeredArchitecture hla = (HierarchicalLayeredArchitecture) arch;
        assertTrue(hla.rows().isEmpty());
        assertTrue(hla.violations().isEmpty());
    }

    @Test
    void singlePackageIsTransparentSoTopLevelHoldsItsClassesDirectly() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "app", 1);
        addClass(domain, "app.HighA", 1, Set.of("app.LowB"));
        addClass(domain, "app.HighB", 1, Set.of("app.LowB"));
        addClass(domain, "app.LowB", 0, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        // "app" is a single-child top-level wrapper — transparent. Effective
        // root becomes "app", and the visible top-level rows directly hold
        // the classes grouped by level.
        assertEquals(2, arch.rows().size(), "two rows — one per class level");
        assertEquals(2, arch.rows().get(0).size(), "row 0 holds the two level-1 classes");
        assertEquals(1, arch.rows().get(0).get(0).level(), "row 0 is the higher level");
        assertEquals(1, arch.rows().get(1).size(), "row 1 holds the single level-0 class");
        assertEquals(0, arch.rows().get(1).get(0).level(), "row 1 is the lower level");
        assertInstanceOf(Element.ClassElement.class, arch.rows().get(0).get(0));
        assertTrue(arch.violations().isEmpty(),
                "all class deps point downward — no violations expected");
    }

    @Test
    void multipleTopLevelPackagesAppearAsPackageElementsInRows() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "ui", 2);
        addPackage(domain, "domain", 0);
        addClass(domain, "ui.View", 2, Set.of());
        addClass(domain, "domain.Model", 0, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        // Two top-level packages → no transparent skip; each package shows up
        // at its own level row, holding its own class.
        assertEquals(2, arch.rows().size());
        Element.PackageElement uiPkg = (Element.PackageElement) arch.rows().get(0).get(0);
        assertEquals("ui", uiPkg.fqn());
        assertEquals(2, uiPkg.level());
        assertEquals(1, uiPkg.rows().size());
        assertEquals("ui.View", uiPkg.rows().get(0).get(0).fqn());

        Element.PackageElement domainPkg = (Element.PackageElement) arch.rows().get(1).get(0);
        assertEquals("domain", domainPkg.fqn());
        assertEquals(0, domainPkg.level());
    }

    @Test
    void classWithUpwardDepProducesUpwardViolation() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "app", 1);
        addClass(domain, "app.Low", 0, Set.of("app.High"));   // upward
        addClass(domain, "app.High", 1, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        assertEquals(1, arch.violations().size());
        Violation v = arch.violations().get(0);
        assertEquals("app.Low", v.sourceFqn());
        assertEquals("app.High", v.targetFqn());
        assertEquals(ViolationKind.UPWARD, v.kind());
        assertEquals(0, v.sourceLevel());
        assertEquals(1, v.targetLevel());
    }

    @Test
    void mutuallyDependentPackagesProduceATangle() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "a", 0);
        addPackage(domain, "b", 0);
        addClass(domain, "a.X", 0, Set.of("b.Y"));
        addClass(domain, "b.Y", 0, Set.of("a.X"));
        domain.setPackageEdgeWeights(Map.of(
                "a", Map.of("b", 1),
                "b", Map.of("a", 1)));
        domain.setPackageBackEdges(Set.of("b\0a"));

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        assertEquals(1, arch.tangles().size(), "one SCC of size 2 → one tangle");
        Tangle tangle = arch.tangles().get(0);
        assertEquals(Set.of("a", "b"), tangle.members());
    }

    @Test
    void transparentSingleChildPackagesAreSkippedToFindEffectiveRoot() {
        DomainModel domain = new DomainModel();
        // Full chain de → de.weigend → de.weigend.s202 → de.weigend.s202.app
        // is collapsed because each ancestor has exactly one sub-package.
        // The first ancestor that breaks transparency is the leaf "app"
        // because it has a class child; it itself is the effective root.
        addPackage(domain, "de", 0);
        addPackage(domain, "de.weigend", 0);
        addPackage(domain, "de.weigend.s202", 0);
        addPackage(domain, "de.weigend.s202.app", 0);
        addClass(domain, "de.weigend.s202.app.X", 0, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        // Visible top-level holds the class directly — "app" is the
        // effective root and doesn't carry its own box at the surface.
        assertEquals(1, arch.rows().size());
        Element first = arch.rows().get(0).get(0);
        assertInstanceOf(Element.ClassElement.class, first);
        assertEquals("de.weigend.s202.app.X", first.fqn());
    }

    @Test
    void rowsAreOrderedFromHighestLevelToLowest() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "app", 2);
        addClass(domain, "app.A", 2, Set.of());
        addClass(domain, "app.B", 1, Set.of());
        addClass(domain, "app.C", 0, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        // "app" is again a single-child wrapper → transparent. Three classes
        // sit at the visible top-level, one row per level, descending.
        assertEquals(3, arch.rows().size());
        assertEquals(2, arch.rows().get(0).get(0).level());
        assertEquals(1, arch.rows().get(1).get(0).level());
        assertEquals(0, arch.rows().get(2).get(0).level());
    }

    @Test
    void parentToChildClassRefSurfacesAsContainmentEdge() {
        // Mirrors the real-world pattern: ui.wfx.S202Module touching
        // ui.wfx.whatif.WhatIfDependenciesModule. Source package strictly
        // contains the target package, so the level calculator filters this
        // edge out — the builder must still surface it as a containment
        // edge so the renderer can show it.
        DomainModel domain = new DomainModel();
        addPackage(domain, "wfx", 0);
        addPackage(domain, "wfx.whatif", 1);
        addClass(domain, "wfx.S202Module", 0, Set.of("wfx.whatif.WhatIfDependenciesModule"));
        addClass(domain, "wfx.whatif.WhatIfDependenciesModule", 1, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        List<ContainmentEdge> classEdges = arch.containmentEdges().stream()
                .filter(e -> e.scope() == EdgeScope.CLASS).toList();
        List<ContainmentEdge> pkgEdges = arch.containmentEdges().stream()
                .filter(e -> e.scope() == EdgeScope.PACKAGE).toList();

        assertEquals(1, classEdges.size());
        assertEquals("wfx.S202Module", classEdges.get(0).sourceFqn());
        assertEquals("wfx.whatif.WhatIfDependenciesModule", classEdges.get(0).targetFqn());

        assertEquals(1, pkgEdges.size());
        assertEquals("wfx", pkgEdges.get(0).sourceFqn());
        assertEquals("wfx.whatif", pkgEdges.get(0).targetFqn());

        // The reverse edge (whatif -> wfx, i.e. child importing parent) is
        // NOT a containment edge — it's the normal child-to-parent case the
        // level calculator keeps in its graph.
        assertTrue(arch.containmentEdges().stream().noneMatch(
                e -> e.sourceFqn().equals("wfx.whatif.WhatIfDependenciesModule")));
    }

    @Test
    void childToParentClassRefIsNotAContainmentEdge() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "wfx", 0);
        addPackage(domain, "wfx.whatif", 1);
        addClass(domain, "wfx.ArchitectureWfxView", 0, Set.of());
        addClass(domain, "wfx.whatif.WhatIfDependenciesModule", 1, Set.of("wfx.ArchitectureWfxView"));
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        assertTrue(arch.containmentEdges().isEmpty(),
                "child -> parent edges flow upward, not into a containment relationship");
    }

    @Test
    void deepContainmentAggregatesAtBothScopes() {
        // Two distinct class-level refs from the same source package into
        // two different sub-packages produce two class edges and two
        // package edges. Reverse refs are ignored.
        DomainModel domain = new DomainModel();
        addPackage(domain, "app", 0);
        addPackage(domain, "app.outline", 1);
        addPackage(domain, "app.tangles", 1);
        addClass(domain, "app.Wiring", 0, Set.of("app.outline.View", "app.tangles.Filter"));
        addClass(domain, "app.outline.View", 1, Set.of());
        addClass(domain, "app.tangles.Filter", 1, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        long classCount = arch.containmentEdges().stream()
                .filter(e -> e.scope() == EdgeScope.CLASS).count();
        long pkgCount = arch.containmentEdges().stream()
                .filter(e -> e.scope() == EdgeScope.PACKAGE).count();
        assertEquals(2, classCount);
        assertEquals(2, pkgCount);
    }

    // ----------------------------------------------- fixture helpers

    private static void addPackage(DomainModel domain, String fqn, int level) {
        domain.addPackage(fqn, new CalculatedElementInfo(
                fqn, simpleName(fqn), "PACKAGE", level, new HashSet<>()));
    }

    private static void addClass(DomainModel domain, String fqn, int level, Set<String> dependencies) {
        domain.addClass(fqn, new CalculatedElementInfo(
                fqn, simpleName(fqn), "CLASS", level, new HashSet<>(dependencies)));
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
