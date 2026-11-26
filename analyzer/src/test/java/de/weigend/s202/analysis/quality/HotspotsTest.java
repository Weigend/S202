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
package de.weigend.s202.analysis.quality;

import de.weigend.s202.analysis.quality.Hotspots.Hotspot;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.Externals;
import de.weigend.s202.domain.Placement;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HotspotsTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    private final DependencyModel raw = shop();
    private final DomainModel model = new LevelCalculator().calculate(raw);
    private final Set<String> all = raw.getAllClassNames();

    @Test
    void theLargestBreakoutComesFirst() {
        List<Hotspot> hotspots = hotspots(all);

        for (int i = 1; i < hotspots.size(); i++) {
            assertFalse(hotspots.get(i - 1).breakout() < hotspots.get(i).breakout(), hotspots.toString());
        }
        Hotspot shop = byName(hotspots).get("com.shop");
        assertEquals(5, shop.classes());
        assertEquals(new QualityCalculator(model, Lookup.lookup(SCCFinder.class)).ofPackage("com.shop").getBreakout(),
                shop.breakout());
    }

    @Test
    void whatRunsAgainstTheLayersCountsForEveryPackageAroundIt() {
        Map<String, Hotspot> hotspots = byName(hotspots(all));

        assertEquals(1, hotspots.get("com.shop").against(), "base -> order, the mistake");
        assertFalse(hotspots.containsKey("com"), "above the top level there is nothing to see");
        assertEquals(0, hotspots.get("com.shop.base").against());
        assertEquals(0, hotspots.get("com.shop.ui").against());
    }

    @Test
    void theExternalsAreTheClassesUsedOutsideTheAnalysis() {
        Map<String, Hotspot> hotspots = byName(hotspots(all));

        assertEquals(2, hotspots.get("com.shop").externals(), "java.util.List and java.sql.Connection");
        assertEquals(1, hotspots.get("com.shop.ui").externals());
        assertEquals(0, hotspots.get("com.shop.order").externals());
    }

    @Test
    void onlyThePackagesOfTheTabAreRanked() {
        Map<String, Hotspot> hotspots = byName(hotspots(Set.of("com.shop.ui.Screen")));

        assertEquals(Set.of("com.shop", "com.shop.ui"), hotspots.keySet());
        assertEquals(5, hotspots.get("com.shop").classes(), "measured in the whole model all the same");
    }

    private List<Hotspot> hotspots(Set<String> shown) {
        return Hotspots.of(model, new QualityCalculator(model, Lookup.lookup(SCCFinder.class)),
                Placement.OF_THE_CODE, Externals.of(raw), shown);
    }

    private static Map<String, Hotspot> byName(List<Hotspot> hotspots) {
        return hotspots.stream().collect(Collectors.toMap(Hotspot::pkg, Function.identity()));
    }

    /** ui uses order, order uses base, and base - the mistake - uses order back. */
    private static DependencyModel shop() {
        DependencyModel raw = new DependencyModel();
        add(raw, "com.shop.ui.Screen", "com.shop.order.Order");
        add(raw, "com.shop.order.Order", "com.shop.order.Line", "com.shop.base.Money", "com.shop.base.Audit");
        add(raw, "com.shop.order.Line", "com.shop.base.Money");
        add(raw, "com.shop.base.Money");
        add(raw, "com.shop.base.Audit", "com.shop.order.Order");
        raw.getClass("com.shop.ui.Screen").addExternalDependency("java.util.List", EdgeKind.USES);
        raw.getClass("com.shop.base.Audit").addExternalDependency("java.sql.Connection", EdgeKind.CALLS);
        PackageHierarchyBuilder.buildPackageHierarchy(raw);
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
