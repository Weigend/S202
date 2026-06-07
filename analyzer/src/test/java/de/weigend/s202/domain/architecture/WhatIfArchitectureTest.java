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
import de.weigend.s202.domain.impl.HierarchicalLayeredArchitecture;
import de.weigend.s202.domain.impl.HierarchicalLayeredArchitectureBuilder;
import de.weigend.s202.domain.architecture.WhatIfArchitecture;
import de.weigend.s202.domain.impl.WhatIfArchitectureModel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link WhatIfArchitecture}. The model starts as a
 * deep copy of the original {@link HierarchicalLayeredArchitecture} and
 * mutates in place under user-initiated moves; violations() must reflect
 * the visual layout the user has rearranged into.
 */
class WhatIfArchitectureTest {

    @Test
    void freshInstanceMatchesOriginalViolations() {
        DomainModel domain = layered();
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);

        // Original: ui at top, domain at bottom — ui.View → domain.Model is downward, no violation.
        assertTrue(wif.violations().isEmpty(),
                "fresh WhatIfArchitecture inherits the original's layout — no violations");
    }

    @Test
    void movingUiBelowDomainTurnsTheDependencyIntoAnUpwardViolation() {
        DomainModel domain = layered();
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);

        // After analysis: rows = [[ui], [domain]]. Drag ui into a new row
        // below domain — stack drop at index 2 means a new row appears
        // after domain. The drag controller then strips the now-empty
        // source row from the visual stack, leaving visual index 1 for
        // the new ui row. moveElementAsNewRow runs with that visual index
        // and the prune-before-insert step keeps the model aligned.
        wif.moveElementAsNewRow("ui", "", 1);

        assertEquals(1, wif.violations().size(),
                "ui.View now sits below domain.Model — upward violation expected");
        Violation v = wif.violations().get(0);
        assertEquals("ui.View", v.sourceFqn());
        assertEquals("domain.Model", v.targetFqn());
        assertEquals(ViolationKind.UPWARD, v.kind());
    }

    @Test
    void whatIfViolationKeepsClassBackEdgeFlagAfterMove() {
        DomainModel domain = layered();
        domain.setClassBackEdges(Set.of("ui.View\0domain.Model"));
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);

        wif.moveElementAsNewRow("ui", "", 1);

        assertEquals(1, wif.violations().size());
        assertTrue(wif.violations().get(0).backEdge(),
                "the renderer should still know that this visible upward edge is an SCC break edge");
    }

    @Test
    void consecutiveMovesKeepRowIndicesAlignedWithVisualScene() {
        DomainModel domain = layered();
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);

        // Move 1: swap ui below domain. Expect: violation.
        wif.moveElementAsNewRow("ui", "", 1);
        assertEquals(1, wif.violations().size(),
                "first move: ui below domain → one upward violation");

        // Move 2: move ui back above domain (new row at visual index 0).
        // Without prune-before-insert this misplaces ui because removeNode
        // leaves a phantom empty row that shifts indices.
        wif.moveElementAsNewRow("ui", "", 0);
        assertTrue(wif.violations().isEmpty(),
                "second move: ui back on top of domain → no violations");
    }

    @Test
    void movingTopLevelPackageWithSkippedPassthroughsKeepsTheNodeInTheTree() {
        // Real projects always have a transparent passthrough chain
        // (de → de.weigend → de.weigend.s202) above their real top-level
        // packages. The DnD handler used to pass the effective-root fqn as
        // the target parent, which the model couldn't resolve — the package
        // got removed by prepareMove and then dropped on the floor, taking
        // every violation involving its classes with it. This test pins the
        // fix: passing "" (the model's root marker) keeps the package in
        // the tree, and its classes still participate in violations().
        DomainModel domain = new DomainModel();
        addPackage(domain, "de", 0);
        addPackage(domain, "de.weigend", 0);
        addPackage(domain, "de.weigend.s202", 0);
        addPackage(domain, "de.weigend.s202.ui", 1);
        addPackage(domain, "de.weigend.s202.domain", 0);
        addClass(domain, "de.weigend.s202.ui.View", 1, Set.of("de.weigend.s202.domain.Model"));
        addClass(domain, "de.weigend.s202.domain.Model", 0, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());

        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);

        assertTrue(wif.violations().isEmpty(),
                "fresh: ui above domain — no upward edge");

        wif.moveElementAsNewRow("de.weigend.s202.ui", "", 1);
        assertEquals(1, wif.violations().size(),
                "ui below domain — one upward violation");
        Violation v = wif.violations().get(0);
        assertEquals("de.weigend.s202.ui.View", v.sourceFqn());
        assertEquals("de.weigend.s202.domain.Model", v.targetFqn());
    }

    @Test
    void resetRestoresTheOriginalArrangement() {
        DomainModel domain = layered();
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);

        wif.moveElementAsNewRow("ui", "", 1);
        assertEquals(1, wif.violations().size());

        wif.reset();
        assertTrue(wif.violations().isEmpty(), "reset returns to the original — no violations");
    }

    @Test
    void groupUpwardViolationsAggregatesByCallerSuppliedRollup() {
        // Two classes in ui sit above two classes in domain; ui depends on
        // domain so once we swap them, every ui.* → domain.* edge becomes
        // upward. The caller's rollup function maps each class FQN to its
        // parent package FQN — the architecture aggregates by that pair.
        DomainModel domain = new DomainModel();
        addPackage(domain, "ui", 1);
        addPackage(domain, "domain", 0);
        addClass(domain, "ui.A", 1, Set.of("domain.X", "domain.Y"));
        addClass(domain, "ui.B", 1, Set.of("domain.X"));
        addClass(domain, "domain.X", 0, Set.of());
        addClass(domain, "domain.Y", 0, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);
        wif.moveElementAsNewRow("ui", "", 1);

        // Three class-level UPWARD edges total: A→X, A→Y, B→X — all
        // ui → domain. Aggregated by parent-package rollup, that's one
        // single (ui, domain) group with three entries.
        java.util.Map<EndpointPair, java.util.List<Violation>> grouped =
                wif.groupUpwardViolations(WhatIfArchitectureTest::parentOf);
        assertEquals(1, grouped.size(), "all three edges roll up to (ui, domain)");
        EndpointPair pair = grouped.keySet().iterator().next();
        assertEquals("ui", pair.source());
        assertEquals("domain", pair.target());
        assertEquals(3, grouped.get(pair).size(), "three class edges in the group");
    }

    @Test
    void groupUpwardViolationsDropsEntriesWhenRollupReturnsNull() {
        // A caller-supplied rollup may filter classes out (e.g. by
        // returning null for invisible ones). The architecture honours
        // that and drops both-source-and-target-resolve-to-null cases.
        DomainModel domain = layered();
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);
        wif.moveElementAsNewRow("ui", "", 1);
        assertEquals(1, wif.violations().size(), "one upward edge after swap");

        java.util.Map<EndpointPair, java.util.List<Violation>> grouped =
                wif.groupUpwardViolations(fqn -> null);
        assertTrue(grouped.isEmpty(), "every rollup returns null — nothing is grouped");
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    @Test
    void packageTanglesAreRemovedWhenClassMoveMakesEdgesIntraPackage() {
        DomainModel domain = withCycle();
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);

        assertEquals(1, wif.tangles().size());
        assertEquals(Set.of("a", "b"), wif.tangles().get(0).members());

        wif.moveElement("a.X", "b", 0, 1);

        assertEquals("b", wif.packageOf("a.X"));
        assertTrue(wif.tangles().isEmpty(),
                "both class edges are now inside package b, so the package cycle disappears");
    }

    @Test
    void packageTanglesCanAppearAfterMovingClassBetweenPackages() {
        DomainModel domain = moveCreatesCycle();
        HierarchicalLayeredArchitecture original =
                (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(domain);
        WhatIfArchitecture wif = new WhatIfArchitectureModel(original, domain);

        assertTrue(wif.tangles().isEmpty(), "original package graph is acyclic");

        wif.moveElement("c.C", "b", 0, 1);

        assertEquals("b", wif.packageOf("c.C"));
        assertEquals(1, wif.tangles().size());
        assertEquals(Set.of("a", "b"), wif.tangles().get(0).members());
    }

    // ----------------------------------------------------- fixtures

    /**
     * Two-layer model: ui (level 1) on top, domain (level 0) below.
     * ui.View depends on domain.Model — downward in the original layout.
     */
    private static DomainModel layered() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "ui", 1);
        addPackage(domain, "domain", 0);
        addClass(domain, "ui.View", 1, Set.of("domain.Model"));
        addClass(domain, "domain.Model", 0, Set.of());
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());
        return domain;
    }

    private static DomainModel withCycle() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "a", 0);
        addPackage(domain, "b", 0);
        addClass(domain, "a.X", 0, Set.of("b.Y"));
        addClass(domain, "b.Y", 0, Set.of("a.X"));
        domain.setPackageEdgeWeights(Map.of(
                "a", Map.of("b", 1),
                "b", Map.of("a", 1)));
        domain.setPackageBackEdges(Set.of("b\0a"));
        return domain;
    }

    private static DomainModel moveCreatesCycle() {
        DomainModel domain = new DomainModel();
        addPackage(domain, "a", 0);
        addPackage(domain, "b", 0);
        addPackage(domain, "c", 0);
        addClass(domain, "a.A", 0, Set.of("b.B"));
        addClass(domain, "b.B", 0, Set.of());
        addClass(domain, "c.C", 0, Set.of("a.A"));
        domain.setPackageEdgeWeights(Map.of(
                "a", Map.of("b", 1),
                "c", Map.of("a", 1)));
        domain.setPackageBackEdges(Set.of());
        return domain;
    }

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
