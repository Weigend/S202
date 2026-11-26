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

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.architecture.DependencyMatrix.Cell;
import de.weigend.s202.domain.impl.HierarchicalLayeredArchitecture;
import de.weigend.s202.domain.impl.HierarchicalLayeredArchitectureBuilder;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyMatrixTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    private final SCCFinder finder = Lookup.lookup(SCCFinder.class);
    private final DomainModel model = shop();
    private final HierarchicalLayeredArchitecture layers =
            (HierarchicalLayeredArchitecture) new HierarchicalLayeredArchitectureBuilder().build(model);

    @Test
    void theOrderIsThatOfTheLayersBelowTheSinglePackages() {
        assertEquals(List.of("com.shop.ui", "com.shop.order", "com.shop.base"), names(matrix(Set.of())));
    }

    @Test
    void aColumnUsesItsRows() {
        DependencyMatrix matrix = matrix(Set.of());

        Cell uiUsesOrder = cell(matrix, "com.shop.order", "com.shop.ui");
        assertEquals(List.of(new DependencyEdge("com.shop.ui.Screen", "com.shop.order.Order")), uiUsesOrder.edges());
        assertEquals(4, uiUsesOrder.weight(), "the calls that carry it");
        assertNull(cell(matrix, "com.shop.ui", "com.shop.order"), "order does not use ui");
        assertNull(matrix.cell(0, 0), "the diagonal is empty");
    }

    @Test
    void whatRunsAgainstTheLayersIsAboveTheDiagonal() {
        DependencyMatrix matrix = matrix(Set.of());

        Cell up = cell(matrix, "com.shop.order", "com.shop.base");
        assertEquals(1, up.againstLayers());
        assertEquals(CycleKind.CLASS_CYCLE, up.cycle(), "Order and Audit use each other");
        assertTrue(matrix.indexOf("com.shop.order") < matrix.indexOf("com.shop.base"));
        assertEquals(0, cell(matrix, "com.shop.base", "com.shop.order").againstLayers());
        assertEquals(1, matrix.tangles());
        assertEquals(4, matrix.dependencies());
    }

    @Test
    void anOpenPackageIsReplacedByItsMembersWhichStayTogether() {
        DependencyMatrix matrix = matrix(Set.of("com.shop.order"));

        assertEquals(List.of("com.shop.ui", "com.shop.order.Order", "com.shop.order.Line", "com.shop.base"), names(matrix));
        assertEquals(List.of(new DependencyMatrix.Group("com.shop.order", 1, 2, 0)), matrix.groups());
        assertNotNull(cell(matrix, "com.shop.order.Line", "com.shop.order.Order"));
        assertEquals(3, matrix.indexOf("com.shop.base.Audit"), "a class of a closed package is found at the package");
    }

    @Test
    void allPackagesOpenIsTheMatrixOfTheClasses() {
        DependencyMatrix closed = matrix(Set.of());
        DependencyMatrix open = matrix(closed.packages());

        assertEquals(model.getAllClasses().size(), open.size());
        assertTrue(open.items().stream().noneMatch(DependencyMatrix.Item::isPackage));
        assertTrue(closed.packagesOfPackages().isEmpty());
    }

    @Test
    void onlyTheGivenClassesCount() {
        DependencyMatrix matrix = of(Set.of(), Set.of("com.shop.ui.Screen", "com.shop.order.Order"),
                (from, to) -> true, Set.of());

        assertEquals(List.of("com.shop.ui", "com.shop.order"), names(matrix));
        assertEquals(1, matrix.dependencies());
    }

    @Test
    void aSinglePackageGivesWayToItsMembers() {
        DependencyMatrix matrix = of(Set.of(), Set.of("com.shop.order.Order", "com.shop.order.Line"),
                (from, to) -> true, Set.of());

        assertEquals(List.of("com.shop.order.Order", "com.shop.order.Line"), names(matrix));
    }

    @Test
    void filteredDependenciesAreNotCounted() {
        DependencyMatrix matrix = of(Set.of(), null, (from, to) -> !to.endsWith(".Order"), Set.of());

        assertNull(cell(matrix, "com.shop.order", "com.shop.ui"));
    }

    @Test
    void aCutDependencyIsShownAsCutAndCountsNoMore() {
        DependencyEdge cut = new DependencyEdge("com.shop.base.Audit", "com.shop.order.Order");

        DependencyMatrix matrix = of(Set.of(), null, (from, to) -> true, Set.of(cut));

        Cell cell = cell(matrix, "com.shop.order", "com.shop.base");
        assertTrue(cell.edges().isEmpty());
        assertEquals(List.of(cut), cell.cut());
        assertEquals(0, cell.againstLayers());
        assertEquals(0, matrix.tangles());
        assertEquals(3, matrix.dependencies());
    }

    @Test
    void theOrderFollowsAMove() {
        WhatIfArchitecture plan = layers.toWhatIf(model);
        plan.moveElementAsNewRow("com.shop.base.Audit", "", 0);

        DependencyMatrix matrix = DependencyMatrix.of(new DependencyMatrix.Input(plan.rows(), null, Set.of(), model,
                EdgeCycleClassifier.of(model, Set.of(), finder), (from, to) -> true, Set.of()), finder);

        assertEquals("com.shop.base.Audit", matrix.items().get(0).fqn());
        assertEquals(0, cell(matrix, "com.shop.order", "com.shop.base.Audit").againstLayers(), "it runs down now");
        assertEquals(1, cell(matrix, "com.shop.base.Audit", "com.shop.order").againstLayers(), "and this one up");
    }

    @Test
    void whatIsUsedOutsideTheAnalysisGetsRowsBelowTheRest() {
        de.weigend.s202.reader.DependencyModel raw = new de.weigend.s202.reader.DependencyModel();
        add(raw, "com.shop.ui.Screen");
        add(raw, "com.shop.base.Money");
        raw.getClass("com.shop.ui.Screen").addExternalDependency("javafx.scene.Node", EdgeKind.CALLS);
        raw.getClass("com.shop.base.Money").addExternalDependency("java.math.BigDecimal", EdgeKind.USES);
        raw.getClass("com.shop.base.Money").addExternalDependency("java.math.RoundingMode", EdgeKind.USES);

        DependencyMatrix matrix = DependencyMatrix.of(new DependencyMatrix.Input(layers.rows(), null, Set.of(),
                model, EdgeCycleClassifier.of(model, Set.of(), finder), (from, to) -> true, Set.of(),
                de.weigend.s202.domain.Externals.of(raw)), finder);

        assertEquals(List.of("com.shop.ui", "com.shop.order", "com.shop.base", "java.math", "javafx.scene"),
                names(matrix));
        assertTrue(matrix.items().get(3).external());
        assertFalse(matrix.packages().contains("java.math"), "nothing to open");
        Cell money = cell(matrix, "java.math", "com.shop.base");
        assertEquals(2, money.edges().size());
        assertEquals(0, money.againstLayers());
        assertEquals(CycleKind.NONE, money.cycle());
        assertEquals(matrix.indexOf("java.math"), matrix.indexOf("java.math.BigDecimal"));
        assertEquals(matrix(Set.of()).tangles(), matrix.tangles(), "no cycle goes through a library");
        assertEquals(List.of("com.shop.ui", "com.shop.order", "com.shop.base"), names(matrix(Set.of())),
                "only when asked for");
    }

    private DependencyMatrix matrix(Set<String> open) {
        return of(open, null, (from, to) -> true, Set.of());
    }

    private DependencyMatrix of(Set<String> open, Set<String> classes, BiPredicate<String, String> filter,
                                Set<DependencyEdge> cuts) {
        return DependencyMatrix.of(new DependencyMatrix.Input(layers.rows(), classes, open, model,
                EdgeCycleClassifier.of(model, cuts, finder), filter, cuts), finder);
    }

    private static List<String> names(DependencyMatrix matrix) {
        return matrix.items().stream().map(DependencyMatrix.Item::fqn).toList();
    }

    private static Cell cell(DependencyMatrix matrix, String row, String column) {
        return matrix.cell(matrix.indexOf(row), matrix.indexOf(column));
    }

    /** ui uses order, order uses base, and base - the mistake - uses order back. */
    private static DomainModel shop() {
        DependencyModel raw = new DependencyModel();
        add(raw, "com.shop.ui.Screen", "com.shop.order.Order");
        calls(raw, "com.shop.ui.Screen", "com.shop.order.Order", 4);
        add(raw, "com.shop.order.Order", "com.shop.order.Line", "com.shop.base.Money", "com.shop.base.Audit");
        calls(raw, "com.shop.order.Order", "com.shop.base.Money", 5);
        add(raw, "com.shop.order.Line", "com.shop.base.Money");
        add(raw, "com.shop.base.Money");
        add(raw, "com.shop.base.Audit", "com.shop.order.Order");
        raw.getClass("com.shop.ui.Screen").addDependency("com.shop.base.Money", EdgeKind.USES);
        PackageHierarchyBuilder.buildPackageHierarchy(raw);
        return new LevelCalculator().calculate(raw);
    }

    private static void add(DependencyModel raw, String name, String... targets) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        for (String target : targets) {
            info.addDependency(target, EdgeKind.CALLS);
        }
        raw.addClass(name, info);
    }

    private static void calls(DependencyModel raw, String from, String to, int count) {
        raw.getClass(from).addMethod("run", "()V");
        raw.getClass(from).getMethod("run", "()V").methodCalls.put(to + ".go", count);
    }
}
