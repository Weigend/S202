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
package de.weigend.s202.domain;

import de.weigend.s202.domain.impl.LevelCalculator;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shop like the one of the handbook: order and payment use each other's
 * api from their impl - a tangle of the two packages that is none of their
 * sub-packages - while common and customer.api use each other for real.
 */
class SliceHierarchyTest {

    private static final char D = SliceHierarchy.DOT;

    @BeforeAll
    static void lookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdown() {
        Lookup.shutdown();
    }

    @Test
    void thePackagesOfOneDepthBecomeSiblings() {
        SliceHierarchy slice = SliceHierarchy.of(shop(), 2);

        assertEquals("com.shop.order" + D + "impl.Processor", slice.toSlice("com.shop.order.impl.Processor"));
        assertEquals("com.shop.common.Audit", slice.toSlice("com.shop.common.Audit"), "not as deep: stays");
        assertEquals("com.shop.order" + D + "api.events.Placed", slice.toSlice("com.shop.order.api.events.Placed"),
                "deeper: stays below its slice");
        assertEquals("com.shop.order.api.events.Placed",
                SliceHierarchy.toLogical("com.shop.order" + D + "api.events.Placed"));
    }

    @Test
    void aTangleOfTheGroupingDisappearsAtTheDepthBelow() {
        List<Set<String>> depthOne = new LevelCalculator().calculate(SliceHierarchy.of(shop(), 1).model())
                .getContainerTangles();
        List<Set<String>> depthTwo = new LevelCalculator().calculate(SliceHierarchy.of(shop(), 2).model())
                .getContainerTangles();

        assertTrue(depthOne.contains(Set.of("com.shop.order", "com.shop.payment")), depthOne.toString());
        assertFalse(depthTwo.stream().anyMatch(t -> t.stream().anyMatch(n -> n.contains("payment"))), depthTwo.toString());
        assertTrue(depthTwo.contains(Set.of("com.shop.common", "com.shop.customer" + D + "api")),
                "a real cycle stays: " + depthTwo);
    }

    @Test
    void theLevelsCountPackagesAndTanglesPerDepth() {
        List<SliceHierarchy.Level> levels = SliceHierarchy.levels(shop(), Lookup.lookup(SCCFinder.class));

        assertEquals(List.of(
                new SliceHierarchy.Level(1, 4, 2, 4, List.of("common \u21C4 customer", "order \u21C4 payment")),
                new SliceHierarchy.Level(2, 6, 1, 2, List.of("common \u21C4 customer.api")),
                new SliceHierarchy.Level(3, 7, 1, 2, List.of("common \u21C4 customer.api"))), levels,
                "order.api.events is a package of its own at depth 3");
    }

    @Test
    void theRootIsWhereThePackagesPart() {
        assertEquals("com.shop", SliceHierarchy.root(Set.of("com.shop.order.api", "com.shop.common")));
        assertEquals("com", SliceHierarchy.root(Set.of("com.shop", "com.shop.order")), "com.shop has classes itself");
        assertEquals("", SliceHierarchy.root(Set.of("com.a", "org.b")));
    }

    @Test
    void shownNamesAreThoseOfTheAnalysis() {
        assertEquals("com.shop.order.impl ↑ com.shop.payment.api",
                ShownNames.shown("com.shop.order" + D + "impl ↑ com.shop.payment" + D + "api"));
        assertEquals("com.shop.order.api.Order", ShownNames.toLogical("com.shop.order" + D + "api.Order"));
    }

    private static DependencyModel shop() {
        DependencyModel raw = new DependencyModel();
        add(raw, "com.shop.order.api.Order", "com.shop.common.Money", "com.shop.customer.api.Customer");
        add(raw, "com.shop.order.api.events.Placed", "com.shop.order.api.Order");
        add(raw, "com.shop.order.impl.Processor", "com.shop.order.api.Order", "com.shop.payment.api.Payments");
        add(raw, "com.shop.payment.api.Payments", "com.shop.common.Money");
        add(raw, "com.shop.payment.impl.Refunds", "com.shop.payment.api.Payments", "com.shop.order.api.Order");
        add(raw, "com.shop.common.Money");
        add(raw, "com.shop.common.Audit", "com.shop.customer.api.Customer");
        add(raw, "com.shop.customer.api.Customer", "com.shop.common.Audit");
        return raw;
    }

    private static void add(DependencyModel raw, String name, String... targets) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        for (String target : targets) {
            info.addDependency(target, EdgeKind.CALLS);
        }
        raw.addClass(name, info);
    }
}
