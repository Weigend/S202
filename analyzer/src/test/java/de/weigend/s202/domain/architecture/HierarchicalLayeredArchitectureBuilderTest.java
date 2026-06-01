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
        assertEquals(1, arch.rows().get(0).get(0).architectureLevel(), "row 0 is the higher level");
        assertEquals(1, arch.rows().get(1).size(), "row 1 holds the single level-0 class");
        assertEquals(0, arch.rows().get(1).get(0).architectureLevel(), "row 1 is the lower level");
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
        assertEquals(2, uiPkg.architectureLevel());
        assertEquals(1, uiPkg.rows().size());
        assertEquals("ui.View", uiPkg.rows().get(0).get(0).fqn());

        Element.PackageElement domainPkg = (Element.PackageElement) arch.rows().get(1).get(0);
        assertEquals("domain", domainPkg.fqn());
        assertEquals(0, domainPkg.architectureLevel());
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
    void classBackEdgeViolationCarriesBackEdgeFlag() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "app", 1);
        addClass(domain, "app.Low", 0, Set.of("app.High"));   // upward
        addClass(domain, "app.High", 1, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());
        domain.setClassBackEdges(Set.of("app.Low\0app.High"));

        HierarchicalLayeredArchitecture arch =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);

        assertEquals(1, arch.violations().size());
        Violation v = arch.violations().get(0);
        assertEquals("app.Low", v.sourceFqn());
        assertEquals("app.High", v.targetFqn());
        assertTrue(v.backEdge(), "the renderer needs this flag to emphasize the selected SCC break edge");
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
        domain.setPackageTangles(List.of(Set.of("a", "b")));

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
        assertEquals(2, arch.rows().get(0).get(0).architectureLevel());
        assertEquals(1, arch.rows().get(1).get(0).architectureLevel());
        assertEquals(0, arch.rows().get(2).get(0).architectureLevel());
    }

    // ----------------------------------------------- fixture helpers

    /**
     * Synthetic test entries set the same value into both architectureLevel
     * and localLevel — the builder sorts by the latter, the tests
     * stay readable with a single per-row parameter.
     */
    private static void addPackage(DomainModel domain, String fqn, int level) {
        CalculatedElementInfo info = new CalculatedElementInfo(
                fqn, simpleName(fqn), "PACKAGE", level, new HashSet<>());
        info.setLocalLevel(level);
        domain.addPackage(fqn, info);
    }

    private static void addClass(DomainModel domain, String fqn, int level, Set<String> dependencies) {
        CalculatedElementInfo info = new CalculatedElementInfo(
                fqn, simpleName(fqn), "CLASS", level, new HashSet<>(dependencies));
        info.setLocalLevel(level);
        domain.addClass(fqn, info);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
